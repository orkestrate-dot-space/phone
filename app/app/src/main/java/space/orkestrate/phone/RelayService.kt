package space.orkestrate.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Holds the relay socket in a foreground service so Doze can't kill it.
 * Owns the notification shade kill-switch (Pause / Resume) and approval notices.
 */
class RelayService : Service() {

  private lateinit var app: PhoneApp
  private lateinit var nm: NotificationManager
  private lateinit var runner: ExecRunner
  private var lastStateText = "connecting…"

  override fun onBind(i: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    app = application as PhoneApp
    runner = ExecRunner(this, app.relay, app.adb, app.store)
    nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("link", "Relay link", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(NotificationChannel("jobs", "Agent requests", NotificationManager.IMPORTANCE_HIGH))
    app.relay.listener = ServiceListener()
    app.adb.watchPorts { connect, pair ->
      if (pair.isNotEmpty()) app.store.adbPairPort = pair.first()
      if (connect.isNotEmpty()) app.store.adbConnectPort = connect.first()
      if (pair.isNotEmpty() && app.store.adbSerial == null) {
        ShellPairing.prompt(this)
      } else {
        ShellPairing.clear(this)
      }
    }
  }

  override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
    val n = note(lastStateText)
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      @Suppress("DEPRECATION") startForeground(1, n)
    }
    app.relay.connect()
    Thread {
      try {
        val s = app.store.adbSerial
        val port = s?.substringAfterLast(":")?.toIntOrNull()
        if (s != null && port != null) {
          app.adb.setSerial(s)
          if (app.adb.connect(port)) {
            selfGrant()
          } else app.adb.drop()
        }
      } catch (e: Exception) { Log.d("phone", "service shell restore: $e") }
    }.start()
    return START_STICKY
  }

  private fun selfGrant() {
    try {
      if (packageManager.checkPermission(
          "android.permission.WRITE_SECURE_SETTINGS", packageName) ==
          PackageManager.PERMISSION_GRANTED) return
      app.adb.exec(listOf("pm", "grant", packageName,
        "android.permission.WRITE_SECURE_SETTINGS"), 15)
    } catch (e: Exception) { Log.d("phone", "service self-grant: $e") }
  }

  override fun onDestroy() {
    app.adb.unwatch()
    app.relay.disconnect()
    super.onDestroy()
  }

  private fun note(state: String): Notification {
    lastStateText = state
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val toggleIntent = Intent(PauseReceiver.ACTION).setPackage(packageName)
    val togglePi = PendingIntent.getBroadcast(
      this, 1, toggleIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val toggleLabel = if (app.store.paused) "Resume agent" else "Pause agent"
    val body = if (app.store.paused) "Paused · $state" else state
    return Notification.Builder(this, "link")
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("Orkestrate Phone")
      .setContentText(body)
      .setContentIntent(open)
      .addAction(Notification.Action.Builder(null, toggleLabel, togglePi).build())
      .setOngoing(true)
      .build()
  }

  private inner class ServiceListener : Relay.Listener {
    override fun onState(s: String) {
      nm.notify(1, note(s))
      app.uiListener?.onState(s)
    }
    override fun onJob(desc: String) { app.uiListener?.onJob(desc) }
    override fun onLog(s: String) { app.uiListener?.onLog(s) }
    override fun onApproval(jobId: String, capability: String) {
      notifyApproval(jobId, capability)
      if (capability == "exec.run") {
        val argv = app.relay.jobParams(jobId)
          ?.optJSONArray("argv")
          ?.let { a -> (0 until a.length()).map { a.getString(it) } }
        if (argv != null && runner.shouldAuto(argv)) {
          runner.runExec(jobId, argv, object : ExecRunner.Sink {
            override fun note(s: String) { Log.d("phone", s) }
          })
          return
        }
      } else if (capability == "app.update" && app.store.allowSelfUpdate) {
        SelfUpdate(this@RelayService, app.relay, app.adb, app.store).run(jobId,
          object : SelfUpdate.Sink {
            override fun note(s: String) { Log.d("phone", s) }
            override fun progress(pct: Int) {}
          })
        return
      }
      app.uiListener?.onApproval(jobId, capability)
    }

    private fun notifyApproval(jobId: String, capability: String) {
      val open = PendingIntent.getActivity(
        this@RelayService, jobId.hashCode(),
        Intent(this@RelayService, MainActivity::class.java).putExtra("approval", jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val p = app.relay.jobParams(jobId)
      val (title, text) = when (capability) {
        "human.handoff" -> "Agent needs you" to (p?.optString("message", "Tap to continue") ?: "Tap to continue")
        "files.op" -> "Agent wants to delete a file" to (p?.optString("path", "…") ?: "…")
        "exec.run" -> "Agent wants to run a command" to (p?.optJSONArray("argv")
          ?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } }
          ?.take(120) ?: "…")
        "app.update" -> "Agent wants to update Orkestrate Phone" to "Tap to approve"
        else -> "Agent requests a photo" to "Tap to approve or reject"
      }
      val n = Notification.Builder(this@RelayService, "jobs")
        .setSmallIcon(if (capability == "photo.capture") android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_manage)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
      nm.notify(jobId.hashCode(), n)
    }
  }
}

class PauseReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION = "space.orkestrate.phone.TOGGLE_PAUSE"
  }

  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val app = ctx.applicationContext as PhoneApp
    app.store.paused = !app.store.paused
    app.relay.sendState()
    app.uiListener?.onState(if (app.store.paused) "paused" else "connected — ${app.store.name}")
    app.ensureService()
  }
}
