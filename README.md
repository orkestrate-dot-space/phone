# phone

Let your AI agents use your real Android phone: observe compact UI trees (`observe`), tap/type/scroll/open apps (`act`), read and write shared storage (`files`), pause for on-glass confirmation (`handoff`), and run allowlisted commands (`shell`).

## Set up

Tell your agent: *Set up my phone: read https://mobile.orkestrate.space/phone/agent.md*

Or run `curl -fsSL https://mobile.orkestrate.space/phone/install | bash`, then `phone pair` and scan the QR code with the Orkestrate Phone app (`https://mobile.orkestrate.space/phone/app`).

- `desktop/`: the 5-tool MCP server, relay, and `phone` CLI (Bun)
- `app/`: the Play-Protect-safe Android companion app (Kotlin, Jetpack Compose, SAF folder picker, persistent Shizuku Binder daemon, LAN-first + OpenTunnel failover, silent Shizuku auto-update)
- `site/`: the static pages, installer, and `agent.md` for `mobile.orkestrate.space/phone`
- `protocol/SPEC.md`: wire protocol
