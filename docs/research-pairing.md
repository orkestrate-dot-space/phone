# OpenCode phone/desktop pairing — server-side research

Source snapshot: `anomalyco/opencode` branch `dev`, commit `5bf6611` (2026-11-13),
local CLI `opencode v2.2.5`. Primary sources only: git-tracked source under
`/tmp/opencode-src` (partial clone, blobs fetched on demand), local OpenAPI via
`opencode api get /openapi.json > /tmp/pair-openapi.json`, and
`services/site/src/docs/content/cli/web.mdx`. No service restarts, no config
writes, no pairing generation were performed. No secrets are reproduced below.

Conventions: citations are `path:line` in the `dev` tree unless stated as a doc
URL or the local OpenAPI dump.

---

## 1. `POST /api/pair` — code generation, expiry, single-use, URL selection

**Route definition.** `POST /api/pair` → operation `server.pair`, success
`PairingCode`, no params.
`packages/protocol/src/groups/server.ts:56-64`; description verbatim:
"Create a short-lived, single-use code for a `/auth/connect/:code` pairing link."
Local OpenAPI (`/tmp/pair-openapi.json`, `paths["/api/pair"].post`): `security: []`
(spec-level; real enforcement is in middleware — see §3; the CLI calls it
authenticated).

**Handler is a one-liner.** `packages/server/src/handlers/server.ts:31`:
`.handle("server.pair", () => pairing.issue())`.

**Generation** (`packages/server/src/pairing.ts:24-27`):

- `code = randomBytes(16).toString("base64url")` — 128 bits, base64url no padding
  (21–22 chars; matches client `CODE = /^[A-Za-z0-9_-]+$/` in
  `packages/app/src/servers/connect/pairing.ts:20`).
- Store: `Cache.set(cache, code, true)` with
  `Cache.make<string, true>({ capacity: 1_000, lookup: noLookup, timeToLive: TTL })`
  (`packages/server/src/pairing.ts:22`).
- `TTL = Duration.minutes(5)` (`packages/server/src/pairing.ts:7`); response is
  `{ code, expires_in: Duration.toSeconds(TTL) }` = 300
  (`packages/server/src/pairing.ts:27`).
- `noLookup` dies if ever called (`packages/server/src/pairing.ts:16-17`): codes
  only exist as cache keys; there is no backing table.

**Single-use enforcement** (`packages/server/src/pairing.ts:29-31`):
`consume = Cache.invalidateWhen(cache, code, () => true)` returns `boolean`
(whether the key was present). Redemption consumes atomically at the Effect
`Cache` layer; a second redeem finds nothing. TTL expiry removes the key
server-side, so expired codes behave identically to consumed ones.

**URL embedding is NOT server-side.** `server.pair` returns only
`{code, expires_in}` — no URLs. URL selection happens in the CLI after the call
(`packages/cli/src/commands/handlers/pair.ts:27-33`):

1. `Service.ensure(...)` gets the local endpoint (authenticated client with
   `Service.headers(endpoint)`), then `client.server.pair()`.
2. `pairingURLs()`: `--remote` → single tunnel URL; `--url` → that URL;
   otherwise `client.server.info().urls`
   (`packages/cli/src/commands/handlers/pair.ts:118-125`).
3. `links = urls.map(url => new URL("/auth/connect/"+code, url).href)`
   (`packages/cli/src/commands/handlers/pair.ts:31`).
4. QR payload is `JSON.stringify({code, urls: remote})` where `remote` excludes
   loopback (`packages/cli/src/commands/handlers/pair.ts:32-33,44,155-159`).

**Loopback vs LAN vs tunnel selection logic:**

- `ServerInfo.connectionURLs(value, requestedHostname)`
  (`packages/server/src/server-info.ts:18-39`): if the bound hostname is
  `0.0.0.0` → `127.0.0.1` loopback entry + one URL per non-internal IPv4
  interface; `::`/`[::]` → IPv6 equivalents; anything else → `[value]` unchanged.
- `remote.urls` (tunnel HTTPS URL, when attached) is appended after local URLs:
  `urls = [...localURLs(), ...(remoteURLs?.() ?? [])]`
  (`packages/cli/src/server-process.ts:100`, `packages/server/src/process.ts:63-69`
  wiring `urls()` through `createRoutes(..., urls)`).
