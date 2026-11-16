// phone desktop process v0 — MCP (stdio) + embedded relay (HTTP+WS).
// Logs go to stderr ALWAYS (stdout is the MCP channel). No deps.

const PORT = Number(process.env.PHONE_PORT ?? "19877");
const DATA_DIR = process.env.PHONE_DATA ?? new URL("../data/", import.meta.url).pathname;
const DEVICES_FILE = DATA_DIR + "/devices.json";
const DIST_DIR = process.env.PHONE_DIST ?? new URL("../../dist/", import.meta.url).pathname;
// Control socket: the one running relay serves tool calls to agent MCP processes and the
// CLI over this unix socket (filesystem-permissioned, unreachable through the tunnel).
const SOCK = process.env.PHONE_SOCK ?? DATA_DIR + "/control.sock";
const PAIR_BASE = process.env.PHONE_PAIR_BASE ?? "https://mobile.orkestrate.space/phone/pair";

import { chmodSync, mkdirSync, unlinkSync } from "node:fs";
import { networkInterfaces, tmpdir } from "node:os";
import { qrPng, qrText } from "./qr";

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
const normCode = (c: unknown) =>
  typeof c === "string" ? c.toUpperCase().replace(/[^0-9A-Z]/g, "").replace(/[IL]/g, "1").replace(/O/g, "0") : "";
const prettyCode = (c: string) => c.slice(0, 4) + "-" + c.slice(4);

/** Call a tool on the relay that is already running (service or another MCP process). */
async function remoteCall(name: string, params?: unknown): Promise<any> {
  let res: Response;
  try {
    res = await fetch("http://phone/call", {
      unix: SOCK, method: "POST", timeout: false,
      body: JSON.stringify({ name, params }),
    } as any);
  } catch {
    throw new Error("relay-not-running: start it with `phone start`, or run the MCP server (it starts the relay)");
  }
  const j = (await res.json()) as { ok: boolean; result?: unknown; error?: string };
  if (!j.ok) throw new Error(j.error);
  return j.result;
}

// ---- CLI: `phone pair|wait|battery|status|doctor|revoke|photo|exec|update|devices` ----
const CLI = new Set(["pair", "wait", "battery", "status", "doctor", "revoke", "photo", "exec", "update", "devices"]);
const CLI_TOOL: Record<string, string> = { wait: "pair_wait", photo: "capture_photo", devices: "pair_list" };
async function runCli(cmd: string, args: string[]) {
  const flag = (f: string) => args.includes(f);
  const rest = args.filter(a => !a.startsWith("--"));
  const params: Record<string, unknown> = {};
  if (cmd === "revoke") params.deviceId = rest[0];
  if (cmd === "wait" && rest[0]) params.code = rest[0];
  if (cmd === "exec") params.argv = args.slice(args[0] === "--" ? 1 : 0);
  const relayArg = args.find(a => a.startsWith("--relay="));
  if (cmd === "pair" && relayArg) params.relay = relayArg.slice(8);
  const out = await remoteCall(CLI_TOOL[cmd] ?? cmd, params);
  if (cmd === "pair") {
    console.log(`Scan this with the phone's camera:\n\n${out.qr}\n`);
    console.log(`Code    ${out.code} (works once, within 10 minutes)`);
    console.log(`Link    ${out.pair_url}`);
    console.log(`Relay   ${out.relay_url} (${out.relay_kind})`);
    console.log(`PNG     ${out.qr_png}`);
    if (out.warning) console.log(`Note    ${out.warning}`);
    if (flag("--no-wait")) { console.log("\nNext: phone wait"); return; }
    console.log("\nWaiting for the phone...");
    const done = await remoteCall("pair_wait", { code: out.code });
    console.log(`Paired: ${done.name} (${done.deviceId})`);
    return;
  }
  if (cmd === "wait") { console.log(`Paired: ${out.name} (${out.deviceId})`); return; }
  console.log(JSON.stringify(out, null, 2));
}
if (CLI.has(process.argv[2] ?? "")) {
  try { await runCli(process.argv[2], process.argv.slice(3)); process.exit(0); }
  catch (e) { console.error("phone: " + (e as Error).message); process.exit(1); }
}

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
const delivered = new Set<string>(); // job ids already pushed down the socket
const sockets = new Map<string, any>(); // deviceId -> ws
const online = (id: string) => sockets.has(id);
// Online device first, else the most recently seen (queue flushes on hello).
const pickDevice = () => [...devices.values()]
  .sort((a, b) => Number(online(b.id)) - Number(online(a.id)) || b.lastSeen - a.lastSeen)[0];

