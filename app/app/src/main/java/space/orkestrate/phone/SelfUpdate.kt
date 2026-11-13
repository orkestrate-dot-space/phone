package space.orkestrate.phone

import android.util.Log

/**
 * Zero-touch self-update: download the relay-published APK, silent-install
 * it through the shell tier (`adb install` as shell-UID needs no tap).
 * Runs headless when the user opted in, else behind an approval card.
 * The installer kills our process mid-install, so the job resolves on a
 * real result when lucky and by relay timeout otherwise — the agent
 * verifies by the reported appVersion on next hello, never by the ack.
 */
class SelfUpdate(
  private val ctx: android.content.Context,
  private val relay: Relay,
  private val adb: AdbShell,
  private val store: Store,
) {
  interface Sink {
    fun note(s: String)
    fun progress(pct: Int)
  }

  fun run(jobId: String, sink: Sink) {
    Thread {
      try {
        val base = store.relayHttp ?: throw IllegalStateException("not paired")
        val token = store.token ?: throw IllegalStateException("not paired")
        if (adb.serial == null) throw IllegalStateException("shell not connected")
        val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
          if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
          else @Suppress("DEPRECATION") it.versionCode
        }
        val client = okhttp3.OkHttpClient.Builder()
          .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
          .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
          .build()
        val meta = client.newCall(okhttp3.Request.Builder()
          .url(base.trimEnd('/') + "/app/version")
          .header("Authorization", "Bearer $token")
          .build()).execute().use { res ->
          if (!res.isSuccessful) throw IllegalStateException("version check: ${res.code}")
          org.json.JSONObject(res.body!!.string())
        }
        val latest = meta.getInt("versionCode")
        if (latest <= mine) {
          relay.approve(jobId, org.json.JSONObject().put("updated", false).put("mine", mine))
          sink.note("update: already on latest ($mine)")
          return@Thread
        }
        sink.note("update: downloading ${meta.optString("versionName")}")
        val tmp = java.io.File(ctx.cacheDir, "update.apk")
        client.newCall(okhttp3.Request.Builder()
          .url(base.trimEnd('/') + "/app.apk")
          .header("Authorization", "Bearer $token")
          .build()).execute().use { res ->
          if (!res.isSuccessful) throw IllegalStateException("download: ${res.code}")
          val total = res.body!!.contentLength()
          var done = 0L
          var lastPct = -1
          res.body!!.byteStream().use { input ->
            tmp.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) {
                  val pct = (done * 100 / total).toInt()
                  if (pct != lastPct) { lastPct = pct; sink.progress(pct) }
                }
              }
            }
          }
        }
        sink.note("update: installing ${meta.optString("versionName")}")
        val r = adb.installApk(tmp.absolutePath)
        Log.d("phone", "self-update install: ${r.exit} ${r.stdout.take(200)} ${r.stderr.take(200)}")
        tmp.delete()
        if (r.exit == 0 || r.stdout.contains("Success")) {
          relay.approve(jobId, org.json.JSONObject()
            .put("updated", true).put("version", meta.optString("versionName")))
          sink.note("update: installed — verify by version on next hello")
        } else {
          relay.reject(jobId, "install failed: ${(r.stdout + " " + r.stderr).take(200)}")
          sink.note("update: install failed")
        }
      } catch (e: Exception) {
        Log.d("phone", "self-update fail: $e")
        try { relay.reject(jobId, "update failed: ${e.message}") } catch (_: Exception) {}
        sink.note("update failed: ${e.message}")
      }
    }.start()
  }
}
