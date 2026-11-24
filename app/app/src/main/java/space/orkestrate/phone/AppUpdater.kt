package space.orkestrate.phone

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class UpdatePhase { Idle, Checking, UpToDate, Available, Downloading, Installing, Error }

data class UpdateState(
  val phase: UpdatePhase = UpdatePhase.Idle,
  val latestVersionCode: Int = 0,
  val latestVersionName: String = "",
  val apkUrl: String = "",
  val notes: String = "",
  val progress: Float = 0f,
  val bytesRead: Long = 0L,
  val totalBytes: Long = 0L,
  val message: String = "",
)

data class ReleaseInfo(
  val versionCode: Int,
  val versionName: String,
  val apkUrl: String,
  val notes: String,
)

class AppUpdater(
  private val ctx: Context,
  private val store: Store,
  private val shizuku: ShizukuBridge,
) {
  companion object {
    const val VERSION_URL = "https://mobile.orkestrate.space/phone-version.json"
    const val DEFAULT_APK_URL = "https://mobile.orkestrate.space/orkestrate-phone.apk"

    fun parseReleaseJson(raw: String): ReleaseInfo {
      val j = JSONObject(raw)
      return ReleaseInfo(
        versionCode = j.optInt("versionCode", 0),
        versionName = j.optString("versionName", ""),
        apkUrl = j.optString("apkUrl", "").ifBlank { DEFAULT_APK_URL },
        notes = j.optString("notes", ""),
      )
    }
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  private val dlClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  private val busy = AtomicBoolean(false)
  @Volatile private var lastAutoCheckMs = 0L
  @Volatile var state: UpdateState = UpdateState()
    private set
  @Volatile var onChange: ((UpdateState) -> Unit)? = null

  private fun emit(s: UpdateState) {
    state = s
    onChange?.invoke(s)
  }

  fun maybeAutoCheck() {
    if (!store.autoUpdate) return
    val now = System.currentTimeMillis()
    if (now - lastAutoCheckMs < 30 * 60 * 1000L) return
    lastAutoCheckMs = now
    checkForUpdate(autoInstall = true)
  }

  fun checkForUpdate(autoInstall: Boolean = false) {
    if (!busy.compareAndSet(false, true)) return
    emit(UpdateState(phase = UpdatePhase.Checking, message = "Checking for updates..."))
    Thread {
      try {
        val req = Request.Builder().url(VERSION_URL).build()
        val body = client.newCall(req).execute().use { res ->
          if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
          res.body?.string() ?: throw IllegalStateException("empty response")
        }
        val info = parseReleaseJson(body)
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val curCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        val curName = pkg.versionName ?: ""
        if (info.versionCode.toLong() <= curCode) {
          emit(
            UpdateState(
              phase = UpdatePhase.UpToDate,
              latestVersionCode = info.versionCode,
              latestVersionName = info.versionName,
              message = "Up to date (v$curName)",
            ),
          )
        } else if (autoInstall && store.autoUpdate && shizuku.hasPermission()) {
          downloadAndInstallSync(info)
        } else {
          emit(
            UpdateState(
              phase = UpdatePhase.Available,
              latestVersionCode = info.versionCode,
              latestVersionName = info.versionName,
              apkUrl = info.apkUrl,
              notes = info.notes,
              message = "Update available: v${info.versionName}",
            ),
          )
        }
      } catch (e: Exception) {
        emit(UpdateState(phase = UpdatePhase.Error, message = "Update check failed: ${e.message}"))
      } finally {
        busy.set(false)
      }
    }.apply { isDaemon = true }.start()
  }

  fun installOrOpen(openBrowser: (String) -> Unit) {
    val cur = state
    if (cur.phase == UpdatePhase.Available && cur.apkUrl.isNotBlank()) {
      if (!shizuku.hasPermission()) {
        openBrowser(cur.apkUrl)
        return
      }
      if (!busy.compareAndSet(false, true)) return
      val info = ReleaseInfo(cur.latestVersionCode, cur.latestVersionName, cur.apkUrl, cur.notes)
      Thread {
        try {
          downloadAndInstallSync(info)
        } catch (e: Exception) {
          emit(UpdateState(phase = UpdatePhase.Error, message = "Update failed: ${e.message}"))
        } finally {
          busy.set(false)
        }
      }.apply { isDaemon = true }.start()
    } else {
      checkForUpdate(autoInstall = shizuku.hasPermission())
    }
  }

  private fun downloadAndInstallSync(info: ReleaseInfo) {
    val file = File(ctx.cacheDir, "orkestrate-update.apk")
    try {
      emit(
        UpdateState(
          phase = UpdatePhase.Downloading,
          latestVersionCode = info.versionCode,
          latestVersionName = info.versionName,
          apkUrl = info.apkUrl,
          notes = info.notes,
          progress = 0f,
          message = "Downloading v${info.versionName}...",
        ),
      )
      val req = Request.Builder().url(info.apkUrl).build()
      dlClient.newCall(req).execute().use { res ->
        if (!res.isSuccessful) throw IllegalStateException("download HTTP ${res.code}")
        val body = res.body ?: throw IllegalStateException("empty download")
        val total = body.contentLength()
        val buf = ByteArray(16 * 1024)
        var read = 0L
        var lastEmit = 0L
        body.byteStream().use { src ->
          FileOutputStream(file).use { dst ->
            while (true) {
              val n = src.read(buf)
              if (n <= 0) break
              dst.write(buf, 0, n)
              read += n
              val now = System.currentTimeMillis()
              if (now - lastEmit >= 100L) {
                lastEmit = now
                val p = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                val pct = (p * 100).toInt()
                val msg = if (total > 0) {
                  String.format(
                    Locale.US,
                    "Downloading v%s · %d%% (%.1f / %.1f MB)",
                    info.versionName,
                    pct,
                    read / 1_048_576f,
                    total / 1_048_576f,
                  )
                } else {
                  String.format(Locale.US, "Downloading v%s · %.1f MB", info.versionName, read / 1_048_576f)
                }
                emit(
                  UpdateState(
                    phase = UpdatePhase.Downloading,
                    latestVersionCode = info.versionCode,
                    latestVersionName = info.versionName,
                    apkUrl = info.apkUrl,
                    notes = info.notes,
                    progress = p,
                    bytesRead = read,
                    totalBytes = total,
                    message = msg,
                  ),
                )
              }
            }
          }
        }
      }
      if (file.length() <= 100_000L) throw IllegalStateException("downloaded APK too small")
      emit(
        UpdateState(
          phase = UpdatePhase.Installing,
          latestVersionCode = info.versionCode,
          latestVersionName = info.versionName,
          apkUrl = info.apkUrl,
          notes = info.notes,
          progress = 1f,
          bytesRead = file.length(),
          totalBytes = file.length(),
          message = "Installing v${info.versionName} via Shizuku...",
        ),
      )
      val res = shizuku.execWithInput(
        listOf("pm", "install", "-r", "-S", file.length().toString()),
        file.inputStream(),
        120,
      )
      if (res.exit != 0 || !res.stdout.contains("Success")) {
        throw IllegalStateException((res.stderr + " " + res.stdout).trim().take(160))
      }
    } finally {
      try { file.delete() } catch (_: Exception) {}
    }
  }
}
