package space.orkestrate.phone

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure loopback-auth helpers for LocalMcpServer.
 *
 * The local MCP/LLM server (127.0.0.1:19879) is reachable by any app on the
 * phone, so every request must carry `Authorization: Bearer <per-process
 * token>`. The token is generated at construction, given to the on-device fx
 * agent only via its process environment, and never logged.
 */
object LocalAuth {
  /** Per-process 128-bit bearer token, hex-encoded. Never logged. */
  fun generateToken(): String {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
  }

  /** Constant-time check of an Authorization header value against the local token. */
  fun bearerMatches(headerValue: String?, token: String): Boolean {
    if (headerValue == null) return false
    return MessageDigest.isEqual(
      headerValue.trim().toByteArray(Charsets.UTF_8),
      "Bearer $token".toByteArray(Charsets.UTF_8),
    )
  }

  /**
   * Upstream Authorization value for the LLM proxy, or null when the caller
   * is not authorized (wrong/missing bearer) or no real key is configured.
   * The caller must forward the returned value and never the incoming one,
   * so the real API key never leaves the app process.
   */
  fun upstreamBearer(incomingAuth: String?, localToken: String, realKey: String?): String? {
    if (!bearerMatches(incomingAuth, localToken)) return null
    if (realKey.isNullOrBlank()) return null
    return "Bearer $realKey"
  }

  /**
   * Naive whitespace tokenization of a shell cmd for the auto-approve
   * exact-match check. Conservative: anything quoted, piped, or otherwise
   * complex cannot equal an ExecPolicy.allowlisted argv, so it raises the card.
   */
  fun tokenizeShell(cmd: String): List<String> =
    cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

  /**
   * Decision rule for the on-device fx (LocalMcpServer `shell`) path only.
   * When the user picked "Let fx decide", fx's auto-mode reviewer already
   * cleared the call, so no card. Otherwise ("Always ask me") the same rule
   * as the relay shell tier applies: only store.autoApprove plus an exact
   * ExecPolicy.allowlisted argv runs without a tap; everything else needs
   * the on-glass approval card.
   */
  fun shellNeedsCard(autoApprove: Boolean, argv: List<String>, fxDecides: Boolean = false): Boolean =
    if (fxDecides) false else !(autoApprove && ExecPolicy.auto(argv))
}
