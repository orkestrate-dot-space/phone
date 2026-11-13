# phone — working rules (repo-local)

## What this is
Mobile surface of the suite: MCP server + relay (Bun, `desktop/`) and the
Android companion app (`app/`). The phone dials out; nothing listens on it.

## Build & verify (no guessing, no memory)
- Desktop: `bun src/index.ts --serve` (port `PHONE_PORT`, default 19877).
  MCP mode = same binary over stdio; logs go to stderr, never stdout.
- App: `gradle :app:assembleDebug` with `JAVA_HOME=<mise java>` and
  `ANDROID_HOME=~/.local/share/android-sdk`. Verify with
  `aapt dump badging` (versionCode must match `dist/version.json`).
- Relay smoke: `echo '<initialize>' | PHONE_PORT=19899 timeout 15 bun src/index.ts`.
- E2E scripts live in /tmp (`phone-e2e.ts`, `phone-photo.ts`, `phone-exec.ts`...); rerun after touching the job path.
- Read files before editing; `edit` needs exact current text.

## Versioning
- App + protocol move together: `0.17.x` patch bumps, `versionCode` +1 each build.
- Publish: copy APK to `dist/app.apk`, write `dist/version.json`, commit.
- Never commit `dist/*.apk` (gitignored), devices.json, or secrets.

## Push rule
Commit freely; push only on direct order. The per-version `git push` in the
dev loop counts as a standing order while it is active — otherwise ask.

## Product rules (load-bearing)
- Approval cards complete ONLY on a real on-glass tap. No API path may fake one.
- Pairing codes: MCP-minted only, never over HTTP (tunnel visitors look loopback).
- Token never leaves prefs + Authorization header. Keys never in logs/journals.
- Shell tier extends privilege, never authorization: same card, same policy.
