import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  adbAct,
  adbFiles,
  adbInstall,
  adbObserve,
  adbShell,
  assertJailedRemotePath,
  formatCompactTree,
  lastNodesBySerial,
  parseUiAutomatorXml,
  resolveAdbRef,
  toMcpContent,
} from "./adb";

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

  test("MCP initialize + tools/list exposes the 5 consolidated tools", async () => {
    const init = await rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "test", version: "1" } });
    expect(init.result.serverInfo.name).toBe("phone");
    const list = await rpc("tools/list");
    const names = list.result.tools.map((t: any) => t.name);
    expect(names).toEqual(["observe", "act", "files", "handoff", "shell"]);
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

  test("tools/list: observe is read-only, devices enum + device params present", async () => {
    const list = await rpc("tools/list");
    const byName = Object.fromEntries(list.result.tools.map((t: any) => [t.name, t]));
    expect(byName.observe.annotations).toEqual({ readOnlyHint: true });
    const include = byName.observe.inputSchema.properties.include.items.enum;
    expect(include).toContain("devices");
    expect(byName.observe.inputSchema.properties.device.type).toBe("string");
    for (const n of ["act", "files", "handoff", "shell"])
      expect(byName[n].inputSchema.properties.device.type).toBe("string");
  });
});

describe("adb driver (no live phone/adb daemon)", () => {
  const XML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
    <node index="0" text="Hello &amp; goodbye &lt;3" resource-id="com.example:id/title" class="android.widget.TextView" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" focusable="false" focused="false" scrollable="false" bounds="[100,200][980,300]" />
    <node index="1" text="" resource-id="" class="android.view.ViewGroup" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" focusable="false" focused="false" scrollable="false" bounds="[0,300][1080,600]" />
    <node index="2" text="OK" resource-id="" class="android.widget.Button" package="com.example" content-desc="Confirm &quot;yes&quot; &#39;A&#39; &#65;" checkable="false" checked="false" clickable="true" focusable="true" focused="false" scrollable="false" bounds="[100,700][500,800]" />
    <node index="3" text="" resource-id="" class="android.widget.EditText" package="com.example" content-desc="" checkable="false" checked="false" clickable="true" focusable="true" focused="true" scrollable="false" bounds="[100,900][980,1000]" />
  </node>
</hierarchy>`;

  test("parseUiAutomatorXml: entities, bounds, filtering, @e1.. refs", () => {
    const nodes = parseUiAutomatorXml(XML);
    // FrameLayout container + empty ViewGroup are filtered; TextView/Button/EditText kept.
    expect(nodes.map((n) => n.ref)).toEqual(["@e1", "@e2", "@e3"]);
    expect(nodes[0].text).toBe("Hello & goodbye <3");
    expect(nodes[0].role).toBe("TextView");
    expect(nodes[0].bounds).toEqual([100, 200, 980, 300]);
    expect(nodes[0].clickable).toBe(false);
    expect(nodes[1].role).toBe("Button");
    expect(nodes[1].clickable).toBe(true);
    expect(nodes[1].desc).toBe('Confirm "yes" \'A\' A'); // &quot; &#39; &#65;
    expect(nodes[2].role).toBe("EditText");
    expect(nodes[2].editable).toBe(true);
    expect(nodes[2].focused).toBe(true);
    expect(nodes.every((n) => n.package === "com.example")).toBe(true);
  });

  test("formatCompactTree renders @eN lines with labels, bounds, flags", () => {
    const tree = formatCompactTree(parseUiAutomatorXml(XML));
    const lines = tree.split("\n");
    expect(lines).toHaveLength(3);
    expect(lines[0]).toBe('@e1 TextView "Hello & goodbye <3" [100,200,980,300]');
    expect(lines[1]).toContain('@e2 Button "OK"');
    expect(lines[1]).toContain("click");
    expect(lines[2]).toContain("edit");
  });

  test("resolveAdbRef: center hit, stale + unknown-serial errors", () => {
    lastNodesBySerial.set("test-serial", parseUiAutomatorXml(XML));
    const hit = resolveAdbRef("test-serial", "@e2");
    expect(hit.x).toBe(300); // (100+500)/2
    expect(hit.y).toBe(750); // (700+800)/2
    expect(() => resolveAdbRef("test-serial", "@e9")).toThrow(/stale ref "@e9"/);
    expect(() => resolveAdbRef("test-serial", "@e9")).toThrow(/call observe first/);
    expect(() => resolveAdbRef("never-seen", "@e1")).toThrow(/call observe first/);
    lastNodesBySerial.delete("test-serial");
  });

  test("adbShell is blocked by default", () => {
    expect(() => adbShell("any-serial", ["echo", "hi"])).toThrow(/blocked by default.*--allow-shell/);
  });

  test("adbFiles.delete is ALWAYS refused", async () => {
    await expect(adbFiles("any-serial", { action: "delete", path: "/sdcard/whatever" })).rejects.toThrow(
      /ALWAYS refused/,
    );
  });

  test("adbFiles.push/write jail: rejects /sdcard/DCIM + .. traversal, allows the jail", async () => {
    await expect(
      adbFiles("any-serial", { action: "push", localPath: "/tmp/x", path: "/sdcard/DCIM/photo.jpg" }),
    ).rejects.toThrow(/jailed to \/sdcard\/Download\/orkestrate\//);
    await expect(
      adbFiles("any-serial", { action: "push", localPath: "/tmp/x", path: "/sdcard/Download/orkestrate/../evil.apk" }),
    ).rejects.toThrow(/traversal/);
    await expect(
      adbFiles("any-serial", { action: "write", path: "/sdcard/Music/note.txt", content: "hi" }),
    ).rejects.toThrow(/jailed to/);
    expect(assertJailedRemotePath("/sdcard/Download/orkestrate/notes/a.txt")).toBe(
      "/sdcard/Download/orkestrate/notes/a.txt",
    );
  });

  test("toMcpContent: screenshot becomes a native image block, base64 stays out of text", () => {
    const content = toMcpContent({
      tree: "@e1 Button",
      screenshot_b64: Buffer.from("fakepng").toString("base64"),
      screenshotMime: "image/png",
      screenshotPath: "/tmp/phone-screen-abc.png",
    });
    expect(content).toHaveLength(2);
    expect(content[0]).toEqual({
      type: "text",
      text: expect.not.stringContaining("ZmFrZXBuZw=="),
    });
    const text = (content[0] as { text: string }).text;
    expect(text).toContain("/tmp/phone-screen-abc.png"); // path intact
    expect(content[1]).toEqual({ type: "image", data: "ZmFrZXBuZw==", mimeType: "image/png" });
  });

  test("adbInstall validates apkPath before spawning adb", async () => {
    await expect(adbInstall("emulator-5554", "")).rejects.toThrow(/apkPath is required/);
    await expect(adbInstall("emulator-5554", "/tmp/does-not-exist-orkestrate.apk")).rejects.toThrow(/APK not found/);
  });

  test("toMcpContent defaults to image/jpeg and skips image block when absent", () => {
    const withDefault = toMcpContent({ tree: "x", screenshot_b64: "eA==" });
    expect(withDefault[1]).toEqual({ type: "image", data: "eA==", mimeType: "image/jpeg" });
    const plain = toMcpContent({ tree: "x" });
    expect(plain).toEqual([{ type: "text", text: JSON.stringify({ tree: "x" }, null, 2) }]);
  });
});

describe("adb AX upgrade v0.24.2", () => {
  const XML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
    <node index="0" text="Hello" resource-id="" class="android.widget.TextView" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" focusable="false" focused="false" scrollable="false" bounds="[100,200][980,300]" />
    <node index="1" text="OK" resource-id="" class="android.widget.Button" package="com.example" content-desc="" checkable="false" checked="false" clickable="true" focusable="true" focused="false" scrollable="false" bounds="[100,700][500,800]" />
    <node index="2" text="" resource-id="" class="android.widget.EditText" package="com.example" content-desc="" checkable="false" checked="false" clickable="true" focusable="true" focused="true" scrollable="false" bounds="[100,900][980,1000]" />
  </node>
</hierarchy>`;
  const enc = new TextEncoder();

  function mockSpawn(rec: string[][], dumpXml: string = XML) {
    const origSpawnSync = Bun.spawnSync;
    const origSleepSync = (Bun as any).sleepSync;
    const sleepCalls: number[] = [];
    (Bun as any).sleepSync = (ms: number) => { sleepCalls.push(ms); };
    (Bun as any).spawnSync = (cmd: string[], _opts?: unknown) => {
      rec.push(cmd);
      const joined = cmd.join(" ");
      if (joined.includes("uiautomator") && joined.includes("dump")) {
        return { exitCode: 0, stdout: enc.encode(dumpXml), stderr: enc.encode("") };
      }
      return { exitCode: 0, stdout: enc.encode(""), stderr: enc.encode("") };
    };
    return () => {
      (Bun as any).spawnSync = origSpawnSync;
      (Bun as any).sleepSync = origSleepSync;
      return sleepCalls;
    };
  }

  test("compact screen output omits nodes unless verbose: true", async () => {
    const rec: string[][] = [];
    const restore = mockSpawn(rec);
    try {
      const compact = await adbObserve("v242-serial", { include: ["screen"] });
      expect("nodes" in compact).toBe(false);
      expect(compact.package).toBe("com.example");
      expect(typeof compact.tree).toBe("string");
      expect((compact.tree as string)).toContain("@e");
      // refs still work: cache populated internally
      const hit = resolveAdbRef("v242-serial", "@e2");
      expect(hit.x).toBe(300);
      const verbose = await adbObserve("v242-serial", { include: ["screen"], verbose: true });
      expect(Array.isArray((verbose as Record<string, unknown>).nodes)).toBe(true);
      expect(((verbose as Record<string, unknown>).nodes as unknown[])).toHaveLength(3);
    } finally {
      restore();
      lastNodesBySerial.delete("v242-serial");
    }
  });

  test('adbAct "back" / "home" shorthand and case-insensitive key normalization ("BACK")', () => {
    const rec: string[][] = [];
    const restore = mockSpawn(rec);
    try {
      const b = adbAct("s1", { action: "back" });
      expect(b).toEqual({ ok: true, action: "back" });
      expect(rec.at(-1)?.join(" ")).toContain("keyevent 4");

      const h = adbAct("s1", { action: "home" });
      expect(h).toEqual({ ok: true, action: "home" });
      expect(rec.at(-1)?.join(" ")).toContain("keyevent 3");

      const r = adbAct("s1", { action: "recents" });
      expect(r).toEqual({ ok: true, action: "recents" });
      expect(rec.at(-1)?.join(" ")).toContain("keyevent 187");

      const k = adbAct("s1", { action: "key", key: "BACK" });
      expect(k).toEqual({ ok: true, action: "key", key: "back" });
      expect(rec.at(-1)?.join(" ")).toContain("keyevent 4");

      const k2 = adbAct("s1", { action: "key", key: "  Home " });
      expect(k2).toEqual({ ok: true, action: "key", key: "home" });
      expect(rec.at(-1)?.join(" ")).toContain("keyevent 3");
    } finally {
      restore();
    }
  });

  test('adbAct "type" with clear: true and ref/x,y tap-first behavior', () => {
    const rec: string[][] = [];
    const restore = mockSpawn(rec);
    try {
      lastNodesBySerial.set("s2", parseUiAutomatorXml(XML));

      // ref + clear: tap @e2 (300,750), sleep 150, Ctrl+A, DEL, then text
      rec.length = 0;
      const out = adbAct("s2", { action: "type", ref: "@e2", text: "hi", clear: true });
      expect(out).toEqual({ ok: true, action: "type", typed: 2, submitted: false });
      expect(rec.length).toBe(4);
      expect(rec[0].join(" ")).toContain("input tap 300 750");
      expect(rec[1].join(" ")).toContain("keycombination 113 29");
      expect(rec[2].join(" ")).toContain("keyevent 67");
      expect(rec[3].join(" ")).toContain("input text hi");

      // x/y without clear: tap first, then text, no clear seq
      rec.length = 0;
      adbAct("s2", { action: "type", x: 100, y: 200, text: "yo" });
      expect(rec.length).toBe(2);
      expect(rec[0].join(" ")).toContain("input tap 100 200");
      expect(rec[1].join(" ")).toContain("input text yo");
      expect(rec.join(" ")).not.toContain("keycombination");
    } finally {
      restore();
      lastNodesBySerial.delete("s2");
    }
  });
});
