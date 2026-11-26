package space.orkestrate.phone

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class ParsedPair(val relay: String, val code: String, val agents: Boolean = false)

/**
 * Pure parser for pairing links, QR payloads, and pasted text.
 * Accepts:
 * - https://mobile.orkestrate.space/phone/pair#code=...&relay=...
 * - phone://pair?code=...&relay=...
 * - agents://pair?code=...&url=... (Agents Hub link; pairs the ACP bridge)
 * - {"code":"...","relay":"..."}
 */
object PairingUri {
  private val CODE_CLEAN = Regex("[^0-9A-Z]")

  fun normalizeCode(raw: String): String =
    raw.trim().uppercase()
      .replace('I', '1')
      .replace('L', '1')
      .replace('O', '0')
      .replace(CODE_CLEAN, "")

  fun normalizeRelay(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty() || t.contains("://")) return t
    val local = Regex("""^(localhost|\d{1,3}(\.\d{1,3}){3})(:\d+)?$""").matches(t)
    return (if (local) "http://" else "https://") + t
  }

  fun parse(raw: String): ParsedPair? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    if (text.startsWith("{")) {
      return try {
        val j = JSONObject(text)
        val code = normalizeCode(j.optString("code", ""))
        val relay = normalizeRelay(j.optString("relay", ""))
        if (code.length == 8 && relay.isNotBlank()) ParsedPair(relay, code) else null
      } catch (_: Exception) {
        null
      }
    }

    val uri = try { URI(text) } catch (_: Exception) { return null }
    if (uri.scheme == "agents" && uri.host == "pair") {
      val params = parseQuery(uri.rawQuery ?: "")
      val code = normalizeCode(params["code"] ?: return null)
      val url = (params["url"] ?: return null).trim().trimEnd('/')
      if (code.length != 8 || url.isBlank()) return null
      return ParsedPair(url, code, agents = true)
    }
    val queryStr = when {
      uri.scheme == "phone" && uri.host == "pair" -> uri.rawQuery
      (uri.scheme == "https" || uri.scheme == "http") && (uri.path ?: "").startsWith("/phone/pair") ->
        uri.rawFragment?.takeIf { it.isNotBlank() } ?: uri.rawQuery
      else -> null
    } ?: return null

    val params = parseQuery(queryStr)
    val code = normalizeCode(params["code"] ?: return null)
    val relay = normalizeRelay(params["relay"] ?: return null)
    if (code.length != 8 || relay.isBlank()) return null
    return ParsedPair(relay, code)
  }

  private fun parseQuery(q: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (part in q.split('&')) {
      val idx = part.indexOf('=')
      if (idx <= 0) continue
      val k = URLDecoder.decode(part.substring(0, idx), "UTF-8")
      val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
      out[k] = v
    }
    return out
  }
}
