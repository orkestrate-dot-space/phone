// QR rendering for phone.pair: terminal text (half blocks) and a PNG, no deps.
// Encoder: Nayuki qrcodegen (MIT), vendored in ./qrcodegen.ts.
import { deflateSync } from "node:zlib";
import { qrcodegen } from "./qrcodegen";

const make = (text: string) => qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);

/** Dark-on-light for a dark terminal: light modules are drawn as filled blocks. */
export function qrText(text: string, quiet = 2): string {
  const qr = make(text);
  const n = qr.size;
  const light = (x: number, y: number) =>
    x < 0 || y < 0 || x >= n || y >= n ? true : !qr.getModule(x, y);
  const lines: string[] = [];
  for (let y = -quiet; y < n + quiet; y += 2) {
    let line = "";
    for (let x = -quiet; x < n + quiet; x++) {
      const top = light(x, y), bot = light(x, y + 1);
      line += top && bot ? "█" : top ? "▀" : bot ? "▄" : " ";
    }
    lines.push(line);
  }
  return lines.join("\n");
}

const crcTable = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
const crc32 = (b: Uint8Array) => {
  let c = 0xffffffff;
  for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (type: string, data: Uint8Array) => {
  const out = new Uint8Array(12 + data.length);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, data.length);
  out.set(new TextEncoder().encode(type), 4);
  out.set(data, 8);
  dv.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
};

/** 1-bit grayscale PNG, black modules on white, `scale` px per module. */
export function qrPng(text: string, scale = 8, quiet = 4): Uint8Array {
  const qr = make(text);
  const px = (qr.size + quiet * 2) * scale;
  const rowBytes = 1 + Math.ceil(px / 8);
  const raw = new Uint8Array(rowBytes * px).fill(0xff);
  for (let y = 0; y < px; y++) {
    raw[y * rowBytes] = 0;
    const my = Math.floor(y / scale) - quiet;
    if (my < 0 || my >= qr.size) continue;
    for (let x = 0; x < px; x++) {
      const mx = Math.floor(x / scale) - quiet;
      if (mx >= 0 && mx < qr.size && qr.getModule(mx, my))
        raw[y * rowBytes + 1 + (x >> 3)] &= ~(0x80 >> (x & 7));
    }
  }
  const ihdr = new Uint8Array(13);
  const dv = new DataView(ihdr.buffer);
  dv.setUint32(0, px); dv.setUint32(4, px);
  ihdr[8] = 1; ihdr[9] = 0; // 1-bit grayscale
  const parts = [
    new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", new Uint8Array(0)),
  ];
  const out = new Uint8Array(parts.reduce((a, p) => a + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
