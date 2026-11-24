package space.orkestrate.phone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Bridge to Shizuku (moe.shizuku.privileged.api, free on Google Play).
 * Gives uid-2000 shell access without bundling libadb.so or AccessibilityService.
 */
class ShizukuBridge(private val ctx: Context) {
  data class Result(val exit: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

  companion object {
    const val PKG = "moe.shizuku.privileged.api"
  }

  fun isInstalled(): Boolean = try {
    ctx.packageManager.getPackageInfo(PKG, 0)
    true
  } catch (_: Exception) {
    false
  }

  fun isRunning(): Boolean = try {
    Shizuku.pingBinder()
  } catch (_: Throwable) {
    false
  }

  fun hasPermission(): Boolean = isRunning() && try {
    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
  } catch (_: Throwable) {
    false
  }

  fun requestPermission(requestCode: Int) {
    if (isRunning()) {
      try { Shizuku.requestPermission(requestCode) } catch (e: Throwable) {
        Log.d("phone", "shizuku requestPermission: $e")
      }
    }
  }

  fun exec(argv: List<String>, timeoutSec: Int = 30): Result {
    if (!hasPermission()) return Result(-1, "", "shizuku-required", false)
    return try {
      val proc = newProcess(argv)
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      val t1 = Thread { try { proc.inputStream.copyTo(out) } catch (_: Exception) {} }
      val t2 = Thread { try { proc.errorStream.copyTo(err) } catch (_: Exception) {} }
      t1.start(); t2.start()
      val done = proc.waitForTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
      t1.join(2000); t2.join(2000)
      if (!done) {
        proc.destroy()
        return Result(-1, "", "shizuku timeout", true)
      }
      Result(proc.exitValue(), out.toString("UTF-8"), err.toString("UTF-8"), false)
    } catch (e: Throwable) {
      Log.d("phone", "shizuku exec fail: $e")
      Result(-1, "", "shizuku failed: ${e.message}", false)
    }
  }

  fun execBytes(argv: List<String>, timeoutSec: Int = 30): ByteArray? {
    if (!hasPermission()) return null
    return try {
      val proc = newProcess(argv)
      val out = ByteArrayOutputStream()
      val t1 = Thread { try { proc.inputStream.copyTo(out) } catch (_: Exception) {} }
      t1.start()
      val done = proc.waitForTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
      t1.join(2000)
      if (!done) { proc.destroy(); return null }
      if (proc.exitValue() != 0) return null
      out.toByteArray()
    } catch (e: Throwable) {
      Log.d("phone", "shizuku execBytes fail: $e")
      null
    }
  }

  @Suppress("DEPRECATION")
  private fun newProcess(argv: List<String>): ShizukuRemoteProcess {
    val m = Shizuku::class.java.getDeclaredMethod(
      "newProcess",
      Array<String>::class.java,
      Array<String>::class.java,
      String::class.java,
    )
    m.isAccessible = true
    return m.invoke(null, argv.toTypedArray(), null, null) as ShizukuRemoteProcess
  }
}

private fun ShizukuRemoteProcess.waitForTimeout(timeout: Long, unit: TimeUnit): Boolean {
  val deadline = System.nanoTime() + unit.toNanos(timeout)
  while (true) {
    try {
      exitValue()
      return true
    } catch (_: IllegalThreadStateException) {
      if (System.nanoTime() >= deadline) return false
      Thread.sleep(25)
    }
  }
}
