# phone wire protocol — v0

One desktop process, one phone app. Phone dials out, jobs go down, results come back.

## Roles

- `relay`: embedded in the desktop process. Loopback HTTP+WS, e.g. `http://127.0.0.1:19877`.
- `app`: companion app on the phone. Holds relay URL + device key. Always the dialer, never listens.

## Pairing

1. Desktop mints code: 8-char Crockford (no 0/O/1/I), 10-min TTL, single-use, max 5 pending.
2. Phone redeems: `POST /pair/{code}` `{"name":"Pixel 8"}` → `{"deviceId":"dev_...","token":"pt_...","relay":"ws://.../device"}`.
3. Wrong code 5x voids all pending (Browse rule).
4. Token: `pt_` + base64url(32 random bytes). Stored both sides. Revoke per-device.

v0 LAN: relay URL is `ws://<laptop-lan-ip>:19877/device`. No tunnel.

## Transport

- `GET /health` → `{"ok":true,"devices":n}` (no auth).
- `WS /device`, header `Authorization: Bearer <token>`. Query `?token=` also accepted.
- Frames are JSON text messages, `{t, ...}`:
  - app→relay `hello`: `{"t":"hello","deviceId":"...","name":"...","caps":["battery.get"]}`
  - relay→app `job`: `{"t":"job","job":{"id":"job_...","capability":"battery.get","params":{},"timeoutMs":60000}}`
  - app→relay `result`: `{"t":"result","id":"job_...","ok":true,"data":{...}}` or `ok:false, error:"..."`
  - both ways `ping`/`pong`: `{"t":"ping"}` / `{"t":"pong"}`, 25s interval, 60s dead.
- Reconnect: app backs off 1s→30s. Redelivered jobs carry same `id`; app answers once (idempotent), relay dedupes.

## Jobs

```
job: { id, capability, params, timeoutMs, state, result }
state: queued → delivered → done | failed | expired
```

- `id`: `job_` + base64url(12 bytes). Idempotency key = id.
- `capability`: dot name, e.g. `battery.get`. Unknown capability → `ok:false, error:"unknown-capability"`.
- Timeout default 60s. Unapproved/unanswered → `expired`.
- Results pruned after 14 days (later; v0 keeps in memory).

## Capabilities

### `battery.get` (ungated)
`{}` → `{"level":61,"charging":true,"voltageMv":3996,"tempC":34.6}`.

### `photo.capture` (approval-gated)
`{"maxPx":1280,"quality":80}` → `{"mime":"image/jpeg","bytes_b64":"..."}`.
System camera opens; nothing captures before the tap. 300s window.

### `exec.run` (approval-gated) — v14
`{"argv":["ls","/system/bin"],"timeoutSec":30}` →
`{"exit":0,"stdout":"...","stderr":"","truncated":false}`.

What the sandbox actually allows (verified against AOSP sepolicy + behavior docs):
- CAN: run **system binaries** (`/system/bin/*`: toybox applets, `getprop`) and read
  world-readable state. Pure recon: `ls`, `cat` of readable paths, `date`, `uname`.
- CANNOT: exec anything from writable dirs (W^X, targetSdk 29+ — no downloaded
  scripts/tools, no node/python payloads); `dumpsys`/`cmd`/`settings`/`input`
  (shell-privileged, denied to apps); other apps' data; silent installs.
- APK-embedded native-lib ELFs are the only shippable binaries (PackageManager
  extracts `.so` by suffix without content checks). Reserved for later.

### Shell tier (embedded ADB, v16)
When the in-app ADB channel is paired + connected, `exec.run` commands run as
shell-UID (uid 2000): `dumpsys`, `cmd`, `pm`, `settings`, `input`, `logcat` —
the full `adb shell` surface. Falls back to app-UID when the channel is down.
Result envelope gains `"tier":"shell"|"app"`, so the agent knows what it got.
Shell channel: prebuilt adb server binary in `jniLibs` (LADB project, BSD —
see `THIRD_PARTY_NOTICES.md`), paired once to the phone's own wireless
debugging over loopback; ADB keys persist in app storage; the wireless toggle
auto-restores after reboot once `WRITE_SECURE_SETTINGS` self-grants through
the first shell. Approval card and auto-approve policy apply unchanged —
tier changes privilege, never authorization.

Rules (all tiers): argv-array form only, never `sh -c` interpolation;
absolute path or bare name resolved against `/system/bin` + a small
allowlist (`ls cat echo date uname getprop df du head dumpsys cmd pm settings
input logcat` — shell tier; app tier keeps the original recon subset); 30s
timeout, kill on expiry; stdout/stderr capped 64KB each with `truncated`
flags. Play would flag remote-driven exec — sideload-only until that story exists.

### Auto-approve policy (locked down, user opt-in)
- Master toggle in-app (default OFF). When ON, **exact-match** argv in
  `ExecPolicy.AUTO` runs without a tap: `date`, `uname -a`, `uptime`,
  `getprop ro.build.version.{release,sdk}`, `getprop ro.product.model`,
  `cat /proc/version`. No prefixes, no globs, no agent-chosen args.
- Everything else in the executor allowlist raises the card; everything
  outside it is rejected outright. Every auto-run is logged on-device and
  visible in the agent transcript (command + result) — audit without taps.

Empirical boundary (SM-M176B, Android 16, Samsung — live runs):
- `getprop ro.build.version.release` → exit 0, `16`. Execution + stdout path proven.
- `ls /system/bin` → exit 1, `Permission denied`. Even dir listing is Knox-hardened.
- Verdict: recon-grade shell (props, `date`, `uname`, readable files), not a
  terminal. System introspection (`dumpsys` etc.) stays shell-privileged.

## Approval rule (v0 and forever)

No API call — from the agent, the relay, or anyone holding the device key —
can fake an approval. A job that requires approval completes only after a
real on-glass tap. Same guarantee as Browse's handoff (only a real click
continues it).

## Remote access (opentunnel)

Same relay, public face. On the desktop:

```sh
opentunnel route add 19877
# https://<route>.<id>.opentunnel.xyz → 127.0.0.1:19877
```

Blind TLS (per `docs/trust-boundary.md` upstream): the tunnel server routes
SNI only; TLS terminates in the local opentunnel client; plaintext never
leaves the laptop. The app needs zero changes — `https://` relay URLs
already upgrade to `wss://`.

Rules, all load-bearing:

- Through the tunnel every visitor looks loopback (peer, Host, headers all
  untrustworthy). So **no HTTP endpoint may decide "local"**: pairing codes
  are minted only via the MCP `phone.pair` tool, never over HTTP.
- Safe to expose: redeem (40-bit Crockford code + 5-strike voiding),
  `WS /device` (Bearer device token), `/app/*` (Bearer device token).
  The random route URL is unguessable, not authentication — tokens are.
- Same WiFi keeps working with the LAN URL; switch relay address in the
  app's advanced box, no re-pair needed (the device token is URL-independent).

## MCP surface (live)

- `phone.status` → paired devices + online state + app versions.
- `phone.battery` → waits for job result (60s; queues while offline, delivers on hello).
- `phone.capture_photo` → approval-gated, 300s window.
- `phone.pair` → mints a pairing code (10 min, single use). The ONLY minter.
- `phone.pair_list` → devices (keys never leave the relay).
- `phone.revoke {deviceId}` → drops key + socket, fails its queued jobs.
- `phone.jobs` → queued/awaiting jobs.
- `phone.doctor` → listener, tunnel routes, devices, queue depths.

Later: `phone.request` (generic approval card), `phone.sms_otp`, `phone.location`.
