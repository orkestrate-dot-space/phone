package space.orkestrate.phone

import android.content.Context
import android.content.SharedPreferences

/** Pairing + identity + safety store. Token never leaves prefs + Authorization header. */
class Store(ctx: Context) {
  private val p: SharedPreferences =
    ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

  var relayHttp: String? get() = p.getString("relayHttp", null); set(v) = put("relayHttp", v)
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

  var autoUpdate: Boolean
    get() = p.getBoolean("autoUpdate", true)
    set(v) = p.edit().putBoolean("autoUpdate", v).apply()

  /** Post-pairing checklist dismissed. Reset on unpair so a re-pair shows it again. */
  var setupDone: Boolean
    get() = p.getBoolean("setupDone", false)
    set(v) = p.edit().putBoolean("setupDone", v).apply()

  val paired: Boolean get() = deviceId != null && token != null && relayHttp != null

  fun clearRelay() = p.edit()
    .remove("setupDone").remove("relayHttp").remove("deviceId").remove("token").remove("paused")
    .apply()

  fun clear() = p.edit()
    .remove("setupDone").remove("relayHttp").remove("deviceId").remove("token").remove("paused")
    .remove("safTreeUri")
    .apply()

  private fun put(k: String, v: String?) =
    p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()

  companion object {
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
