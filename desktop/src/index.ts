#!/usr/bin/env bun
// phone desktop process v1 — MCP (stdio) + embedded relay (HTTP+WS).
// Logs go to stderr ALWAYS (stdout is the MCP channel). No deps.

const PORT = Number(process.env.PHONE_PORT ?? "19877");
const DATA_DIR = process.env.PHONE_DATA ?? new URL("../data/", import.meta.url).pathname;
const DEVICES_FILE = DATA_DIR + "/devices.json";
const DIST_DIR = process.env.PHONE_DIST ?? new URL("../../dist/", import.meta.url).pathname;
// Control socket: the one running relay serves tool calls to agent MCP processes and the
// CLI over this unix socket (filesystem-permissioned, unreachable through the tunnel).
const SOCK = process.env.PHONE_SOCK ?? DATA_DIR + "/control.sock";
const PAIR_BASE = process.env.PHONE_PAIR_BASE ?? "https://mobile.orkestrate.space/phone/pair";
const MAX_PUSH_BYTES = 10 * 1024 * 1024;

import { chmodSync, existsSync, mkdirSync, unlinkSync } from "node:fs";
import { networkInterfaces, tmpdir } from "node:os";
import { basename } from "node:path";
import { qrPng, qrText } from "./qr";
import {
  adbAct,
  adbFiles,
  adbHandoff,
  adbObserve,
  adbShell,
  formatCompactTree,
  listAdbDevices,
  resolveAdbDevice,
  toMcpContent,
} from "./adb";
import pkg from "../package.json";

const VERSION = (pkg as { version?: string }).version ?? "0.24.0";
// Raw `adb shell` is blocked by default; this flag (or env) opts the relay process into it.
const ALLOW_SHELL = process.argv.includes("--allow-shell") || process.env.PHONE_ALLOW_SHELL === "1";

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // 32 symbols, no I/L/O/U
const rand = (n: number) => crypto.getRandomValues(new Uint8Array(n));
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

// ---- CLI ----
const CLI = new Set([
  "pair", "wait", "device", "battery", "screen", "act", "apps", "files",
  "notifications", "clipboard", "handoff", "status", "doctor", "revoke",
  "photo", "exec", "devices", "install-app", "observe", "shell",
]);
const CLI_TOOL: Record<string, string> = { wait: "pair_wait", photo: "capture_photo", devices: "pair_list" };