function deliver(deviceId: string, w: Waiter) {
  const id = (w.job as any).id as string;
  if (delivered.has(id)) return true; // already down the wire; don't double-post
  const ws = sockets.get(deviceId);
  if (!ws) return false;
  try { ws.send(JSON.stringify({ t: "job", job: w.job })); delivered.add(id); return true; }
  catch { return false; }
}

function sendJob(deviceId: string, capability: string, params: Record<string, unknown>, timeoutMs = 60000) {
  const id = "job_" + b64url(rand(9));
  const job = { id, capability, params, timeoutMs };
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      waiting.delete(id); delivered.delete(id);
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

let proxyMode = false;
try {
const server = Bun.serve({
  port: PORT,
  hostname: "0.0.0.0",
  async fetch(req, srv) {
    const u = new URL(req.url);
    // Public (the tunnel exposes it): reachability probe only, nothing about devices.
    if (u.pathname === "/version" || u.pathname === "/health") {
      return Response.json({ ok: true, relay: "phone", protocol: 1 });
    }
    // NOTE: no HTTP mint endpoint. Codes are minted only via the MCP
    // phone.pair tool (loopback stdin, never the network): through a tunnel
    // every visitor looks loopback, so no HTTP endpoint may mean "local".
    // Redeem (below) is safe to expose: 40-bit code + 5-strike voiding.
    // Redeem — phone path (LAN ok)
    const m = u.pathname.match(/^\/pair\/([A-Za-z0-9-]{8,9})$/);
    if (m && req.method === "POST") {
      const code = normCode(m[1]);
      const hit = codes.get(code);
      if (!hit || hit.expires < Date.now()) {
        if (++strikes >= 5) { voidCodes(); strikes = 0; log("5 strikes, voided"); }
        return Response.json({ error: "invalid or expired code" }, { status: 401 });
      }
      codes.delete(code); strikes = 0;
      let name = "phone";
      try { name = ((await req.json()) as any)?.name ?? name; } catch {}
      const id = "dev_" + b64url(rand(9));
      const token = "pt_" + b64url(rand(32));
      const d: Device = { id, name: String(name).slice(0, 64), token, lastSeen: Date.now() };
      devices.set(id, d); byToken.set(token, d); await save();
      markRedeemed(code, d);
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
        clearTimeout(p.timer); waiting.delete(msg.id); delivered.delete(msg.id);
        if (msg.ok) p.resolve(msg.data ?? {});
        else p.reject(new Error(String(msg.error ?? "phone failed")));
        return;
      }
    },
    close(ws: any) {
      const id = ws.data?.deviceId;
      if (id && sockets.get(id) === ws) {
        sockets.delete(id);
        // The app may have died between delivery and response (approvals live
        // in its memory). Unmark delivered so the next hello truly redelivers
        // instead of logging a no-op "delivered on hello" for a job the fresh
        // process never saw.
        for (const [jid, w] of waiting) if (w.deviceId === id) delivered.delete(jid);
        log(`bye ${id}`);
      }
    },
  },
});
} catch (e) {
  if ((e as any)?.code !== "EADDRINUSE" && !/EADDRINUSE|in use/i.test(String(e))) throw e;
  proxyMode = true;
}
if (proxyMode && process.argv.includes("--serve")) { log(`port ${PORT} is in use; is a relay already running?`); process.exit(1); }

if (!proxyMode) {
  setInterval(() => {
    for (const [, ws] of sockets) { try { ws.send(JSON.stringify({ t: "ping" })); } catch {} }
  }, 25000);
  mkdirSync(DATA_DIR, { recursive: true });
  try { unlinkSync(SOCK); } catch {}
  Bun.serve({
    unix: SOCK, idleTimeout: 0,
    async fetch(req) {
      if (req.method !== "POST") return new Response("POST only", { status: 405 });
      try {
        const { name, params } = (await req.json()) as { name: string; params?: any };
        return Response.json({ ok: true, result: await localCall(name, params) });
      } catch (e) { return Response.json({ ok: false, error: (e as Error).message }); }
    },
  });
  try { chmodSync(SOCK, 0o600); } catch {}
}

// stash token on upgrade: Bun doesn't pass headers to open(), so re-derive via URL is
// already authed above; bind pendingAuth -> _token not needed since hello checks id.
log(proxyMode ? `relay already running on :${PORT}; forwarding tool calls to it`
  : `relay on :${PORT} data=${DATA_DIR}`);

