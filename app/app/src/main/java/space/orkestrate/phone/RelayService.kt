package space.orkestrate.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Holds the relay socket in a foreground service so Doze can't kill it.
 * Owns the approval surface for background arrivals: notifies always,
 * auto-runs allowlisted commands headlessly.
 */
class RelayService : Service() {

  private lateinit var app: PhoneApp
  private lateinit var nm: NotificationManager
  private lateinit var runner: ExecRunner

  override fun onBind(i: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    app = application as PhoneApp
    runner = ExecRunner(this, app.relay, app.adb, app.store)
    nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("link", "relay link", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(NotificationChannel("jobs", "agent jobs", NotificationManager.IMPORTANCE_HIGH))
    app.relay.listener = ServiceListener()
    // Always-on mDNS watch: caches pair/connect ports so a pairing reply
    // fires instantly (codes expire fast), and posts the reply notification
    // exactly while the pair dialog is open.
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
    val n = note("connecting…")
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      @Suppress("DEPRECATION") startForeground(1, n)
    }
    // Silent restores: relay socket + shell channel (keys persist).
    app.relay.connect()
    Thread {
      try {
        val s = app.store.adbSerial
        val port = s?.substringAfterLast(":")?.toIntOrNull()
        if (s != null && port != null) {
          app.adb.setSerial(s)
          if (app.adb.connect(port)) {
            Log.d("phone", "service: shell restored ${app.adb.serial}")
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
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, "link")
      .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
      .setContentTitle("phone relay")
      .setContentText(state)
      .setContentIntent(open)
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
      // headless paths (no dialog possible): auto-allowlisted exec + opted-in self-update
      if (capability == "exec.run") {
        val argv = app.relay.jobParams(jobId)
          ?.optJSONArray("argv")
          ?.let { a -> (0 until a.length()).map { a.getString(it) } }
        if (argv != null && runner.shouldAuto(argv)) {
          runner.runExec(jobId, argv, object : ExecRunner.Sink {
            override fun note(s: String) { Log.d("phone", s) }
          })
        }
      } else if (capability == "app.update" && app.store.allowSelfUpdate) {
        SelfUpdate(this@RelayService, app.relay, app.adb, app.store).run(jobId,
          object : SelfUpdate.Sink {
            override fun note(s: String) { Log.d("phone", s) }
            override fun progress(pct: Int) {}
          })
      }
      // anything else waits for the on-glass tap (notification tap opens the card)
      app.uiListener?.onApproval(jobId, capability)
    }

    private fun notifyApproval(jobId: String, capability: String) {
      val open = PendingIntent.getActivity(
        this@RelayService, jobId.hashCode(),
        Intent(this@RelayService, MainActivity::class.java).putExtra("approval", jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val (title, text) = if (capability == "exec.run") {
        "Agent wants to run a command" to (app.relay.jobParams(jobId)?.optJSONArray("argv")
          ?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } }
          ?.take(120) ?: "…")
      } else {
        "Agent requests a photo" to "Tap to approve or reject"
      }
      val n = Notification.Builder(this@RelayService, "jobs")
        .setSmallIcon(if (capability == "exec.run") android.R.drawable.ic_menu_manage else android.R.drawable.ic_menu_camera)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
      nm.notify(jobId.hashCode(), n)
    }
  }
}
