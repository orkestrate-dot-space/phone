package space.orkestrate.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-hold the relay socket after a reboot. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    try {
      (ctx.applicationContext as PhoneApp).ensureService()
    } catch (e: Exception) {
      Log.d("phone", "boot start refused: $e")
    }
  }
}
