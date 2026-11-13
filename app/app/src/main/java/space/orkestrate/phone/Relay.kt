package space.orkestrate.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v0 relay client: pairs once, holds one WS, executes jobs.
 * Transport rule (SPEC): the phone always dials out; the relay never calls in.
 * Approval rule (SPEC): gated capabilities complete only after a real
 * on-glass tap. No code path auto-approves — ever.
 */
class Relay(private val ctx: Context, private val store: Store) {

  interface Listener {
    fun onState(s: String)
    fun onJob(desc: String)
    fun onLog(s: String)
    /** Gated job arrived. Show card/notification; call exactly one of approve/reject. */
    fun onApproval(jobId: String, capability: String)
  }

  var listener: Listener? = null
  @Volatile private var ws: WebSocket? = null
  @Volatile private var wantUp = false

  /** jobId -> (socket, job) for jobs awaiting the human tap. */
  private val approvals = mutableMapOf<String, Pair<WebSocket, JSONObject>>()

  fun pendingApproval(id: String): Boolean = synchronized(approvals) { approvals.containsKey(id) }

  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WS
    .build()

  // ---- pairing: POST {relay}/pair/{code} -> {deviceId, token} ----
  fun pair(relayHttp: String, code: String, cb: (ok: Boolean, msg: String) -> Unit) {
    Thread {
      try {
        val base = relayHttp.trim().trimEnd('/')
        val url = "$base/pair/${code.trim().uppercase()}"
        Log.d("phone", "pair POST $url")
        val req = Request.Builder()
          .url(url)
          .post(okhttp3.RequestBody.create(null, "{\"name\":${JSONObject.quote(store.name)}}".toByteArray()))
          .header("Content-Type", "application/json")
          .build()
        Log.d("phone", "pair exec…")
        http.newCall(req).execute().use { res ->
          val body = res.body?.string() ?: ""
          Log.d("phone", "pair -> ${res.code} $body")
          if (!res.isSuccessful) { cb(false, "pair ${res.code}: $body"); return@Thread }
          val j = JSONObject(body)
          store.relayHttp = base
          store.deviceId = j.getString("deviceId")
          store.token = j.getString("token")
          cb(true, "paired as ${store.deviceId}")
        }
      } catch (e: Exception) { Log.d("phone", "pair fail: $e"); cb(false, "pair failed: ${e.message}") }
    }.start()
  }

  // ---- connection holder with backoff ----
  fun connect() {
    if (wantUp) return
    wantUp = true
    Thread {
      var wait = 1000L
      while (wantUp) {
        try {
          listener?.onState("connecting…")
          dial()
          wait = 1000L // dial() blocks until the socket dies
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
        val hello = JSONObject()
          .put("t", "hello")
          .put("deviceId", store.deviceId)
          .put("name", store.name)
          .put("caps", org.json.JSONArray().put("battery.get").put("photo.capture"))
        w.send(hello.toString())
      }
      override fun onMessage(w: WebSocket, text: String) {
        try { onFrame(w, JSONObject(text)) } catch (e: Exception) {
          listener?.onLog("bad frame: ${e.message}")
        }
      }
      override fun onClosed(w: WebSocket, code: Int, reason: String) { gone.countDown() }
      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
        Log.d("phone", "ws fail: $t")
        listener?.onLog("ws: ${t.message}")
        gone.countDown()
      }
      override fun onClosing(w: WebSocket, code: Int, reason: String) {
        Log.d("phone", "ws closing $code $reason")
      }
    })
    ws = socket
    gone.await() // blocks until close/failure; connect() loop redials
    ws = null
  }

  private fun onFrame(w: WebSocket, m: JSONObject) {
    when (m.optString("t")) {
      "welcome" -> listener?.onState("connected — ${store.name}")
      "ping" -> w.send("{\"t\":\"pong\"}")
      "job" -> {
        val job = m.getJSONObject("job")
        val id = job.getString("id")
        val cap = job.getString("capability")
        listener?.onJob("$cap · $id")
        if (cap == "photo.capture") {
          synchronized(approvals) {
            if (approvals.isNotEmpty()) {
              w.send(result(id, false, null, "busy: another approval pending"))
              return
            }
            approvals[id] = w to job
          }
          Log.d("phone", "approval needed: $id")
          listener?.onApproval(id, cap)
        } else Thread {
          try {
            w.send(result(id, true, exec(job), null))
          } catch (e: Exception) {
            w.send(result(id, false, null, e.message ?: "failed"))
          }
        }.start()
      }
    }
  }

  private fun result(id: String, ok: Boolean, data: JSONObject?, err: String?): String =
    JSONObject().put("t", "result").put("id", id).put("ok", ok)
      .apply { if (ok && data != null) put("data", data) else put("error", err ?: "failed") }
      .toString()

  /** Human tapped Approve with captured data. Only path that completes a gated job. */
  fun approve(id: String, data: JSONObject) {
    val (w, _) = synchronized(approvals) { approvals.remove(id) } ?: return
    Log.d("phone", "approved by tap: $id")
    w.send(result(id, true, data, null))
  }

  /** Human tapped Reject (or the flow aborted). Only other path that ends a gated job. */
  fun reject(id: String, why: String = "rejected") {
    val (w, _) = synchronized(approvals) { approvals.remove(id) } ?: return
    Log.d("phone", "rejected by tap: $id ($why)")
    w.send(result(id, false, null, why))
  }

  // ---- capability executor (ungated only) ----
  private fun exec(job: JSONObject): JSONObject {
    when (job.getString("capability")) {
      "battery.get" -> {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return JSONObject()
          .put("level", if (level >= 0) (level * 100 / scale) else -1)
          .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL)
          .put("voltageMv", b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
          .put("tempC", ((b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)) / 10.0)
      }
    }
    throw IllegalArgumentException("unknown-capability: ${job.getString("capability")}")
  }
}
