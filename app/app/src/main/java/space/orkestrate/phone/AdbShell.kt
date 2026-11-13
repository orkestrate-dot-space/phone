package space.orkestrate.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * Embedded ADB: shell-UID execution with no second app.
 * Mechanism (same as LADB, own code): a prebuilt adb server binary shipped
 * in jniLibs runs as the app UID, pairs once with the device's own wireless
 * debugging over loopback, then carries shell commands. ADB keys persist
 * under filesDir, so pairing survives reboots; only the wireless-debugging
 * toggle resets (auto re-enabled once WRITE_SECURE_SETTINGS is self-granted
 * through the first shell).
 *
 * Third-party pieces live under third_party/ladb with their BSD notice.
 */
class AdbShell(private val ctx: Context) {

  data class Result(val exit: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

  private val adbPath = "${ctx.applicationInfo.nativeLibraryDir}/libadb.so"
  private val envHome = ctx.filesDir.path

  @Volatile var serial: String? = null   // e.g. localhost:41253
    private set

  fun setSerial(s: String?) { serial = s }
  fun drop() { serial = null }

  /** The bundled adb server must be extracted at install time. */
  fun serverPresent(): Boolean = try { java.io.File(adbPath).canExecute() } catch (_: Exception) { false }

  private fun base(): ProcessBuilder = ProcessBuilder()
    .directory(ctx.filesDir)
    .apply {
      environment()["HOME"] = envHome
      environment()["TMPDIR"] = ctx.cacheDir.path
    }

  private fun run(args: List<String>, stdin: String? = null, timeoutSec: Long = 30): Result {
    return try {
      val proc = base().command(listOf(adbPath) + args).start()
      if (stdin != null) {
        try { PrintStream(proc.outputStream).apply { println(stdin); flush() }.close() }
        catch (_: Exception) {}
      }
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      val t1 = Thread { try { proc.inputStream.copyTo(out) } catch (_: Exception) {} }
      val t2 = Thread { try { proc.errorStream.copyTo(err) } catch (_: Exception) {} }
      t1.start(); t2.start()
      val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
      t1.join(2000); t2.join(2000)
      if (!done) { proc.destroyForcibly(); return Result(-1, "", "adb timeout", true) }
      Result(proc.exitValue(), out.toString(), err.toString(), false)
    } catch (e: Exception) {
      Log.d("phone", "adb fail: $e")
      Result(-1, "", "adb failed: ${e.message}", false)
    }
  }

  fun startServer(): Boolean = run(listOf("start-server"), timeoutSec = 60).exit == 0

  fun devices(): List<String> {
    val r = run(listOf("devices"))
    if (r.exit != 0) return emptyList()
    return r.stdout.lines()
      .filter { it.contains("\tdevice") }
      .map { it.substringBefore("\t").trim() }
      .filter { it.isNotEmpty() }
  }

  /** Pair with the on-device wireless debugging daemon. One-time; keys persist. */
  fun pair(host: String, pairPort: Int, code: String): Boolean {
    startServer()
    val r = run(listOf("pair", "$host:$pairPort"), stdin = code.trim(), timeoutSec = 20)
    Log.d("phone", "adb pair -> ${r.exit} ${r.stdout.take(200)} ${r.stderr.take(200)}")
    return r.exit == 0
  }

  fun connect(port: Int): Boolean {
    startServer()
    val target = "localhost:$port"
    val r = run(listOf("connect", target), timeoutSec = 20)
    Log.d("phone", "adb connect -> ${r.exit} ${r.stdout.take(200)}")
    val ok = devices().any { it == target || it.endsWith(":$port") }
    if (ok) serial = devices().firstOrNull { it == target || it.endsWith(":$port") }
    return ok
  }

  fun disconnectAll() {
    try { serial?.let { run(listOf("disconnect", it), timeoutSec = 10) } } catch (_: Exception) {}
    serial = null
  }

  /**
   * One discrete shell command as shell-UID. Args are single-quote escaped
   * before the device shell sees them (adb joins argv with spaces).
   */
  fun exec(argv: List<String>, timeoutSec: Int): Result {
    val s = serial ?: return Result(-1, "", "adb not connected", false)
    val quoted = argv.map { q(it) }
    return run(listOf("-s", s, "shell") + quoted, timeoutSec = timeoutSec.toLong() + 10)
  }

  private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  fun looksDead(r: Result): Boolean {
    if (r.exit == 0) return false
    val t = (r.stdout + "\n" + r.stderr).lowercase()
    return "no devices" in t || "offline" in t || "unauthorized" in t ||
      "device not found" in t || "no route to host" in t
  }

  /** Continuous mDNS watch for wireless-debugging ports. Live-updates as the
   *  Settings dialogs open/close. Caller must call [unwatch] (e.g. onPause). */
  private var watchListeners = mutableListOf<NsdManager.DiscoveryListener>()

  fun watchPorts(onUpdate: (connect: List<Int>, pair: List<Int>) -> Unit) {
    unwatch()
    try {
      val nsd = ctx.getSystemService(NsdManager::class.java) ?: run {
        onUpdate(emptyList(), emptyList()); return
      }
      val connectPorts = mutableSetOf<Int>()
      val pairPorts = mutableSetOf<Int>()
      val mkListener = { sink: MutableSet<Int> ->
        object : NsdManager.DiscoveryListener {
          override fun onStartDiscoveryFailed(s: String?, e: Int) {}
          override fun onStopDiscoveryFailed(s: String?, e: Int) {}
          override fun onDiscoveryStarted(s: String?) {}
          override fun onDiscoveryStopped(s: String?) {}
          override fun onServiceFound(info: NsdServiceInfo) {
            try {
              nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {}
                override fun onServiceResolved(i: NsdServiceInfo) {
                  if (i.port > 0) {
                    val added = synchronized(sink) { sink.add(i.port) }
                    if (added) onUpdate(
                      synchronized(connectPorts) { connectPorts.toList().sorted() },
                      synchronized(pairPorts) { pairPorts.toList().sorted() })
                  }
                }
              })
            } catch (_: Exception) {}
          }
          override fun onServiceLost(info: NsdServiceInfo) {}
        }
      }
      val cL = mkListener(connectPorts)
      val pL = mkListener(pairPorts)
      watchListeners.add(cL); watchListeners.add(pL)
      nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, cL)
      nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pL)
    } catch (e: Exception) {
      Log.d("phone", "nsd watch fail: $e")
      onUpdate(emptyList(), emptyList())
    }
  }

  fun unwatch() {
    try {
      val nsd = ctx.getSystemService(NsdManager::class.java)
      for (l in watchListeners) { try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) {} }
    } catch (_: Exception) {}
    watchListeners.clear()
  }

  /** One-shot mDNS scan (~10s). Kept for background flows without a lifecycle. */
  fun discover(cb: (connect: List<Int>, pair: List<Int>) -> Unit) {
    try {
      val nsd = ctx.getSystemService(NsdManager::class.java) ?: run { cb(emptyList(), emptyList()); return }
      val connectPorts = mutableSetOf<Int>()
      val pairPorts = mutableSetOf<Int>()
      val mkListener = { sink: MutableSet<Int> ->
        object : NsdManager.DiscoveryListener {
          override fun onStartDiscoveryFailed(s: String?, e: Int) {}
          override fun onStopDiscoveryFailed(s: String?, e: Int) {}
          override fun onDiscoveryStarted(s: String?) {}
          override fun onDiscoveryStopped(s: String?) {}
          override fun onServiceFound(info: NsdServiceInfo) {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
              override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {}
              override fun onServiceResolved(i: NsdServiceInfo) {
                if (i.port > 0) synchronized(sink) { sink.add(i.port) }
              }
            })
          }
          override fun onServiceLost(info: NsdServiceInfo) {}
        }
      }
      val cL = mkListener(connectPorts)
      val pL = mkListener(pairPorts)
      nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, cL)
      nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pL)
      Thread {
        Thread.sleep(10000)
        try { nsd.stopServiceDiscovery(cL) } catch (_: Exception) {}
        try { nsd.stopServiceDiscovery(pL) } catch (_: Exception) {}
        cb(connectPorts.toList().sorted(), pairPorts.toList().sorted())
      }.start()
    } catch (e: Exception) {
      Log.d("phone", "nsd fail: $e")
      cb(emptyList(), emptyList())
    }
  }

  fun lanIp(): String? = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
      .flatMap { it.inetAddresses.toList() }
      .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
      ?.hostAddress
  } catch (_: Exception) { null }

  fun isWirelessOn(): Boolean = try {
    android.provider.Settings.Global.getInt(
      ctx.contentResolver, "adb_wifi_enabled", 0) == 1
  } catch (_: Exception) { false }

  /** Best-effort reconnect using the stored serial (keys persist, toggle may not). */
  fun reconnectStored(): Boolean {
    val s = serial ?: return false
    val port = s.substringAfterLast(":").toIntOrNull() ?: return false
    return connect(port)
  }
}