async function installAppViaAdb(args: string[]) {
  const flag = (f: string) => args.includes(f);
  const rest = args.filter(a => !a.startsWith("--"));
  const localDist = `${DIST_DIR}/app.apk`;
  let apkPath = rest[0] ?? "";
  let downloaded = false;

  if (!apkPath) {
    if (!flag("--download") && existsSync(localDist)) {
      apkPath = localDist;
    } else {
      apkPath = `${tmpdir()}/orkestrate-phone.apk`;
      const url = "https://mobile.orkestrate.space/orkestrate-phone.apk";
      console.error(`[phone] downloading ${url}...`);
      const res = await fetch(url);
      if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`);
      await Bun.write(apkPath, new Uint8Array(await res.arrayBuffer()));
      downloaded = true;
    }
  }

  try {
    const hasDevice = () => {
      const d = Bun.spawnSync(["adb", "devices"]);
      if (d.exitCode !== 0) throw new Error(`adb devices failed: ${d.stderr.toString().trim()}`);
      return d.stdout.toString().split("\n").some(l => /\tdevice\s*$/.test(l));
    };

    if (!hasDevice()) {
      const mdns = Bun.spawnSync(["adb", "mdns", "services"]);
      const m = mdns.stdout.toString().match(/_adb-tls-connect\._tcp\s+([^\s:]+:\d+)/);
      if (m) {
        console.error(`[phone] connecting to wireless adb ${m[1]}...`);
        Bun.spawnSync(["adb", "connect", m[1]]);
      }
    }

    if (!hasDevice()) {
      throw new Error("no adb device connected. Plug in USB or pair Wireless Debugging (adb connect <ip>:<port>), then retry.");
    }

    console.error(`[phone] installing ${apkPath} via adb...`);
    const inst = Bun.spawnSync(["adb", "install", "-r", apkPath]);
    const out = (inst.stdout.toString() + inst.stderr.toString()).trim();
    if (inst.exitCode !== 0) throw new Error(`adb install failed: ${out}`);
    console.log(out || "Success");
  } finally {
    if (downloaded) { try { unlinkSync(apkPath); } catch {} }
  }
}

async function runCli(cmd: string, args: string[]) {
  if (cmd === "install-app") {
    await installAppViaAdb(args);
    return;
  }
  const flag = (f: string) => args.includes(f);
  const rest = args.filter(a => !a.startsWith("--"));
  const params: Record<string, unknown> = {};
  if (cmd === "revoke") params.deviceId = rest[0];
  if (cmd === "wait" && rest[0]) params.code = rest[0];
  if (cmd === "exec" || cmd === "shell") {
    const dash = args.indexOf("--");
    const flagIdx = args.indexOf("--allow-shell");
    if (flagIdx !== -1 && (dash === -1 || flagIdx < dash)) {
      (params as Record<string, unknown>).allowShell = true;
      args.splice(flagIdx, 1);
    }
    params.argv = args.slice(args[0] === "--" ? 1 : 0);
  }
  if (cmd === "screen") {
    if (flag("--screenshot")) params.screenshot = true;
  }
  if (cmd === "act") {
    if (!rest[0]) throw new Error("usage: phone act '<json>'");
    Object.assign(params, JSON.parse(rest[0]));
  }
  if (cmd === "apps") {
    const sub = rest[0] ?? "list";
    params.action = sub;
    if (sub === "open" && rest[1]) {
      if (/^https?:\/\//.test(rest[1])) params.url = rest[1];
      else params.package = rest[1];
    } else if (rest[1]) {
      params.query = rest[1];
    }
  }
  if (cmd === "files") {
    const sub = rest[0];
    if (!sub) throw new Error("usage: phone files <list|read|write|search|move|delete|pull|push> ...");
    params.action = sub;
    if (rest[1]) params.path = rest[1];
    if (sub === "move" && rest[2]) params.dest = rest[2];
    if (sub === "write" && rest[2] !== undefined) params.content = rest[2];
    if (sub === "search" && rest[1]) { params.query = rest[1]; if (rest[2]) params.path = rest[2]; else delete params.path; }
    if ((sub === "pull" || sub === "push") && rest[2]) params.localPath = rest[2];
  }
  if (cmd === "notifications") {
    const sub = rest[0] ?? "list";
    params.action = sub;
    if (rest[1]) params.key = rest[1];
    if (rest[2]) params.text = rest[2];
  }
  if (cmd === "clipboard") {
    const sub = rest[0] ?? "get";
    params.action = sub;
    if (sub === "set" && rest[1] !== undefined) params.text = rest.slice(1).join(" ");
  }
  if (cmd === "handoff") {
    params.message = rest.join(" ") || "Continue on your phone";
  }
  const relayArg = args.find(a => a.startsWith("--relay="));
  if (cmd === "pair" && relayArg) params.relay = relayArg.slice(8);
  if (cmd === "observe") {
    params.include = rest.length ? rest : ["screen"];
    if (flag("--screenshot")) params.include = [...new Set([...(params.include as string[]), "screenshot"])];
    if (flag("--diff")) params.diff = true;
    const findArg = args.find(a => a.startsWith("--find="));
    if (findArg) params.find = findArg.slice(7);
  }
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
type Device = {
  id: string;
  name: string;
  token: string;
  lastSeen: number;
  appVersion?: number;
  caps?: string[];
  paused?: boolean;
  permissions?: Record<string, boolean>;
};
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
type Waiter = {
  resolve: (v: unknown) => void;
  reject: (e: Error) => void;
  timer: Timer;
  cap: string;
  deviceId: string;
  job: Record<string, unknown>;
};
const waiting = new Map<string, Waiter>(); // by job id, queued or delivered
const delivered = new Set<string>(); // job ids already pushed down the socket
const sockets = new Map<string, any>(); // deviceId -> ws
const online = (id: string) => sockets.has(id);

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
Bun.serve({
  port: PORT,
  hostname: "0.0.0.0",
  async fetch(req, srv) {
    const u = new URL(req.url);
    // Public (the tunnel exposes it): reachability probe only, nothing about devices.
    if (u.pathname === "/version" || u.pathname === "/health") {
      return Response.json({ ok: true, relay: "phone", protocol: 1 });
    }
    // Redeem — phone path (LAN/tunnel ok; 40-bit code + 5-strike voiding)
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
    return new Response("phone relay v1", { status: 404 });
  },
  websocket: {
    maxPayloadLength: 32 * 1024 * 1024,
    open(ws: any) { ws.data = { ...ws.data, authed: false }; },
    async message(ws: any, raw: any) {
      let msg: any;
      try { msg = JSON.parse(String(raw)); } catch { return; }
      if (msg.t === "hello") {
        const dev = devices.get(msg.deviceId);
        const authedId = ws.data?.pendingAuth;
        if (!dev || dev.id !== authedId || msg.deviceId !== authedId) { ws.close(4401, "bad hello"); return; }
        if (msg.name) dev.name = String(msg.name).slice(0, 64);
        if (typeof msg.appVersion === "number") dev.appVersion = msg.appVersion;
        if (Array.isArray(msg.caps)) dev.caps = msg.caps.map(String);
        if (typeof msg.paused === "boolean") dev.paused = msg.paused;
        if (msg.permissions && typeof msg.permissions === "object") dev.permissions = msg.permissions;
        dev.lastSeen = Date.now();
        await save();
        sockets.set(dev.id, ws);
        ws.data.authed = true; ws.data.deviceId = dev.id;
        log(`hello ${dev.id} caps=${(msg.caps ?? []).join(",")}`);
        ws.send(JSON.stringify({ t: "welcome", deviceId: dev.id, lanUrl: lanRelayUrl() }));
        flushQueue(dev.id);
        return;
      }
      if (msg.t === "state") {
        const id = ws.data?.deviceId;
        const dev = id ? devices.get(id) : undefined;
        if (!dev) return;
        if (typeof msg.paused === "boolean") dev.paused = msg.paused;
        if (msg.permissions && typeof msg.permissions === "object") dev.permissions = msg.permissions;
        dev.lastSeen = Date.now();
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

log(proxyMode ? `relay already running on :${PORT}; forwarding tool calls to it`
  : `relay on :${PORT} data=${DATA_DIR}`);

// ---- MCP over stdio ----
function lanRelayUrl(): string | null {
  for (const ifs of Object.values(networkInterfaces())) {
    for (const i of ifs ?? []) {
      if (i.family === "IPv4" && !i.internal) return `http://${i.address}:${PORT}`;
    }
  }
  return null;
}

