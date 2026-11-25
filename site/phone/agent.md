# Phone MCP: setup for agents

Phone MCP lets you (an AI agent) use the user's real Android phone: see and operate apps (`screen`, `act`, `apps`), read and write shared storage (`files`), inspect and reply to notifications (`notifications`), read/set the clipboard (`clipboard`), request a photo (`capture_photo`), and hand off sensitive steps to the user (`handoff`). Photos, file deletes, handoffs, and shell commands wait for a real on-glass tap. You cannot approve for them.

This works for any agent that can run shell commands. MCP is optional: every step below is a `phone` shell command, so you do not need to restart to finish setup.

Requirements: a Linux or macOS computer with `curl`, and an Android 10+ phone with the user holding it. Setup needs the user for one step: scanning a QR code with the phone.

## 1. Install

Skip if `phone doctor` already works.

```sh
curl -fsSL https://mobile.orkestrate.space/phone/install | bash
```

Tell the user what this does before running it: installs Bun if missing (from bun.sh), puts a few files in `~/.local/share/phone`, adds a `phone` command to `~/.local/bin` and starts a relay as a user service. No root. `phone uninstall` removes it. If `phone` is not found afterwards, call `~/.local/bin/phone`.

## 2. Check how the phone will reach this computer

```sh
phone doctor
```

Read `publicUrl.kind`:

- `tunnel` or `env`: works from anywhere. Continue.
- `lan` or `tailnet`: the phone only connects on the same Wi-Fi or Tailscale network. Ask the user whether that is enough. If not, give the relay a public HTTPS URL for port 19877 (for example `opentunnel route add 19877`, a Cloudflare tunnel, or a reverse proxy) and pass it in step 4 with `--relay=https://...`. The relay speaks plain HTTP and WebSocket and does not encrypt anything itself, so use a tunnel that terminates TLS.

## 3. Add the MCP server (optional)

Add a stdio server to your own config: command `phone`, args `["mcp"]`.

- Claude Code: `claude mcp add phone -- phone mcp`
- OpenCode: `"phone": { "type": "local", "command": ["phone", "mcp"] }`
- Cursor / Antigravity / Codex: `"phone": { "command": "phone", "args": ["mcp"] }`

Your client may need a restart before the tools appear. Keep going with the shell commands.

## 4. Pair the phone

```sh
phone pair --no-wait
```

(MCP: call `pair`.) It prints a QR code, a link and a one-time code valid for 10 minutes. Show the QR block to the user exactly as printed, inside a code block. If your interface cannot draw it, give the user the `PNG` path or the `Link` line instead.

Tell the user: "Open Orkestrate Phone and tap **Scan QR code** (or scan with your phone's camera). If the app isn't installed yet, get it from the link that opens, install it, then scan again. In the app, grant Notifications, pick a shared folder for Files, and optionally install free Shizuku from Google Play for screen control, notifications, and shell."

Then wait for the phone:

```sh
phone wait
```

(MCP: call `pair_wait`.) It blocks until the phone connects, up to 10 minutes. On success it prints `Paired: <phone name> (<id>)`.

## 5. Confirm

```sh
phone device
phone screen
```

Report the result to the user (e.g. battery, model, and whether Shizuku and folder access are enabled). You are done.

## Tools (5 MCP tools)

- `observe`: get compact UI tree (`@e1`..), `device` telemetry, `notifications`, `apps`, or `screenshot` in one call. Supports `find` (filter tree) and `diff` (changes since last observe).
- `act`: tap, long_press, type (full Unicode), scroll, swipe, key, open app/URL, clipboard, or dismiss notification. Supports batched `steps[]` and returns the settled UI tree automatically.
- `files`: `list`, `read`, `write`, `search`, `move`, `pull`, `push`, `delete` (on-glass approval) in the granted folder or `/sdcard`.
- `handoff`: pause and ask the user to complete a step on glass (login, 2FA, payment, confirmation).
- `shell`: optional shell tier (via Shizuku `uid 2000`) and app-tier recon commands.

Setup & admin commands stay in the CLI: `phone pair`, `phone wait`, `phone doctor`, `phone status`, `phone install-app`.

## When something fails

| Symptom | Fix |
|---|---|
| `relay-not-running` | `phone start`, then retry. `phone logs` shows why it stopped. |
| `paused-by-user` | The user tapped Pause in the app or notification shade. Ask them to tap Resume. |
| `shizuku-required` / `shizuku-permission-required` | Ask the user to tap **Shizuku** on the app's Home screen to install/start Shizuku and grant permission. |
| `folder-not-granted` | Ask the user to tap **Files** on the app's Home screen to pick a shared folder. |
| `code expired or voided` | Run `phone pair --no-wait` again for a new QR. Five wrong tries void every pending code. |
| `no-device` | Nothing is paired yet. Do step 4. |
| Phone cannot reach the relay | Rerun `phone doctor`. On `lan`, the phone must share the Wi-Fi; otherwise set a public URL (step 2). |

Source and protocol: https://github.com/orkestrate-dot-space/phone
