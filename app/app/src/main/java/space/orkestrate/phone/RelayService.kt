package space.orkestrate.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Holds the relay socket in a foreground service so Doze can't kill it.
 * One persistent notification, updated on state changes.
 */
class RelayService : Service() {

  private lateinit var app: PhoneApp
  private lateinit var nm: NotificationManager

  override fun onBind(i: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    app = application as PhoneApp
    nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("link", "relay link", NotificationManager.IMPORTANCE_MIN))
  }

  override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
    app.relay.listener = ForwardingListener(app.relay.listener)
    val n = note("connecting…")
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      @Suppress("DEPRECATION") startForeground(1, n)
    }
    app.relay.connect()
    return START_STICKY
  }

  override fun onDestroy() {
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

  /** Updates the persistent notification, passes everything else to the UI listener. */
  private inner class ForwardingListener(private val ui: Relay.Listener?) : Relay.Listener {
    override fun onState(s: String) {
      nm.notify(1, note(s))
      ui?.onState(s)
    }
    override fun onJob(desc: String) { ui?.onJob(desc) }
    override fun onLog(s: String) { ui?.onLog(s) }
    override fun onApproval(jobId: String, capability: String) { ui?.onApproval(jobId, capability) }
  }
}
