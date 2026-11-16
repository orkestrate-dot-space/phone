// Builds the static pages in ./phone from ./src/*.html. Run: bun site/build.ts
// Inlines the Orkestrate mark and real QR codes (encoder: ../desktop/src/qrcodegen.ts).
import { qrcodegen } from "../desktop/src/qrcodegen";

const DIR = import.meta.dir;
const SITE = "https://mobile.orkestrate.space";

function qrSvg(text: string): string {
  const qr = qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);
  let d = "";
  for (let y = 0; y < qr.size; y++)
    for (let x = 0; x < qr.size; x++) if (qr.getModule(x, y)) d += `M${x} ${y}h1v1h-1z`;
  return `<svg class="qr" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${qr.size} ${qr.size}" shape-rendering="crispEdges" role="img" aria-label="QR code: ${text}"><path fill="#000" d="${d}"/></svg>`;
}

const parts: Record<string, string> = {
  mark: (await Bun.file(`${DIR}/assets/orkestrate-mark.svg`).text()).replace(/<svg /, '<svg aria-hidden="true" '),
  "qr-app": qrSvg(`${SITE}/phone/app`),
  // Illustrative values (design.md 6.4): a real QR of a sample link, not a live code.
  "qr-demo": qrSvg(`${SITE}/phone/pair#code=K7QM4XWD&relay=https://example-relay.opentunnel.xyz`),
  head: await Bun.file(`${DIR}/src/_head.html`).text(),
  nav: await Bun.file(`${DIR}/src/_nav.html`).text(),
  footer: await Bun.file(`${DIR}/src/_footer.html`).text(),
};
const expand = (s: string) => {
  for (let i = 0; i < 3; i++) s = s.replace(/\{\{([\w-]+)\}\}/g, (_, k) => parts[k] ?? `MISSING:${k}`);
  return s;
};
const pages: Record<string, string> = {
  "index.html": "phone/index.html",
  "pair.html": "phone/pair/index.html",
  "app.html": "phone/app/index.html",
};
for (const [src, out] of Object.entries(pages)) {
  const html = expand(await Bun.file(`${DIR}/src/${src}`).text());
  if (html.includes("MISSING:")) throw new Error(`${src}: unresolved partial`);
  await Bun.write(`${DIR}/${out}`, html);
  console.log("built", out, html.length, "bytes");
}
