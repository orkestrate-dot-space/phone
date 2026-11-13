// phone desktop process v0 — MCP (stdio) + embedded relay (HTTP+WS).
// Logs go to stderr ALWAYS (stdout is the MCP channel). No deps.

const PORT = Number(process.env.PHONE_PORT ?? "19877");
const DATA_DIR = process.env.PHONE_DATA ?? new URL("../data/", import.meta.url).pathname;
const DEVICES_FILE = DATA_DIR + "/devices.json";
const DIST_DIR = process.env.PHONE_DIST ?? new URL("../../dist/", import.meta.url).pathname;

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // 32 symbols, no I/L/O/U
const rand = (n: number) => {
  const b = crypto.getRandomValues(new Uint8Array(n));
  return b;
};
const b64url = (b: Uint8Array) =>
  Buffer.from(b).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const makeCode = () => {
  const b = rand(5); // 40 bits like Browse
  let v = 0n;
  for (const x of b) v = (v << 8n) | BigInt(x);
  let s = "";
  for (let i = 0; i < 8; i++) { s = CROCKFORD[Number(v & 31n)] + s; v >>= 5n; }
  return s;
};
const log = (...a: unknown[]) => console.error("[phone]", ...a);

// ---- stores ----
type Device = { id: string; name: string; token: string; lastSeen: number; appVersion?: number };
type Code = { code: string; expires: number };
const devices = new Map<string, Device>();
const byToken = new Map<string, Device>();
const codes = new Map<string, Code>();
let strikes = 0;

async function load() {
  try {
    const f = Bun.file(DEVICES_FILE);
    if (await f.exists()) {
      const j = await f.json() as { devices: Device[] };
      for (const d of j.devices) { devices.set(d.id, d); byToken.set(d.token, d); }
      log(`loaded ${devices.size} device(s)`);
    }
  } catch (e) { log("load err", String(e)); }
}
async function save() {
  try {
    await Bun.write(DEVICES_FILE, JSON.stringify({ devices: [...devices.values()] }, null, 2));
  } catch (e) { log("save err", String(e)); }
}

// ---- jobs: queued → delivered → done/failed/expired (SPEC §3) ----
type Waiter = { resolve: (v: unknown) => void; reject: (e: Error) => void;
  timer: Timer; cap: string; deviceId: string; job: Record<string, unknown> };
const waiting = new Map<string, Waiter>(); // by job id, queued or delivered
const sockets = new Map<string, any>(); // deviceId -> ws
const online = (id: string) => sockets.has(id);

function deliver(deviceId: string, w: Waiter) {
  const ws = sockets.get(deviceId);
  if (!ws) return false;
  try { ws.send(JSON.stringify({ t: "job", job: w.job })); return true; }
  catch { return false; }
}

function sendJob(deviceId: string, capability: string, params: Record<string, unknown>, timeoutMs = 60000) {
  const id = "job_" + b64url(rand(9));
  const job = { id, capability, params, timeoutMs };
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      waiting.delete(id);
      reject(new Error("timeout waiting for phone"));
    }, timeoutMs);
    const w: Waiter = { resolve, reject, timer, cap: capability, deviceId, job };
    waiting.set(id, w);
    if (deliver(deviceId, w)) log(`job ${id} ${capability} → live`);
    else log(`job ${id} ${capability} queued (device offline)`);
  });
}

/** Push queued jobs on (re)connect. Called from the hello handler. */
function flushQueue(deviceId: string) {
  for (const [id, w] of waiting) {
    if (w.deviceId === deviceId && deliver(deviceId, w)) log(`job ${id} ${w.cap} → delivered on hello`);
  }
}

// ---- relay: HTTP (redeem, app channel) + WS (device channel) ----

