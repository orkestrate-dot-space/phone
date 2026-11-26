// AdbDriver — zero-APK Android control over ADB. No dependencies.
//
// Transports:
//   - Relay (companion app): remote access, on-glass approvals, full file/photo/clipboard.
//   - ADB (this file): local USB / emulator / wireless debugging. No APK install needed.
//
// Safety (hardcoded, non-negotiable):
//   - Raw `adb shell` is refused unless explicitly allowed (--allow-shell / PHONE_ALLOW_SHELL=1).
//   - `files.delete` is ALWAYS refused in ADB mode (no /sdcard delete).
//   - `files.push` / `files.write` are strictly jailed to /sdcard/Download/orkestrate/
//     (.. traversal and any path outside the jail are rejected).
//   - Approvals can never be faked: adbHandoff only reports needs_human, never resolves.

import { chmodSync, statSync } from "node:fs";
import { tmpdir } from "node:os";

export type AdbDevice = {
  serial: string;
  state: string;
  model?: string;
  transport: "adb";
};

export type UiNode = {
  ref: string;
  role: string;
  text: string;
  desc: string;
  bounds: [number, number, number, number];
  clickable: boolean;
  editable: boolean;
  scrollable: boolean;
  checked: boolean;
  focused: boolean;
  package: string;
};

const log = (...a: unknown[]) => console.error("[phone:adb]", ...a);
const randHex = (n: number) =>
  [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");

// ---- low-level adb ----

function adbPath(): string {
  const p = Bun.which("adb");
  if (!p)
    throw new Error(
      "adb not found on PATH: install Android platform-tools (https://developer.android.com/tools/releases/platform-tools), " +
        "then run `adb devices` to confirm your device/emulator is visible",
    );
  return p;
}

function spawnAdb(args: string[], stdin?: Uint8Array): { code: number; out: string; err: string; bytes: Uint8Array } {
  const p = Bun.spawnSync([adbPath(), ...args], {
    stdout: "pipe",
    stderr: "pipe",
    stdin: stdin ?? "ignore",
  } as any);
  const bytes = new Uint8Array(p.stdout as unknown as Uint8Array);
  return {
    code: p.exitCode ?? 1,
    out: new TextDecoder().decode(bytes),
    err: new TextDecoder().decode(p.stderr as unknown as Uint8Array).trim(),
    bytes,
  };
}

/** Run adb, return stdout text; throw a self-correcting error on failure. */
export function runAdbText(args: string[]): string {
  const r = spawnAdb(args);
  if (r.code !== 0)
    throw new Error(`adb ${args.join(" ")} failed (exit ${r.code}): ${r.err || r.out.trim() || "no output"}`);
  return r.out;
}

// ---- device discovery ----

function parseAdbDevices(text: string): AdbDevice[] {
  const out: AdbDevice[] = [];
  const lines = text.split("\n").slice(1); // skip "List of devices attached"
  for (const line of lines) {
    const t = line.trim();
    if (!t) continue;
    const parts = t.split(/\s+/);
    const serial = parts[0];
    const state = parts[1] ?? "unknown";
    if (!serial) continue;
    let model: string | undefined;
    for (const kv of parts.slice(2)) {
      const m = kv.match(/^model:(.+)$/);
      if (m) model = m[1];
    }
    out.push({ serial, state, ...(model ? { model } : {}), transport: "adb" });
  }
  return out;
}

const normModel = (s: string) => s.replace(/_/g, " ").toLowerCase();
const online = (d: AdbDevice) => d.state === "device";

/** List ADB devices. When none are online, tries `adb mdns services` + `adb connect` once, then re-lists. */
export function listAdbDevices(opts?: { autoConnect?: boolean }): AdbDevice[] {
  const autoConnect = opts?.autoConnect ?? true;
  let devices = parseAdbDevices(runAdbText(["devices", "-l"]));
  if (autoConnect && !devices.some(online)) {
    try {
      const mdns = runAdbText(["mdns", "services"]);
      const m = mdns.match(/_adb-tls-connect\._tcp\s+([^\s:]+:\d+)/);
      if (m) {
        log(`connecting to wireless adb ${m[1]}...`);
        try {
          runAdbText(["connect", m[1]]);
        } catch (e) {
          log("adb connect failed:", (e as Error).message);
        }
        devices = parseAdbDevices(runAdbText(["devices", "-l"]));
      }
    } catch (e) {
      log("mdns fallback failed:", (e as Error).message);
    }
  }
  return devices;
}

function adbDeviceHelp(): string {
  return "run `adb devices` (plug in USB, accept the RSA prompt, or `adb connect <ip>:<port>` for wireless debugging)";
}

/** Resolve a requested serial/model to a serial, or auto-select when exactly 1 online device exists. */
export function resolveAdbDevice(requested?: string): string {
  const all = listAdbDevices();
  const up = all.filter(online);
  const req = (requested ?? "").trim();
  if (req) {
    const bySerial = all.find((d) => d.serial === req);
    if (bySerial) {
      if (!online(bySerial))
        throw new Error(
          `adb device "${req}" is ${bySerial.state} (not online): reconnect it, then ${adbDeviceHelp()}`,
        );
      return bySerial.serial;
    }
    const byModel = up.filter((d) => d.model && normModel(d.model) === normModel(req));
    if (byModel.length === 1) return byModel[0].serial;
    if (byModel.length > 1)
      throw new Error(
        `multiple adb devices match model "${req}": ${byModel.map((d) => d.serial).join(", ")} — specify the serial`,
      );
    throw new Error(
      `unknown adb device "${req}": no serial or model matches. Seen: ${up.map((d) => `${d.serial}${d.model ? ` (${d.model})` : ""}`).join(", ") || "none"} — ${adbDeviceHelp()}`,
    );
  }
  if (up.length === 1) return up[0].serial;
  if (up.length === 0)
    throw new Error(`no adb devices online: ${adbDeviceHelp()}, or pair the companion app with \`phone pair\``);
  throw new Error(
    `multiple adb devices online: ${up.map((d) => `${d.serial}${d.model ? ` (${d.model})` : ""}`).join(", ")} — specify one with the device parameter`,
  );
}

// ---- uiautomator XML parsing (zero-dep) ----

const ENTITY_MAP: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', "#39": "'", "#x27": "'" };

function decodeEntities(s: string): string {
  return s.replace(/&(amp|lt|gt|quot|#39|#x27);|&#(\d+);|&#x([0-9a-fA-F]+);/g, (m, named: string, dec: string, hex: string) => {
    if (named) return ENTITY_MAP[named] ?? m;
    const code = dec ? parseInt(dec, 10) : parseInt(hex, 16);
    try {
      return String.fromCodePoint(code);
    } catch {
      return m;
    }
  });
}

function parseBounds(s: string): [number, number, number, number] {
  const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(s);
  if (!m) return [0, 0, 0, 0];
  return [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])];
}

