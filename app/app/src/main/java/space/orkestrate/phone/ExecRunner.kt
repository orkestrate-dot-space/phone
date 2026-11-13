package space.orkestrate.phone

import android.app.NotificationManager
import android.content.Context
import android.util.Log

/** Shared exec engine: used by the activity (dialog-approved) and the service (auto-approved). */
class ExecRunner(
  private val ctx: Context,
  private val relay: Relay,
  private val adb: AdbShell,
  private val store: Store,
) {
  interface Sink {
    fun note(s: String)
  }

  companion object {
    val APP_ALLOW = setOf("ls", "cat", "echo", "date", "uname", "getprop", "df", "du", "head", "logcat")
    val SHELL_ALLOW = APP_ALLOW + setOf(
      "dumpsys", "cmd", "pm", "settings", "input", "logcat", "am", "wm",
      "appops", "service", "svc", "getevent", "screencap", "screenrecord")

    fun Context.cancelJobNotif(jobId: String) {
      getSystemService(NotificationManager::class.java).cancel(jobId.hashCode())
    }
  }

  fun shouldAuto(argv: List<String>): Boolean =
    store.autoApprove && ExecPolicy.auto(argv)

  fun runExec(jobId: String, argv: List<String>, sink: Sink) {
    Thread {
      try {
        val cmd = argv.toMutableList()
        val bin = cmd[0]
        val shellUp = adb.serial != null
        val allowed = if (shellUp) SHELL_ALLOW else APP_ALLOW
        if ("/" !in bin) {
          if (bin !in allowed) throw IllegalArgumentException("not allowed: $bin")
          cmd[0] = "/system/bin/$bin"
        } else {
          if (!bin.startsWith("/system/bin/")) throw IllegalArgumentException("only /system/bin: $bin")
        }
        val params = relay.jobParams(jobId)
        val timeoutSec = (params?.optInt("timeoutSec", 30) ?: 30).coerceIn(5, 120)
        // Tier S (shell-UID) when the embedded ADB channel is up, else app-UID.
        val sh = if (shellUp) adb.exec(cmd, timeoutSec) else null
        if (sh != null && !adb.looksDead(sh)) {
          finishExec(jobId, sh.exit, sh.stdout.toByteArray(), sh.stderr.toByteArray(), "shell", sink)
          return@Thread
        }
        if (sh != null) adb.drop()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val t1 = Thread { try { proc.inputStream.copyTo(out) } catch (_: Exception) {} }
        val t2 = Thread { try { proc.errorStream.copyTo(err) } catch (_: Exception) {} }
        t1.start(); t2.start()
        val done = proc.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        t1.join(2000); t2.join(2000)
        ctx.cancelJobNotif(jobId)
        if (!done) {
          proc.destroyForcibly()
          relay.reject(jobId, "timed out after ${timeoutSec}s")
          sink.note("exec $jobId timed out")
          return@Thread
        }
        finishExec(jobId, proc.exitValue(), out.toByteArray(), err.toByteArray(), "app", sink)
      } catch (e: Exception) {
        Log.d("phone", "exec fail: $e")
        ctx.cancelJobNotif(jobId)
        relay.reject(jobId, "exec failed: ${e.message}")
        sink.note("exec failed: ${e.message}")
      }
    }.start()
  }

  private fun finishExec(jobId: String, exit: Int, so: ByteArray, se: ByteArray, tier: String, sink: Sink) {
    ctx.cancelJobNotif(jobId)
    val cap = 65536
    val data = org.json.JSONObject()
      .put("exit", exit)
      .put("tier", tier)
      .put("stdout", String(so, 0, minOf(so.size, cap)))
      .put("stderr", String(se, 0, minOf(se.size, cap)))
      .put("truncated", so.size > cap || se.size > cap)
    relay.approve(jobId, data)
    sink.note("exec $jobId [$tier] exit=$exit")
  }
}