const server = Bun.serve({
  port: PORT,
  hostname: "0.0.0.0",
  async fetch(req, srv) {
    const u = new URL(req.url);
    if (u.pathname === "/health") {
      return Response.json({ ok: true, devices: devices.size,
        online: [...sockets.keys()],
        versions: Object.fromEntries([...devices.values()].map(d => [d.id, d.appVersion ?? null])) });
    }
    // NOTE: no HTTP mint endpoint. Codes are minted only via the MCP
    // phone.pair tool (loopback stdin, never the network): through a tunnel
    // every visitor looks loopback, so no HTTP endpoint may mean "local".
    // Redeem (below) is safe to expose: 40-bit code + 5-strike voiding.
    // Redeem — phone path (LAN ok)
    const m = u.pathname.match(/^\/pair\/([A-Z0-9]{8})$/);
    if (m && req.method === "POST") {
      const hit = codes.get(m[1]);
      if (!hit || hit.expires < Date.now()) {
        if (++strikes >= 5) { codes.clear(); strikes = 0; log("5 strikes, voided"); }
        return Response.json({ error: "invalid or expired code" }, { status: 401 });
      }
      codes.delete(m[1]); strikes = 0;
      let name = "phone";
      try { name = ((await req.json()) as any)?.name ?? name; } catch {}
      const id = "dev_" + b64url(rand(9));
      const token = "pt_" + b64url(rand(32));
      const d: Device = { id, name: String(name).slice(0, 64), token, lastSeen: Date.now() };
      devices.set(id, d); byToken.set(token, d); await save();
      const wsBase = `ws://${u.host}/device`;
      log(`paired ${id} (${d.name})`);
      return Response.json({ deviceId: id, token, relay: wsBase });
    }
    // ---- self-update channel: version + APK, device-token authed ----
    if (u.pathname === "/app/version" || u.pathname === "/app.apk") {
      const auth = req.headers.get("authorization") ?? "";
      const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
      if (!byToken.has(token)) return new Response("unauthorized", { status: 401 });
      try {
        if (u.pathname === "/app/version") {
          const v = await Bun.file(DIST_DIR + "/version.json").json();
          return Response.json(v);
        }
        const apk = Bun.file(DIST_DIR + "/app.apk");
        if (!(await apk.exists())) return new Response("no build published", { status: 404 });
        return new Response(apk as any, { headers: {
          "content-type": "application/vnd.android.package-archive",
          "content-length": String(apk.size),
        }});
      } catch { return new Response("dist not published", { status: 404 }); }
    }
    // WS upgrade
    if (u.pathname === "/device") {
      const auth = req.headers.get("authorization") ?? "";
      const token = auth.startsWith("Bearer ") ? auth.slice(7)
        : u.searchParams.get("token") ?? "";
      const dev = byToken.get(token);
      if (!dev) return new Response("unauthorized", { status: 401 });
      const ok = srv.upgrade(req, { data: { pendingAuth: dev.id } });
      return ok ? new Response("upgrading") : new Response("upgrade failed", { status: 500 });
    }
    return new Response("phone relay v0", { status: 404 });
  },
  websocket: {
    open(ws: any) { ws.data = { ...ws.data, authed: false }; },
    async message(ws: any, raw: any) {
      let msg: any;
      try { msg = JSON.parse(String(raw)); } catch { return; }
      if (msg.t === "hello") {
        const dev = devices.get(msg.deviceId);
        const authedId = ws.data?.pendingAuth;
        if (!dev || dev.id !== authedId || msg.deviceId !== authedId) { ws.close(4401, "bad hello"); return; }
        if (msg.name) { dev.name = String(msg.name).slice(0, 64); }
        if (typeof msg.appVersion === "number") dev.appVersion = msg.appVersion;
        await save();
        dev.lastSeen = Date.now();
        sockets.set(dev.id, ws);
        ws.data.authed = true; ws.data.deviceId = dev.id;
        log(`hello ${dev.id} caps=${(msg.caps ?? []).join(",")}`);
        ws.send(JSON.stringify({ t: "welcome", deviceId: dev.id }));
        flushQueue(dev.id);
        return;
      }
      if (msg.t === "pong") return;
      if (msg.t === "ping") { ws.send(JSON.stringify({ t: "pong" })); return; }
      if (msg.t === "result") {
        const p = waiting.get(msg.id);
        if (!p) return;
        clearTimeout(p.timer); waiting.delete(msg.id);
        if (msg.ok) p.resolve(msg.data ?? {});
        else p.reject(new Error(String(msg.error ?? "phone failed")));
        return;
      }
    },
    close(ws: any) {
      const id = ws.data?.deviceId;
      if (id && sockets.get(id) === ws) { sockets.delete(id); log(`bye ${id}`); }
    },
  },
});

setInterval(() => {
  for (const [, ws] of sockets) { try { ws.send(JSON.stringify({ t: "ping" })); } catch {} }
}, 25000);

// stash token on upgrade: Bun doesn't pass headers to open(), so re-derive via URL is
// already authed above; bind pendingAuth -> _token not needed since hello checks id.
log(`relay on :${PORT} data=${DATA_DIR}`);

