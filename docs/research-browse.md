# Browse internals — reimplementation research

Source: https://github.com/anomalyco/browse, `dev` branch at `8358067` (2026-11-12).
All claims verified against that tree only (code + `cli/skills/browse/SKILL.md`).
Line numbers refer to that commit. No pairing/install commands were run; read-only.

Related test files worth reading alongside: `test/agent-relay-pairing.test.ts`,
`test/relay-stub.ts`, `test/debugger-hub.test.ts`, `cli/test/*`.

## 0. Architecture (recap, with code anchors)

- Relay = `cli/` (Effect-based Node/Bun CLI). Entry `cli/src/main.ts`; relay core `cli/src/relay.ts`.
  The relay runs agent sessions and exposes (a) an HTTP API on `127.0.0.1:19988`
  and (b) a WebSocket endpoint `/extension` for the browser extension.
- Extension = MV3 service worker: `src/background/agent-relay.ts` (connection owner),
  `src/background/agent-relay/{handshake,wire,commands,debugger,groups,grouping,discovery,discovered,recording,window,popups,log}.ts`,
  content script `src/content/page-status.ts` (in-page handoff card).
- MCP server = thin client over the relay HTTP API: `cli/src/mcp/*.ts` registers tools,
  `cli/src/relay/client.ts` speaks HTTP to the relay. CLI and MCP share the same routes.
- Playwright dispatch: the relay connects to its own HTTP/CDP front-end via
  `chromium.connectOverCDP(endpointUrl, ...)` (`cli/src/execute.ts:537`),
  translating Playwright protocol into `debugger.sendCommand` extension commands.

## 1. Relay↔extension wire protocol

Transport: plain WebSocket `ws://host:port/extension`. No TLS even cross-machine
(see §8). Max inbound message 256 MiB on the relay (`cli/src/relay/extension-handshake.ts:49`
pre-auth cap `maxPreAuthMessageBytes = 64 KiB`; post-auth the ws-server limit
`256 * 1024 * 1024`, `cli/src/relay/transport-server.ts:113`).
Extension-side send cap is "a little under" 256 MiB (`MAX_MESSAGE = 255 * 1024 * 1024`,
`src/background/agent-relay/wire.ts:58`).

### 1.1 Message envelopes (both directions are JSON text, except recording frames)

Relay→extension (commands), `cli/src/protocol.ts:178-182`:
`{ id: number, method: ExtensionCommandMethod, params?: JsonObject }`.
Extension→relay (responses), `cli/src/protocol.ts:184-188`:
`{ id: number, result?: JsonObject, error?: string }` (result and error mutually exclusive,
`isExtensionResponse`, `cli/src/protocol.ts:227-232`).
Extension→relay (events), `cli/src/protocol.ts:191-193`:
`{ method: ExtensionEventMethod, params?: JsonObject }` — no `id`.
CDP client sockets (`/devtools/browser/...`) use a different envelope
(`CdpRequest`/`CdpResponse`/`CdpEvent` with `sessionId`, `cli/src/protocol.ts:65-85`).

### 1.2 Command methods (relay→extension), `cli/src/protocol.ts:130-153`

`ping`, `debugger.attach`, `debugger.detach`, `debugger.sendCommand`,
`tabs.create`, `tabs.remove`, `tabs.group`, `tabs.ungroup`,
`action.setAttached`, `action.setBadge`, `pageStatus.set`, `pageStatus.clear`,
`runtime.reload`, `recording.start`, `recording.stop`, `recording.status`,
`recording.cancel`, `tabs.cleanup`, `update.notice`, `relay.settings`,
`settings.get`, `settings.set`.
Extension parses with `parseExtensionCommand` (`cli/src/protocol.ts:207-218`;
mirror in `src/background/agent-relay` via `src/agent-relay/protocol.ts:73-84`).

### 1.3 Event methods (extension→relay), `cli/src/protocol.ts:155-173`

`protocol`, `hello`, `auth.code`, `auth.response`, `ready`,
`toolbar.clicked`, `handoff.completed`, `debugger.event`, `debugger.attached`,
`debugger.detached`, `tabs.removed`, `tabs.opened`, `pong`, `log`,
`recording.cancelled`, `pageStatus.requested`, `settings`.
Key payloads: `debugger.attached { tabId, agent? }` is re-sent for every known
relay tab after each reconnect (`src/background/agent-relay/handshake.ts:395-401`);
`ready` marks the link usable; `handoff.completed` / `toolbar.clicked` drive §7;
`debugger.event` forwards `chrome.debugger.onEvent` for relay tabs
(`src/background/agent-relay.ts:554+`).

### 1.4 Reconnect / heartbeat / timeouts

- Extension dials `ws://host:port/extension` where host/port come from
  `relayTarget()` (`src/background/agent-relay/discovery.ts:22-30`): explicit address
  or dev-port pin = exact; otherwise automatic (discovered/hint/scan port, default 19988).
- Before auth the extension sends only `protocol { protocolVersion, unpaired? }`
  for presence, then closes (`src/background/agent-relay.ts:467-487`).
  With a credential it sends `hello` after `onopen` and must complete pairing within
  `CONNECT_TIMEOUT = 10_000` ms (`src/background/agent-relay/wire.ts:59`), else the
  relay closes with 4006 (`src/background/agent-relay/handshake.ts:245-250`).
- Relay pre-auth socket cap: handshake must finish within
  `preAuthHandshakeTimeoutMs = 10_000` ms (`cli/src/relay/extension-handshake.ts:52`).
- Keepalive: relay-side ping interval `keepaliveIntervalMs`, default 20 s
  (`OPENCODE_BROWSER_KEEPALIVE_MS`, `cli/src/relay.ts:287`); extension-side
  `KEEPALIVE = 20_000` sends `{ method: "pong" }` events on its own timer once
  authenticated (`src/background/agent-relay.ts:367-369`,
  `src/background/agent-relay/wire.ts:60`).
- Backoff: capped exponential `BACKOFF_BASE 500 ms` → `BACKOFF_CAP 15_000` ms with
  jitter; a connection lasting `STABLE_AFTER = 5_000` ms resets the counter
  (`src/background/agent-relay/wire.ts:61-64`, `src/background/agent-relay.ts:415-429,501-505`).
  An MV3-alarm keeps retries alive while the worker sleeps
  (`extensionReconnectAlarmPeriodMs = 30_000`, `src/agent-relay/protocol.ts:29`).
