package space.orkestrate.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Re-hold the sockets after a reboot (best effort). */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    try {
      val app = ctx.applicationContext as PhoneApp
      app.ensureService()
      // ADB channel: re-enable wireless debugging if we hold the permission,
      // then reconnect with persisted keys.
      try {
        if (app.packageManager.checkPermission(
            "android.permission.WRITE_SECURE_SETTINGS", app.packageName) ==
            PackageManager.PERMISSION_GRANTED) {
          android.provider.Settings.Global.putInt(
            ctx.contentResolver, "adb_wifi_enabled", 1)
          Thread.sleep(3000)
          app.adb.setSerial(app.store.adbSerial)
          val port = app.store.adbSerial?.substringAfterLast(":")?.toIntOrNull()
          if (app.store.adbPaired && port != null && app.adb.connect(port)) {
            app.store.adbSerial = app.adb.serial
            Log.d("phone", "boot: shell reconnected")
          } else app.adb.drop()
        }
      } catch (e: Exception) {
        Log.d("phone", "boot adb: $e")
      }
    } catch (e: Exception) {
      Log.d("phone", "boot start refused: $e")
    }
  }
}
