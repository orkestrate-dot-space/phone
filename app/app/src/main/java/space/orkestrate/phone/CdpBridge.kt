package space.orkestrate.phone

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

/**
 * Standalone uid-2000 process entrypoint launched via Shizuku `app_process`.
 * Bridges stdin/stdout to Chrome/Brave's abstract Unix domain socket (`@chrome_devtools_remote`).
 */
object CdpBridge {
  @JvmStatic
  fun main(args: Array<String>) {
    val requested = args.firstOrNull()?.ifBlank { "chrome_devtools_remote" } ?: "chrome_devtools_remote"
    val socketName = resolveSocketName(requested)
    val sock = LocalSocket()
    try {
      sock.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
    } catch (e: Exception) {
      System.err.println("cdp connect failed ($socketName): ${e.message}")
      System.exit(1)
      return
    }

    val tIn = Thread {
      val buf = ByteArray(16 * 1024)
      try {
        val os = sock.outputStream
        while (true) {
          val n = System.`in`.read(buf)
          if (n <= 0) break
          os.write(buf, 0, n)
          os.flush()
        }
        try { sock.shutdownOutput() } catch (_: Exception) {}
      } catch (_: Exception) {}
    }.apply { isDaemon = true }
    tIn.start()

    val buf = ByteArray(16 * 1024)
    try {
      val ins = sock.inputStream
      while (true) {
        val n = ins.read(buf)
        if (n <= 0) break
        System.out.write(buf, 0, n)
        System.out.flush()
      }
    } catch (_: Exception) {
    } finally {
      try { sock.close() } catch (_: Exception) {}
    }
  }

  private fun resolveSocketName(requested: String): String {
    if (canConnect(requested)) return requested
    val found = try {
      File("/proc/net/unix").readLines()
        .mapNotNull { line ->
          val idx = line.indexOf('@')
          if (idx < 0) null else line.substring(idx + 1).trim()
        }
        .firstOrNull { it.endsWith("_devtools_remote") }
    } catch (_: Exception) {
      null
    }
    return found ?: requested
  }

  private fun canConnect(name: String): Boolean {
    val s = LocalSocket()
    return try {
      s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
      true
    } catch (_: Exception) {
      false
    } finally {
      try { s.close() } catch (_: Exception) {}
    }
  }
}
