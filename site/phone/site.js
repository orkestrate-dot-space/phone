// Lucide paths (lucide-react only, per design.md section 8).
const I = {
  "chevron-right": '<path d="m9 18 6-6-6-6"/>',
  "chevron-down": '<path d="m6 9 6 6 6-6"/>',
  "arrow-left": '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  "arrow-right": '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
  "arrow-up-right": '<path d="M7 7h10v10"/><path d="M7 17 17 7"/>',
  copy: '<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
  scan: '<path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><rect x="7" y="7" width="10" height="10" rx="1"/>',
  bell: '<path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/>',
  battery: '<rect width="16" height="10" x="2" y="7" rx="2"/><line x1="22" x2="22" y1="11" y2="13"/><line x1="6" x2="6" y1="11" y2="13"/><line x1="10" x2="10" y1="11" y2="13"/>',
  camera: '<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/>',
  alert: '<circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/>',
  share: '<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><polyline points="16 6 12 2 8 6"/><line x1="12" x2="12" y1="2" y2="15"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
  menu: '<line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="18" y2="18"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  paste: '<rect width="8" height="4" x="8" y="2" rx="1"/><path d="M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2"/>',
  settings: '<path d="M20 7h-9"/><path d="M14 17H5"/><circle cx="17" cy="17" r="3"/><circle cx="7" cy="7" r="3"/>',
  shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
};
document.querySelectorAll("i[data-i]").forEach((el) => {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("viewBox", "0 0 24 24");
  s.setAttribute("class", "i " + (el.className || ""));
  s.setAttribute("aria-hidden", "true");
  s.innerHTML = I[el.dataset.i] || "";
  el.replaceWith(s);
});

// Harmonograph: the shipped component's formula (orkestrate website
// src/components/landing/harmonograph.tsx): 5200 points, step 0.024, decay 0.0042,
// 0.7px at white/16, ~25fps, dpr capped at 1.5, paused off screen, still under reduced motion.
document.querySelectorAll("canvas.harm").forEach((canvas) => {
  const ctx = canvas.getContext("2d");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const op = parseFloat(canvas.dataset.op || "1");
  let w = 0, h = 0, on = true, last = 0, phase = 0.6;
  const size = () => {
    const dpr = Math.min(devicePixelRatio || 1, 1.5);
    w = canvas.clientWidth; h = canvas.clientHeight;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  };
  const draw = () => {
    ctx.clearRect(0, 0, w, h);
    const ax = w * 0.52, ay = h * 0.62;
    ctx.beginPath();
    for (let i = 0; i < 5200; i++) {
      const t = i * 0.024, d = Math.exp(-0.0042 * t);
      const x = ax * d * (0.6 * Math.sin(3 * t + phase) + 0.4 * Math.sin(2.003 * t + 1.1));
      const y = ay * d * (0.6 * Math.sin(2 * t) + 0.4 * Math.sin(3.004 * t + phase * 0.5 + 0.3));
      i ? ctx.lineTo(w / 2 + x, h / 2 + y) : ctx.moveTo(w / 2 + x, h / 2 + y);
    }
    ctx.strokeStyle = `rgba(255,255,255,${0.16 * op})`; ctx.lineWidth = 0.7; ctx.stroke();
  };
  const frame = (now) => {
    requestAnimationFrame(frame);
    if (!on || now - last < 40) return;
    phase += (now - last) * 0.00006; last = now; draw();
  };
  size(); draw();
  new ResizeObserver(() => { size(); draw(); }).observe(canvas);
  new IntersectionObserver(([e]) => { on = e.isIntersecting; }).observe(canvas);
  if (!reduce) requestAnimationFrame(frame);
});

// Copy button: swaps to a check for 2s and announces "copied".
const live = document.getElementById("live");
document.querySelectorAll("[data-copy]").forEach((btn) => {
  btn.addEventListener("click", async () => {
    const text = document.querySelector(btn.dataset.copy)?.textContent?.trim();
    if (!text) return;
    try { await navigator.clipboard.writeText(text); } catch { return; }
    const old = btn.innerHTML;
    btn.innerHTML = '<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';
    if (live) live.textContent = "copied";
    setTimeout(() => { btn.innerHTML = old; if (live) live.textContent = ""; }, 2000);
  });
});

// Setup tabs: one command block, two commands.
const seg = document.querySelector("[data-seg]");
if (seg) {
  const code = document.querySelector("#cmd-text"), after = document.querySelector("#cmd-after");
  seg.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => {
    seg.querySelectorAll("button").forEach((o) => { o.classList.toggle("on", o === b); o.setAttribute("aria-pressed", o === b); });
    code.textContent = b.dataset.cmd; after.textContent = b.dataset.after;
  }));
}

// "Send link to my computer": the system share sheet, so nobody retypes a URL.
document.querySelectorAll("[data-share]").forEach((b) => {
  if (!navigator.share) { b.hidden = true; return; }
  b.addEventListener("click", () => navigator.share({ title: "Phone MCP", url: "https://mobile.orkestrate.space/phone" }).catch(() => {}));
});
