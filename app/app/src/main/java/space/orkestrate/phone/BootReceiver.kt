package space.orkestrate.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-hold the socket after a reboot (best effort; background FGS start may be refused on 14+). */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    try {
      (ctx.applicationContext as PhoneApp).ensureService()
    } catch (e: Exception) {
      Log.d("phone", "boot start refused: $e")
    }
  }
}
