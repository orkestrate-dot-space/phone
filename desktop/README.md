# phone desktop — MCP + embedded relay (v0)

One Bun process: MCP server over stdio for the agent, HTTP+WS relay the phone dials.

## Run

```sh
bun src/index.ts --serve   # relay only (dev/logs)
```

As an MCP server (OpenCode / any client, stdio) it runs the relay too.
Logs always go to stderr — stdout is the MCP channel.

Env: `PHONE_PORT` (default 19877), `PHONE_DATA` (device store).

## Try it (no phone yet)

```sh
bun src/index.ts --serve &                    # the relay
curl -s localhost:19877/version               # public probe
bun src/index.ts pair --no-wait               # QR + link (codes are minted only here, never over HTTP)
bun src/index.ts wait                         # blocks until a phone redeems it
curl -s -X POST localhost:19877/pair/PD4YAQWJ -H 'content-type: application/json' -d '{"name":"pixel"}'
```

Other CLI commands: `battery`, `status`, `doctor`, `photo`, `exec -- <argv>`, `update`, `devices`, `revoke <id>`.
They talk to the running relay over `$PHONE_DATA/control.sock`. End users get the same as `phone <cmd>` from
`site/phone/install`.

`qrcodegen.ts` is Nayuki's QR Code generator (MIT), vendored; see `THIRD_PARTY_NOTICES.md`.

## Protocol

See [`../protocol/SPEC.md`](../protocol/SPEC.md).
