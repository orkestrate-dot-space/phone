# Phone MCP: setup for agents

Phone MCP lets you (an AI agent) use the user's real Android phone: read its battery, ask for a photo, run a command. Photos, commands and updates only happen after the user taps Approve on the phone. You cannot approve for them.

This works for any agent that can run shell commands. MCP is optional: every step below is a `phone` shell command, so you do not need to restart to finish setup.

Requirements: a Linux or macOS computer with `curl`, and an Android 10+ phone with the user holding it. Setup needs the user for one step: scanning a QR code with the phone camera.

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
- Others: the same command and args in that client's MCP config.

Your client may need a restart before the tools appear. Keep going with the shell commands. Tools: `pair`, `pair_wait`, `pair_list`, `status`, `battery`, `capture_photo`, `exec`, `update`, `jobs`, `revoke`, `doctor`. Each has a `phone <name>` equivalent where it makes sense (`phone battery`, `phone photo`, `phone exec -- echo hi`).

## 4. Pair the phone

```sh
phone pair --no-wait
```

(MCP: call `pair`.) It prints a QR code, a link and a one-time code valid for 10 minutes. Show the QR block to the user exactly as printed, inside a code block. If your interface cannot draw it, give the user the `PNG` path or the `Link` line instead.

Tell the user: "Open your phone's camera and scan this. If the Orkestrate Phone app isn't installed, the page that opens offers the download (Android 10 or newer); install it, then scan again. In the app, allow notifications and background access when it asks."

Then wait for the phone:

```sh
phone wait
```

(MCP: call `pair_wait`.) It blocks until the phone connects, up to 10 minutes. If your shell tool times out sooner, run `phone wait` again; it keeps working until the code expires. On success it prints `Paired: <phone name> (<id>)`.

## 5. Confirm

```sh
phone battery
```

Report the result to the user, for example "Your phone is at 82% and charging." If it fails, see below. You are done.

## Using it afterwards

- `capture_photo` and `exec` show an approval card on the phone. They wait up to five minutes for the tap. If the user rejects, you get `rejected`. Do not retry in a loop; ask the user.
- `exec` runs one command as an argument list, for example `["getprop", "ro.product.model"]`, inside the app's limits.
- `status` and `pair_list` show paired phones and whether they are online. `revoke` forgets one.

## When something fails

| Symptom | Fix |
|---|---|
| `relay-not-running` | `phone start`, then retry. `phone logs` shows why it stopped. |
| `code expired or voided` | Run `phone pair --no-wait` again for a new QR. Five wrong tries void every pending code. |
| `no-device` | Nothing is paired yet. Do step 4. |
| Job waits and nothing happens | The phone is offline. `phone status` shows `online`. Ask the user to open the app and check the connection line. |
| The page after scanning says the app is missing | The user needs to install it from the page, then scan the QR again. |
| Phone cannot reach the relay | Rerun `phone doctor`. On `lan`, the phone must share the Wi-Fi; otherwise set a public URL (step 2). |

Source and protocol: https://github.com/orkestrate-dot-space/phone
