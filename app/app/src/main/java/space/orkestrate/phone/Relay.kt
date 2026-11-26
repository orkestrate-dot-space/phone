package space.orkestrate.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

  /**
   * Local (loopback MCP) shell approvals. Same on-glass card as relay
   * exec.run when the user picked "Always ask me" (Store.APPROVAL_ASK).
   * With the default "Let fx decide" (Store.APPROVAL_FX), fx's auto-mode
   * reviewer already cleared the call and no card is raised. Relay shell
   * tier (ExecRunner/exec.run from a remote machine) always uses the card
   * policy regardless of this setting.
   *
   * The blocked MCP handler thread only proceeds when a real tap calls
   * [decideLocalShell]. No code path completes one without a tap —
   * auto-run happens only for the exact ExecPolicy.allowlisted argv, same
   * rule as the relay tier.
   *
   * Declared before [localMcp] so the gate is wired at construction.
   */
  private data class LocalShellReq(
    val params: JSONObject,
    val latch: java.util.concurrent.CountDownLatch,
    @Volatile var approved: Boolean = false,
  )

  private val localShell = mutableMapOf<String, LocalShellReq>()

  private val shellGate = object : LocalMcpServer.ShellGate {
    override fun approveShell(cmd: String, argv: List<String>): Boolean {
      // fx-decides mode: fx's auto-mode reviewer cleared the call, no card.
      // Always-ask mode: same decision path as the relay shell tier.
      if (!LocalAuth.shellNeedsCard(store.autoApprove, argv, store.fxApproval == Store.APPROVAL_FX)) return true
      val id = "local-shell-${System.currentTimeMillis()}"
      val req = LocalShellReq(
        JSONObject().put("argv", org.json.JSONArray(argv)).put("cmd", cmd),
        java.util.concurrent.CountDownLatch(1),
      )
      synchronized(localShell) { localShell[id] = req }
      try {
        val l = listener ?: return false // no approval UI: fail closed
        l.onApproval(id, "exec.run")
        return req.latch.await(LOCAL_SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) && req.approved
      } catch (_: Exception) {
        return false
      } finally {
        synchronized(localShell) { localShell.remove(id) }
      }
    }
  }

  internal val localMcp = LocalMcpServer(ctx, store, shizuku, onHandoff = { id, reason ->
    listener?.onApproval(id, "human.handoff: $reason")
  }).also { s ->
    s.onToolEvent = { tool, detail ->
      listener?.onJob(if (detail.isNotBlank()) "$tool · $detail" else tool)
    }
    s.shellGate = shellGate
  }
  internal val phoneAgent = PhoneAgent(ctx, store, localMcp)
  @Volatile private var usingLan = false
  private var wifiCb: ConnectivityManager.NetworkCallback? = null

  private val approvals = mutableMapOf<String, Pair<WebSocket, JSONObject>>()

  private val ALWAYS_GATED = setOf("photo.capture", "human.handoff", "exec.run")

  companion object {
    /** How long an MCP `shell` call blocks waiting for the on-glass tap. */
    const val LOCAL_SHELL_TIMEOUT_MS = 120_000L
  }

  fun isLocalShellApproval(id: String): Boolean = synchronized(localShell) { localShell.containsKey(id) }

  fun decideLocalShell(id: String, approved: Boolean) {
    val req = synchronized(localShell) { localShell[id] } ?: return
    req.approved = approved
    req.latch.countDown()
  }

  fun pendingApproval(id: String): Boolean = synchronized(approvals) { approvals.containsKey(id) } ||
    synchronized(localShell) { localShell.containsKey(id) }

  fun jobParams(id: String): JSONObject? = synchronized(approvals) {
    approvals[id]?.second?.optJSONObject("params")
  } ?: synchronized(localShell) { localShell[id]?.params }

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
    registerWifiCallback()
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
    unregisterWifiCallback()
    localMcp.stop()
    try { ws?.close(1000, "ui") } catch (_: Exception) {}
    ws = null
    listener?.onState("offline")
  }

  private fun registerWifiCallback() {
    if (wifiCb != null) return
    try {
      val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
      val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
      val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val lan = store.relayLan
          if (!usingLan && !lan.isNullOrBlank()) {
            Thread { if (probeLan(lan)) ws?.close(1000, "wifi-lan") }.apply { isDaemon = true }.start()
          }
        }
      }
      cm.registerNetworkCallback(req, cb)
      wifiCb = cb
    } catch (_: Exception) {}
  }

  private fun unregisterWifiCallback() {
    val cb = wifiCb ?: return
    wifiCb = null
    try { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
  }

  private fun probeLan(url: String): Boolean {
    return try {
      val c = http.newBuilder().connectTimeout(600, TimeUnit.MILLISECONDS).readTimeout(600, TimeUnit.MILLISECONDS).build()
      c.newCall(Request.Builder().url(url.trimEnd('/') + "/version").build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
      false
    }
  }

  private fun dial() {
    val tunnelBase = store.relayHttp ?: throw IllegalStateException("no relay")
    val lanBase = store.relayLan
    val base = if (!lanBase.isNullOrBlank() && probeLan(lanBase)) {
      usingLan = true
      lanBase
    } else {
      usingLan = false
      tunnelBase
    }
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
        val hello = JSONObject()
          .put("t", "hello")
          .put("deviceId", store.deviceId)
          .put("name", store.name)
          .put("appVersion", vcode)
          .put("paused", store.paused)
          .put("permissions", AppTools.permissionsMap(ctx, store, shizuku))
          .put("caps", caps)
        w.send(hello.toString())
        shizuku.prebindDaemon()
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
      "welcome" -> {
        val lan = m.optString("lanUrl", "")
        if (lan.isNotBlank()) store.relayLan = lan
        listener?.onState(if (usingLan) "connected (LAN) — ${store.name}" else "connected (tunnel) — ${store.name}")
        if (lan.isNotBlank() && !usingLan) {
          Thread { if (probeLan(lan)) ws?.close(1000, "switch-lan") }.apply { isDaemon = true }.start()
        }
      }
      "ping" -> w.send("{\"t\":\"pong\"}")
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