const TOOLS = [
  {
    name: "observe",
    description: "Observe the phone: compact UI tree (@e1..), device/battery/permissions, active notifications, installed apps, or screenshot. Defaults to [\"screen\"].",
    annotations: { readOnlyHint: true },
    inputSchema: {
      type: "object",
      properties: {
        include: {
          type: "array",
          items: { type: "string", enum: ["screen", "device", "notifications", "apps", "screenshot", "devices"] },
        },
        device: { type: "string", description: "Paired relay device id/name, or ADB serial/model. Defaults to the online relay device, else the ADB device." },
        maxPx: { type: "number" },
        quality: { type: "number" },
        query: { type: "string" },
        find: { type: "string", description: "Filter UI tree lines case-insensitively" },
        diff: { type: "boolean", description: "Return only UI tree lines added/changed since the last observe" },
      },
      additionalProperties: false,
    },
  },
  {
    name: "act",
    description: "Perform one or more UI/app/clipboard actions (tap, long_press, type, scroll, swipe, key, open, clipboard_get, clipboard_set, dismiss_notification). Supports batched steps[] and returns the settled UI tree by default (observe: true).",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["tap", "long_press", "type", "scroll", "swipe", "key", "open", "clipboard_get", "clipboard_set", "dismiss_notification"],
        },
        ref: { type: "string" },
        text: { type: "string" },
        x: { type: "number" },
        y: { type: "number" },
        x2: { type: "number" },
        y2: { type: "number" },
        clear: { type: "boolean" },
        submit: { type: "boolean" },
        direction: { type: "string", enum: ["up", "down", "left", "right"] },
        key: { type: "string", enum: ["back", "home", "recents", "enter"] },
        package: { type: "string" },
        url: { type: "string" },
        steps: { type: "array", items: { type: "object" } },
        observe: { type: "boolean" },
        device: { type: "string", description: "Paired relay device id/name, or ADB serial/model. Defaults to the online relay device, else the ADB device." },
      },
      additionalProperties: false,
    },
  },
  {
    name: "files",
    description: "Read, write, list, search, move, pull, push, or delete (on-glass approval) files in the granted folder or /sdcard.",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["list", "read", "write", "search", "move", "delete", "pull", "push"] },
        path: { type: "string" },
        dest: { type: "string" },
        localPath: { type: "string" },
        content: { type: "string" },
        encoding: { type: "string", enum: ["utf8", "base64"] },
        query: { type: "string" },
        maxBytes: { type: "number" },
        device: { type: "string", description: "Paired relay device id/name, or ADB serial/model. Defaults to the online relay device, else the ADB device." },
      },
      required: ["action"],
      additionalProperties: false,
    },
  },
  {
    name: "handoff",
    description: "Pause and ask the user to complete a step on their phone (login, 2FA, payment, confirmation), resolving only on a real tap.",
    inputSchema: {
      type: "object",
      properties: {
        message: { type: "string" },
        timeoutSec: { type: "number" },
        device: { type: "string", description: "Paired relay device id/name, or ADB serial/model. Defaults to the online relay device, else the ADB device." },
      },
      required: ["message"],
      additionalProperties: false,
    },
  },
  {
    name: "shell",
    description: "Run an allowlisted system command on the phone (on-glass approval unless exact-match auto-approved). Over ADB it is blocked by default; pass --allow-shell to permit it.",
    inputSchema: {
      type: "object",
      properties: {
        argv: { type: "array", items: { type: "string" } },
        timeoutSec: { type: "number" },
        device: { type: "string", description: "Paired relay device id/name, or ADB serial/model. Defaults to the online relay device, else the ADB device." },
      },
      required: ["argv"],
      additionalProperties: false,
    },
  },
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