- The `https:` entry in `urls` is by construction the tunnel (plain-HTTP server
  + HTTPS tunnel URL); client helper `remoteAddress()` finds it by
  `protocol === "https:"`
  (`packages/gui-plugins/src/pairing/remote.ts:1-7`).

## 2. `GET /auth/connect/{code}` — redemption flow

**Route.** `GET /auth/connect/:code` (`PAIRING_CONNECT_PATH`,
`packages/protocol/src/groups/server.ts:35`), operation `server.connect`,
`params: {code: Schema.String}`, success `PairingSession`, error
`UnauthorizedError` (`packages/protocol/src/groups/server.ts:66-79`).
OpenAPI description: "Redeem a pairing code. Browsers receive a session cookie
and a redirect to the web app; requests that accept JSON receive a session token
to use as the password." Local OpenAPI `paths["/auth/connect/{code}"].get`
confirms `security: []` (public by design; see bypass below).

**Handler** (`packages/server/src/handlers/server.ts:32-55`):

1. `browser = request.headers.accept?.includes("text/html") === true`
   (`packages/server/src/handlers/server.ts:37`).
2. `token = (yield* pairing.consume(code)) ? issueSession(auth) : undefined`
   (`packages/server/src/handlers/server.ts:38`). Consume-first: invalid/expired/
   already-used code → `token === undefined`.
3. Failure: non-browser → `new UnauthorizedError({message:
   "Pairing link expired or already used"})` (HTTP 401 JSON
   `{_tag:"UnauthorizedError", message}`); browser → `text/plain` 401
   `"This pairing link expired or was already used. Run \`opencode pair\` to get
   a new one."` (`packages/server/src/handlers/server.ts:39-44`).
4. Success non-browser → `{ token }` JSON
   (`packages/server/src/handlers/server.ts:46`).
5. Success browser → `302 redirect("/")` + `Set-Cookie: <cookieName>=<token>;
   Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000`
   (`packages/server/src/handlers/server.ts:47-53`).

**PairingSession token format** (`packages/server/src/auth.ts:40-44`):
`issueSession` returns `` `${expires}.${sign(password, expires)}` `` where
`expires = String(floor(now/1000) + 2592000)` (30 days). `sign`:
`key = HMAC-SHA256(password, "opencode-session-v1")`,
`sig = HMAC-SHA256(key, expires).digest("base64url")`
(`packages/server/src/auth.ts:63-65`). Hence the observed
`^[0-9]{10,}\.[A-Za-z0-9_-]{20,}$`: 10-digit epoch + `.` + 43-char unpadded
base64url (32-byte HMAC). Returns `undefined` (→ same 401 path) when no server
password is configured (`packages/server/src/auth.ts:41`).

**No expiry/rotation beyond the 30-day `expires` claim.** `verifySession`
(`packages/server/src/auth.ts:46-55`) splits on `.`, requires exactly 2 parts,
`Number.isSafeInteger(expires)` and `expires*1000 > now`, recomputes the HMAC
and compares with `timingSafeEqual` after a length check. There is no refresh
endpoint, no sliding window, no revocation list; rotating the server password
revokes every session because the HMAC key derives from it
(`packages/server/src/auth.ts:39` comment + `services/site/.../cli/web.mdx:21-24`:
"Sessions last 30 days; changing the server password signs every session out.").

**Pre-ready behavior.** The front `dispatch()` in
`packages/server/src/process.ts:182-211` bypasses auth for
`isPairingConnectURL(url)` (`PAIRING_CONNECT_URL = /^\/auth\/connect\/[^/]+$/`,
`packages/protocol/src/groups/server.ts:37-41`) even while `!ready`
(`packages/server/src/process.ts:201-207`), so pairing redemption works during
startup; the router-level `authorizationLayer` likewise skips
`route.route.path === PAIRING_CONNECT_PATH`
(`packages/server/src/middleware/authorization.ts:87-94`).

## 3. Auth middleware — per-request `Basic "opencode:token"` validation

**Credential extraction** (`packages/server/src/middleware/authorization.ts:33-40`):
`?auth_token=<base64>` query wins if present (decoded identically to Basic);
else `Authorization: Basic <base64>`; else empty credential. `decodeCredential`
(`packages/server/src/middleware/authorization.ts:20-30`) base64-decodes,
splits on the first `:` into `{username, password (Redacted)}`; malformed →
empty.