// ---- MCP over stdio ----
const TOOLS = [
  { name: "status", description: "Paired devices and online state",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "battery", description: "Read the phone battery via the companion app",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "capture_photo", description: "Ask the human to take a photo (on-glass approval, up to 300s)",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "exec", description: "Run a system command on the phone (on-glass approval every time, app-sandbox limits)",
    inputSchema: { type: "object", properties: { argv: { type: "array", items: { type: "string" } }, timeoutSec: { type: "number" } }, required: ["argv"], additionalProperties: false } },
  { name: "update", description: "Ship the relay-published build to the phone: downloads + silent shell install, zero taps if opted in",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "pair", description: "Start pairing a phone: mints a one-time code (10 min) and returns a QR + link for the user to scan with the phone camera. Show the QR to the user, then call pair_wait. Optional relay overrides the public URL.",
    inputSchema: { type: "object", properties: { relay: { type: "string" } }, additionalProperties: false } },
  { name: "pair_wait", description: "Block until the phone redeems the pairing code (up to 10 min). Call after showing the QR from pair.",
    inputSchema: { type: "object", properties: { code: { type: "string" } }, additionalProperties: false } },
  { name: "pair_list", description: "Paired devices (keys never shown)",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "revoke", description: "Revoke a device: drops its key and socket",
    inputSchema: { type: "object", properties: { deviceId: { type: "string" } }, required: ["deviceId"], additionalProperties: false } },
  { name: "jobs", description: "Jobs currently queued or awaiting results",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
  { name: "doctor", description: "Relay health: listener, tunnel, devices, queues",
    inputSchema: { type: "object", properties: {}, additionalProperties: false } },
];

const callTool = (name: string, params?: any) => (proxyMode ? remoteCall(name, params) : localCall(name, params));

// ---- pairing: public URL, redemption tracking ----
const redeemed = new Map<string, { deviceId: string; name: string }>();
const pairWaiters = new Map<string, Array<{ resolve: (v: unknown) => void; reject: (e: Error) => void; timer: Timer }>>();
let latestCode = "";
function markRedeemed(code: string, d: Device) {
  const info = { deviceId: d.id, name: d.name };
  redeemed.set(code, info);
  setTimeout(() => redeemed.delete(code), 10 * 60 * 1000);
  for (const w of pairWaiters.get(code) ?? []) { clearTimeout(w.timer); w.resolve({ paired: true, ...info }); }
  pairWaiters.delete(code);
}
function voidCodes() {
  codes.clear();
  for (const ws of pairWaiters.values()) for (const w of ws) { clearTimeout(w.timer); w.reject(new Error("codes voided after 5 wrong tries: call pair again")); }
  pairWaiters.clear();
}
function tunnelHost(): string | null {
  const extra = ["~/.bun/bin", "~/.local/bin", "~/.cargo/bin"].map(p => p.replace("~", process.env.HOME ?? "")).join(":");
  const bin = Bun.which("opentunnel", { PATH: `${process.env.PATH ?? ""}:${extra}` });
  if (!bin) return null;
  try {
    const out = new TextDecoder().decode(Bun.spawnSync([bin, "route", "list"]).stdout);
    for (const line of out.split("\n")) {
      const m = line.match(/^(\S+)\s+(?:→|->)\s+(?:127\.0\.0\.1|localhost):(\d+)\s*$/);
      if (m && Number(m[2]) === PORT) return m[1];
    }
  } catch {}
  return null;
}
function lanIp(): string | null {
  const found: string[] = [];
  for (const [name, addrs] of Object.entries(networkInterfaces()))
    if (!/^(docker|br-|veth|virbr|lo)/.test(name))
      for (const a of addrs ?? []) if (a.family === "IPv4" && !a.internal) found.push(a.address);
  const tail = found.find(a => /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(a));
  return tail ?? found[0] ?? null;
}
/** Where the phone should dial: explicit, env, opentunnel route, tailnet, then LAN (same Wi-Fi only). */
function publicRelay(override?: string): { url: string; kind: string; warning?: string } {
  const o = (override ?? process.env.PHONE_PUBLIC_URL ?? "").trim().replace(/\/+$/, "");
  if (o) return { url: /^https?:\/\//.test(o) ? o : "https://" + o, kind: override ? "param" : "env" };
  const host = tunnelHost();
  if (host) return { url: "https://" + host, kind: "tunnel" };
  const ip = lanIp();
  if (!ip) throw new Error("no-public-url: set PHONE_PUBLIC_URL (e.g. a tunnel or Tailscale URL for port " + PORT + ")");
  const tail = ip.startsWith("100.");
  return { url: `http://${ip}:${PORT}`, kind: tail ? "tailnet" : "lan",
    warning: tail ? "Tailscale address: the phone must be on the same tailnet."
      : "LAN address: the phone must be on the same Wi-Fi. For use anywhere, set up a tunnel (see agent.md) and rerun pair." };
}

async function localCall(rawName: string, params?: any) {
  const name = "phone." + rawName.replace(/^phone[._]/, "");
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
      if (w.deviceId === id) { clearTimeout(w.timer); waiting.delete(jid); delivered.delete(jid);
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
    let publicUrl: unknown;
    try { publicUrl = publicRelay(); } catch (e) { publicUrl = (e as Error).message; }
    return {
      relay: { port: PORT, uptimeSec: Math.round(performance.now() / 1000) },
      publicUrl,
      devices: devices.size, online: [...sockets.keys()],
      queuedJobs: [...waiting.values()].filter(w => !online(w.deviceId)).length,
      liveJobs: [...waiting.values()].filter(w => online(w.deviceId)).length,
    };
  }
  if (name === "phone.pair") {
    if ([...codes.values()].filter(c => c.expires > Date.now()).length >= 5)
      throw new Error("too many pending codes");
    const relay = publicRelay(params?.relay);
    const code = makeCode();
    codes.set(code, { code, expires: Date.now() + 10 * 60 * 1000 });
    latestCode = code;
    const pairUrl = `${PAIR_BASE}#code=${code}&relay=${relay.url}`;
    const png = `${tmpdir()}/phone-pair-${code}.png`;
    await Bun.write(png, qrPng(pairUrl), { mode: 0o600 });
    setTimeout(() => { try { unlinkSync(png); } catch {} }, 10 * 60 * 1000);
    log("minted pairing code via MCP");
    return { code: prettyCode(code), expires_in: 600, pair_url: pairUrl, relay_url: relay.url,
      relay_kind: relay.kind, qr_png: png, qr: qrText(pairUrl),
      ...(relay.warning ? { warning: relay.warning } : {}),
      next: "Show the QR to the user (or the PNG path / link if it can't render), then call pair_wait." };
  }
  if (name === "phone.pair_wait") {
    const code = normCode(params?.code) || latestCode;
    if (!code) throw new Error("no pending code: call pair first");
    const done = redeemed.get(code);
    if (done) return { paired: true, ...done };
    const c = codes.get(code);
    if (!c || c.expires < Date.now()) throw new Error("code expired or voided: call pair again");
    return await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pairWaiters.set(code, (pairWaiters.get(code) ?? []).filter(w => w.timer !== timer));
        reject(new Error("timed out: the phone did not redeem the code in time. Call pair again."));
      }, c.expires - Date.now());
      pairWaiters.set(code, [...(pairWaiters.get(code) ?? []), { resolve, reject, timer }]);
    });
  }
  if (name === "phone.battery") {
    const dev = pickDevice(); // queue tolerates offline, hello flushes
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const data = await sendJob(dev.id, "battery.get", {});
    return data as Record<string, unknown>;
  }
  if (name === "phone.capture_photo") {
    const dev = pickDevice(); // queue tolerates offline, hello flushes
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const data = await sendJob(dev.id, "photo.capture", { maxPx: 1280, quality: 80 }, 300000);
    return data as Record<string, unknown>;
  }
  if (name === "phone.exec") {
    const dev = pickDevice();
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    const argv = params?.argv;
    if (!Array.isArray(argv) || argv.length === 0 || argv.some((a: unknown) => typeof a !== "string"))
      throw new Error("argv must be a non-empty string array");
    const data = await sendJob(dev.id, "exec.run",
      { argv, timeoutSec: Math.min(Math.max(params?.timeoutSec ?? 30, 5), 120) }, 300000);
    return data as Record<string, unknown>;
  }
  if (name === "phone.update") {
    const dev = pickDevice();
    if (!dev) throw new Error("no-device: pair the app and keep it online");
    let meta: any = null;
    try { meta = await Bun.file(DIST_DIR + "/version.json").json(); }
    catch { throw new Error("no build published in dist/"); }
    const data = await sendJob(dev.id, "app.update",
      { versionCode: meta.versionCode, versionName: meta.versionName ?? "",
        notes: meta.notes ?? "" }, 180000);
    return data as Record<string, unknown>;
  }
  throw new Error("unknown tool " + name);
}

function reply(id: unknown, result: unknown) {
  try {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
  } catch { /* MCP client gone; relay keeps serving the device channel */ }
}
function replyErr(id: unknown, code: number, message: string) {
  try {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n");
  } catch { /* MCP client gone; relay keeps serving the device channel */ }
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
          if (id !== undefined) {
            const { qr, ...rest } = (out ?? {}) as Record<string, unknown>;
            const content: { type: "text"; text: string }[] = [{ type: "text", text: JSON.stringify(rest, null, 2) }];
            if (typeof qr === "string") content.push({ type: "text", text: qr });
            reply(id, { content });
          }
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

if (!proxyMode) await load();
await mcpLoop();