/**
 * Parse `uiautomator dump` XML into compact nodes. Filters empty non-interactive
 * containers (no text/desc and not clickable, editable, scrollable, or checkable).
 * Kept nodes get stable refs @e1, @e2, ... in document order.
 */
export function parseUiAutomatorXml(xml: string): UiNode[] {
  const nodes: UiNode[] = [];
  const tagRe = /<node\b([^>]*)>/g;
  const attrRe = /([\w:.-]+)\s*=\s*"([^"]*)"/g;
  let tag: RegExpExecArray | null;
  while ((tag = tagRe.exec(xml)) !== null) {
    const attrs: Record<string, string> = {};
    attrRe.lastIndex = 0;
    let a: RegExpExecArray | null;
    while ((a = attrRe.exec(tag[1])) !== null) attrs[a[1]] = decodeEntities(a[2]);
    const text = attrs["text"] ?? "";
    const desc = attrs["content-desc"] ?? "";
    const cls = attrs["class"] ?? "";
    const clickable = attrs["clickable"] === "true";
    const scrollable = attrs["scrollable"] === "true";
    const editable = attrs["focusable"] === "true" || /EditText/i.test(cls);
    const checkable = attrs["checkable"] === "true";
    if (!text && !desc && !clickable && !editable && !scrollable && !checkable) continue; // empty container
    nodes.push({
      ref: `@e${nodes.length + 1}`,
      role: cls.includes(".") ? (cls.split(".").pop() as string) : cls || "View",
      text,
      desc,
      bounds: parseBounds(attrs["bounds"] ?? ""),
      clickable,
      editable,
      scrollable,
      checked: attrs["checked"] === "true",
      focused: attrs["focused"] === "true",
      package: attrs["package"] ?? "",
    });
  }
  return nodes;
}