type Route = { kind: "relay"; dev: Device } | { kind: "adb"; serial: string };

function relayDeviceByName(idOrName: string): Device | undefined {
  return devices.get(idOrName) ?? [...devices.values()].find((d) => d.name === idOrName);
}

function noDeviceErr(): Error {
  return new Error(
    "no-device: no online paired phone and no adb device found. " +
      "Run `adb devices` (USB / emulator / `adb connect <ip>:<port>`) for local control, " +
      "or `phone pair` to pair the companion app for remote access.",
  );
}

/**
 * Pick a transport for a device-touching call.
 * - `device` given and matching a paired relay device (id or name) → Relay.
 * - no `device`, but an online paired relay device exists → Relay.
 * - otherwise → ADB (resolve serial/model, auto-select when exactly 1 online).
 * - neither available → self-correcting error mentioning `adb devices` and `phone pair`.
 */
async function routeTarget(requested?: unknown): Promise<Route> {
  const req = typeof requested === "string" && requested.trim() ? requested.trim() : undefined;
  if (req) {
    const rd = relayDeviceByName(req);
    if (rd) return { kind: "relay", dev: rd };
    try {
      return { kind: "adb", serial: resolveAdbDevice(req) };
    } catch (e) {
      throw new Error(`unknown device "${req}": ${(e as Error).message} — see also \`phone pair\``);
    }
  }
  const on = [...devices.values()].find((d) => online(d.id));
  if (on) return { kind: "relay", dev: on };
  try {
    return { kind: "adb", serial: resolveAdbDevice(undefined) };
  } catch {
    throw noDeviceErr();
  }
}

