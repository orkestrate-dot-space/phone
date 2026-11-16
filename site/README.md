# Phone MCP site (static)

Files for `mobile.orkestrate.space/phone`, built with the Orkestrate design system
(`orkestrate/website/public/design.md`). They are static on purpose: the website repo is
owned by another agent, so these are handed over rather than edited in place.

```
bun site/build.ts          # rebuild the three HTML pages from site/src/
```

| URL | File | Notes |
|---|---|---|
| `/phone` | `phone/index.html` | product page (design.md section 5 template) |
| `/phone/pair` | `phone/pair/index.html` | opened by the QR when the app is not installed; reads `#code=…&relay=…` in the browser only |
| `/phone/app` | `phone/app/index.html` | download page; the QR on `/phone` points here |
| `/phone/agent.md` | `phone/agent.md` | harness-agnostic setup instructions; serve as `text/markdown` |
| `/phone/install` | `phone/install` | installer script; serve as `text/plain`, no redirects |
| `/.well-known/assetlinks.json` | `phone/.well-known/assetlinks.json` | must be served from the **host root** of `mobile.orkestrate.space`, `application/json`, no redirect |

Shared: `phone/brand.css`, `phone/site.js`.

## Integration note for the orkestrate website

1. Host routing: `mobile.orkestrate.space/` and `/phone` are the Mobile surface. Nothing in
   `src/proxy.ts` knows the host yet. Add it like `web.`; the apex should redirect `/phone*` there.
2. Drop these under `website/public/` (or port the pages to Next routes using the same markup).
   `/phone/pair` and `/phone/app` need rewrites to their `index.html` if served from `public/`.
3. `assetlinks.json` is what makes the camera open the app directly. Without it Android shows
   a chooser and the pair page appears first; pairing still works.
4. Docs-sync (website/AGENTS.md): `surfaces.ts` Mobile `href`, `sitemap.ts`, `llms.txt`,
   `orkestrate.md`, `website/design.md`. The announcement bar says "coming soon", the one place
   design.md allows a status word.
5. The app download button points at the GitHub release asset
   `releases/latest/download/orkestrate-phone.apk`. That release does not exist yet.

## Before this is public

- Publish a signed APK as that release asset.
- Replace the fingerprint in `assetlinks.json` with the release signing key's SHA-256
  (it currently holds the debug key's, which is what builds are signed with today). A key change
  later means users must uninstall and reinstall.
- The installer fetches relay files from `raw.githubusercontent.com/orkestrate-dot-space/phone/main`,
  so the relay changes must be pushed first.
