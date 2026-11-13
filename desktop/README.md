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
curl -s localhost:19877/health
curl -s -X POST localhost:19877/pair        # loopback only → {code, expires_in}
curl -s -X POST localhost:19877/pair/PD4YAQWJ -H 'content-type: application/json' -d '{"name":"pixel"}'
```

Full loop (pair → WS hello → job → result) is covered by `/tmp/phone-e2e.ts`
with a fake phone; run it after touching `src/`.

## Protocol

See [`../protocol/SPEC.md`](../protocol/SPEC.md).