- Close codes (`src/background/agent-relay/wire.ts:72-83`): 4001 replaced,
  4002 liveness/hello failure, 4003 protocol incompatible, 4004 another browser
  connected (conflict), 4006 pairing failed, plus `checkoutReloadPrefix` reload
  signalling (`cli/src/protocol.ts:21`).
- Liveness: relay `ExtensionRpc` sends `ping` commands; unanswered ping → `unresponsive`
  (`browserUnresponsive`, `cli/src/protocol.ts:17`); wedged past `wedgedAfterMs`
  (default 60 s) → relay sends `{ id: 0, method: "runtime.reload" }` and drops the
  link (`cli/src/relay/rpc.ts:215-229,292-297`). Command timeouts:
  `OPENCODE_BROWSER_EXTENSION_TIMEOUT_MS` default 30 s (min 15 s, max 300 s),
  ping timeout default 10 s, `awaitPromise` evaluates up to 5 min
  (`cli/src/relay/rpc.ts:41-53,199-204`). A timed-out command fails only that command;
  only a failed ws-level liveness probe tears the connection down
  (`cli/src/relay/rpc.ts:55-62,263-285`).

### 1.5 Command routing to sessions/tabs

- The relay keeps one `ExtensionRpc` + one WebSocket per connected browser
  (`cli/src/relay/browser-links.ts`, `BrowserLink { key, id, name, rpc, socket, generation, ... }`).
- Chrome tab ids collide across browsers, so the relay translates them at the link
  edge to its own large ids (`nextTabId` from `1_000_000_000`,
  `cli/src/relay/browser-links.ts:88-91`): `browsers.relayTabId(link, tabId)` on events
  (recording frames too, `cli/src/relay/extension-handshake.ts:225`), reverse on commands.
- Extension tabs↔relay sessions: `TargetRegistry` (`cli/src/cdp/target-registry.ts`)
  holds live `ConnectedTarget { tabId, sessionId, opencodeBrowserSessionId, targetInfo, owner }`
  (`cli/src/relay/types.ts:11-30`). `opencodeBrowserSessionId` = owning browse session
  for relay-owned tabs; user-attached/adopted tabs carry `owner: "user"`.
- Session→browser: `ExtensionHandshake.resolveSessionBrowser(requested?, session?)`
  (`cli/src/relay/extension-handshake.ts:129-156`): a session is pinned to its
  browser profile key; new sessions use `browsers.defaultLink()` (first connected).
  `session.browser = { id, key }` persists (`cli/src/session-manager/types.ts:87-89`).

## 2. Pairing ceremony

Files: relay `cli/src/relay/browser-pairing.ts` (+ handshake server
`cli/src/relay/extension-handshake.ts`); extension `src/background/pairing.ts`
(+ `src/background/agent-relay/handshake.ts`).

### 2.1 Code format / entropy / expiry

- 8-char Crockford base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), canonical form
  uppercases and maps I/L→1, O→0 (`cli/src/relay/browser-pairing.ts:34-35,61-64`;
  extension `src/background/pairing.ts:7-22`). Printed as `XXXX-XXXX`
  (`formatPairingCode`, `cli/src/relay/browser-pairing.ts:67-69`).
- Entropy: 40 bits (`crypto.randomBytes(8)`, one char per 5 bits,
  `cli/src/relay/browser-pairing.ts:99`). Relay stores only the slow hash
  `PBKDF2-SHA256(code, salt "opencode-browser-pairing-v3|code", 100_000 iters)` hex
  (`pairingCodeKey`, `cli/src/relay/browser-pairing.ts:72-74`; extension derives the
  same in `pairingCodeMaterial`, `src/background/pairing.ts:47-52`).
- TTL: `pairingCodeTtlMs = 10 min`; install-bundled codes `bundledPairingCodeTtlMs = 60 min`
  (`cli/src/relay/browser-pairing.ts:41-43`). Pending file `<stateRoot>/pairing-codes.json`;
  max 5 pending codes; 5 consecutive wrong guesses (`maxPairingFailures`) discards all
  (`cli/src/relay/browser-pairing.ts:3-6,96-126`). File removed when empty
  (`writePendingCodes`, `cli/src/relay/browser-pairing.ts:88-94`).

### 2.2 ECDH key agreement (message sequence)

Comment block `cli/src/relay/browser-pairing.ts:8-17`:

1. ext→relay `hello { pairing: { mode: "code", nonce, ecdh } }` — ephemeral P-256
   keypair per attempt, raw uncompressed public key base64url
   (`src/background/pairing.ts:55-71`; relay `ecdhExchange`,
   `cli/src/relay/browser-pairing.ts:232-242`, validates 65 bytes starting `0x04`).
2. relay→ext `auth.challenge { nonce, ecdh, protocol }`
   (`cli/src/relay/extension-handshake.ts:397`).
3. Both derive `sessionKey = HMAC(PBKDF2(code) | code, "…|session|endpoint|extNonce|relayNonce|extKey|relayKey|" || ECDHsecret)`
   (`pairingSessionKey`, `cli/src/relay/browser-pairing.ts:256-262`;
   extension `src/background/pairing.ts:76-84`). Endpoint is port-bound
   (`pairingEndpoint(port) = "127.0.0.1:{port}/extension"`,
   `cli/src/relay/browser-pairing.ts:222-224`) so a listener on another port cannot
   forward the handshake (`src/background/pairing.ts:28-31`).
4. ext→relay `auth.code { proof: HMAC(sessionKey, "…|proof|extension") }`
   (`sessionProof`, `cli/src/relay/browser-pairing.ts:264-266`).
   Relay finds the matching pending code by trial over stored PBKDF2 keys
   (`cli/src/relay/extension-handshake.ts:415-427`).
5. relay→ext `auth.token { proof: HMAC(sessionKey,"…|proof|relay"), wrapped }` where
   `wrapped = base64url(token ⊕ HMAC(sessionKey,"…|wrap"))[32 bytes]`
   (`wrapToken`, `cli/src/relay/browser-pairing.ts:269-272`).
