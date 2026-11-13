package space.orkestrate.phone

import android.app.Application
import android.content.Intent
import android.os.Build

/** Single Relay owner. The foreground service holds the socket; the UI only listens. */
class PhoneApp : Application() {
  lateinit var store: Store
    private set
  lateinit var relay: Relay
    private set

  override fun onCreate() {
    super.onCreate()
    store = Store(this)
    relay = Relay(this, store)
  }

  fun ensureService() {
    if (!store.paired) return
    val i = Intent(this, RelayService::class.java)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
  }
}
