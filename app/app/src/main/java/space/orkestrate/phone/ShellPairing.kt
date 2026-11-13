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

  @Volatile private var prompted = false

  fun reset() { prompted = false }

  fun prompt(ctx: Context) {
    if (prompted) return
    prompted = true
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
    prompted = false
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
        val store = Store(ctx)
        // cached port first (dialog may have closed after the code was read),
        // fresh discovery only if we have nothing cached
        var pp: Int? = store.adbPairPort.takeIf { it > 0 }
        if (pp == null) {
          val latch = java.util.concurrent.CountDownLatch(1)
          adb.discover { _, pair ->
            pair.firstOrNull()?.let { store.adbPairPort = it }
            pp = store.adbPairPort.takeIf { it > 0 }
            latch.countDown()
          }
          latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        }
        val pairPort: Int = pp ?: run {
          ShellPairing.note(ctx, "no pair dialog found — open it in Settings first", done = true)
          return@Thread
        }
        // pair to the LAN address shown in the dialog (loopback is flaky on some builds)
        val host = adb.lanIp() ?: "127.0.0.1"
        if (!adb.pair(host, pairPort, code)) {
          ShellPairing.note(ctx, "pair failed — check the code", done = true)
          return@Thread
        }
        store.adbPaired = true
        // cached connect port first (service watch keeps it fresh), else one scan
        var cp: Int? = store.adbConnectPort.takeIf { it > 0 }
        if (cp == null) {
          val latch2 = java.util.concurrent.CountDownLatch(1)
          var connectPort: Int? = null
          adb.discover { connect, _ ->
            connectPort = connect.firstOrNull()
            connectPort?.let { store.adbConnectPort = it }
            latch2.countDown()
          }
          latch2.await(15, java.util.concurrent.TimeUnit.SECONDS)
          cp = connectPort
        }
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