/** One-line-per-node tree, same shape as the relay path. */
export function formatCompactTree(nodes: UiNode[]): string {
  return nodes
    .map((n) => {
      const label = [n.text, n.desc].filter(Boolean).map((s: string) => JSON.stringify(s)).join(" ");
      const flags = [
        n.clickable && "click",
        n.editable && "edit",
        n.scrollable && "scroll",
        n.checked && "checked",
        n.focused && "focused",
      ]
        .filter(Boolean)
        .join(",");
      const b = Array.isArray(n.bounds) ? `[${n.bounds.join(",")}]` : "";
      return `${n.ref} ${n.role || "View"}${label ? " " + label : ""} ${b}${flags ? " " + flags : ""}`.trim();
    })
    .join("\n");
}

/** Last observed nodes per serial, for @eN ref resolution. */
export const lastNodesBySerial = new Map<string, UiNode[]>();

/** Resolve `@eN` to its center point, or throw a self-correcting error. */
export function resolveAdbRef(serial: string, ref: string): { x: number; y: number; node: UiNode } {
  const nodes = lastNodesBySerial.get(serial);
  if (!nodes)
    throw new Error(
      `unknown ref "${ref}": no UI tree cached for ${serial} — call observe first (phone.observe with include ["screen"]), then use a ref from the tree`,
    );
  const m = /^@e(\d+)$/.exec(ref.trim());
  if (!m)
    throw new Error(`bad ref "${ref}": use @eN from the last observe tree (e.g. @e1..@e${nodes.length || 1})`);
  const node = nodes[Number(m[1]) - 1];
  if (!node)
    throw new Error(
      `stale ref "${ref}": the tree for ${serial} has ${nodes.length} node(s) — call observe first, then use @e1..@e${nodes.length || 1}`,
    );
  const [x1, y1, x2, y2] = node.bounds;
  return { x: Math.round((x1 + x2) / 2), y: Math.round((y1 + y2) / 2), node };
}

// ---- observe ----

function adbScreen(serial: string): Record<string, unknown> {
  let xml = "";
  try {
    xml = spawnAdb(["-s", serial, "exec-out", "uiautomator", "dump", "/dev/tty"]).out;
  } catch (e) {
    log("dump /dev/tty failed:", (e as Error).message);
  }
  if (!xml.includes("<hierarchy")) {
    // Fallback for older devices: dump to a file, then cat it.
    try {
      runAdbText(["-s", serial, "shell", "uiautomator", "dump", "/data/local/tmp/uidump.xml"]);
      xml = spawnAdb(["-s", serial, "exec-out", "cat", "/data/local/tmp/uidump.xml"]).out;
    } catch (e) {
      log("dump fallback failed:", (e as Error).message);
    }
  }
  if (!xml.includes("<hierarchy")) {
    const secure = /FLAG_SECURE|secure/i.test(xml);
    return {
      package: "",
      nodes: [],
      tree: "",
      blocked: true,
      secureScreen: true,
      reason: secure
        ? "screen is FLAG_SECURE (banking/DRM): uiautomator cannot see it — use handoff"
        : "uiautomator dump failed (screen off? locked?) — wake/unlock the device, or use handoff",
    };
  }
  const nodes = parseUiAutomatorXml(xml);
  lastNodesBySerial.set(serial, nodes);
  const counts = new Map<string, number>();
  for (const n of nodes) if (n.package) counts.set(n.package, (counts.get(n.package) ?? 0) + 1);
  const pkg = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? "";
  return { package: pkg, nodes, tree: formatCompactTree(nodes), blocked: false, secureScreen: false };
}

async function adbScreenshot(serial: string): Promise<Record<string, unknown>> {
  const r = spawnAdb(["-s", serial, "exec-out", "screencap", "-p"]);
  if (r.code !== 0 || r.bytes.length < 100)
    throw new Error(`screencap failed: ${r.err || "no output"} — is the screen on?`);
  const outPath = `${tmpdir()}/phone-screen-${randHex(6)}.png`;
  await Bun.write(outPath, r.bytes, { mode: 0o600 });
  try {
    chmodSync(outPath, 0o600);
  } catch {}
  return {
    screenshotPath: outPath,
    screenshotBytes: r.bytes.length,
    screenshot_b64: Buffer.from(r.bytes).toString("base64"),
    screenshotMime: "image/png",
  };
}