async function localCall(rawName: string, params?: any) {
  const name = "phone." + rawName.replace(/^phone[._]/, "");
  if (name === "phone.status" || name === "phone.pair_list") {
    const list = [...devices.values()].map(d => ({
      id: d.id,
      name: d.name,
      appVersion: d.appVersion ?? null,
      online: online(d.id),
      paused: d.paused ?? false,
      permissions: d.permissions ?? null,
      caps: d.caps ?? null,
      lastSeen: d.lastSeen,
    }));
    return name === "phone.status" ? { paired: devices.size > 0, devices: list } : { devices: list };
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
  if (name === "phone.device") {
    const t = await routeTarget(params?.device);
    if (t.kind === "adb") return (await adbObserve(t.serial, { include: ["device"] })).device;
    const dev = t.dev;
    try {
      return await sendJob(dev.id, "device.info", {}) as Record<string, unknown>;
    } catch (e) {
      if ((e as Error).message.includes("unknown-capability")) {
        const battery = await sendJob(dev.id, "battery.get", {});
        return { name: dev.name, appVersion: dev.appVersion ?? null, battery };
      }
      throw e;
    }
  }
  if (name === "phone.battery") {
    const t = await routeTarget(params?.device);
    if (t.kind === "adb") {
      const info = (await adbObserve(t.serial, { include: ["device"] })).device as Record<string, unknown>;
      return { level: info.batteryLevel, status: info.batteryStatus, model: info.model };
    }
    const dev = t.dev;
    return await sendJob(dev.id, "battery.get", {}) as Record<string, unknown>;
  }
  if (name === "phone.screen") {
    const t = await routeTarget(params?.device);
    if (t.kind === "adb") {
      const res = await adbObserve(t.serial, {
        include: params?.screenshot ? ["screen", "screenshot"] : ["screen"],
      });
      if (!params?.inlineBase64) delete res.screenshot_b64;
      return res;
    }
    const dev = t.dev;
    const res = await sendJob(dev.id, "screen.observe", {
      screenshot: Boolean(params?.screenshot),
      maxPx: params?.maxPx ?? 1080,
      quality: params?.quality ?? 75,
    }) as Record<string, unknown>;
    if (typeof res.screenshot_b64 === "string") {
      const buf = Buffer.from(res.screenshot_b64, "base64");
      const outPath = `${tmpdir()}/phone-screen-${b64url(rand(6))}.jpg`;
      await Bun.write(outPath, buf, { mode: 0o600 });
      try { chmodSync(outPath, 0o600); } catch {}
      res.screenshotPath = outPath;
      res.screenshotBytes = buf.length;
      if (!params?.inlineBase64) delete res.screenshot_b64;
    }
    return res;
  }
  if (name === "phone.act_legacy") {
    const t = await routeTarget(params?.device);
    if (!params?.action) throw new Error("action is required");
    if (t.kind === "adb") return adbAct(t.serial, params);
    const dev = t.dev;
    const { device: _omitAct, ...jobParams } = params;
    return await sendJob(dev.id, "screen.act", jobParams) as Record<string, unknown>;
  }
  if (name === "phone.apps") {
    const t = await routeTarget(params?.device);
    if (!params?.action) throw new Error("action is required");
    if (t.kind === "adb") {
      if (params.action === "open") {
        if (params.package || params.url)
          return adbAct(t.serial, { action: "open", package: params.package, url: params.url });
        throw new Error("apps open needs package or url");
      }
      if (params.action === "list")
        return await adbObserve(t.serial, { include: ["apps"], query: params?.query });
      throw new Error(
        `apps.${params.action} is not supported over ADB (only list/open): pair the companion app for full app management`,
      );
    }
    const dev = t.dev;
    const { device: _omitApps, ...appParams } = params;
    return await sendJob(dev.id, "apps.manage", appParams) as Record<string, unknown>;
  }
  if (name === "phone.files") {
    const t = await routeTarget(params?.device);
    const action = params?.action;
    if (!action) throw new Error("action is required");
    if (t.kind === "adb") return await adbFiles(t.serial, params);
    const dev = t.dev;
    const jobParams: Record<string, unknown> = { ...params };
    delete jobParams.localPath;
    delete jobParams.device;
    if (action === "push") {
      if (!params.localPath) throw new Error("localPath is required for push");
      const f = Bun.file(params.localPath);
      if (!(await f.exists())) throw new Error("local file not found: " + params.localPath);
      if (f.size > MAX_PUSH_BYTES) throw new Error(`file exceeds ${MAX_PUSH_BYTES} bytes limit`);
      const buf = Buffer.from(await f.arrayBuffer());
      jobParams.bytes_b64 = buf.toString("base64");
      if (!jobParams.path) jobParams.path = basename(params.localPath);
    }
    const timeoutMs = action === "delete" ? 300000 : 60000;
    const res = await sendJob(dev.id, "files.op", jobParams, timeoutMs) as Record<string, unknown>;
    if (action === "pull" && typeof res.bytes_b64 === "string") {
      const buf = Buffer.from(res.bytes_b64, "base64");
      const outPath = params.localPath || `${tmpdir()}/phone-pull-${basename(String(params.path ?? "file"))}`;
      await Bun.write(outPath, buf, { mode: 0o600 });
      try { chmodSync(outPath, 0o600); } catch {}
      return { path: outPath, bytes: buf.length, remotePath: res.path ?? params.path };
    }
    return res;
  }
  if (name === "phone.notifications") {
    const t = await routeTarget(params?.device);
    if (!params?.action) throw new Error("action is required");
    if (t.kind === "adb") {
      if (params.action === "list") return await adbObserve(t.serial, { include: ["notifications"] });
      throw new Error(
        `notifications.${params.action} is not supported over ADB (only list): pair the companion app for dismiss/reply`,
      );
    }
    const dev = t.dev;
    const { device: _omitNotif, ...notifParams } = params;
    return await sendJob(dev.id, "notifications.op", notifParams) as Record<string, unknown>;
  }
  if (name === "phone.clipboard") {
    const t = await routeTarget(params?.device);
    if (!params?.action) throw new Error("action is required");
    if (t.kind === "adb")
      throw new Error("clipboard is not supported over ADB: pair the companion app for clipboard access");
    const dev = t.dev;
    const { device: _omitClip, ...clipParams } = params;
    return await sendJob(dev.id, "clipboard.op", clipParams) as Record<string, unknown>;
  }
  if (name === "phone.handoff") {
    const t = await routeTarget(params?.device);
    const message = String(params?.message ?? "").trim();
    if (!message) throw new Error("message is required");
    if (t.kind === "adb") return await adbHandoff(t.serial, message);
    const dev = t.dev;
    const timeoutSec = Math.min(Math.max(Number(params?.timeoutSec ?? 300), 30), 600);
    return await sendJob(dev.id, "human.handoff", { message, timeoutSec }, timeoutSec * 1000) as Record<string, unknown>;
  }
  if (name === "phone.capture_photo") {
    const t = await routeTarget(params?.device);
    if (t.kind === "adb")
      throw new Error("capture_photo is not supported over ADB (no camera API): use observe with include [\"screenshot\"]");
    const dev = t.dev;
    const res = await sendJob(dev.id, "photo.capture", {
      maxPx: params?.maxPx ?? 1280,
      quality: params?.quality ?? 80,
    }, 300000) as Record<string, unknown>;
    if (typeof res.bytes_b64 === "string") {
      const buf = Buffer.from(res.bytes_b64, "base64");
      const outPath = `${tmpdir()}/phone-photo-${b64url(rand(6))}.jpg`;
      await Bun.write(outPath, buf, { mode: 0o600 });
      try { chmodSync(outPath, 0o600); } catch {}
      res.path = outPath;
      res.bytes = buf.length;
      if (!params?.inlineBase64) delete res.bytes_b64;
    }
    return res;
  }
  if (name === "phone.exec") {
    const t = await routeTarget(params?.device);
    const argv = params?.argv;
    if (!Array.isArray(argv) || argv.length === 0 || argv.some((a: unknown) => typeof a !== "string"))
      throw new Error("argv must be a non-empty string array");
    if (t.kind === "adb") return adbShell(t.serial, argv, params?.allowShell === true || ALLOW_SHELL);
    const dev = t.dev;
    return await sendJob(dev.id, "exec.run",
      { argv, timeoutSec: Math.min(Math.max(params?.timeoutSec ?? 30, 5), 120) }, 300000) as Record<string, unknown>;
  }
  if (name === "phone.shell") {
    return await localCall("phone.exec", params);
  }
  if (name === "phone.observe") {
    const inc = new Set<string>(Array.isArray(params?.include) && params.include.length ? params.include : ["screen"]);
    const wantShot = inc.has("screenshot");
    const out: Record<string, unknown> = {};
    if (inc.has("devices")) {
      out.relay = [...devices.values()].map((d) => ({
        id: d.id,
        name: d.name,
        appVersion: d.appVersion ?? null,
        online: online(d.id),
        paused: d.paused ?? false,
        permissions: d.permissions ?? null,
        caps: d.caps ?? null,
        lastSeen: d.lastSeen,
      }));
      try {
        out.adb = listAdbDevices();
      } catch (e) {
        log("adb list failed:", (e as Error).message);
        out.adb = [];
      }
      inc.delete("devices");
      if (inc.size === 0) return out;
    }
    const t = await routeTarget(params?.device);
    if (t.kind === "adb") {
      const r = await adbObserve(t.serial, {
        include: [...inc],
        query: params?.query,
        find: params?.find,
      });
      if (typeof r.tree === "string" && r.tree && params?.diff) {
        const key = `adb:${t.serial}`;
        const prev = lastTreeByDevice.get(key);
        lastTreeByDevice.set(key, r.tree as string);
        if (prev !== undefined) {
          const prevSet = new Set(prev.split("\n"));
          const changed = (r.tree as string).split("\n").filter((l) => !prevSet.has(l));
          if (changed.length === 0) { r.unchanged = true; r.tree = ""; }
          else r.tree = changed.join("\n");
        }
      }
      if (!params?.inlineBase64) delete r.screenshot_b64;
      if ("relay" in out || "adb" in out) { r.relay = out.relay; r.adb = out.adb; }
      return r;
    }
    const dev = t.dev;
    const tasks: Promise<void>[] = [];
    if (inc.has("screen") || wantShot) {
      tasks.push(localCall("phone.screen", {
        screenshot: wantShot,
        maxPx: params?.maxPx,
        quality: params?.quality,
        device: dev.id,
        inlineBase64: params?.inlineBase64,
      }).then((s) => {
        out.package = s.package ?? "";
        out.blocked = s.blocked ?? false;
        out.secureScreen = s.secureScreen ?? false;
        if (s.reason) out.reason = s.reason;
        if (Array.isArray(s.nodes)) {
          let tree = formatCompactTree(s.nodes as any[]);
          const prev = lastTreeByDevice.get(dev.id);
          lastTreeByDevice.set(dev.id, tree);
          if (params?.diff && prev !== undefined) {
            const prevSet = new Set(prev.split("\n"));
            const changed = tree.split("\n").filter((l) => !prevSet.has(l));
            if (changed.length === 0) { out.unchanged = true; tree = ""; }
            else tree = changed.join("\n");
          }
          if (typeof params?.find === "string" && params.find.trim()) {
            const q = params.find.trim().toLowerCase();
            tree = tree.split("\n").filter((l) => l.toLowerCase().includes(q)).join("\n");
          }
          out.tree = tree;
        }
        if (s.screenshotPath) out.screenshotPath = s.screenshotPath;
        if (typeof s.screenshot_b64 === "string") out.screenshot_b64 = s.screenshot_b64;
        if (typeof s.screenshotMime === "string") out.screenshotMime = s.screenshotMime;
      }));
    }
    if (inc.has("device")) tasks.push(localCall("phone.device", { device: dev.id }).then((d) => { out.device = d; }));
    if (inc.has("notifications")) tasks.push(localCall("phone.notifications", { action: "list", device: dev.id }).then((n) => { out.notifications = n.notifications ?? n; }));
    if (inc.has("apps")) tasks.push(localCall("phone.apps", { action: "list", query: params?.query, device: dev.id }).then((a) => { out.apps = a.apps ?? a; }));
    await Promise.all(tasks);
    if (!params?.inlineBase64) delete out.screenshot_b64;
    return out;
  }
  if (name === "phone.act") {
    const t = await routeTarget(params?.device);
    const steps: any[] = Array.isArray(params?.steps) && params.steps.length ? params.steps : [params];
    if (t.kind === "adb") {
      const results: unknown[] = [];
      for (const step of steps) {
        const a = step?.action;
        if (!a) throw new Error("action is required");
        if (a === "clipboard_get" || a === "clipboard_set" || a === "dismiss_notification")
          throw new Error(`${a} is not supported over ADB: pair the companion app for clipboard/notification actions`);
        results.push(adbAct(t.serial, step));
      }
      const res: Record<string, unknown> = { ok: true, transport: "adb", device: t.serial, results };
      if (params?.observe !== false) {
        await Bun.sleep(250);
        res.screen = await adbObserve(t.serial, { include: ["screen"] });
      }
      return res;
    }
    const dev = t.dev;
    const results: unknown[] = [];
    let uiTouched = false;
    for (const step of steps) {
      const a = step?.action;
      if (!a) throw new Error("action is required");
      if (a === "open") {
        results.push(await localCall("phone.apps", { action: "open", package: step.package, url: step.url, device: dev.id }));
        uiTouched = true;
      } else if (a === "clipboard_get") {
        results.push(await localCall("phone.clipboard", { action: "get", device: dev.id }));
      } else if (a === "clipboard_set") {
        results.push(await localCall("phone.clipboard", { action: "set", text: step.text, device: dev.id }));
      } else if (a === "dismiss_notification") {
        results.push(await localCall("phone.notifications", { action: "dismiss", key: step.key, device: dev.id }));
      } else {
        const { device: _omitStep, ...stepParams } = step ?? {};
        results.push(await sendJob(dev.id, "screen.act", stepParams));
        uiTouched = true;
      }
    }
    const res: Record<string, unknown> = { ok: true, results };
    if (uiTouched && params?.observe !== false) {
      await Bun.sleep(250);
      res.screen = await localCall("phone.observe", { include: ["screen"], device: dev.id });
    }
    return res;
  }
  if (name === "phone.update") {
    const t = await routeTarget(params?.device);
    if (t.kind === "adb")
      throw new Error("app update needs the companion app: over ADB, install it with `phone install-app` instead");
    const dev = t.dev;
    let meta: any = null;
    try { meta = await Bun.file(DIST_DIR + "/version.json").json(); }
    catch { throw new Error("no build published in dist/"); }
    return await sendJob(dev.id, "app.update",
      { versionCode: meta.versionCode, versionName: meta.versionName ?? "",
        notes: meta.notes ?? "" }, 180000) as Record<string, unknown>;
  }
  throw new Error("unknown tool " + name);
}

const lastTreeByDevice = new Map<string, string>();

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
          capabilities: { tools: {} }, serverInfo: { name: "phone", version: VERSION } });
        break;
      case "notifications/initialized": break;
      case "ping": if (id !== undefined) reply(id, {}); break;
      case "tools/list":
        if (id !== undefined) reply(id, { tools: TOOLS });
        break;
      case "tools/call": {
        const name = msg.params?.name;
        try {
          const args = (msg.params?.arguments ?? {}) as Record<string, unknown>;
          // Screenshots ride along for the image-block builder, never for JSON text.
          const wantShot = name === "observe" || name === "phone.observe" || name === "screen" || name === "phone.screen";
          const out = await callTool(name, wantShot ? { ...args, inlineBase64: true } : args);
          if (id !== undefined) reply(id, { content: toMcpContent(out) });
        } catch (e) { if (id !== undefined) replyErr(id, -32000, (e as Error).message); }
        break;
      }
      default:
        if (id !== undefined) replyErr(id, -32601, "method not found: " + msg.method);
    }
  } catch (e) { if (id !== undefined) replyErr(id, -32603, String(e)); }
}

async function mcpLoop() {
  if (process.argv.includes("--serve")) { log("serve mode (no MCP)"); return; }
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
