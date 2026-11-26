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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native ACP-over-WebSocket client for @orkestrate/agents (`/join`).
 * Pairs via POST <agentsUrl>/pair/<CODE>, then holds one outbound WebSocket
 * to <wsUrl>/join and pipes it to `libfx.so acp --auto` stdio.
 */
class AcpJoinBridge(
  private val ctx: Context,
  private val store: Store,
  private val phoneAgent: PhoneAgent,
) {
  @Volatile var status: String = "offline"
    private set
  var onStatus: ((String) -> Unit)? = null

  @Volatile private var ws: WebSocket? = null
  @Volatile private var wantUp = false
  @Volatile private var fxProc: Process? = null
  @Volatile private var homeDir: File? = null
  @Volatile private var localToken: String? = null

  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  private fun setStatus(s: String) {
    status = s
    try { onStatus?.invoke(s) } catch (_: Exception) {}
  }

  fun pair(agentsUrl: String, code: String, cb: (ok: Boolean, msg: String) -> Unit) {
    Thread {
      try {
        val base = agentsUrl.trim().trimEnd('/')
        val url = "$base/pair/${code.trim().uppercase()}"
        val req = Request.Builder()
          .url(url)
          .post(okhttp3.RequestBody.create(null, "{\"id\":\"phone\",\"name\":${JSONObject.quote(store.name)}}".toByteArray()))
          .header("Content-Type", "application/json")
          .build()
        http.newCall(req).execute().use { res ->
          val body = res.body?.string() ?: ""
          if (!res.isSuccessful) { cb(false, "pair ${res.code}: $body"); return@Thread }
          val j = JSONObject(body)
          val peerId = j.optString("peerId", "").ifBlank { j.optString("peer_id", "") }
            .ifBlank { j.optString("id", "") }
          val token = j.optString("token", "")
          if (token.isBlank()) { cb(false, "pair: no token in response"); return@Thread }
          store.agentsUrl = base
          store.agentsPeerId = peerId.ifBlank { null }
          store.agentsToken = token
          cb(true, "linked as ${peerId.ifBlank { "phone" }}")
        }
        connect()
      } catch (e: Exception) {
        Log.d("phone", "acp pair fail: $e")
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
          setStatus("connecting…")
          dial()
          if (android.os.SystemClock.elapsedRealtime() - t0 > 30000) wait = 1000L
        } catch (e: Exception) {
          Log.d("phone", "acp dial: $e")
        }
        if (!wantUp) break
        setStatus("retry in ${wait / 1000}s")
        Thread.sleep(wait)
        wait = minOf(wait * 2, 30000L)
      }
    }.start()
  }

  fun disconnect() {
    wantUp = false
    try { ws?.close(1000, "ui") } catch (_: Exception) {}
    ws = null
    killFx()
    setStatus("offline")
  }

  private fun killFx() {
    try { fxProc?.destroyForcibly() } catch (_: Exception) {}
    fxProc = null
  }

  private fun dial() {
    val httpBase = store.agentsUrl ?: throw IllegalStateException("no agents url")
    val token = store.agentsToken ?: throw IllegalStateException("not linked")
    val wsUrl = httpBase.replaceFirst("http", "ws").trimEnd('/') + "/join"
    val req = Request.Builder()
      .url(wsUrl)
      .header("Authorization", "Bearer $token")
      .build()
    val gone = CountDownLatch(1)
    val socket = http.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(w: WebSocket, r: Response) {
        val (home, token) = try {
          phoneAgent.prepareForAcp()
        } catch (e: Exception) {
          Log.d("phone", "acp prepare fail: $e")
          w.close(1011, "prepare failed")
          gone.countDown()
          return
        }
        homeDir = home
        // Per-process loopback token (never the real API key).
        localToken = token
        val hello = JSONObject()
          .put("t", "hello")
          .put("id", store.agentsPeerId ?: "phone")
          .put("name", store.name)
          .put("description", "Android on-device fx agent")
        try { w.send(hello.toString()) } catch (_: Exception) {}

        val fxBin = File(ctx.applicationInfo.nativeLibraryDir, "libfx.so")
        val pb = ProcessBuilder(FxConfig.acpArgv(fxBin.absolutePath))
        try { pb.directory(home) } catch (_: Exception) {}
        try {
          val env = pb.environment()
          env["HOME"] = home.absolutePath
          env["TMPDIR"] = ctx.cacheDir.absolutePath
          env["SSL_CERT_FILE"] = File(ctx.cacheDir, "cacerts.pem").absolutePath
          env["SHELL"] = "/system/bin/sh"
          env["PATH"] = "/system/bin:/system/xbin"
          // Local loopback token only; the real API key never enters fx's env.
          if (!token.isNullOrBlank()) env["PHONE_AGENT_API_KEY"] = token
        } catch (_: Exception) {
        }
        val proc = try {
          pb.start()
        } catch (e: Exception) {
          Log.d("phone", "acp spawn fail: $e")
          w.close(1011, "spawn failed")
          gone.countDown()
          return
        }
        fxProc = proc
        setStatus("connected — ${store.name}")
        Thread {
          try {
            proc.inputStream.bufferedReader().forEachLine { line ->
              if (line.isNotBlank()) {
                try { w.send(line) } catch (_: Exception) {}
              }
            }
          } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        Thread {
          try { proc.errorStream.bufferedReader().forEachLine { } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
      }

      override fun onMessage(w: WebSocket, text: String) {
        try {
          val proc = fxProc ?: return
          var line = text
          try {
            val j = JSONObject(text)
            if (j.optString("method", "") == "session/new") {
              val home = homeDir
              if (home != null) {
                val params = j.optJSONObject("params") ?: JSONObject()
                params.put("cwd", home.absolutePath)
                // ACP HTTP MCP servers take headers [{name, value}]; the
                // per-process loopback token rides as the Authorization
                // bearer (never the real API key).
                val hdrs = JSONArray()
                localToken?.takeIf { it.isNotBlank() }?.let { t ->
                  hdrs.put(JSONObject().put("name", "Authorization").put("value", "Bearer $t"))
                }
                val servers = JSONArray().put(JSONObject()
                  .put("name", "phone")
                  .put("type", "http")
                  .put("url", LocalMcpServer.URL)
                  .put("headers", hdrs))
                if (!store.agentsUrl.isNullOrBlank() && !store.agentsToken.isNullOrBlank()) {
                  val agentsHdrs = JSONArray()
                  localToken?.takeIf { it.isNotBlank() }?.let { t ->
                    agentsHdrs.put(JSONObject().put("name", "Authorization").put("value", "Bearer $t"))
                  }
                  servers.put(JSONObject()
                    .put("name", "agents")
                    .put("type", "http")
                    .put("url", LocalMcpServer.AGENTS_MCP_URL)
                    .put("headers", agentsHdrs))
                }
                params.put("mcpServers", servers)
                j.put("params", params)
                val meta = j.optJSONObject("_meta") ?: JSONObject()
                val fx = meta.optJSONObject("fx") ?: JSONObject()
                fx.put("profileMcpServers", true)
                meta.put("fx", fx)
                j.put("_meta", meta)
                line = j.toString()
              }
            }
          } catch (_: Exception) {
            line = text
          }
          try {
            proc.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
          } catch (_: Exception) {}
        } catch (e: Exception) {
          Log.d("phone", "acp msg: ${e.message}")
        }
      }

      override fun onClosed(w: WebSocket, code: Int, reason: String) {
        killFx()
        gone.countDown()
      }

      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
        Log.d("phone", "acp ws fail: $t")
        killFx()
        gone.countDown()
      }
    })
    ws = socket
    gone.await()
    ws = null
    killFx()
  }
}
