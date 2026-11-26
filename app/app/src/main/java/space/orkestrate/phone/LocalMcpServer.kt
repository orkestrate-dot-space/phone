package space.orkestrate.phone

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Loopback-only Streamable HTTP MCP server (127.0.0.1:19879) + streaming LLM proxy (/llm/...)
 * for the on-device fx agent.
 */
class LocalMcpServer(
  private val ctx: Context,
  private val store: Store,
  private val shizuku: ShizukuBridge,
  private val onHandoff: (id: String, reason: String) -> Unit,
  var onToolEvent: ((tool: String, detail: String) -> Unit)? = null,
) {
  companion object {
    const val PORT = 19879
    const val URL = "http://127.0.0.1:$PORT/mcp"
    const val AGENTS_MCP_URL = "http://127.0.0.1:$PORT/agents-mcp"
    const val LLM_URL = "http://127.0.0.1:$PORT/llm"
  }

  /**
   * Per-process loopback token (>=128 bits). fx learns it via its process
   * environment only. Never logged. Every /mcp and /llm request must carry
   * `Authorization: Bearer <token>`, else 401.
   */
  val localToken: String = LocalAuth.generateToken()

  /**
   * Gate for the `shell` tool. Must complete only via a real on-glass tap
   * (or an allowlisted auto-approve). Null/failing gate denies (fail-closed).
   */
  interface ShellGate {
    fun approveShell(cmd: String, argv: List<String>): Boolean
  }

  var shellGate: ShellGate? = null

  @Volatile var llmUpstreamBaseUrl: String = "https://openrouter.ai/api/v1"
  @Volatile var llmSessionId: String = "ses_orkestrate_phone"
  @Volatile private var server: ServerSocket? = null
  @Volatile private var running = false

  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

  private val agentsShort = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  private val agentsLong = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.MINUTES)
    .build()

  fun start() {
    if (running) return
    running = true
    Thread {
      try {
        val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        Log.i("phone", "LocalMcpServer listening on $URL")
        while (running) {
          val sock = try { ss.accept() } catch (_: Exception) { break }
          Thread { handle(sock) }.apply { isDaemon = true }.start()
        }
      } catch (e: Exception) {
        Log.e("phone", "LocalMcpServer error: ${e.message}")
      }
    }.apply { isDaemon = true }.start()
  }

  fun stop() {
    running = false
    try { server?.close() } catch (_: Exception) {}
    server = null
  }

  private fun handle(sock: Socket) {
    try {
      handleInner(sock)
    } catch (e: Exception) {
      Log.i("phone", "LocalMcpServer socket closed: ${e.message}")
      try { sock.close() } catch (_: Exception) {}
    }
  }

  private fun handleInner(sock: Socket) {
    sock.use { s ->
      s.soTimeout = 120000
      val inp = BufferedInputStream(s.getInputStream())
      val out = s.getOutputStream()
      val headerBuf = ByteArrayOutputStream()
      var cur: Int
      var headerEnd = false
      while (inp.read().also { cur = it } != -1) {
        headerBuf.write(cur)
        val b = headerBuf.toByteArray()
        val n = b.size
        if (n >= 4 && b[n - 4] == 13.toByte() && b[n - 3] == 10.toByte() && b[n - 2] == 13.toByte() && b[n - 1] == 10.toByte()) {
          headerEnd = true
          break
        }
      }
      if (!headerEnd) return
      val rawHeaders = headerBuf.toString(StandardCharsets.UTF_8.name())
      val lines = rawHeaders.split("\r\n")
      val reqLine = lines.firstOrNull()?.split(" ") ?: emptyList()
      val httpMethod = reqLine.getOrNull(0)?.uppercase() ?: "GET"
      val path = reqLine.getOrNull(1) ?: "/"

      val headers = mutableMapOf<String, String>()
      for (line in lines.drop(1)) {
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }

      val bodyBytes = if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
        readChunked(inp)
      } else {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
          val r = inp.read(buf, read, len - read)
          if (r == -1) break
          read += r
        }
        if (read == len) buf else buf.copyOf(read)
      }

      if (path.startsWith("/llm")) {
        if (!LocalAuth.bearerMatches(headers["authorization"], localToken)) {
          writeHttp(out, 401, JSONObject().put("error", "unauthorized").toString())
          return
        }
        proxyLlm(httpMethod, path, headers, bodyBytes, out)
        return
      }

      if (!LocalAuth.bearerMatches(headers["authorization"], localToken)) {
        writeHttp(out, 401, JSONObject().put("error", "unauthorized").toString())
        return
      }

      if (httpMethod == "GET") {
        writeHttp(out, 405, "")
        return
      }
      if (httpMethod == "DELETE") {
        writeHttp(out, 200, "")
        return
      }

      val body = String(bodyBytes, StandardCharsets.UTF_8)
      if (body.isBlank()) {
        writeHttp(out, 202, "")
        return
      }
      val req = try { JSONObject(body) } catch (_: Exception) {
        writeHttp(out, 400, """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}""")
        return
      }
      val id = req.opt("id")
      val method = req.optString("method", "")
      val isAgents = path.startsWith("/agents-mcp")
      Log.i("phone", "LocalMcpServer <- $method")
      if (method.startsWith("notifications/")) {
        writeHttp(out, 202, "")
        return
      }
      val res = try {
        when (method) {
          "initialize" -> JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", JSONObject()
              .put("protocolVersion", "2025-03-26")
              .put("capabilities", JSONObject().put("tools", JSONObject()))
              .put("serverInfo", JSONObject().put("name", "orkestrate-phone-local").put("version", "0.23.3")))
          "tools/list" -> JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", JSONObject().put("tools", if (isAgents) agentsToolsList() else toolsList()))
          "tools/call" -> {
            val params = req.optJSONObject("params") ?: JSONObject()
            val name = params.optString("name", "")
            val args = params.optJSONObject("arguments") ?: JSONObject()
            try {
              onToolEvent?.invoke(name, toolDetail(name, args))
            } catch (_: Exception) {
            }
            val toolRes = if (isAgents) callAgentsTool(name, args) else callTool(name, args)
            JSONObject()
              .put("jsonrpc", "2.0")
              .put("id", id)
              .put("result", JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", toolRes.toString()))))
          }
          else -> JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", -32601).put("message", "Method not found: $method"))
        }
      } catch (e: Exception) {
        JSONObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("error", JSONObject().put("code", -32000).put("message", e.message ?: "error"))
      }
      writeHttp(out, 200, res.toString())
    }
  }

  private fun proxyLlm(
    method: String,
    path: String,
    headers: Map<String, String>,
    bodyBytes: ByteArray,
    out: java.io.OutputStream,
  ) {
    // handleInner already verified the incoming bearer == localToken. Swap it
    // for the real key here so the real key never leaves the app process
    // (fx only ever holds the local token in PHONE_AGENT_API_KEY).
    val upstreamAuth = LocalAuth.upstreamBearer(headers["authorization"], localToken, store.agentApiKey)
    if (upstreamAuth == null) {
      writeHttp(out, 502, JSONObject().put("error", "no agent API key configured").toString())
      return
    }
    val targetUrl = llmUpstreamBaseUrl.trimEnd('/') + path.removePrefix("/llm")
    val ct = headers["content-type"] ?: "application/json"
    val reqBody = if (method in setOf("POST", "PUT", "PATCH")) {
      bodyBytes.toRequestBody(ct.toMediaTypeOrNull())
    } else null
    val rb = Request.Builder().url(targetUrl).method(method, reqBody)
    rb.header("Authorization", upstreamAuth)
    headers["accept"]?.let { rb.header("Accept", it) }
    if (targetUrl.contains("opencode.ai")) {
      rb.header("x-opencode-session", llmSessionId)
      rb.header("x-opencode-request", "req_${System.currentTimeMillis()}")
    }

    try {
      http.newCall(rb.build()).execute().use { res ->
        Log.i("phone", "LocalMcpServer LLM proxy -> $targetUrl (${res.code})")
        val resCt = res.header("Content-Type") ?: "application/json"
        val hdr = "HTTP/1.1 ${res.code} ${res.message.ifBlank { "OK" }}\r\n" +
          "Content-Type: $resCt\r\n" +
          "Transfer-Encoding: chunked\r\n" +
          "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        val stream = res.body?.byteStream()
        if (stream != null) {
          val buf = ByteArray(4096)
          while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write("${n.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.write(buf, 0, n)
            out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
          }
        }
        out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
      }
    } catch (e: Exception) {
      Log.e("phone", "LocalMcpServer LLM proxy error: ${e.message}")
      writeHttp(out, 502, JSONObject().put("error", e.message ?: "proxy error").toString())
    }
  }

  private fun readChunked(inp: BufferedInputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val lineBuf = ByteArrayOutputStream()
      var c: Int
      while (inp.read().also { c = it } != -1) {
        if (c == '\n'.code) break
        if (c != '\r'.code) lineBuf.write(c)
      }
      val sizeStr = lineBuf.toString(StandardCharsets.US_ASCII.name()).trim().substringBefore(';')
      val size = sizeStr.toIntOrNull(16) ?: 0
      if (size == 0) {
        // consume trailing CRLF
        inp.read(); inp.read()
        break
      }
      val buf = ByteArray(size)
      var read = 0
      while (read < size) {
        val r = inp.read(buf, read, size - read)
        if (r == -1) break
        read += r
      }
      out.write(buf, 0, read)
      inp.read(); inp.read()
    }
    return out.toByteArray()
  }

  private fun writeHttp(out: java.io.OutputStream, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val statusText = when (status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 405 -> "Method Not Allowed"; 502 -> "Bad Gateway"; else -> "Error" }
    val hdr = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    out.write(hdr.toByteArray(StandardCharsets.UTF_8))
    if (bytes.isNotEmpty()) out.write(bytes)
    out.flush()
  }

  /** Short human detail for the live tool-call pill (e.g. act -> "open"). */
  private fun toolDetail(name: String, args: JSONObject): String {
    return try {
      when (name) {
        "observe" -> {
          val find = args.optString("find", "")
          when {
            find.isNotBlank() -> find.take(24)
            args.optBoolean("device", false) -> "device"
            args.optBoolean("notifications", false) -> "notifications"
            args.optBoolean("apps", false) -> "apps"
            args.optBoolean("screenshot", false) -> "screen"
            else -> ""
          }
        }
        "act" -> {
          val steps = args.optJSONArray("steps")
          if (steps != null && steps.length() > 0) {
            try {
              val first = steps.getJSONObject(0)
              (first.optString("action", "").takeIf { it.isNotBlank() }
                ?: first.optString("a", "")).take(24)
            } catch (_: Exception) {
              ""
            }
          } else {
            args.optString("action", "").take(24)
          }
        }
        "shell" -> args.optString("cmd", "").trim().substringBefore(" ").take(24)
        "handoff" -> args.optString("reason", "").take(32)
        "agents_list" -> ""
        "agents_ask" -> (args.optString("to", "").takeIf { it.isNotBlank() }
          ?: args.optString("taskId", "")).take(24)
        else -> ""
      }
    } catch (_: Exception) {
      ""
    }
  }

  private fun callTool(name: String, args: JSONObject): JSONObject {
    return when (name) {
      "observe" -> when {
        args.optBoolean("device", false) ->
          AppTools.deviceInfo(ctx, store, shizuku).put("battery", AppTools.batteryInfo(ctx))
        args.optBoolean("notifications", false) -> ShizukuScreen.notifications(shizuku, args)
        args.optBoolean("apps", false) -> AppTools.appsManage(ctx, args)
        else -> ShizukuScreen.observe(
          shizuku = shizuku,
          store = store,
          screenshot = args.optBoolean("screenshot", false),
          maxPx = args.optInt("maxPx", 1080),
          quality = args.optInt("quality", 75),
        )
      }
      "act" -> ShizukuScreen.act(shizuku, args)
      "shell" -> {
        val cmd = args.optString("cmd", "")
        val argv = LocalAuth.tokenizeShell(cmd)
        val ok = try {
          shellGate?.approveShell(cmd, argv) ?: false
        } catch (_: Exception) {
          false
        }
        if (!ok) throw SecurityException("denied by user")
        val res = shizuku.exec(listOf("sh", "-c", cmd), 15)
        JSONObject().put("exit", res.exit).put("stdout", res.stdout).put("stderr", res.stderr)
      }
      "handoff" -> {
        val reason = args.optString("reason", "Agent requests human input")
        onHandoff("local-${System.currentTimeMillis()}", reason)
        JSONObject().put("ok", true).put("handedOff", true).put("reason", reason)
      }
      else -> throw IllegalArgumentException("Unknown tool: $name")
    }
  }

  private fun agentsToolsList(): JSONArray {
    return JSONArray()
      .put(tool(
        "agents_list",
        "List agent peers with status.",
        JSONObject(),
      ))
      .put(tool(
        "agents_ask",
        "Ask an agent. Start: {to, prompt, sessionId?, wait?}. Check a task: {taskId}.",
        JSONObject()
          .put("to", JSONObject().put("type", "string"))
          .put("prompt", JSONObject().put("type", "string"))
          .put("sessionId", JSONObject().put("type", "string"))
          .put("wait", JSONObject().put("type", "boolean"))
          .put("taskId", JSONObject().put("type", "string")),
      ))
  }

  /**
   * Bridge to the paired @orkestrate/agents daemon. Forwards to Store.agentsUrl
   * with `Authorization: Bearer <Store.agentsToken>`; the daemon token never
   * leaves the app process (fx only holds the loopback localToken).
   * Never logs tokens, prompts, or answers: one short line per call only.
   */
  private fun callAgentsTool(name: String, args: JSONObject): JSONObject {
    val base = store.agentsUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val daemonToken = store.agentsToken?.takeIf { it.isNotBlank() }
    if (base == null || daemonToken == null) throw IllegalStateException("not linked to an agents daemon")
    return when (name) {
      "agents_list" -> {
        val (code, body) = agentsHttpGet("$base/agents", daemonToken)
        Log.i("phone", "AgentsMcp agents_list ($code)")
        if (code !in 200..299) throw IllegalStateException(daemonErrorMessage(body, code))
        JSONObject(body.ifBlank { "{}" })
      }
      "agents_ask" -> {
        val taskId = args.optString("taskId", "").takeIf { it.isNotBlank() }
        if (taskId != null) {
          val enc = try {
            java.net.URLEncoder.encode(taskId, "UTF-8")
          } catch (_: Exception) {
            taskId
          }
          val (code, body) = agentsHttpGet("$base/tasks/$enc", daemonToken)
          Log.i("phone", "AgentsMcp agents_ask task ($code)")
          if (code !in 200..299) throw IllegalStateException(daemonErrorMessage(body, code))
          val j = try {
            JSONObject(body.ifBlank { "{}" })
          } catch (_: Exception) {
            throw IllegalStateException(daemonErrorMessage(body, code))
          }
          j.optJSONObject("task") ?: j
        } else {
          val toRaw = args.opt("to")
          val promptRaw = args.opt("prompt")
          val to = if (toRaw is String) toRaw else ""
          if (to.isBlank() || promptRaw !is String) {
            throw IllegalArgumentException("agents_ask needs {taskId} or {to, prompt}")
          }
          val selfId = store.agentsPeerId?.takeIf { it.isNotBlank() }
          if (selfId != null && to == selfId) throw IllegalStateException("cannot ask yourself")
          val wait = if (args.has("wait")) args.optBoolean("wait", true) else true
          val sessionId = args.optString("sessionId", "").takeIf { it.isNotBlank() }
          val bodyJson = JSONObject()
            .put("to", to)
            .put("prompt", promptRaw)
            .put("wait", wait)
            .put("trace", JSONArray())
          if (sessionId != null) bodyJson.put("sessionId", sessionId)
          val from = store.agentsPeerId?.takeIf { it.isNotBlank() }
          if (from != null) bodyJson.put("from", from)
          val (code, body) = agentsHttpPost("$base/ask", daemonToken, bodyJson.toString(), wait)
          Log.i("phone", "AgentsMcp agents_ask -> $to ($code)")
          if (code !in 200..299) throw IllegalStateException(daemonErrorMessage(body, code))
          try {
            JSONObject(body.ifBlank { "{}" })
          } catch (_: Exception) {
            throw IllegalStateException(daemonErrorMessage(body, code))
          }
        }
      }
      else -> throw IllegalArgumentException("Unknown tool: $name")
    }
  }

  private fun agentsHttpGet(url: String, daemonToken: String): Pair<Int, String> {
    val req = Request.Builder()
      .url(url)
      .get()
      .header("Authorization", "Bearer $daemonToken")
      .build()
    agentsShort.newCall(req).execute().use { res ->
      return res.code to (res.body?.string() ?: "")
    }
  }

  private fun agentsHttpPost(url: String, daemonToken: String, jsonBody: String, wait: Boolean): Pair<Int, String> {
    val client = if (wait) agentsLong else agentsShort
    val req = Request.Builder()
      .url(url)
      .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
      .header("Authorization", "Bearer $daemonToken")
      .build()
    client.newCall(req).execute().use { res ->
      return res.code to (res.body?.string() ?: "")
    }
  }

  private fun daemonErrorMessage(body: String, code: Int): String {
    return try {
      val j = JSONObject(body)
      j.optString("error", "").takeIf { it.isNotBlank() } ?: "daemon HTTP $code"
    } catch (_: Exception) {
      if (body.isNotBlank()) body.take(200) else "daemon HTTP $code"
    }
  }

  private fun toolsList(): JSONArray {
    return JSONArray()
      .put(tool("observe", "Observe current Android screen UI tree (@e1.. refs), optional find/diff/notifications/apps.",
        JSONObject()
          .put("find", JSONObject().put("type", "string").put("description", "Filter elements by text/desc/id"))
          .put("device", JSONObject().put("type", "boolean").put("description", "Return device info + battery instead of the screen tree"))
          .put("notifications", JSONObject().put("type", "boolean"))
          .put("apps", JSONObject().put("type", "boolean"))))
      .put(tool("act", "Perform UI actions on Android. Supports batched steps[] (tap, type, scroll, swipe, key, open, clipboard).",
        JSONObject()
          .put("action", JSONObject().put("type", "string"))
          .put("ref", JSONObject().put("type", "string"))
          .put("text", JSONObject().put("type", "string"))
          .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")))))
      .put(tool("handoff", "Ask the human to take over on the phone (login, payment, 2FA, or risky confirmation).",
        JSONObject().put("reason", JSONObject().put("type", "string")),
        listOf("reason")))
      .put(tool("shell", "Run an Android shell command. Allowlisted read-only recon may run directly; anything else raises an on-glass approval card the user must tap.",
        JSONObject().put("cmd", JSONObject().put("type", "string")),
        listOf("cmd")))
  }

  private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
    val schema = JSONObject().put("type", "object").put("properties", props)
    if (required.isNotEmpty()) schema.put("required", JSONArray(required))
    return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
  }
}