6. ext→relay `auth.response { proof: HMAC(token,"…|extension|endpoint|extNonce|relayNonce") }`
   (`pairingProof`, `cli/src/relay/browser-pairing.ts:227-229`;
   extension `src/background/pairing.ts:34-36`).
- Legacy 32-char shared code (`extension-pairing` file) uses the same shape with
  `mode: "legacy"` and the code itself as session-key material; extensions too old
  to take a token pair "the old way" (`cli/src/relay/browser-pairing.ts:22-24,199-217`).
  Nothing creates that file anymore.

### 2.3 Token storage / HMAC challenge on every connection

- Token: 32 random bytes base64url (43 chars), `newBrowserToken`
  (`cli/src/relay/browser-pairing.ts:170`). Relay: `<dataRoot>/browser-tokens.json`
  keyed by extension profile id: `{ token, pairedAt, receipt?, lastSeen?, id?, name? }`
  (`BrowserPairing`, `cli/src/relay/browser-pairing.ts:138-147,150-160`).
  Extension: `chrome.storage.local { "host:port": token }` (`TOKENS_KEY`,
  `src/background/agent-relay/wire.ts:43`), most-recently-used key order
  (`rememberToken`, `src/background/agent-relay/handshake.ts:286-297`).
  Both files mode 0600 via `writePrivateJsonFileSync` (`cli/src/fs-durability.ts`).
- Every later connection: `hello { pairing: { mode: "token", nonce } }`,
  relay answers `auth.challenge { nonce, proof: HMAC(token,"…|relay|endpoint|relayNonce|extNonce"), protocol }`,
  extension verifies with constant-time `sameSecret`, then sends
  `auth.response { proof: HMAC(token,"…|extension|endpoint|extNonce|relayNonce") }`
  (`cli/src/relay/extension-handshake.ts:378-382`;
  `src/background/agent-relay/handshake.ts:304-376`).
- `pair` waits for redemption via a `receipt` uuid: mint stores it with the pending
  code, the issued token carries it, CLI polls `readBrowserPairings()` for a
  `lastSeen` entry with that receipt (`cli/src/setup/commands.ts:238-264`,
  `issueBrowserToken(..., receipt)`, `cli/src/relay/browser-pairing.ts:173-177`).

### 2.4 pair --list / --revoke

- `--list`: joins `browser-tokens.json` with `browsers.json` (relay's stable
  `{ profileKey: { id, name } }`), prints id/name/profile/paired/last-seen, plus
  legacy-code status (`cli/src/setup/commands.ts:268-296`).
- `--revoke <id|name|profile>|legacy`: `revokeBrowserToken(key)` deletes the token;
  a running relay drops the live socket with close 4006 via `POST /extension/revoke { key }`
  (`cli/src/setup/commands.ts:298-335`; route `cli/src/http-api.ts:134-141`;
  `revokeBrowser`, `cli/src/relay/extension-handshake.ts:112-118`).

### 2.5 Extension-side credential bookkeeping

`chrome.storage.local`: `agentRelayPairingCode` (typed/link code, one-shot),
`agentRelayCodePort` (the only port that code may go to),
`agentRelayTokens` (per `host:port`), `agentRelaySpentCodes` (last 20 tried codes),
`agentRelayProfile` (stable random profile id), `agentRelayDiscoveredPort/Kind`
(hint|scan), `agentRelayProvenPort`, `agentRelayPort` (dev pin)
(`src/background/agent-relay/wire.ts:24-46`).
A code is never sent to a scanned port; typed code without a port uses 19988
(`credential()`, `src/background/agent-relay/handshake.ts:177-207`).

## 3. Relay HTTP API + auth

### 3.1 Relay token

- File `<dataRoot>/relay-token`: 32 random bytes base64url + `\n`, mode 0600,
  created atomically (tmp + `link`, first-writer-wins) on first use
  (`cli/src/relay/secrets.ts:17-62`). Pattern `^[A-Za-z0-9_-]{43}$`.
- Required as `Authorization: Bearer <token>` (constant-time compare after
  SHA-256-hash + `timingSafeEqual` + length check, `secretsEqual`,
  `cli/src/relay/secrets.ts:33-37`; `hasRelayToken`,
  `cli/src/http-api/errors.ts:25-29`) on every route except `/` and `/version`,
  or as `?opencodeBrowserToken=` on CDP WebSocket URLs where headers can't be set
  (`relayTokenQueryParam`, `cli/src/relay/secrets.ts:14`;
  `cli/src/relay/transport-server.ts:147`;
  `/json/version` mints a URL with the token embedded, `cli/src/http-api.ts:113-127`).
- `Browse-Token` note: header name is literally `authorization` checked with
  `Bearer` scheme (`relayTokenHeader`, `cli/src/relay/secrets.ts:12`).

### 3.2 Endpoint inventory (`cli/src/http-api.ts:78-214`, routes in `cli/src/http-api/*.ts`)

Unauthenticated: `GET /`, `GET /version` (version, buildId, instanceId, pid,
managed, shutdownProtocol 2, extension-protocol range, pairing scheme;
`x-browse-install: waiting` lease flag), `GET /json/version` (needs token —
returns `webSocketDebuggerUrl` with token), `GET /json/list` (target summaries),
`GET /extension/status` (extension status + sessions + targets).
Authenticated: `POST /shutdown` (`RelayShutdownRequest`), `POST /extension/reload`,
`POST /extension/revoke { key }`, `POST /tabs/cleanup { idleMinutes?, sessions? }`,
`GET|POST /settings`, `/recording/*`, `/flight-recorder/*`, `/network/*`
(start/status/stop/cancel), `/auth/*` (status/refresh/run),
`/v1/sessions/ensure`, `/v1/authenticated-origin/json`, `/cli/*`
(sessions, session/new, session/allowlist, session/delete, session/reset,
session/adopt, execute).
Unknown → 404 `Not found` (`sendNotFound`, `cli/src/http-api/errors.ts:163-165`).

### 3.3 Host allowlisting / why CDP answers on 127.0.0.1 only

- Every HTTP request: `validateHostHeader` — Host must parse and be one of
  `localhost, 127.0.0.1, ::1, <configured host>` with matching port
  (`cli/src/relay/origins.ts:56-69`); `Sec-Fetch-Site` must be absent/same-origin/none
  (`validateBrowserFetchSite`, `cli/src/relay/origins.ts:71-78`).