**Check** (`packages/server/src/middleware/authorization.ts:59-63`,
`packages/server/src/auth.ts:32-37`):

- `authorized(creds, config)`: false unless a non-empty server password is set
  and `username === "opencode"`. Then `password === storedPassword ||
  verifySession(password, config)`. Note: direct password comparison is plain
  `===`; only the session-token branch uses `timingSafeEqual`
  (`packages/server/src/auth.ts:54`).
- OR `authorizedSessionCookie` (`packages/server/src/middleware/authorization.ts:65-72`):
  cookie `sessionCookieName(host)` value must `verifySession`, and if an `Origin`
  header is present its host must equal the request `Host` (cross-port
  same-site cookie isolation).

**Cookie name** (`packages/server/src/auth.ts:57-61`):
`opencode_session_<port>` when the Host URL parses to a port, else
`opencode_session` (ports isolate co-hosted local servers; comment at
`packages/server/src/auth.ts:57`).

**When auth is skipped.** If `!required(config)` (no password / empty) every
request passes (`packages/server/src/middleware/authorization.ts:78`). Else the
`Authorization` HttpApi middleware enforces on every route except ticketed PTY /
persistent-PTY / client-MCP connects carrying `?ticket=` and the pairing path
(`packages/server/src/middleware/authorization.ts:83-94`). The pre-router
`dispatch()` duplicates the bypass for boot/early requests
(`packages/server/src/process.ts:201-207`).

**Challenge behavior** (`packages/server/src/middleware/authorization.ts:44-49,96-100`):
`WWW-Authenticate: Basic realm="Secure Area"` is only attached when
`sec-fetch-mode` is absent or `navigate` (page loads, curl); `fetch` from the
web app gets a bare 401 JSON so the app can show its own sign-in UI instead of
the browser's native prompt. `unauthorizedResponse`
(`packages/server/src/middleware/authorization.ts:52-57`) returns
`{_tag:"UnauthorizedError", message:"Authentication required"}` / 401.

**Client construction.** Local service probe and CLI use
`headers(endpoint) = {authorization: "Basic " + base64(username+":"+password)}`
(`packages/client/src/service-probe.ts:87-93`, re-exported via
`packages/client/src/effect/service.ts`). The redeemed pairing token is used
verbatim as `password` with username `opencode`.

## 4. `password` vs `OPENCODE_API_KEY` vs redeemed tokens — distinct credentials

- **Server password is the single root secret.** `ServerOptions.password`
  (`packages/server/src/options.ts`, `Schema.optional(Schema.String)`); the
  server refuses to start without one
  (`packages/server/src/process.ts:56-57`). `ServerAuth.Config` always pins
  `username: "opencode"` (`packages/server/src/auth.ts:18-21`); only the
  password varies.
- **Where the service password lives.** `service.json` `password` field
  (`packages/cli/src/services/service-config.ts:25`). Generated once as
  `randomBytes(32).toString("base64url")`
  (`packages/cli/src/services/service-config.ts:183-192`); comment:
  "Keep one private credential across server restarts so discovered clients can
  reconnect". Persisted on first listen if absent
  (`packages/cli/src/server-process.ts:151`). Mirrored into the state-dir
  registration file (`url, pid, version, password`, mode `0600`) by
  `ServiceRegistration.register`
  (`packages/cli/src/services/service-registration.ts:14-30,39-40`); local
  discovery reads that file and Basic-auths with it
  (`packages/client/src/service-probe.ts:55` + `headers()`).
- **`OPENCODE_API_KEY` is unrelated to pairing auth.** Zero hits in
  `packages/server/src`, `packages/cli/src/{env,server-process,services}`, and
  `packages/client/src` (`git grep OPENCODE_API_KEY` returns only docs/agent and
  provider-key noise). `service.json` `env` is a generic
  `Record<string,string>` forwarded into the service process
  (`packages/cli/src/services/service-config.ts:27,137-141`;
  `packages/cli/src/server-process.ts:80`); an `OPENCODE_API_KEY` entry there is
  just an env var for model/provider code paths, never consulted by
  `ServerAuth.authorized/verifySession`. Do not conflate it with the password.