// ---- MCP over stdio ----
const TOOLS = [
  { name: "phone.status", description: "Paired devices and online state",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.battery", description: "Read the phone battery via the companion app",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.capture_photo", description: "Ask the human to take a photo (on-glass approval, up to 300s)",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.exec", description: "Run a system command on the phone (on-glass approval every time, app-sandbox limits)",
    inputSchema: { type: "object", properties: { argv: { type: "array", items: { type: "string" } }, timeoutSec: { type: "number" } }, required: ["argv"], additionalProperties: false } },
  { name: "phone.pair", description: "Mint a pairing code (10 min, single use)",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.pair_list", description: "Paired devices (keys never shown)",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.revoke", description: "Revoke a device: drops its key and socket",
    inputSchema: { type: "object", properties: { deviceId: { type: "string" } }, required: ["deviceId"], additionalProperties: false } },
  { name: "phone.jobs", description: "Jobs currently queued or awaiting results",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "phone.doctor", description: "Relay health: listener, tunnel, devices, queues",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
];

async function callTool(name: string, params?: any) {
  if (name === "phone.status") {
    return {
      paired: devices.size > 0,
      devices: [...devices.values()].map(d => ({ id: d.id, name: d.name,
        appVersion: d.appVersion ?? null, online: online(d.id), lastSeen: d.lastSeen })),
    };
  }
  if (name === "phone.pair_list") {
    return {
      devices: [...devices.values()].map(d => ({ id: d.id, name: d.name,
        appVersion: d.appVersion ?? null, online: online(d.id), lastSeen: d.lastSeen })),
    };
  }
  if (name === "phone.revoke") {
    const id = params?.deviceId;
    const dev = devices.get(id);
    if (!dev) throw new Error("unknown device");
    devices.delete(id); byToken.delete(dev.token);
    try { sockets.get(id)?.close(4403, "revoked"); } catch {}
    sockets.delete(id);
    for (const [jid, w] of waiting) {
      if (w.deviceId === id) { clearTimeout(w.timer); waiting.delete(jid);
        w.reject(new Error("device revoked")); }
    }
    await save();
    log(`revoked ${id}`);
    return { revoked: id };
  }
  if (name === "phone.jobs") {
    return {
      waiting: [...waiting.entries()].map(([id, w]) => ({ id,
        capability: w.cap, deviceId: w.deviceId, live: online(w.deviceId) })),
    };
  }
  if (name === "phone.doctor") {
    let tunnel: unknown = "unknown (opentunnel CLI not on PATH?)";
    try {
      const p = Bun.spawnSync(["/home/pracurser/.bun/bin/opentunnel", "route", "list"]);
      tunnel = new TextDecoder().decode(p.stdout).trim() || "(no routes)";
    } catch { tunnel = "opentunnel unavailable"; }
    return {
      relay: { port: PORT, uptimeSec: Math.round(performance.now() / 1000) },
      tunnelRoutes: tunnel,
      devices: devices.size, online: [...sockets.keys()],
      queuedJobs: [...waiting.values()].filter(w => !online(w.deviceId)).length,
      liveJobs: [...waiting.values()].filter(w => online(w.deviceId)).length,
    };
  }
  if (name === "phone.pair") {
    if ([...codes.values()].filter(c => c.expires > Date.now()).length >= 5)
      throw new Error("too many pending codes");
    const code = makeCode();
    codes.set(code, { code, expires: Date.now() + 10 * 60 * 1000 });
    log("minted pairing code via MCP");
    return { code, expires_in: 600 };
  }
  if (name === "phone.battery") {
    const dev = [...devices.values()][0]; // queue tolerates offline, hello flushes
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const data = await sendJob(dev.id, "battery.get", {});
    return data as Record<string, unknown>;
  }
  if (name === "phone.capture_photo") {
    const dev = [...devices.values()][0]; // queue tolerates offline, hello flushes
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const data = await sendJob(dev.id, "photo.capture", { maxPx: 1280, quality: 80 }, 300000);
    return data as Record<string, unknown>;
  }
  if (name === "phone.exec") {
    const dev = [...devices.values()][0];
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const argv = params?.argv;
    if (!Array.isArray(argv) || argv.length === 0 || argv.some((a: unknown) => typeof a !== "string"))
      throw new Error("argv must be a non-empty string array");
    const data = await sendJob(dev.id, "exec.run",
      { argv, timeoutSec: Math.min(Math.max(params?.timeoutSec ?? 30, 5), 120) }, 300000);
    return data as Record<string, unknown>;
  }
  throw new Error("unknown tool " + name);
}

function reply(id: unknown, result: unknown) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}
function replyErr(id: unknown, code: number, message: string) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n");
}

async function handle(msg: any) {
  if (!msg || msg.jsonrpc !== "2.0") return;
  const id = msg.id;
  try {
    switch (msg.method) {
      case "initialize":
        if (id !== undefined) reply(id, { protocolVersion: "2024-11-05",
          capabilities: { tools: {} }, serverInfo: { name: "phone", version: "0.1.0" } });
        break;
      case "notifications/initialized": break;
      case "ping": if (id !== undefined) reply(id, {}); break;
      case "tools/list":
        if (id !== undefined) reply(id, { tools: TOOLS });
        break;
      case "tools/call": {
        const name = msg.params?.name;
        try {
          const out = await callTool(name, msg.params?.arguments);
          if (id !== undefined) reply(id, { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] });
        } catch (e) { if (id !== undefined) replyErr(id, -32000, (e as Error).message); }
        break;
      }
      default:
        if (id !== undefined) replyErr(id, -32601, "method not found: " + msg.method);
    }
  } catch (e) { if (id !== undefined) replyErr(id, -32603, String(e)); }
}

async function mcpLoop() {
  // --serve: relay only, stdout is free for logs
  if (process.argv.includes("--serve")) { log("serve mode (no MCP)"); return; }
  // MCP mode only if stdin is piped; else just run the relay (handy for dev)
  if (process.stdin.isTTY) { log("tty stdin: relay only, MCP idle. use --serve to silence this."); return; }
  let buf = "";
  for await (const chunk of Bun.stdin.stream()) {
    buf += new TextDecoder().decode(chunk);
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line) continue;
      try { await handle(JSON.parse(line)); }
      catch (e) { log("bad frame", String(e)); }
    }
  }
}

await load();
await mcpLoop();
