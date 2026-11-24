import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const PORT = 19897;
let dir = "";
let proc: Bun.Subprocess<"pipe", "pipe", "pipe">;
let stdinWriter: FileSink;
const pending = new Map<number, (msg: any) => void>();
let nextId = 1;

async function rpc(method: string, params: unknown = {}): Promise<any> {
  const id = nextId++;
  const p = new Promise((resolve) => pending.set(id, resolve));
  stdinWriter.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
  return await p;
}

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "phone-test-"));
  proc = Bun.spawn([process.execPath, join(import.meta.dir, "index.ts")], {
    env: { ...process.env, PHONE_PORT: String(PORT), PHONE_DATA: dir, PHONE_PUBLIC_URL: `http://127.0.0.1:${PORT}` },
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
  });
  stdinWriter = proc.stdin;

  // Read stdout NDJSON
  (async () => {
    const dec = new TextDecoder();
    let buf = "";
    for await (const chunk of proc.stdout) {
      buf += dec.decode(chunk);
      let idx;
      while ((idx = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        const msg = JSON.parse(line);
        const cb = pending.get(msg.id);
        if (cb) { pending.delete(msg.id); cb(msg); }
      }
    }
  })();

  // Wait for HTTP /version
  for (let i = 0; i < 30; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/version`);
      if (r.ok) break;
    } catch {}
    await Bun.sleep(100);
  }
});

afterAll(() => {
  try { proc.kill(); } catch {}
  try { rmSync(dir, { recursive: true, force: true }); } catch {}
});

describe("phone desktop relay + MCP", () => {
  test("/version answers publicly", async () => {
    const r = await fetch(`http://127.0.0.1:${PORT}/version`);
    expect(r.status).toBe(200);
    const j = await r.json() as any;
    expect(j.ok).toBe(true);
    expect(j.relay).toBe("phone");
  });

  test("MCP initialize + tools/list exposes all 19 tools", async () => {
    const init = await rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "test", version: "1" } });
    expect(init.result.serverInfo.name).toBe("phone");
    const list = await rpc("tools/list");
    const names = list.result.tools.map((t: any) => t.name);
    for (const expected of ["status", "device", "battery", "screen", "act", "apps", "files", "notifications", "clipboard", "handoff", "capture_photo", "exec", "update", "pair", "pair_wait", "pair_list", "revoke", "jobs", "doctor"]) {
      expect(names).toContain(expected);
    }
  });

  test("5-strike wrong pairing codes void all pending", async () => {
    const p1 = await rpc("tools/call", { name: "pair", arguments: {} });
    const meta = JSON.parse(p1.result.content[0].text);
    expect(meta.code).toMatch(/^[0-9A-Z]{4}-[0-9A-Z]{4}$/);

    for (let i = 0; i < 5; i++) {
      const r = await fetch(`http://127.0.0.1:${PORT}/pair/00000000`, { method: "POST" });
      expect(r.status).toBe(401);
    }
    const rValid = await fetch(`http://127.0.0.1:${PORT}/pair/${meta.code}`, { method: "POST" });
    expect(rValid.status).toBe(401);
  });

  test("pair -> WS hello -> screen screenshot temp file + files push/pull -> revoke", async () => {
    const p = await rpc("tools/call", { name: "pair", arguments: {} });
    const code = JSON.parse(p.result.content[0].text).code;

    const redeem = await fetch(`http://127.0.0.1:${PORT}/pair/${code}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "TestPhone" }),
    });
    expect(redeem.status).toBe(200);
    const { deviceId, token } = await redeem.json() as any;

    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/device?token=${token}`);
    await new Promise<void>((resolve) => {
      ws.onopen = () => {
        ws.send(JSON.stringify({
          t: "hello",
          deviceId,
          name: "TestPhone",
          appVersion: 43,
          paused: false,
          permissions: { accessibility: true, files: true },
          caps: ["device.info", "screen.observe", "files.op"],
        }));
      };
      ws.onmessage = (ev) => {
        const m = JSON.parse(String(ev.data));
        if (m.t === "welcome") resolve();
        if (m.t === "job") {
          const j = m.job;
          if (j.capability === "screen.observe") {
            ws.send(JSON.stringify({
              t: "result", id: j.id, ok: true,
              data: { package: "com.example", nodes: [], screenshot_b64: Buffer.from("fakejpg").toString("base64") },
            }));
          } else if (j.capability === "files.op" && j.params.action === "pull") {
            ws.send(JSON.stringify({
              t: "result", id: j.id, ok: true,
              data: { path: j.params.path, bytes_b64: Buffer.from("hello-phone").toString("base64") },
            }));
          } else if (j.capability === "files.op" && j.params.action === "push") {
            ws.send(JSON.stringify({
              t: "result", id: j.id, ok: true,
              data: { path: j.params.path, bytes: Buffer.from(j.params.bytes_b64, "base64").length },
            }));
          }
        }
      };
    });

    const scr = await rpc("tools/call", { name: "screen", arguments: { screenshot: true } });
    const scrJson = JSON.parse(scr.result.content[0].text);
    expect(scrJson.screenshotPath).toBeTruthy();
    expect(scrJson.screenshot_b64).toBeUndefined();
    expect(statSync(scrJson.screenshotPath).mode & 0o777).toBe(0o600);
    rmSync(scrJson.screenshotPath, { force: true });

    const localIn = join(dir, "in.txt");
    await Bun.write(localIn, "push-data");
    const pushRes = await rpc("tools/call", { name: "files", arguments: { action: "push", localPath: localIn, path: "Download/in.txt" } });
    expect(JSON.parse(pushRes.result.content[0].text).bytes).toBe(9);

    const localOut = join(dir, "out.txt");
    const pullRes = await rpc("tools/call", { name: "files", arguments: { action: "pull", path: "Download/out.txt", localPath: localOut } });
    expect(JSON.parse(pullRes.result.content[0].text).bytes).toBe(11);
    expect(await Bun.file(localOut).text()).toBe("hello-phone");

    const rev = await rpc("tools/call", { name: "revoke", arguments: { deviceId } });
    expect(JSON.parse(rev.result.content[0].text).revoked).toBe(deviceId);
    ws.close();
  });
});
