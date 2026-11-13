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

## Capabilities (v0)

- `battery.get` `{}` → `{"level":61,"charging":true,"voltageMv":3996,"tempC":34.6}`. No approval.
- `photo.capture` `{"maxPx":1280,"quality":80}` → `{"mime":"image/jpeg","bytes_b64":"..."}`.
  **Approval-gated**: the app raises a notification + on-screen card; NOTHING
  captures until the user taps Approve (system camera UI opens, inherently
  visible). Reject, timeout, or deny → `{"t":"result","ok":false,
  "error":"rejected"}`. Timeout for this capability: 300s (user needs time
  to frame the shot), carried in `job.timeoutMs`.

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

## MCP surface (v0, all live)

- `phone.status` → paired devices + online state.
- `phone.battery` → waits for job result (60s timeout).
- `phone.capture_photo` → approval-gated, 300s window.
- `phone.pair` → mints a pairing code (10 min, single use). The ONLY minter.

Later: `phone.request`, `phone.pair_list/revoke`, `phone.doctor`.
