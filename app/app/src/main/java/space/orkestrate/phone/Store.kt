package space.orkestrate.phone

import android.content.Context
import android.content.SharedPreferences

/** Pairing + identity store. Token never leaves prefs + Authorization header. */
class Store(ctx: Context) {
  private val p: SharedPreferences =
    ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

  var relayHttp: String? get() = p.getString("relayHttp", null); set(v) = put("relayHttp", v)
  var deviceId: String? get() = p.getString("deviceId", null); set(v) = put("deviceId", v)
  var token: String? get() = p.getString("token", null); set(v) = put("token", v)
  var name: String get() = p.getString("name", android.os.Build.MODEL) ?: android.os.Build.MODEL
  set(v) = put("name", v)

  val paired: Boolean get() = deviceId != null && token != null && relayHttp != null

  fun clear() = p.edit().remove("relayHttp").remove("deviceId").remove("token").apply()

  private fun put(k: String, v: String?) =
    p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
}
