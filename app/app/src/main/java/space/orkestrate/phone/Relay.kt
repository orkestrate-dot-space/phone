package space.orkestrate.phone

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Relay client: pairs once, holds one outbound WebSocket, executes jobs.
 * Transport rule (SPEC): the phone always dials out; the relay never calls in.
 * Approval rule (SPEC): gated capabilities complete only after a real on-glass tap.
 */
class Relay(
  private val ctx: Context,
  private val store: Store,
  private val shizuku: ShizukuBridge,
) {
  interface Listener {
    fun onState(s: String)
    fun onJob(desc: String)
    fun onLog(s: String)
    fun onApproval(jobId: String, capability: String)
  }

  var listener: Listener? = null
  @Volatile private var ws: WebSocket? = null
  @Volatile private var wantUp = false

  private val fileStore = SafFileStore(ctx, store, shizuku)
  private val cdp = CdpTunnel(shizuku)

  private val approvals = mutableMapOf<String, Pair<WebSocket, JSONObject>>()

  private val ALWAYS_GATED = setOf("photo.capture", "human.handoff", "exec.run")

  fun pendingApproval(id: String): Boolean = synchronized(approvals) { approvals.containsKey(id) }

  fun jobParams(id: String): JSONObject? = synchronized(approvals) {
    approvals[id]?.second?.optJSONObject("params")
  }

  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  fun sendState() {
    val w = ws ?: return
    val frame = JSONObject()
      .put("t", "state")
      .put("paused", store.paused)
      .put("permissions", AppTools.permissionsMap(ctx, store, shizuku))
    try { w.send(frame.toString()) } catch (_: Exception) {}
  }

  fun pair(relayHttp: String, code: String, cb: (ok: Boolean, msg: String) -> Unit) {
    Thread {
      try {
        val base = relayHttp.trim().trimEnd('/')
        val url = "$base/pair/${code.trim().uppercase()}"
        val req = Request.Builder()
          .url(url)
          .post(okhttp3.RequestBody.create(null, "{\"name\":${JSONObject.quote(store.name)}}".toByteArray()))
          .header("Content-Type", "application/json")
          .build()
        http.newCall(req).execute().use { res ->
          val body = res.body?.string() ?: ""
          if (!res.isSuccessful) { cb(false, "pair ${res.code}: $body"); return@Thread }
          val j = JSONObject(body)
          store.relayHttp = base
          store.deviceId = j.getString("deviceId")
          store.token = j.getString("token")
          cb(true, "paired as ${store.deviceId}")
        }
      } catch (e: Exception) {
        Log.d("phone", "pair fail: $e")
        cb(false, "pair failed: ${e.message}")
      }
    }.start()
  }

  fun connect() {
    if (wantUp) return
    wantUp = true
    Thread {
      var wait = 1000L
      while (wantUp) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
          listener?.onState("connecting…")
          dial()
          if (android.os.SystemClock.elapsedRealtime() - t0 > 30000) wait = 1000L
        } catch (e: Exception) {
          Log.d("phone", "dial: $e")
          listener?.onLog("dial: ${e.message}")
        }
        if (!wantUp) break
        listener?.onState("retry in ${wait / 1000}s")
        Thread.sleep(wait)
        wait = minOf(wait * 2, 30000L)
      }
    }.start()
  }

  fun disconnect() {
    wantUp = false
    cdp.closeAll()
    try { ws?.close(1000, "ui") } catch (_: Exception) {}
    ws = null
    listener?.onState("offline")
  }

  private fun dial() {
    val base = store.relayHttp ?: throw IllegalStateException("no relay")
    val token = store.token ?: throw IllegalStateException("not paired")
    val wsUrl = base.replaceFirst("http", "ws").trimEnd('/') + "/device"
    val req = Request.Builder()
      .url(wsUrl)
      .header("Authorization", "Bearer $token")
      .build()
    val gone = CountDownLatch(1)
    val socket = http.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(w: WebSocket, r: Response) {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val vcode = pm.getPackageInfo(pkg, 0).let {
          if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
          else @Suppress("DEPRECATION") it.versionCode
        }
        val caps = JSONArray()
          .put("device.info")
          .put("battery.get")
          .put("screen.observe")
          .put("screen.act")
          .put("apps.manage")
          .put("files.op")
          .put("notifications.op")
          .put("clipboard.op")
          .put("human.handoff")
          .put("photo.capture")
          .put("exec.run")
          .put("cdp.tunnel")
        val hello = JSONObject()
          .put("t", "hello")
          .put("deviceId", store.deviceId)
          .put("name", store.name)
          .put("appVersion", vcode)
          .put("paused", store.paused)
          .put("permissions", AppTools.permissionsMap(ctx, store, shizuku))
          .put("caps", caps)
        w.send(hello.toString())
      }

      override fun onMessage(w: WebSocket, text: String) {
        try { onFrame(w, JSONObject(text)) } catch (e: Exception) {
          listener?.onLog("bad frame: ${e.message}")
        }
      }

      override fun onClosed(w: WebSocket, code: Int, reason: String) { cdp.closeAll(); gone.countDown() }
      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
        cdp.closeAll()
        Log.d("phone", "ws fail: $t")
        listener?.onLog("ws: ${t.message}")
        gone.countDown()
      }
    })
    ws = socket
    gone.await()
    ws = null
  }

  private fun isGated(cap: String, params: JSONObject?): Boolean {
    if (cap in ALWAYS_GATED) return true
    if (cap == "files.op" && params?.optString("action") == "delete") return true
    return false
  }

  private fun onFrame(w: WebSocket, m: JSONObject) {
    when (m.optString("t")) {
      "welcome" -> listener?.onState("connected — ${store.name}")
      "ping" -> w.send("{\"t\":\"pong\"}")
      "cdp.open" -> cdp.open(m.getString("connId"), m.optString("socket", "chrome_devtools_remote")) { w.send(it.toString()) }
      "cdp.data" -> cdp.data(m.getString("connId"), m.getString("b64"))
      "cdp.close" -> cdp.close(m.getString("connId"))
      "job" -> {
        val job = m.getJSONObject("job")
        val id = job.getString("id")
        val cap = job.getString("capability")
        val params = job.optJSONObject("params") ?: JSONObject()
        listener?.onJob("$cap · $id")

        if (store.paused && cap != "device.info" && cap != "battery.get") {
          w.send(result(id, false, null, "paused-by-user"))
          return
        }

        if (isGated(cap, params)) {
          synchronized(approvals) {
            if (approvals.isNotEmpty()) {
              w.send(result(id, false, null, "busy: another approval pending"))
              return
            }
            approvals[id] = w to job
          }
          listener?.onApproval(id, cap)
        } else {
          Thread {
            try {
              w.send(result(id, true, execUngated(cap, params), null))
            } catch (e: Exception) {
              w.send(result(id, false, null, e.message ?: "failed"))
            }
          }.start()
        }
      }
    }
  }

  private fun result(id: String, ok: Boolean, data: JSONObject?, err: String?): String =
    JSONObject().put("t", "result").put("id", id).put("ok", ok)
      .apply { if (ok && data != null) put("data", data) else put("error", err ?: "failed") }
      .toString()

  fun approve(id: String, data: JSONObject) {
    val (w, _) = synchronized(approvals) { approvals.remove(id) } ?: return
    Log.d("phone", "approved by tap: $id")
    w.send(result(id, true, data, null))
  }

  fun reject(id: String, why: String = "rejected") {
    val (w, _) = synchronized(approvals) { approvals.remove(id) } ?: return
    Log.d("phone", "rejected by tap: $id ($why)")
    w.send(result(id, false, null, why))
  }

  fun deleteFile(params: JSONObject): JSONObject = fileStore.exec(params)

  private fun execUngated(cap: String, params: JSONObject): JSONObject {
    return when (cap) {
      "battery.get" -> AppTools.batteryInfo(ctx)
      "device.info" -> AppTools.deviceInfo(ctx, store, shizuku)
      "screen.observe" -> ShizukuScreen.observe(
        shizuku = shizuku,
        store = store,
        screenshot = params.optBoolean("screenshot", false),
        maxPx = params.optInt("maxPx", 1080),
        quality = params.optInt("quality", 75),
      )
      "screen.act" -> ShizukuScreen.act(shizuku, params)
      "apps.manage" -> AppTools.appsManage(ctx, params)
      "files.op" -> fileStore.exec(params)
      "notifications.op" -> ShizukuScreen.notifications(shizuku, params)
      "clipboard.op" -> AppTools.clipboardOp(ctx, params)
      else -> throw IllegalArgumentException("unknown-capability: $cap")
    }
  }
}
