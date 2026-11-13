package space.orkestrate.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Code entry without leaving Settings: a notification with an inline reply.
 * User opens "Pair device with pairing code" in wireless debugging, types the
 * 6 digits into the notification, and pairing + connect happen in background.
 */
object ShellPairing {
  const val ACTION_REPLY = "space.orkestrate.phone.SHELL_PAIR_REPLY"
  const val KEY_CODE = "code"
  const val NOTIF_ID = 2

  fun prompt(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("shell", "shell setup", NotificationManager.IMPORTANCE_HIGH))
    val pi = PendingIntent.getBroadcast(
      ctx, 0, Intent(ACTION_REPLY).setPackage(ctx.packageName),
      PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
    val reply = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()
    val action = Notification.Action.Builder(null, "Enter code", pi)
      .addRemoteInput(reply).build()
    val n = Notification.Builder(ctx, "shell")
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Pair this phone's shell")
      .setContentText("Open “Pair device with pairing code” in Settings, type the code here")
      .addAction(action)
      .setOngoing(true)
      .build()
    nm.notify(NOTIF_ID, n)
  }

  fun note(ctx: Context, text: String, done: Boolean = false) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val n = Notification.Builder(ctx, "shell")
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Pair this phone's shell")
      .setContentText(text)
      .setOngoing(!done)
      .setAutoCancel(done)
      .build()
    nm.notify(NOTIF_ID, n)
  }

  fun clear(ctx: Context) {
    ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
  }
}

class ShellPairReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != ShellPairing.ACTION_REPLY) return
    val code = RemoteInput.getResultsFromIntent(intent)
      ?.getCharSequence(ShellPairing.KEY_CODE)?.toString()?.filter { it.isDigit() }
    if (code.isNullOrBlank()) return
    Thread {
      try {
        ShellPairing.note(ctx, "pairing…")
        val adb = AdbShell(ctx)
        var pairPort: Int? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        adb.discover { _, pair ->
          pairPort = pair.firstOrNull()
          latch.countDown()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        val pp = pairPort
        if (pp == null) {
          ShellPairing.note(ctx, "no pair dialog found — open it in Settings first", done = true)
          return@Thread
        }
        if (!adb.pair("127.0.0.1", pp, code)) {
          ShellPairing.note(ctx, "pair failed — check the code", done = true)
          return@Thread
        }
        val store = Store(ctx)
        store.adbPaired = true
        val latch2 = java.util.concurrent.CountDownLatch(1)
        var connectPort: Int? = null
        adb.discover { connect, _ ->
          connectPort = connect.firstOrNull()
          latch2.countDown()
        }
        latch2.await(15, java.util.concurrent.TimeUnit.SECONDS)
        val cp = connectPort
        if (cp != null && adb.connect(cp)) {
          store.adbSerial = adb.serial
          try {
            adb.exec(listOf("pm", "grant", ctx.packageName,
              "android.permission.WRITE_SECURE_SETTINGS"), 15)
          } catch (_: Exception) {}
          ShellPairing.note(ctx, "shell connected — ${adb.serial}", done = true)
        } else {
          ShellPairing.note(ctx, "paired — open the app to finish connecting", done = true)
        }
      } catch (e: Exception) {
        Log.d("phone", "shell pair fail: $e")
        ShellPairing.note(ctx, "pair failed: ${e.message}", done = true)
      }
    }.start()
  }
}