function adbDeviceInfo(serial: string): Record<string, unknown> {
  const model = runAdbText(["-s", serial, "shell", "getprop", "ro.product.model"]).trim();
  const androidRelease = runAdbText(["-s", serial, "shell", "getprop", "ro.build.version.release"]).trim();
  let batteryLevel: number | null = null;
  let batteryStatus: string | null = null;
  try {
    const dump = runAdbText(["-s", serial, "shell", "dumpsys", "battery"]);
    const lvl = dump.match(/^\s*level:\s*(\d+)/m);
    if (lvl) batteryLevel = Number(lvl[1]);
    const st = dump.match(/^\s*status:\s*(\d+)/m);
    if (st)
      batteryStatus =
        ({ 1: "unknown", 2: "charging", 3: "discharging", 4: "not-charging", 5: "full" } as Record<string, string>)[st[1]] ??
        st[1];
  } catch (e) {
    log("dumpsys battery failed:", (e as Error).message);
  }
  return { serial, transport: "adb", model, androidRelease, batteryLevel, batteryStatus };
}

function adbApps(serial: string, query?: string): string[] {
  const out = runAdbText(["-s", serial, "shell", "pm", "list", "packages", "-3"]);
  const q = (query ?? "").trim().toLowerCase();
  const pkgs: string[] = [];
  for (const line of out.split("\n")) {
    const m = line.trim().match(/^package:(.+)$/);
    if (!m) continue;
    if (q && !m[1].toLowerCase().includes(q)) continue;
    pkgs.push(m[1]);
  }
  return pkgs;
}

/** Bounded compact summary of active notifications (best-effort parse, never throws). */
function adbNotifications(serial: string): Array<Record<string, unknown>> {
  let text: string;
  try {
    text = runAdbText(["-s", serial, "shell", "dumpsys", "notification", "--noredact"]);
  } catch (e) {
    log("dumpsys notification failed:", (e as Error).message);
    return [];
  }
  const items: Array<Record<string, unknown>> = [];
  const lines = text.split("\n");
  let cur: Record<string, unknown> | null = null;
  let scanned = 0;
  const clean = (s: string) => s.trim().replace(/\s+/g, " ").replace(/[,}]+$/, "").slice(0, 160) || undefined;
  for (const line of lines) {
    const rec = line.match(/NotificationRecord.*pkg=([^\s)]+)/);
    if (rec) {
      if (cur) items.push(cur);
      if (items.length >= 20) break;
      cur = { pkg: rec[1] };
      scanned = 0;
      continue;
    }
    if (cur && scanned < 12) {
      scanned++;
      const t = line.match(/android\.title=(.*)/);
      if (t && cur.title === undefined) cur.title = clean(t[1]);
      const x = line.match(/android\.text=(.*)/);
      if (x && cur.text === undefined) cur.text = clean(x[1]);
    }
  }
  if (cur && items.length < 20) items.push(cur);
  return items;
}

export type AdbObserveParams = {
  include?: string[];
  query?: string;
  find?: string;
};

/** Observe a device over ADB. Screen failures degrade to secureScreen instead of crashing. */
export async function adbObserve(serial: string, params?: AdbObserveParams): Promise<Record<string, unknown>> {
  const inc = new Set(Array.isArray(params?.include) && params.include.length ? params.include : ["screen"]);
  const out: Record<string, unknown> = {};
  if (inc.has("screen")) Object.assign(out, adbScreen(serial));
  if (inc.has("screenshot")) {
    try {
      Object.assign(out, await adbScreenshot(serial));
    } catch (e) {
      out.screenshotError = (e as Error).message;
    }
  }
  if (inc.has("device")) out.device = adbDeviceInfo(serial);
  if (inc.has("apps")) out.apps = adbApps(serial, params?.query);
  if (inc.has("notifications")) out.notifications = adbNotifications(serial);
  if (typeof params?.find === "string" && params.find.trim() && typeof out.tree === "string") {
    const q = params.find.trim().toLowerCase();
    out.tree = (out.tree as string).split("\n").filter((l) => l.toLowerCase().includes(q)).join("\n");
  }
  return out;
}

// ---- act ----

function coordsFor(serial: string, step: Record<string, unknown>): { x: number; y: number } {
  if (typeof step.ref === "string" && step.ref.trim()) {
    const { x, y } = resolveAdbRef(serial, step.ref);
    return { x, y };
  }
  if (typeof step.x === "number" && typeof step.y === "number") return { x: Math.round(step.x), y: Math.round(step.y) };
  throw new Error(
    `tap needs a target: pass ref "@eN" from the last observe tree, or x/y coordinates — call observe first`,
  );
}