- Non-local TCP peers (`isLocalPeer`: `::1` or `127.*`,
  `cli/src/relay/config.ts:42-46`) get 404 on HTTP
  (`cli/src/relay/transport-server.ts:89-92`) and only `/extension` upgrades;
  everything else (incl. `/devtools/browser/...`) is destroyed
  (`cli/src/relay/transport-server.ts:120-125`).
- `/extension` upgrade additionally requires a chrome-extension origin:
  the pinned unpacked id, configured store ids, or `OPENCODE_BROWSER_EXTENSION_ORIGINS`
  (`validateWebSocketOrigin`, `cli/src/relay/origins.ts:80-98`;
  `chromeExtensionOriginForPath` derives unpacked ids from the path hash,
  `cli/src/relay/origins.ts:11-22`).
- Default bind `127.0.0.1:19988` (`defaultHost`/`defaultPort`,
  `cli/src/relay/helpers.ts:28-30`); port pin/fallback in §8-adjacent:
  configured port = `OPENCODE_BROWSER_PORT` else 19988; unpinned relay falls back
  over `[19990..19997]` (19989 skipped = Browser Control's),
  state keyed by configured port, running port recorded in
  `<stateRoot>/relays/<configuredPort>/relay.json`
  (`cli/src/relay/port.ts:1-13,26-33`).
- Rationale in code: "Connections from other computers only ever reach the
  extension's WebSocket, where pairing applies; agents, the CLI and CDP clients
  use 127.0.0.1 either way" (`cli/src/relay/config.ts:3-5`). I.e. CDP/agent power
  is loopback-only; cross-machine agents must run their own loopback relay
  (or the CLI) — the extension link is the only remote surface, gated by pairing.

## 4. Session model

### 4.1 Identity, persistence, restore

- In-memory `OpenCodeBrowserSessions.sessions: Map<id, OpenCodeBrowserSession>`
  (`cli/src/session-manager.ts:59`); session object
  (`cli/src/relay/types.ts:82-98`): `{ id, createdAt, updatedAt, readOnly,
  allowedOrigins?, browser?: { id, key? }, sandbox, executeSemaphore,
  target?: { id, owner }, clientPid? }`.
- Durable catalog `<dataRoot>/relays/<port>/sessions.json` (`{ version: 1, sessions[] }`,
  `PersistedSession`, `cli/src/session-manager/catalog.ts:10-36`) written atomically
  (tmp `wx 0600` + rename + dir fsync, `cli/src/session-manager/catalog.ts:67-101`),
  corrupt file renamed aside and started empty (`:48-64`).
- Session ids: MCP-implicit `mcp-<8 hex>` (sha256 of the OpenCode agent session id,
  `cli/src/mcp/registry.ts:65-67`), generated `adjective-noun-NNN`
  (`cli/src/relay/helpers.ts:101-114`), or agent-chosen (validated:
  1–63 chars, lowercase/digits/dashes etc., `isValidSessionId`,
  `cli/src/relay/helpers.ts:65-69`).
- `SessionAdmission` serializes create/restore/delete vs persistence
  (`cli/src/session-manager/admission.ts`); `restore()` rehydrates sandbox +
  target binding (`cli/src/session-manager.ts:118-143`); unknown/user tabs never
  inferred (`sweepOrphanedRelayTargets`, `cli/src/session-manager.ts:636-639`).
- CLI-side current session pointer: `<stateRoot>/session.json`
  `{ endpoints: { "<endpoint>": { id } } }` scoped per relay endpoint, mode 0600
  (`cli/src/session-manager/store.ts:5-14`).

### 4.2 Tab↔session mapping

- Relay-owned tabs (`Target.createTarget`) vs user-owned adopted tabs
  (`owner: "relay"|"user"`, `cli/src/relay/types.ts:6-9`).
  Default-page binding `session.target = { id (durable target id), owner }`
  (`cli/src/relay/types.ts:6-9`, `cli/src/session-manager/types.ts:235-246`).
- Ownership authoritative in `TargetRegistry` (`MemoryTargetOwnership`,
  `cli/src/session-manager.ts:81`); only one session may own/interact with a tab
  (adoption reserves + validates registry generation; `TargetOwnershipError`
  not-found/owned/generation-changed → 404/409, `cli/src/http-api/errors.ts:142-150`).
- Tab presentation per tab: badge ON/RUN/WAIT + page-status attached/running/waiting
  (`cli/src/relay/tab-presentation.ts:24-26,66-115`); grouping per session or shared
  (`src/background/agent-relay/grouping.ts`, `GROUP_MARKER U+2063`).

### 4.3 Idle / lifecycle rules

- Extension closes agent-created tracked tabs idle past the `idleMinutes` setting
  (default 10; values 10/30/60/never; `settingsFromExtension` maps 0↔never,
  `cli/src/relay/user-settings.ts:...`; `DEFAULT_IDLE_MINUTES`,
  `src/shared/protocol.ts:113-115`). Skips: focused active tab, audible tabs,
  pinned tabs (forgotten), handoff-waiting or recording tabs (guards)
  (`src/background/tab-cleanup.ts:108-150`). `tabs.cleanup` RPC with per-session
  scoping (`own` thresholds, `src/background/tab-cleanup.ts:110-121`).
- Relay reaps only auto-named sessions (`mcp-*`, `adjective-noun-NNN`, `oc-*`;
  named sessions never) idle past `OPENCODE_BROWSER_IDLE_TAB_TTL_MS` (default
  15 min), skipping executing/admission-pending/capture-active sessions and busy
  targets (`reapIdleSessions`, `cli/src/session-manager.ts:606-632`).
- Orphan collection: sessions whose `clientPid` (from `OpenCode-Browser-Client-Pid`
  header, `noteClient`, `cli/src/http-api/errors.ts:19-22`) is dead get captures
  ended and `mcp-*` sessions deleted after `clientGoneGraceMs`
  (`cli/src/relay/housekeeping.ts:165-192`).
- Executes serialize per session (`executeSemaphore`, `cli/src/session-manager.ts:828-849`;
  lifecycle ops race the lock with a 10 s permit timeout).

## 5. `execute` sandbox

### 5.1 Realm and membrane

- Default: `node:vm` context with `codeGeneration: { strings: false, wasm: false }`,
  no `eval`/`new Function`, microtask mode `afterEvaluate`
  (`cli/src/execute-realm.ts:176-184`). Host objects cross a proxy membrane
  (host→sandbox proxies re-wrap results; sandbox→host copies plain data;
  sandbox functions wrapped; host intrinsics mapped to sandbox ones so
  `page.constructor.constructor("return process")()` compiles clean
  (`cli/src/execute-realm.ts:11-51`).
- Withheld (`deniedHostValues`, `cli/src/execute-realm/membrane-policy.ts:26-48`):
  `process`, relay global, `fs`/`fs/promises`, `child_process`, `cluster`, `dgram`,
  `dns`, `http`, `http2`, `https`, `inspector`, `module`, `net`, `os`, `tls`, `v8`,
  `vm`, `worker_threads`, Node `fetch`, `eval`, `util.inspect/format`,
  `crypto.setEngine`, sync CPU hogs (`pbkdf2Sync`…), `events.setMaxListeners`.
  Denied classes (`membrane-policy.ts:51-69`): `APIRequest/Context/Response`
  (= `page.request`/`context.request`), `Tracing`, `Download`, `Video`,
  `BrowserType`, `Playwright`, `Android`, `Electron`, `LocalUtils`.
  Underscore-prefixed members hidden (`isHiddenKey`, `membrane-policy.ts:126`).
  `page.setViewportSize` tracked+restored, `page.bringToFront` refused
  (message at `file-roots.ts:73-74`), download behavior guarded
  (`cli/src/execute/guards.ts:28-64`).
- Available: `page, context, browser, state` (persistent per sandbox),
  `modules` = `{ path, url, util, crypto, buffer, zlib, events, stream }`
  (full Node only with `OPENCODE_BROWSER_ALLOW_NODE=1`, when `nodeModules` adds
  `fs, os, http, https`, `cli/src/execute/code-runner.ts:37`),
  `files`, `snapshot/ref/ariaSnapshot/screenshotWithLabels/screenshotDiff`,
  `webmcp`, `ghostCursor`, `handoff/demonstrate`, `network`, `pageConsole`,
  `secrets`, `fillInput/fillInputs` (`cli/src/execute/sandbox-globals.ts:127-214`;
  tool input schema `cli/src/mcp/execute.ts:13-44`).
- Explicitly not a security boundary ("defence in depth… agent keeps everything
  the browser itself can do", `cli/src/execute-realm.ts:46-51`).

### 5.2 File roots and timeout slices

- File access must stay inside execute file roots: `os.tmpdir()`, `/tmp`
  (non-Windows), plus `OPENCODE_BROWSER_FILE_ROOTS` (`executeFileRoots`,
  `cli/src/execute-realm/file-roots.ts:109-114`); every path component between root
  and target must be user-owned and not group/world-writable; files directly in a
  shared root refused — use `files.path(name)` (`files.dir` =
  `opencode-browser-files-<pid>-XXXXXX` in TMPDIR, 0700, pruned when owner pid dies,
  `cli/src/execute-realm/file-roots.ts:78-107`). Raw CDP paths checked the same
  (`checkCdpSend`, `checkFileOptions`, same dir).
- CPU slices: one synchronous VM entry ≤ `OPENCODE_BROWSER_EXECUTE_SLICE_MS`
  (default 2 000, clamp 50–60 000; V8 TerminateExecution; realm disposed after)
  (`executeSliceMs`, `file-roots.ts:41-49`); sustained >50 % of relay thread CPU
  over 3 slices also disposes (`cli/src/execute-realm.ts:30-36,253-269`).
  Async entries fairly scheduled across sessions (`ExecuteCpuScheduler`).
- Overall deadline: `timeoutMs` default 60 000 (0 disables; env
  `OPENCODE_BROWSER_EXECUTE_TIMEOUT_MS`), starts at call time so queueing counts;
  paused during `handoff()` (`cli/src/session-manager/execution.ts:60-91`,
  `ExecutionDeadline`, `cli/src/session-manager/types.ts:110-157`).
  When set (or env), Playwright action/navigate budget = max(30 s, timeout),
  else Playwright's 30 s default (`cli/src/session-manager/execution.ts:62-65`;
  SKILL.md `timeoutMs`).
- Unhandled rejections stop only the realm and warn on next call
  (`handleRealmUnhandledRejection`, `execute-realm.ts:227`;
  SKILL.md "Await promises").
- `page.evaluate` runs in an isolated world (page service worker skipped);
  4th arg `false` = main world (`cli/skills/browse/SKILL.md:286-290`).
  Cookie access narrowed to session page hosts
  (`cli/src/cdp/guardrails.ts:117-119`); `context.grantPermissions` etc. refused
  (guardrails list `cli/src/cdp/guardrails.ts:30-48`).

### 5.3 Patchright tells removed

`cli/src/execute/patchright-tuning.ts:17-28`: Patchright's closed-shadow-root walk
(`DOM.getDocument depth -1` + `_hasClosedShadowRoots`) disabled
(`selectors._hasClosedShadowRoots = async () => false`); `count()`/`querySelectorAll()`
reverted to Playwright lookup. Pages can't see the isolated-world scripts.

### 5.4 Human-like input sketch

`cli/src/execute/human-input.ts:1-16`: relay intercepts `Input.dispatchMouseEvent` /
`DOM.scrollIntoViewIfNeeded` in the CDP path (`beforeInput`,
`human-input.ts:119-131`): curved cubic-Bézier minimum-jerk pointer moves
(`trajectory`, Fitts-law duration, sub-pixel tremor, 25 % overshoot on long moves,
`human-input.ts:75-111`), 60–140 ms press holds, 30–110 ms key spacing, eased
wheel-burst scrolling + wheel-flick scroll-into-view (`glide`, `scrollToward`).
Per-tab serialized queue; coordinates nudged a few px off-center but kept on-target.
Pacing waits at most the sample gap (backgrounded tabs can hold pointer replies ~1 s,
`pace`, `human-input.ts:285-290`). Toggle: extension `humanInput` setting
(default on) overridden by `OPENCODE_BROWSER_HUMAN_INPUT`
(`humanInputEnabled`/`humanInputOverride`, `human-input.ts:26-40`).

## 6. Secrets flow

### 6.1 Storage

- Site secrets (typed into pages): one JSON file per secret
  `<dataRoot>/site-secrets/` (0700): `{ version: 1, name, kind: password|totp,
  value, allowedOrigins[], createdAt, updatedAt }`
  (`StoredSiteSecret`, `cli/src/auth/site-secrets.ts:24-33`;
  header comment `:1-13`). Values enter only via stdin/hidden prompt, never argv
  (`:11-12`). Names `[A-Za-z0-9._-]{1,64}`.
- Secret profiles (network-capture credential sets): `<dataRoot>/...` via
  `auth/profile.ts` under per-name lock files (`withLock`,
  `cli/src/auth/site-secrets.ts:112`); run injects `BC_SECRET_N` env vars
  (`secrets_run` description, `cli/src/mcp/secrets.ts:86-90`).
- Relay token / browser tokens / pairing codes: §2.3, §3.1 (all 0600).

### 6.2 `secrets.type` resolution + origin checks

`ExecuteSecretsBridge.typeSecret` (`cli/src/execute/secrets-bridge.ts:91-145`):
resolves string selector or Locator → `elementHandle` (10 s default) →
`ownerFrame()` from the CDP frame tree (not page script); checks frame URL
against the secret's `allowedOrigins` (`httpsOnlyWithoutScheme`: bare host =
https-only except local hosts) and against the session allowlist when present;
opaque `about:/data:/blob:` URLs always fail (`isOpaqueDocumentUrl`).
On failure throws `domain_not_allowed` / `target_not_editable` and types nothing.
Types via `frame.page().keyboard.insertText(secretText)` — the value never enters
the vm realm (`:130-134`); TOTP secrets type the current RFC 6238 code
(`totpCode`/`secretText`, `cli/src/auth/site-secrets.ts:178-192`).
Without a session allowlist a loud warning is added (`:138-140`).

### 6.3 Redaction points

- Typed values registered in `typedSecrets` map + capture `addRedaction`
  before insertion (`cli/src/execute/secrets-bridge.ts:131-133`); redacted from
  execute `text`/`value`/`logs`/`aftermath`/warnings
  (`redactOutputText/Value`, `redactCaptureLogs/Aftermath`, `:37-84`).
- Network capture: live `SecretCollector` redacts credential headers/cookies/query/
  fragments/structured bodies into `${BC_SECRET_N}` (values) and
  `${BC_REDACTED_N}` (passwords/OTPs/token-like path segments)
  (`cli/src/network-capture/*`, `har-redaction.ts`; MCP text in
  `cli/src/mcp/network.ts:73-75`); capture while `sensitive:true` authenticated
  request → `SensitiveCaptureActive` outcome instead
  (`cli/src/execute.ts:340`).
- Journals: credential-looking literals redacted by regexes (assignments,
  fill/type calls on credential lines, query params, JWT/Bearer/well-known key
  prefixes) before append (`redactJournalText`,
  `cli/src/session-manager/journal.ts:80-90`); code truncated to 2000 chars,
  preview 400 (`:48-50`); dirs 0700/files 0600, 14-day / 200-session retention
  (`:145-146`, `pruneSessionJournals`).

## 7. Handoff mechanics

- Registration: `HandoffRegistry.wait({ sessionId, tabId, targetId, targetSessionId,
  message, timeoutMs })` — one pending handoff per session (new replaces old as
  timeout), `crypto.randomUUID` id (`cli/src/session-manager/handoff.ts:104-172`).
  Exact-target binding: completion requires matching id + tabId + targetId +
  targetSessionId (`complete`, `:175-192`); target replace rebinds
  (`rebindTarget`, `:213-229`).
- Presentation: relay sends `pageStatus.set { tabId, status: { state: "waiting",
  owner, sessionId, message, handoffId, recording? } }`
  (`sendPageStatusEffect`, `cli/src/relay/tab-presentation.ts:74-92`) and badge
  `WAIT` (`waitingBadge`, `:26`); `requestHandoff` presents first, then runs
  optional `start` with completion held (`holdCompletion`) so a human click during
  the action still counts (`awaitHandoffAction`,
  `cli/src/session-manager/handoff.ts:36-79`; `requestHandoff`,
  `cli/src/relay/tab-presentation.ts:205-263`).
- Card: content script `src/content/page-status.ts` renders an in-page card +
  `Continue` button in a top-layer host sized to the card (page stays usable,
  `:8-14`); click sends `handoff.complete { handoffId }` → extension event
  `handoff.completed` → `handoffs.complete(...)`
  (`src/content/page-status.ts:250-258`;
  `cli/src/relay/extension-events.ts:300-304`).
  Only real UI completion paths resolve it: the in-page button or the toolbar
  button (`handleToolbarClick` → `complete-handoff`,
  `cli/src/relay/tab-presentation.ts:279-304`); page script cannot fake it
  (comment in `handoff.ts:90-93`: toolbar is "the user's way to finish a handoff
  from outside the page (which page script can't fake)").
  `handoff()` without `start` = human step only; default timeout 10 min
  (`defaultHandoffTimeoutMs`, `cli/src/execute/page-recovery.ts:30`).
- After resolve: `finishHandoff` re-waits for the execution context
  (15 s, `handoffPageContextTimeoutMs`); stale-context error tells the caller to
  run a short follow-up (`cli/src/execute/page-recovery.ts:219-257`).
  Timeout/cancel/start-failure semantics in `finishHandoff`/`awaitHandoffAction`.
- Notification fallback during recordings: `src/background/handoff-notice.ts` —
  recorded tab gets badge WAIT + system notification (`browse needs you on <origin>`,
  `requireInteraction`, clicking focuses the tab) instead of the card; if
  notifications are unavailable the card is drawn anyway (a silent handoff is
  worse than a card in the video). `handoffNotifications: recording|always`
  setting (`HANDOFF_NOTIFICATIONS_KEY`, `src/shared/protocol.ts:128-130`).
- Execute deadline paused during handoff (`pauseExecutionTimeout`,
  `cli/src/session-manager.ts:261-265`); stop/delete/reset cancels the handoff
  (`cancelForSession`, `handoff.ts:247-253`; relay hook
  `cli/src/relay.ts:405`); debugging-bar dismiss stops the script
  (`handleDebuggingBarCancel`, `tab-presentation.ts:311-320`).

## 8. Cross-machine mode (relay next to agent, extension elsewhere)

- Relay side: `browse set hostname 0.0.0.0` (`config.json` in config root,
  `cli/src/relay/config.ts:1-13`) or `install --remote`, which opens the
  hostname, ensures the relay, mints a code and prints `Relay address` (first
  non-virtual LAN IPv4, preferring Tailscale 100.64/10 + MagicDNS) + one-time
  code (`cli/src/setup/remote.ts:57-93`, `remoteRelayAddresses` `:21-31`).
- Extension side: `RELAY_ADDRESS_KEY` (`agentRelayAddress`, `host` or `host:port`,
  `src/shared/protocol.ts:50-56`); typed code is pinned to the typed port
  (`CODE_PORT_KEY`), never to a scanned one
  (`src/background/agent-relay/handshake.ts:454-471`).
- What's encrypted: **nothing** — plain `ws://`. Code comment:
  "browse doesn't encrypt this connection: use it on a network you trust, or
  over Tailscale, not the open internet" (`cli/src/setup/remote.ts:90`).
  Confidentiality comes only from the network; the ECDH code exchange ( §2.2)
  still protects code/token from passive observers, and proofs are port-bound
  so a different-port listener can't relay the handshake
  (`src/background/pairing.ts:28-31`).
- Threat-model notes in code: remote peers can only ever reach `/extension`
  (HTTP 404, non-`/extension` upgrades destroyed,
  `cli/src/relay/transport-server.ts:89-92,120-125`); agents/CLI/CDP stay
  loopback-only (`cli/src/relay/config.ts:3-5`); unpaired presence sockets send
  no identity/tabs/commands (`src/background/agent-relay.ts:450-456`).

## 9. MCP tool surface (from `cli/src/mcp/*.ts`, not marketing)

Order prefixes: execute 100, session 200, browser 300-ish, network 400-ish,
recording 500-ish, secrets 600 (`registerMcpTools({ order, build })`,
`cli/src/mcp/registry.ts:39-45`).

| tool | inputs | outputs | notes |
|---|---|---|---|
| `execute` | `code*`, `session?`, `targetUrl?`, `targetIndex?`, `browser?`, `timeoutMs?` | text or `{value|media|logs|warnings|diagnostic|aftermath}`; no outputSchema (images carry no structuredContent) | destructive, openWorld; auto-creates caller session; retries once on `browser-unavailable` (`cli/src/mcp/execute.ts:7-83`) |
| `session_new` | `browser?`, `id?`, `readOnly?`, `allowedOrigins?` | `SessionContainer { session }` | makes it caller's current (`cli/src/mcp/session.ts:21-63`) |
| `session_allowlist` | `session?`, `allowedOrigins*` (empty clears) | `SessionContainer` | idempotent (`cli/src/mcp/session.ts:64-94`) |
| `session_list` | — | `SessionsContainer` | readOnly, no relay-compat needed |
| `session_use` | `id*` | `{ currentSession }` | only switches caller pointer |
| `session_reset` | `id`/`session` | `SessionResetResponse { session, stopped? }` | destructive; also makes it current |
| `session_delete` | `id`/`session` | `SessionDeleted + { currentSession }` | destructive idempotent; resets caller id to fresh `mcp-*` |
| `session_adopt` | `session?`, `targetUrl?`/`targetIndex?`/`tabId?` (exactly one of the three) | `SessionAdoptResponse { session, adoptedUrl, adoptedTargetId }` | never navigates |
| `status` | — | `{ endpoint, currentSession, sharedSession, version, status: ExtensionStatus, buildProblem?, update?, autoUpdate }` | readOnly |
| `tabs_cleanup` | `idleMinutes?`, `session?` | `TabsCleanupResponse { closed[] }` | scoped semantics per §4.3 |
| `settings` | `set?` (partial; `autoUpdate` excluded for agents) | `{ settings, changed?, unchanged?, overridden?, message? }` + note | user-initiated only per SKILL.md |
| `network` | `action*: start\|status\|stop\|cancel`, `session?` + per-action opts (urlFilter, resourceTypes, content omit/embed, maxBodyBytes, maxTotalBodyBytes, maxEntries, maxDurationMs, outputPath, secrets) | `NetworkToolOutput` | stop writes HAR with `${BC_SECRET_N}` refs |
| `recording` | `action*: start\|stop\|status\|cancel`, `session?`/`tabId?`, `outputPath?`, `mode?: auto\|tab-capture\|cdp`, `audio?`, `frameRate?`, `videoBitsPerSecond?`, `audioBitsPerSecond?`, `maxDurationMs?`, `cursor?` | `RecordingToolOutput` | auto: tab-capture for user tabs, CDP for relay tabs |
| `flight_recorder` | `action*: start\|status\|save_last\|cancel`, `retentionMs?` (1 s–120 s), `durationMs?`, … | `FlightRecorderToolOutput` | rolling buffer |
| `secrets_list` | — | `{ secrets: [{ name, kind, allowedOrigins, createdAt, updatedAt }] }` | never values |
| `secrets_status` | `name*` | `AuthProfileSummary` (refs/sources/expiry) | |
| `secrets_refresh` | `name*`, `session?`, `urlFilter?`, `timeoutMs?` | `NetworkStopResponse` | destructive openWorld |
| `secrets_run` | `name*`, `command*`, `args?`, `cwd?`, `timeoutMs?` | `AuthRunResponse { exitCode, signal, stdout, stderr, …truncated, durationMs }` | destructive; values reach the child, redaction doesn't protect against exfiltration by the command itself |

Session scoping across agents (`cli/src/mcp/registry.ts:69-134`): the caller id
comes from `tools/call` `_meta["ai.opencode/sessionID"]` (`opencodeSessionMetaKey`);
each caller gets its own current session + established set (LRU 1024). Without a
caller id all agents share one current session (`sharedSession: true`), so
subagents must `session_new` + pass `session` explicitly.
`session_new`/`session_use`/adopt-with-`session`/`execute`-with-`session` set the
caller's current; `session_reset` re-establishes; `session_delete` forgets + resets.

Wire contract for all of the above: `cli/src/relay/schema.ts` (Effect Schemas,
single source of truth, `:1-10`); error codes `RelayErrorCode`
(`:643-661`); HTTP mapping in `cli/src/http-api/errors.ts:86-152`.

## 10. Shapes appendix

### Extension↔relay handshake messages (extension→relay events / relay→extension)

```
// presence (no credential yet)
{ method: "protocol", params: { protocolVersion: 3, unpaired?: true } }
// code attempt
{ method: "hello", params: { version, build, protocolVersion: 3,
    profileId, profileName, browser, name?,
    pairing: { nonce, mode: "token"|"code"|"legacy", ecdh? } } }
// relay challenge (token):  auth.challenge { nonce, proof, protocol:{version,min} }
// relay challenge (code):   auth.challenge { nonce, ecdh, protocol }
// extension round 2 (code): auth.code { proof }
// relay round 2 (code):     auth.token { proof, wrapped }
// extension final:          auth.response { proof }
// steady state relay→ext:   { id, method, params? }   (ExtensionCommand)
// steady state ext→relay:   { id, result? } | { id, error } | { method, params? }
```
(`cli/src/protocol.ts:30-49,130-193`; `cli/src/relay/extension-handshake.ts:339-433`;
`src/background/agent-relay/handshake.ts:226-269,304-376`)

### Key / token / code formats

- Pairing code: `/^[0-9A-HJKMNP-TV-Z]{8}$/` (I/L→1, O→0), `XXXX-XXXX` display.
- Relay token / browser token / ECDH nonces: base64url 32 bytes (43 chars);
  HMAC proofs base64url 32 bytes; wrapped token base64url 32 bytes
  (`/^[A-Za-z0-9_-]{43}$/`, `src/background/pairing.ts:91`).
- `pairing-codes.json`: `{ codes: [{ key: <pbkdf2-hex-64>, expiresAt, receipt? }], failures }`.
- `browser-tokens.json`: `{ <profileId>: { token, pairedAt, receipt?, lastSeen?, id?, name? } }`.
- `relay-token`: `<43-char base64url>\n`, 0600.
- CDP URL: `ws://127.0.0.1:<port>/devtools/browser/<browserId>?opencodeBrowserSessionId=<id>&opencodeBrowserToken=<token>`
  (`cli/src/http-api.ts:113-127`; transport auth `cli/src/relay/transport-server.ts:147-166`).

### Session object (relay)

```
SessionSummary = { id, createdAt, updatedAt, connected: bool,
  pageUrl: string|null, stateKeys: string[],
  readOnly?, allowedOrigins?, browser?: <browser id> }
```
(`cli/src/relay/schema.ts:12-22`; `sessionSummary`,
`cli/src/session-manager/types.ts:213-226`).
Persisted form adds `browserKey`, `target { id, owner }`, `clientPid`
(`cli/src/session-manager/catalog.ts:10-26`,
`persistedSession`, `cli/src/session-manager/types.ts:235-246`).

### Relay↔extension tab/status objects

`PageStatus = { state: attached|running|waiting, owner: session|user,
sessionId?, readOnly?, message?, handoffId?, recording? }`
(`cli/src/protocol.ts:99-108`). `TargetSummary` adds `tabId, sessionId,
opencodeBrowserSessionId, owner, crashed?, protectedUi?`
(`cli/src/relay/schema.ts:264-276`).

### Recording frames (extension→relay binary)

`BCRD` magic + u8 version(1) + u8 flags(final) + u16 headerLen(20) +
u32 tabId + u32 sequence + u32 payloadLen + payload; ≤4 MiB payload
(`cli/src/recording-relay/protocol.ts:1-60`;
mirror `src/agent-relay/protocol.ts:112-130`).

## 11. Open questions / could not verify (read-only limits)

1. **Exact `hello`/`auth.*` JSON field names beyond `pairing.*`**: the relay reads
   them via `getString/getObject` accessors (`cli/src/relay/extension-handshake.ts:340-409`)
   and the extension builds them inline (`src/background/agent-relay/handshake.ts:253-269`);
   shapes above are reconstructed from both sides but no single schema type pins
   the handshake messages (unlike `ExtensionCommand`/`ExtensionEvent`).
2. **Store extension IDs**: allowed origins are release configuration
   (`cli/src/paths.ts` `storeConfig`, referenced `cli/src/relay/origins.ts:3-5`);
   not enumerated here.
3. **`debugger.*` payload details** (attach/detach/sendCommand params, `tabs.create`
   window semantics): implemented in `src/background/agent-relay/commands.ts` and
   `cli/src/relay/cdp-dispatch.ts` — skimmed only; reimplementation needs a full read.
4. **Network-capture internals** (CDP domains tapped, HAR redaction rules):
   `cli/src/network-capture/` + `har-redaction.ts` — redaction surface described
   from the MCP/error layer only.
5. **Ghost-cursor rendering**: `cli/src/ghost-cursor.ts` + recording modes
   (`tab-capture` vs `cdp`) in `cli/src/recording-relay*` — not read in depth.
6. **Auto-update / supervisor / install flows** (`cli/src/update/*`,
   `cli/src/setup/*`): only `pair`/`remote` paths were read.
7. **Snapshot format evolution** (`cli/src/snapshot/*`): helpers enumerated from
   the tool description; exact `snapshot()` text grammar lives in
   `aria-snapshot.ts`/`browser-capture.ts` and was not pinned down.
8. Behavior under `OPENCODE_BROWSER_*` env overrides taken from comments
   (`OPENCODE_BROWSER_ALLOW_ALL_COOKIES/STORAGE/FILE_URLS`,
   `OPENCODE_BROWSER_UPDATE_CHECK`, `OPENCODE_BROWSER_CHALLENGE_WAIT_MS`,
   `OPENCODE_BROWSER_KEEPALIVE_MS`, `OPENCODE_BROWSER_IDLE_TAB_TTL_MS`,
   `OPENCODE_BROWSER_EXECUTE_TIMEOUT_MS/SLICE_MS`) — defaults cited where found;
   a full env-var sweep was not done.
