package space.orkestrate.phone

import android.app.Application
import android.content.Intent
import android.os.Build

/** Single Relay + AdbShell owner. The service holds sockets; the UI only listens. */
class PhoneApp : Application() {
  lateinit var store: Store
    private set
  lateinit var relay: Relay
    private set
  lateinit var adb: AdbShell
    private set
  /** Foreground UI listener, if any. The service forwards events here. */
  @Volatile var uiListener: Relay.Listener? = null

  override fun onCreate() {
    super.onCreate()
    store = Store(this)
    relay = Relay(this, store)
    adb = AdbShell(this)
  }

  fun ensureService() {
    if (!store.paired) return
    val i = Intent(this, RelayService::class.java)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
  }
}
