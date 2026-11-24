package space.orkestrate.phone

import android.util.Base64
import org.json.JSONObject
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.ConcurrentHashMap

/** Manages Shizuku-backed CDP socket bridges for `phone cdp` / `agent-browser`. */
class CdpTunnel(private val shizuku: ShizukuBridge) {
  private val conns = ConcurrentHashMap<String, ShizukuRemoteProcess>()
  private val SOCKET_RE = Regex("^[a-zA-Z0-9_.-]{1,80}$")

  fun open(connId: String, socketName: String, send: (JSONObject) -> Unit) {
    val sock = socketName.ifBlank { "chrome_devtools_remote" }
    if (!SOCKET_RE.matches(sock)) {
      send(JSONObject().put("t", "cdp.close").put("connId", connId).put("error", "invalid socket name"))
      return
    }
    val proc = shizuku.startProcess(
      listOf(
        "sh",
        "-c",
        "APK=\$(pm path space.orkestrate.phone | head -n1 | cut -d: -f2); CLASSPATH=\"\$APK\" exec app_process / space.orkestrate.phone.CdpBridge \"\$1\"",
        "_",
        sock,
      ),
    )
    if (proc == null) {
      send(JSONObject().put("t", "cdp.close").put("connId", connId).put("error", "shizuku-required"))
      return
    }
    conns[connId] = proc
    Thread {
      val buf = ByteArray(16 * 1024)
      var errMsg = ""
      try {
        val ins = proc.inputStream
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          send(
            JSONObject()
              .put("t", "cdp.data")
              .put("connId", connId)
              .put("b64", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)),
          )
        }
        try {
          val err = proc.errorStream.readBytes().toString(Charsets.UTF_8).trim()
          if (err.isNotEmpty()) errMsg = err.take(200)
        } catch (_: Exception) {}
      } catch (e: Exception) {
        errMsg = e.message ?: "read failed"
      } finally {
        conns.remove(connId)
        try { proc.destroy() } catch (_: Exception) {}
        val msg = JSONObject().put("t", "cdp.close").put("connId", connId)
        if (errMsg.isNotEmpty()) msg.put("error", errMsg)
        send(msg)
      }
    }.apply { isDaemon = true }.start()
  }

  fun data(connId: String, b64: String) {
    val proc = conns[connId] ?: return
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    synchronized(proc) {
      try {
        proc.outputStream.write(bytes)
        proc.outputStream.flush()
      } catch (_: Exception) {
        close(connId)
      }
    }
  }

  fun close(connId: String) {
    val proc = conns.remove(connId) ?: return
    try { proc.outputStream.close() } catch (_: Exception) {}
    try { proc.destroy() } catch (_: Exception) {}
  }

  fun closeAll() {
    for (id in conns.keys.toList()) close(id)
  }
}
