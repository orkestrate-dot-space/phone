# phone: what we lift, what we adapt, what we drop

Source research: `research-pairing.md` (OpenCode server-side pairing),
`research-browse.md` (Browse relay + extension). This file maps both onto the
phone product. Rule of thumb: **steal the shapes, adapt the transport ends,
drop anything that assumes a DOM.**

## 1. Pairing (lift, with one change)

| Concern | OpenCode | Browse | phone decision |
|---|---|---|---|
| Code | `randomBytes(16).base64url`, 5-min TTL, in-memory cache (cap 1000), single-use via invalidate-on-consume | 8-char Crockford (~40-bit), 10-min TTL, max 5 pending, 5 wrong guesses void all | **Browse-style short code** — a human may type it; 10-min TTL, 5-strike voiding |
| Exchange | `GET /auth/connect/{code}` → `{token}` JSON (or 302 + cookie for browsers) | ECDH key agreement, then per-device key | **OpenCode-style first**: code → token JSON over HTTPS. ECDH later if threat model demands it |
| Credential | Stateless HMAC token, 30-day TTL (`<epoch>.<b64(hmac)>`), password rotation revokes all | Per-device random key, stored both sides, HMAC proof per connection, `pair --list/--revoke` | **Per-device stored keys + revocation list** (phones get lost; revocation must be per-device, not password-rotation) |
| Auth on calls | `Basic base64("opencode:token")` (or `?auth_token=`, or cookie + same-origin) | `Authorization: Bearer <relay-token>`, `?opencodeBrowserToken=` for CDP | Bearer relay-token for machine clients; keep Basic-compat for OpenCode-plugin path |
| What's paired | Nothing stored — no device registry exists | Browser record (id, name, key, last-seen) | **Registry required** — devices, keys, last-seen, revoked flags |
| QR payload | `{code, urls}` (loopback filtered out), deep links `opencode://` | Pairing link/file with port | `{code, urls}` + `phone://pair` deep link into the companion app |

Reuse-verified facts: redemption consumes atomically (second redeem 401s);
expired == consumed; pairing endpoints bypass auth middleware explicitly.

## 2. Transport (mirror Browse exactly)

- Phone app opens an outbound WebSocket (fallback: SSE + POST) to the relay and
  **holds it**; jobs are pushed down it. Same direction as extension→relay.
  Rationale is identical: NAT/firewall traversal, no listening socket on the
  user device, one long-lived authenticated channel.
- Remote access = OpenTunnel-style public HTTPS in front of a loopback-only
  relay (both predecessors do exactly this; relay itself stays plain HTTP).
- Keepalive/ping, backoff reconnect, per-message request/response/event
  envelopes — lift Browse's constants as starting values (see research-browse
  §1), tune on real radios (Doze, app standby, OEM killers will hurt more
  than a desktop browser).

## 3. Sessions → jobs (adapt)

Browse sessions (tab groups, idle-reap, orphan collection) become a **job
queue per device**: `{jobID, capability, params, idempotencyKey, state
(queued→delivered→approved→running→done/failed/expired), approvals, result}`.
- Idempotency keys are load-bearing (reconnects redeliver) — same reason as
  Browse's at-least-once command path.
- Expiry + dead-letter replace tab idle-close: a job nobody approves in N
  minutes expires; results prune after 14 days like Browse journals.
- `status`/`doctor` equivalents from day one; redacted audit log (see §5).

## 4. Handoff semantics (lift verbatim)

- Agent works until a human-only step → card on glass → **only a real tap
  continues it** (Browse: only a real click finishes a handoff; pages can't
  fake it). Same rule: no API call may approve a job.
- Badge states map directly: `ON` (paired) / `RUN` (job executing) /
  `WAIT` (needs you). Put them in a persistent notification + widget.
- Lend-a-tab → share-into-app (`ACTION_SEND` already confirmed on the
  official client; our fork keeps it and routes shares into the job queue).

## 5. Secrets/OTP (lift with a phone twist)

- Browse: TOTP seeds in `0700` store, typed via CDP after frame-origin
  double-check, never in transcript/logs/HAR.
- Phone: OTP arrives by SMS, not from a seed. Flow: `sms_otp.request`
  (approval-gated) → companion reads the matching SMS (user consents once,
  per-sender allowlist) → value goes **only** into the job result consumed
  by the awaiting tool call → redacted in journals exactly like Browse's
  typed-values map. Never logged, never stored.
- Camera/mic/location reads follow the same rule: result flows to the
  awaiting call, a redacted marker flows to the log.

## 6. Cross-machine + threat model (copy the homework)

- `hostname 0.0.0.0` equivalent + explicit **no encryption on the relay
  listener**; encryption lives at the tunnel (Tailscale/WireGuard/OpenTunnel).
  Document it as loudly as Browse does.
- Loopback-only for any privileged local surface (mirrors CDP-on-127.0.0.1).
- Per-device keys mean a stolen phone = `revoke <device>`, not a password
  rotation that nukes every client (this is where we deliberately differ
  from OpenCode's password-bound tokens).

## 7. MCP surface (draft, mirrors Browse's 18)

`phone.list_devices`, `phone.status`, `phone.request` (approval card w/
message), `phone.capture_photo`, `phone.location`, `phone.battery`,
`phone.sms_otp`, `phone.clipboard_read`, `phone.share_read`,
`phone.notify`, `phone.call_session` (voice), `phone.jobs_{list,cancel}`,
`phone.pair_{list,revoke}`, `phone.settings`, `phone.doctor`. Session/job
scoping per calling agent like Browse's `_meta[sessionID]` rule.

## 8. Explicit non-goals for v0 (from the research)

- No remote actuation without an on-glass tap (matches both predecessors'
  human-in-the-loop guarantees; the OS wouldn't allow silent capture anyway).
- No server→phone push channel of our own: outbound WebSocket from the app
  covers online; FCM attention pings need our own Firebase project —
  deferred, and the relay must never be able to mint silent captures.
- No session transcript on the relay beyond the redacted audit log (Browse
  journals redact typed secrets; we redact OTP/media bytes the same way).
- No TOTP-seed-style stored phone credentials in v0.

## 9. Build order (unchanged)

1. Protocol envelopes + pairing ceremony (this doc + research files are the spec).
2. Relay skeleton + Termux reference client (validates job round-trip, no app build).
3. Companion fork: pairing UI (reuse), WebSocket holder, executor + approval UI, share receiver, FCM attention (own project).
4. OpenCode plugin as privileged integration (session-aware tools, Server
   Details status); generic MCP stays harness-agnostic.
