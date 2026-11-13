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

## MCP surface (v0)

- `phone.status` → `{"paired":bool,"devices":[{"id","name","online","lastSeen"}]}`
- `phone.battery` → waits for job result or timeout (60s). No device → `{"ok":false,"error":"no-device"}`.

Later: `phone.request`, `phone.capture_photo`, `phone.pair_list/revoke`, `phone.doctor`.
