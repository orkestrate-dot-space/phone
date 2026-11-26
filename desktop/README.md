# phone desktop — MCP + embedded relay + ADB driver (v0.24.0)

One Bun process: MCP server over stdio for the agent, HTTP+WS relay the phone dials, and a zero-APK ADB driver for local devices. No dependencies.

## Zero-APK quickstart (ADB — no app install)

If the device is on this machine or LAN, you don't need the companion app.
All you need is `adb` on PATH (Android [platform-tools](https://developer.android.com/tools/releases/platform-tools))
and the device visible in `adb devices`:

| Target | Connect |
| --- | --- |
| Android Studio emulator | just launch it (`adb devices` shows `emulator-XXXX`) |
| Waydroid | `waydroid show-full-ui`, then `adb connect 192.168.250.112:5555` |
| Twoyi / virtual devices | enable its ADB fed-in port, then `adb connect <ip>:<port>` |
| USB phone | enable USB debugging, plug in, accept the RSA prompt |
| Wireless debugging | pair in Developer options, then `adb connect <ip>:<port>` |

Then:

```sh
bunx @orkestrate/phone --serve &   # the relay (also serves local MCP calls)
bunx @orkestrate/phone observe     # auto-uses the ADB device when no app is paired
bunx @orkestrate/phone act '{"action":"tap","ref":"@e1"}'
```

As an MCP server (OpenCode / any client, stdio) it runs the relay too.
Logs always go to stderr — stdout is the MCP channel.

Env: `PHONE_PORT` (default 19877), `PHONE_DATA` (device store),
`PHONE_ALLOW_SHELL=1` (permit raw `adb shell`; otherwise blocked — see below).

## Which transport runs your call?

`observe`, `act`, `files`, `handoff`, and `shell` accept an optional `device`
(relay id/name, or ADB serial/model):

- `device` matches a paired relay device → **Relay** (companion app).
- No `device`, but an online paired device exists → **Relay**.
- Otherwise → **ADB** (serial/model match, or auto-select when exactly 1 online).
- Neither available → a self-correcting error telling you to run
  `adb devices` or `phone pair`.
- `observe` with `include: ["devices"]` returns both lists: `{ relay: [...], adb: [...] }`.

## ADB safe defaults (hardcoded)

- `shell` is **blocked by default** — re-run with `--allow-shell`
  (or `PHONE_ALLOW_SHELL=1`) to allow raw `adb shell` commands.
- `files.delete` is **always refused** in ADB mode (no `/sdcard` delete).
- `files.push` / `files.write` are strictly jailed to
  `/sdcard/Download/orkestrate/` — `..` traversal and any path outside
  (e.g. `/sdcard/DCIM/...`) is rejected. `list` / `pull` / `read` are allowed.
- `handoff` only ever reports `needs_human` — completion comes from the user,
  never from an API path. Screenshots ride along as native MCP `image` blocks,
  never as base64 in the JSON text.

## Companion app (remote + on-glass approvals)

For phones that aren't local — anywhere with internet — pair the
[Orkestrate Phone app](https://mobile.orkestrate.space/phone/app):

```sh
bun src/index.ts --serve &                    # the relay
curl -s localhost:19877/version               # public probe
bun src/index.ts pair --no-wait               # QR + link (codes are minted only here, never over HTTP)
bun src/index.ts wait                         # blocks until a phone redeems it
```

Other CLI commands: `battery`, `status`, `doctor`, `photo`, `exec -- <argv>`, `update`, `devices`, `revoke <id>`.
They talk to the running relay over `$PHONE_DATA/control.sock`. End users get the same as `phone <cmd>` from
`site/phone/install`.

`qrcodegen.ts` is Nayuki's QR Code generator (MIT), vendored; see `THIRD_PARTY_NOTICES.md`.

## Protocol

See [`../protocol/SPEC.md`](../protocol/SPEC.md).