- **`OPENCODE_PASSWORD` / legacy `OPENCODE_SERVER_PASSWORD`** are the env
  sources for foreground/standalone/`--server` passwords
  (`packages/cli/src/env.ts:7-13`): adopted by `opencode serve` / standalone
  (`packages/cli/src/server-process.ts:85-96`), sent by `opencode --server`
  (`packages/cli/src/services/server-connection.ts`); explicitly deleted from
  the tool environment of `--stdio` children
  (`packages/cli/src/server-process.ts:87-90`).
- **Redeemed tokens are derived, not stored.** HMAC of expiry under a
  password-derived key (§2); accepted "anywhere the password is"
  (`packages/server/src/auth.ts:32` comment). No per-token rows, no rotation on
  use, 30-day fixed lifetime (`SESSION_TTL_SECONDS = 30*24*60*60`,
  `packages/server/src/auth.ts:16`).

## 5. OpenTunnel integration — route, serving, TLS, reconnect

- **Packages / topology.** `@opentunnel/client@0.6.1`
  (`packages/cli/package.json:42`). "One tunnel per device in its default
  profile, shared by the opentunnel CLI and every app using the SDK; each claims
  its own routes. The service claims a subdomain (its route)" —
  `packages/cli/src/services/remote-tunnel.ts:7-10`. Certificate covers
  subdomains with a wildcard; the random route keeps the address unguessable
  (same comment).
- **Route provisioning/persistence.** `route() = randomBytes(8).toString("hex")`
  — 64 bits, 16 hex chars, "a valid DNS label"
  (`packages/cli/src/services/service-config.ts:170-173`). Schema:
  `remote: {route: string}` "generated, never user-set: the secret subdomain the
  service is served on" (`packages/cli/src/services/service-config.ts:15-19`).
  Created once via `remote()` and kept across restarts
  (`packages/cli/src/services/service-config.ts:205-212`); disabling deletes the
  route (next enable mints a fresh address) but preserves `remoteAccepted`
  (`packages/cli/src/services/service-config.ts:198-203`). Legacy boolean
  `remote: true` configs are repaired to a generated route in place
  (`packages/cli/src/services/service-config.ts:161-168`). `service get`
  redacts both password and route (`{password: _} ` stripped, `remote: {}`,
  `packages/cli/src/services/service-config.ts:214-218`); comment: "The route is
  as sensitive as the password" (`packages/cli/src/services/service-config.ts:217`).
