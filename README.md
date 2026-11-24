# phone

Let your AI agents use your real Android phone: see and drive apps (`screen`, `act`, `apps`), read and write `/sdcard` (`files`), inspect and reply to notifications (`notifications`), read/set the clipboard (`clipboard`), request photos (`capture_photo`), and pause for on-glass confirmation (`handoff`).

## Set up

Tell your agent: *Set up my phone: read https://mobile.orkestrate.space/phone/agent.md*

Or run `curl -fsSL https://mobile.orkestrate.space/phone/install | bash`, then `phone pair` and scan the QR code with the Orkestrate Phone app.

- `desktop/`: the MCP server, relay, and `phone` CLI (Bun)
- `app/`: the Android companion app (Kotlin, Jetpack Compose, AccessibilityService)
- `site/`: the static pages, installer, and `agent.md` for `mobile.orkestrate.space/phone`
- `protocol/SPEC.md`: wire protocol