/** Escape for `adb shell input text`: spaces become %s; argv passing keeps shell metachars safe. */
export function escapeInputText(s: string): string {
  return s.split(" ").join("%s");
}

function wmSize(serial: string): { w: number; h: number } {
  const out = runAdbText(["-s", serial, "shell", "wm", "size"]);
  const m = out.match(/(\d+)\s*x\s*(\d+)/);
  if (!m) throw new Error(`could not read screen size (wm size): ${out.trim() || "no output"}`);
  return { w: Number(m[1]), h: Number(m[2]) };
}

const KEYCODES: Record<string, string> = { back: "4", home: "3", recents: "187", enter: "66" };

/** Perform one UI/app action over ADB. */
export function adbAct(serial: string, step: Record<string, unknown>): Record<string, unknown> {
  const action = step?.action;
  if (typeof action !== "string" || !action) throw new Error("action is required");
  switch (action) {
    case "tap": {
      const { x, y } = coordsFor(serial, step);
      runAdbText(["-s", serial, "shell", "input", "tap", String(x), String(y)]);
      return { ok: true, action, x, y };
    }
    case "long_press": {
      const { x, y } = coordsFor(serial, step);
      runAdbText(["-s", serial, "shell", "input", "swipe", String(x), String(y), String(x), String(y), "600"]);
      return { ok: true, action, x, y, durationMs: 600 };
    }
    case "type": {
      if (typeof step.ref === "string" && step.ref.trim()) {
        const { x, y } = resolveAdbRef(serial, step.ref);
        runAdbText(["-s", serial, "shell", "input", "tap", String(x), String(y)]);
      } else if (typeof step.x === "number" && typeof step.y === "number") {
        runAdbText(["-s", serial, "shell", "input", "tap", String(Math.round(step.x)), String(Math.round(step.y))]);
      }
      if (step.clear) {
        runAdbText(["-s", serial, "shell", "input", "keyevent", "123"]); // MOVE_END
        runAdbText(["-s", serial, "shell", "for i in $(seq 1 40); do input keyevent 67; done"]);
      }
      const text = String(step.text ?? "");
      if (text) runAdbText(["-s", serial, "shell", "input", "text", escapeInputText(text)]);
      if (step.submit) runAdbText(["-s", serial, "shell", "input", "keyevent", "66"]);
      return { ok: true, action, typed: text.length, submitted: Boolean(step.submit) };
    }
    case "scroll": {
      const dir = String(step.direction ?? "down");
      if (!["up", "down", "left", "right"].includes(dir))
        throw new Error(`bad scroll direction "${dir}": use up, down, left, or right`);
      const { w, h } = wmSize(serial);
      const cx = Math.round(w / 2);
      const cy = Math.round(h / 2);
      const [x1, y1, x2, y2] =
        dir === "down"
          ? [cx, Math.round(h * 0.7), cx, Math.round(h * 0.3)]
          : dir === "up"
            ? [cx, Math.round(h * 0.3), cx, Math.round(h * 0.7)]
            : dir === "left"
              ? [Math.round(w * 0.7), cy, Math.round(w * 0.3), cy]
              : [Math.round(w * 0.3), cy, Math.round(w * 0.7), cy];
      runAdbText(["-s", serial, "shell", "input", "swipe", String(x1), String(y1), String(x2), String(y2), "300"]);
      return { ok: true, action, direction: dir };
    }
    case "swipe": {
      for (const k of ["x", "y", "x2", "y2"])
        if (typeof step[k] !== "number") throw new Error(`swipe needs numeric ${["x", "y", "x2", "y2"].join(", ")}`);
      const argv = ["-s", serial, "shell", "input", "swipe",
        String(Math.round(step.x as number)), String(Math.round(step.y as number)),
        String(Math.round(step.x2 as number)), String(Math.round(step.y2 as number))];
      if (typeof step.durationMs === "number") argv.push(String(Math.round(step.durationMs as number)));
      runAdbText(argv);
      return { ok: true, action };
    }
    case "key": {
      const k = String(step.key ?? "");
      const code = KEYCODES[k];
      if (!code) throw new Error(`bad key "${k}": use back, home, recents, or enter`);
      runAdbText(["-s", serial, "shell", "input", "keyevent", code]);
      return { ok: true, action, key: k };
    }
    case "open": {
      if (typeof step.url === "string" && step.url.trim()) {
        runAdbText(["-s", serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", step.url.trim()]);
        return { ok: true, action, url: step.url.trim() };
      }
      if (typeof step.package === "string" && step.package.trim()) {
        const pkg = step.package.trim();
        runAdbText(["-s", serial, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"]);
        return { ok: true, action, package: pkg };
      }
      throw new Error(`open needs url ("https://...") or package ("com.example.app")`);
    }
    default:
      throw new Error(`unsupported adb action "${action}": use tap, long_press, type, scroll, swipe, key, or open`);
  }
}

// ---- safety-gated shell/files/handoff ----

/** Raw shell. Refused by default; only runs with explicit allowShell (CLI --allow-shell / PHONE_ALLOW_SHELL=1). */
export function adbShell(serial: string, argv: unknown, allowShell?: boolean): Record<string, unknown> {
  if (allowShell !== true)
    throw new Error(
      "shell is blocked by default in ADB mode: re-run with --allow-shell (or PHONE_ALLOW_SHELL=1) to allow raw `adb shell` commands",
    );
  if (!Array.isArray(argv) || argv.length === 0 || argv.some((a) => typeof a !== "string"))
    throw new Error("argv must be a non-empty string array");
  return { ok: true, output: runAdbText(["-s", serial, "shell", ...(argv as string[])]) };
}

const PUSH_JAIL = "/sdcard/Download/orkestrate/";

function posixNormalize(p: string): string {
  const abs = p.startsWith("/");
  const parts = p.split("/").filter((s) => s.length > 0 && s !== ".");
  return (abs ? "/" : "") + parts.join("/");
}

/** Validate a push/write destination: jailed to PUSH_JAIL, no `..` traversal. Returns normalized path. */
export function assertJailedRemotePath(p: unknown): string {
  if (typeof p !== "string" || !p.trim()) throw new Error("path is required");
  const raw = p.trim();
  const norm = posixNormalize(raw);
  if (raw.split("/").includes("..") || norm.split("/").includes("..") || /(^|\/)\.\.($|\/)/.test(raw))
    throw new Error(`rejected path "${raw}": ".." traversal is not allowed in ADB mode`);
  if (norm !== "/sdcard/Download/orkestrate" && !norm.startsWith(PUSH_JAIL))
    throw new Error(`rejected path "${raw}": ADB push/write is jailed to ${PUSH_JAIL} (got ${norm})`);
  return norm;
}

export type AdbFilesParams = {
  action?: string;
  path?: string;
  dest?: string;
  localPath?: string;
  content?: string;
  encoding?: string;
  maxBytes?: number;
};

/** Files over ADB. `delete` is ALWAYS refused; push/write are jailed; list/pull/read are allowed. */
export async function adbFiles(serial: string, params: AdbFilesParams): Promise<Record<string, unknown>> {
  const action = params?.action;
  if (!action) throw new Error("action is required");
  if (action === "delete")
    throw new Error(
      "files.delete is ALWAYS refused in ADB mode (no /sdcard delete): remove files on the device screen instead, or use handoff",
    );
  if (action === "search" || action === "move")
    throw new Error(
      `files.${action} is not supported in ADB mode: use action "list" to browse, "read" to inspect, or pair the companion app for full file ops`,
    );
  if (action === "list") {
    const dir = (params.path ?? "/sdcard").trim() || "/sdcard";
    return { action, path: dir, listing: runAdbText(["-s", serial, "shell", "ls", "-la", dir]) };
  }
  if (action === "read") {
    if (!params.path) throw new Error("path is required");
    const maxBytes = Math.min(Math.max(Number(params.maxBytes ?? 65536), 1), 1024 * 1024);
    const r = spawnAdb(["-s", serial, "exec-out", "cat", params.path.trim()]);
    if (r.code !== 0) throw new Error(`read failed for ${params.path}: ${r.err || "no such file?"}`);
    const truncated = r.bytes.length > maxBytes;
    const slice = r.bytes.slice(0, maxBytes);
    return {
      action,
      path: params.path,
      bytes: r.bytes.length,
      truncated,
      content: Buffer.from(slice).toString("base64"),
      encoding: "base64" as const,
    };
  }
  if (action === "pull") {
    if (!params.path) throw new Error("path is required");
    const remote = params.path.trim();
    const local = (params.localPath ?? `${tmpdir()}/phone-pull-${randHex(4)}-${remote.split("/").pop()}`).trim();
    const r = spawnAdb(["-s", serial, "exec-out", "cat", remote]);
    if (r.code !== 0) throw new Error(`pull failed for ${remote}: ${r.err || "no such file?"}`);
    await Bun.write(local, r.bytes, { mode: 0o600 });
    try {
      chmodSync(local, 0o600);
    } catch {}
    return { action, path: local, bytes: r.bytes.length, remotePath: remote };
  }
  if (action === "push") {
    if (!params.localPath) throw new Error("localPath is required for push");
    if (!params.path) throw new Error("path is required for push");
    const remote = assertJailedRemotePath(params.path);
    // Bun.file().exists() is async; use a sync stat so validation stays fail-fast.
    try {
      const st = statSync(params.localPath);
      if (!st.isFile()) throw new Error("not a file");
      if (st.size > 10 * 1024 * 1024) throw new Error("file exceeds 10 MiB limit");
    } catch (e) {
      if ((e as Error).message === "not a file" || (e as Error).message === "file exceeds 10 MiB limit") throw e;
      throw new Error("local file not found: " + params.localPath);
    }
    runAdbText(["-s", serial, "shell", "mkdir", "-p", remote.split("/").slice(0, -1).join("/") || PUSH_JAIL.slice(0, -1)]);
    const r = spawnAdb(["-s", serial, "push", params.localPath, remote]);
    if (r.code !== 0) throw new Error(`push failed: ${r.err || r.out.trim()}`);
    return { ok: true, action, path: remote, pushed: r.out.trim() };
  }
  if (action === "write") {
    if (!params.path) throw new Error("path is required");
    const remote = assertJailedRemotePath(params.path);
    let bytes: Uint8Array;
    if (params.encoding === "base64") bytes = Buffer.from(String(params.content ?? ""), "base64");
    else bytes = new TextEncoder().encode(String(params.content ?? ""));
    if (bytes.length > 10 * 1024 * 1024) throw new Error("content exceeds 10 MiB limit");
    runAdbText(["-s", serial, "shell", "mkdir", "-p", remote.split("/").slice(0, -1).join("/") || PUSH_JAIL.slice(0, -1)]);
    const r = spawnAdb(["-s", serial, "shell", "cat > " + remote], bytes);
    if (r.code !== 0) throw new Error(`write failed for ${remote}: ${r.err || "no output"}`);
    return { ok: true, action, path: remote, bytes: bytes.length };
  }
  throw new Error(`unknown files action "${action}": use list, read, pull, push, or write`);
}

/**
 * Pause for the human. NEVER resolves on its own and never fakes a tap —
 * completion is reported by the agent after the user confirms.
 */
export async function adbHandoff(serial: string, message: string): Promise<Record<string, unknown>> {
  const msg = String(message ?? "").trim();
  if (!msg) throw new Error("message is required");
  const res: Record<string, unknown> = {
    status: "needs_human",
    transport: "adb",
    device: serial,
    message: msg,
    next: "Show the message to the user; they complete the step on the device screen, then tell you it is done (or describe what they see).",
  };
  try {
    const shot = await adbScreenshot(serial);
    if (typeof shot.screenshotPath === "string") res.screenshotPath = shot.screenshotPath;
  } catch {
    // screenshot is best-effort; the handoff itself must not fail
  }
  return res;
}

// ---- MCP content: native image blocks (base64 stays out of the JSON text) ----

export type McpContentBlock =
  | { type: "text"; text: string }
  | { type: "image"; data: string; mimeType: string };

/** Build MCP content blocks: screenshot_b64 becomes a native image block, never JSON text. */
export function toMcpContent(out: unknown): McpContentBlock[] {
  const o = (out ?? {}) as Record<string, unknown>;
  const { qr, screenshot_b64, ...rest } = o;
  const content: McpContentBlock[] = [{ type: "text", text: JSON.stringify(rest, null, 2) }];
  if (typeof qr === "string") content.push({ type: "text", text: qr });
  if (typeof screenshot_b64 === "string" && screenshot_b64.length > 0)
    content.push({
      type: "image",
      data: screenshot_b64,
      mimeType: typeof o.screenshotMime === "string" ? o.screenshotMime : "image/jpeg",
    });
  return content;
}