- **What serves it.** The server itself always listens plain HTTP (hostname/port
  from flags → service config → defaults; §6). When `config.remote` is set, the
  service forks `RemoteTunnel.run({route, target, onURL})`
  (`packages/cli/src/server-process.ts:190-205`) with
  `target = 127.0.0.1:<port>` for wildcard binds else the bound socket address
  (`packages/cli/src/server-process.ts:191-196`; comment: "A wildcard bind also
  listens on loopback, which is all the tunnel needs to reach.").
  `run()` connects `{routes: {[route]: target}}` and publishes
  `https://{route}.{tunnel.hostname}` via `onURL`, which feeds `server.info().urls`
  (`packages/cli/src/services/remote-tunnel.ts:26-30`).
- **TLS termination.** Relay-side: local target is plain HTTP; the advertised
  URL is `https:`. Corroborated client-side: "The server itself listens over
  plain HTTP, so the HTTPS address among its URLs is its remote access tunnel."
  (`packages/gui-plugins/src/pairing/remote.ts:1-3`). Cipher/TLS-version details
  live in the `@opentunnel/client` SDK / relay, not in this repo (see open
  questions).
- **Reconnect behavior.** "The SDK reconnects through network failures itself,
  so only setup failures reach the retry here" — non-fatal errors log
  "remote access tunnel unavailable; retrying" and retry on
  `min(exponential(1s), spaced(30s))`
  (`packages/cli/src/services/remote-tunnel.ts:12-14,34-40`). Fatal (no retry):
  `OpenTunnelAttachError`, `"Certificate issuance failed*"`, proxy errors
  (`/unsupported proxy scheme|not a valid proxy URL/`)
  (`packages/cli/src/services/remote-tunnel.ts:21-25`); terminal failure logs
  "remote access tunnel stopped; run `opencode service set remote true` to retry"
  (`packages/cli/src/services/remote-tunnel.ts:42-46`). `onURL(undefined)` clears
  the advertised URL on teardown (`packages/cli/src/services/remote-tunnel.ts:33`).
- **First-time provisioning.** `ServiceConfig.set("remote","true")` runs
  `RemoteTunnel.ensure()` in the foreground first ("Setting up remote access;
  this can take a minute...", 5-min timeout, resumable issuance)
  (`packages/cli/src/services/remote-tunnel.ts:50-66`,
  `packages/cli/src/services/service-config.ts:261-268`). `pair --remote`
  additionally polls `server.info()` every 1s up to 3 min for the tunnel hostname
  (`packages/cli/src/commands/handlers/pair.ts:127-153`); proxy note: the
  background service needs its own `HTTPS_PROXY`/`NO_PROXY` via
  `service set env` (`packages/cli/src/commands/handlers/pair.ts:144-148`,
  `services/site/.../network.mdx` proxy/service sections).

## 6. `service set hostname` / `pair --remote` mechanics

- **Every `service set` stops the service first, then writes.**
  `packages/cli/src/services/service-config.ts:250-311`: each case
  (`disabled/hostname/port/password/env/cors/remote`) calls
  `Service.stop(await options())` before `write(...)`. Applies on next start;
  docs: "Changing a setting stops the background server. Start it again..."
  (`services/site/src/docs/content/cli/web.mdx:99-103`).
- **`hostname`.** Default bind is `127.0.0.1`
  (`packages/cli/src/server-process.ts:72`); `options.hostname ?? config.hostname
  ?? "127.0.0.1"`. Setting `0.0.0.0` makes `connectionURLs()` expand to LAN
  addresses (§1), which is what makes same-WiFi pairing links/QR reachable.
  CLI hint after loopback-bound `pair`:
  "To connect from other devices, run `opencode service set hostname 0.0.0.0`."
  (`packages/cli/src/commands/handlers/pair.ts:56-68`); docs
  (`services/site/src/docs/content/cli/web.mdx:80-97`).
- **`pair --remote` full sequence**
  (`packages/cli/src/commands/handlers/pair.ts:14-27`): refuse if
  `disabled` or `--remote --url` combined; if `config.remote === undefined`
  → `remoteConsent()` (warn + TTY confirm, `--yes` for scripts; records
  `remoteAccepted`, `packages/cli/src/commands/handlers/pair.ts:90-116`; warning
  text at `packages/cli/src/commands/handlers/pair.ts:72-76`, mirrored in docs
  `services/site/src/docs/content/cli/web.mdx:63-76`) → `ServiceConfig.set(
  "remote","true")` (foreground tunnel `ensure()` + stop + route write) →
  `Service.ensure(...)` restarts the service with the tunnel → poll for tunnel
  URL → `POST /api/pair` → print `https://<route>.<tunnel-host>/auth/connect/<code>`
  only (loopback filtered from QR). Later runs skip consent when
  `config.remote !== undefined || remoteAccepted === true`
  (`packages/cli/src/commands/handlers/pair.ts:95`); turning off via
  `service set remote false` stops, deletes the route, keeps `remoteAccepted`
  (`packages/cli/src/services/service-config.ts:261-271`,
  `packages/cli/src/services/service-config.ts:198-203`).
- **Ports.** Service default `0xc0de` (49374; `0x70de`/28894 on win32),
  `0xc0df` for `local` channel
  (`packages/cli/src/services/service-config.ts:46-60`); docs table
  (`services/site/src/docs/content/cli/web.mdx:35-45`). Unspecified ports fall
  back to 4096-up scan in foreground `serve`
  (`packages/server/src/process.ts` `listen()`), while the managed service uses
  the channel default (`packages/cli/src/server-process.ts:73`).

## 7. Device/connection registry — there is none (confirmed)

- Pairing codes: in-memory Effect `Cache` only, capacity 1000, 5-min TTL
  (`packages/server/src/pairing.ts:22`); process-local, lost on restart.
- Sessions: stateless HMACs, no storage, no allowlist, no per-device rows
  (`packages/server/src/auth.ts:39-55`). Password rotation is the only
  revocation.
- Database: `packages/core/src/database/schema.gen.ts` tables are
  `account_state, account, control_account, credential, event_sequence, event,
  job, kv, permission, project_directory, project, schedule, blob_ref, blob,
  instruction_blob, instruction_entry, instruction_state, session_inbox,
  session_message, session_part, session_v2, workspace, worktree` — no
  pairing/device/session-token tables. `git grep -in "pairing|paired.device"
  -- packages/core/src` returns nothing; the only `device` hits repo-wide for
  pairing are the OpenTunnel "one tunnel per device" comments
  (`packages/cli/src/services/remote-tunnel.ts:7,50`).
- Reimplementation consequence: persist only (a) server password, (b) tunnel
  route; keep codes in a TTL map; verify sessions by recomputation. There is no
  "paired devices" list to sync or display.

## 8. Pairing-link formats accepted (server vs client contract)

**Server side** accepts exactly one shape: `GET /auth/connect/{code}` with a
single non-empty path segment (`PAIRING_CONNECT_URL`,
`packages/protocol/src/groups/server.ts:37`; route
`packages/protocol/src/groups/server.ts:35`). `code` is `Schema.String` with no
further server-side syntax check — validity = cache hit. The QR/JSON envelope
is never sent to the server; the client extracts `code` locally and calls the
per-URL endpoint.

**Client side** (`packages/app/src/servers/connect/pairing.ts:20-51`, tests in
`packages/app/src/servers/connect/pairing.test.ts`) accepts two shapes, both
gated by `CODE = /^[A-Za-z0-9_-]+$/`:

1. JSON QR payload `{"code": string, "urls": string[]}` — deduped, every entry
   must parse as an `http(s)` URL without credentials/query/fragment, else the
   whole payload is rejected.
2. Bare pairing URL `http(s)://<origin>/auth/connect/<code>` — exactly one
   segment; `/auth/connect/` (empty), extra segments, `/connect#…`, and
   non-http(s) schemes (incl. `opencode-ios://…`) are rejected by test.

Also distinguished: `bareServerAddress()` (host with no path; used when a server
address is typed into the pairing field) and `legacyPairingLink()` (`/connect`
with `#`/`?data=` credentials, pre-one-time-link servers)
(`packages/app/src/servers/connect/pairing.ts:53-75`). No `opencode://` deep-link
scheme exists anywhere in `packages/{app,cli,server}` (`git grep "opencode://"`
empty). Redemption fans out to every URL with `Promise.any` (5s timeout each)
and maps `AggregateError(all UnauthorizedError)` → `expired`, else
`unreachable`; the redeemed token is then reused against the preferred (first)
URL after a ≤1s health check (`redeemPairingLink`/`preferFirst`,
`packages/app/src/servers/connect/pairing.ts:84-132`).

---

## Shapes — exact contracts for reimplementation

### Endpoints

| Method | Path | Op | Auth | Req | Success | Errors |
|---|---|---|---|---|---|---|
| `POST` | `/api/pair` | `server.pair` | required (Basic password or session token; middleware, not in OpenAPI `security`) | — | `PairingCode` | `InvalidRequestError`, `UnauthorizedError` |
| `GET` | `/auth/connect/{code}` | `server.connect` | public (explicit bypass in `authorizationLayer` + pre-router `dispatch`) | `code: string` path | `PairingSession`, or (browser `Accept: text/html`) `302 /` + session cookie | non-browser 401 `UnauthorizedError{"Pairing link expired or already used"}`; browser 401 `text/plain` same-message variant |
| `GET` | `/api/info` | `server.info` | required when ready; during `starting/failed` still requires auth via `dispatch` special-case | — | `ServerInfo{version,pid,hostname?,urls,paths:{tmp},capabilities?}` | `UnauthorizedError` |

OpenAPI source: `GET /openapi.json` (`openapiPath: "/openapi.json"`,
`packages/server/src/routes.ts`); operation summaries in §1–2; note all three
routes list `"security": []` in the dumped spec — enforcement lives in
`authorizationLayer` + `dispatch`, not in the_toggle spec.

### JSON schemas (from `packages/protocol/src/groups/server.ts:5-33` + OpenAPI dump)

```json
// PairingCode — POST /api/pair → 200
{ "code": "string (base64url, 128-bit, e.g. 22 chars)", "expires_in": 300 }

// PairingSession — GET /auth/connect/{code} → 200 (non-browser)
{ "token": "<expiresEpochSec>.<base64url(HMAC-SHA256(HMAC-SHA256(password,'opencode-session-v1'), expires))>" }
// token ≈ 10 digits + "." + 43 chars; 30-day lifetime; no refresh endpoint

// UnauthorizedError (JSON APIs)
{ "_tag": "UnauthorizedError", "message": "Pairing link expired or already used" }
// or { "_tag": "UnauthorizedError", "message": "Authentication required" } (middleware)

// ServerInfo — GET /api/info → 200 (abridged)
{ "version": "string", "pid": 0, "hostname": "string?",
  "urls": ["http://127.0.0.1:PORT", "http://192.168.x.x:PORT", "https://<route>.<tunnel-host>"],
  "paths": { "tmp": "string" }, "capabilities": { "persistentPty": true } }
```

### HTTP auth shapes (all `/api/*` except the bypasses)

```http
Authorization: Basic base64("opencode:<password-or-session-token>")
```

- `username` must be exactly `opencode`
  (`packages/server/src/auth.ts:20,34`).
- Alternative: `?auth_token=<same base64>` query
  (`packages/server/src/middleware/authorization.ts:33-36`).
- Alternative (browsers): `Cookie: opencode_session[_<port>]=<token>` +
  same-origin (`packages/server/src/middleware/authorization.ts:65-72`).
- Pairing QR envelope (client-local, never sent as-is):
  `{"code":"<base64url>","urls":["https://…","http://…"]}`
  (`packages/cli/src/commands/handlers/pair.ts:44`).

### Config shapes (disk)

```jsonc
// ~/.config/opencode/service.json  (mode 0600; password+route redacted by `service get`)
{ "remoteAccepted": true, "password": "<32-byte base64url>",
  "hostname": "0.0.0.0", "port": 49374, "cors": ["https://…"], "env": { "<K>": "<V>" },
  "remote": { "route": "<16-hex-chars>" } }
// Registration (XDG_STATE_HOME/opencode/service.json, mode 0600):
{ "id": "uuid", "version": "x.y.z", "url": "http://…", "pid": 123, "password": "<same>" }
```

Field semantics/persistence: `packages/cli/src/services/service-config.ts:15-29`
(schema), `:170-173` (route gen), `:183-192` (password gen),
`:198-212` (disable/route lifecycle), `:214-248` (`get` redaction),
`:250-361` (`set`/`unset` + stop-before-write).

---

## Open questions / could not verify

1. **OpenTunnel relay/TLS specifics.** Relay hostname, cert issuance flow
   (ACME? wildcard cert logs — the code notes tunnel hostnames "appear in public
   certificate logs", `packages/cli/src/services/remote-tunnel.ts:9`), TLS
   versions/ciphers, and whether the relay forwards the original `Host`/SNI or
   rewrites to the local target. Lives in `@opentunnel/client@0.6.1` and the
   relay operator, not this repo. Would need the opentunnel SDK source or packet
   capture (out of scope: read-only task).
2. **Effect `Cache` eviction/TTL-refresh semantics.** Capacity is 1000 with a
   5-min TTL, but whether a failed `consume` (read-miss) affects recency, and the
   exact race between two concurrent `consume`s of the same code (only one
   `invalidateWhen→true` expected, but the atomicity boundary is inside
   Effect's `Cache`), was not load-tested.
3. **`expires_in` rounding.** Server returns exactly 300; CLI prints
   `Math.round(expires_in/60)` minutes
   (`packages/cli/src/commands/handlers/pair.ts:37`). A reimplementation should
   return `300`, not compute from remaining TTL.
4. **Embedded/workerd parity.** `createEmbeddedRoutes` forces
   `password: none` (`packages/server/src/routes.ts:119`), i.e. no auth and
   `issueSession` → `undefined`. Pairing against embedded instances is
   untested here.
5. **Rate limiting / brute force.** No throttling on `/api/pair` or
   `/auth/connect` was found in `packages/server/src`; 128-bit codes make
   guessing infeasible, but lockout behavior (if any) at the OpenTunnel relay
   is unknown.
6. **Docs drift.** `https://opencode.ai/docs/server` still serves v1 content
   (auth via `OPENCODE_SERVER_PASSWORD`, `/doc` endpoint); the v2 contract is
   `https://opencode.ai/v2` intro + `services/site/src/docs/content/cli/web.mdx`
   (pairing/sessions/remote) and the live `/openapi.json`. Cite the latter, not
   the former, for v2 behavior.
