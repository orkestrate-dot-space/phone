# phone

Let your AI agents use your real Android phone: observe compact UI trees (`observe`), tap/type/scroll/open apps (`act`), read and write shared storage (`files`), pause for on-glass confirmation (`handoff`), and run allowlisted commands (`shell`).

## Zero-APK quickstart (local devices over ADB)

No app install needed — just `adb` on PATH and a device visible in `adb devices`
(Android Studio emulator, Waydroid, Twoyi, USB with USB debugging, or wireless
`adb connect <ip>:<port>`):

```sh
bunx @orkestrate/phone --serve &   # the relay (also serves local MCP calls)
bunx @orkestrate/phone observe     # auto-uses the ADB device when no app is paired
```

ADB safe defaults: raw `shell` is blocked unless `--allow-shell` /
`PHONE_ALLOW_SHELL=1`; `files.delete` is always refused; `files.push`/`write`
are jailed to `/sdcard/Download/orkestrate/`; `handoff` only ever reports
`needs_human` (approvals are never faked).

## Remote phones (companion app + QR pairing)

Tell your agent: *Set up my phone: read https://mobile.orkestrate.space/phone/agent.md*

Or run `curl -fsSL https://mobile.orkestrate.space/phone/install | bash`, then `phone pair` and scan the QR code with the Orkestrate Phone app (`https://mobile.orkestrate.space/phone/app`).
Pairing codes are minted only by the relay and redeemed by a real on-glass tap —
remote control with human approval for logins, 2FA, payments, and confirmation.

- `desktop/`: the 5-tool MCP server, relay, `phone` CLI, and ADB driver (Bun)
- `app/`: the Play-Protect-safe Android companion app (Kotlin, Jetpack Compose, SAF folder picker, persistent Shizuku Binder daemon, LAN-first + OpenTunnel failover, silent Shizuku auto-update)
- `site/`: the static pages, installer, and `agent.md` for `mobile.orkestrate.space/phone`
- `protocol/SPEC.md`: wire protocol
