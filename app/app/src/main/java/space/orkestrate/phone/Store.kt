package space.orkestrate.phone

import android.content.Context
import android.content.SharedPreferences

/** Pairing + identity + safety store. Token never leaves prefs + Authorization header. */
class Store(ctx: Context) {
  private val p: SharedPreferences =
    ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

  var relayHttp: String? get() = p.getString("relayHttp", null); set(v) = put("relayHttp", v)
  var relayLan: String? get() = p.getString("relayLan", null); set(v) = put("relayLan", v)
  var deviceId: String? get() = p.getString("deviceId", null); set(v) = put("deviceId", v)
  var token: String? get() = p.getString("token", null); set(v) = put("token", v)
  var name: String get() = p.getString("name", android.os.Build.MODEL) ?: android.os.Build.MODEL
    set(v) = put("name", v)

  var safTreeUri: String? get() = p.getString("safTreeUri", null); set(v) = put("safTreeUri", v)

  var paused: Boolean
    get() = p.getBoolean("paused", false)
    set(v) = p.edit().putBoolean("paused", v).apply()

  var blockedPackages: Set<String>
    get() = p.getStringSet("blockedPackages", DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
    set(v) = p.edit().putStringSet("blockedPackages", v).apply()

  var autoApprove: Boolean
    get() = p.getBoolean("autoApprove", false)
    set(v) = p.edit().putBoolean("autoApprove", v).apply()

  /**
   * On-device fx (LocalMcpServer `shell`) approval mode. "fx" (default): fx's
   * auto-mode reviewer already cleared the call, so no on-glass card.
   * "ask": every fx shell call raises the card (unless autoApprove + allowlist).
   * Relay shell tier (ExecRunner/exec.run) is unaffected: always card policy.
   */
  var fxApproval: String
    get() = p.getString("fxApproval", APPROVAL_FX) ?: APPROVAL_FX
    set(v) = put("fxApproval", if (v == APPROVAL_ASK) APPROVAL_ASK else APPROVAL_FX)

  var autoUpdate: Boolean
    get() = p.getBoolean("autoUpdate", true)
    set(v) = p.edit().putBoolean("autoUpdate", v).apply()

  var agentBaseUrl: String
    get() = p.getString("agentBaseUrl", "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1"
    set(v) = put("agentBaseUrl", v)

  var agentModel: String
    get() = p.getString("agentModel", "google/gemini-2.5-flash") ?: "google/gemini-2.5-flash"
    set(v) = put("agentModel", v)

  var agentApiKey: String?
    get() = p.getString("agentApiKey", null)
    set(v) = put("agentApiKey", v)

  var agentsUrl: String? get() = p.getString("agentsUrl", null); set(v) = put("agentsUrl", v)
  var agentsToken: String? get() = p.getString("agentsToken", null); set(v) = put("agentsToken", v)
  var agentsPeerId: String? get() = p.getString("agentsPeerId", null); set(v) = put("agentsPeerId", v)

  /** Post-pairing checklist dismissed. Reset on unpair so a re-pair shows it again. */
  var setupDone: Boolean
    get() = p.getBoolean("setupDone", false)
    set(v) = p.edit().putBoolean("setupDone", v).apply()

  val paired: Boolean get() = deviceId != null && token != null && relayHttp != null

  fun clearRelay() = p.edit()
    .remove("setupDone").remove("relayHttp").remove("relayLan").remove("deviceId").remove("token").remove("paused")
    .apply()

  fun clear() = p.edit()
    .remove("setupDone").remove("relayHttp").remove("relayLan").remove("deviceId").remove("token").remove("paused")
    .remove("safTreeUri")
    .remove("agentsUrl").remove("agentsToken").remove("agentsPeerId")
    .apply()

  private fun put(k: String, v: String?) =
    p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()

  companion object {
    const val APPROVAL_FX = "fx"
    const val APPROVAL_ASK = "ask"

    val DEFAULT_BLOCKED = setOf(
      "com.google.android.apps.walletnfcrel",
      "com.google.android.apps.authenticator2",
      "com.authy.authy",
      "com.onepassword.android",
      "com.agilebits.onepassword",
      "com.x8bit.bitwarden",
    )
  }
}
