package space.orkestrate.phone

import android.app.Application
import android.content.Intent
import android.os.Build

/** Single Relay + ShizukuBridge owner. The service holds sockets; the UI only listens. */
class PhoneApp : Application() {
  lateinit var store: Store
    private set
  lateinit var shizuku: ShizukuBridge
    private set
  lateinit var relay: Relay
    private set
  lateinit var acpBridge: AcpJoinBridge
    private set
  lateinit var updater: AppUpdater
    private set
  @Volatile var uiListener: Relay.Listener? = null

  override fun onCreate() {
    super.onCreate()
    store = Store(this)
    shizuku = ShizukuBridge(this)
    relay = Relay(this, store, shizuku)
    acpBridge = AcpJoinBridge(this, store, relay.phoneAgent)
    updater = AppUpdater(this, store, shizuku)
  }

  fun ensureService() {
    if (!store.paired) return
    val i = Intent(this, RelayService::class.java)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
  }
}
