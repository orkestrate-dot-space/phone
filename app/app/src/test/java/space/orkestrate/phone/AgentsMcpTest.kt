package space.orkestrate.phone

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Exercises the /agents-mcp bridge to the paired @orkestrate/agents daemon.
 * Daemon stub is a plain ServerSocket (no extra test deps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentsMcpTest {
  private lateinit var server: LocalMcpServer
  private lateinit var store: Store
  private lateinit var stub: ServerSocket
  private lateinit var daemonBase: String
  @Volatile private var stubRunning = false

  @Volatile var nextCode = 200
  @Volatile var nextBody = "{}"
  @Volatile var lastAuth: String? = null
  @Volatile var lastPath: String? = null
  @Volatile var lastMethod: String? = null
  @Volatile var lastBody: String? = null

  @Before fun setUp() {
    stub = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    daemonBase = "http://127.0.0.1:${stub.localPort}"
    stubRunning = true
    Thread {
      while (stubRunning) {
        val sock = try {
          stub.accept()
        } catch (_: Exception) {
          break
        }
        Thread { handleStub(sock) }.apply { isDaemon = true }.start()
      }
    }.apply { isDaemon = true }.start()

    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    store = Store(ctx)
    store.agentsUrl = daemonBase
    store.agentsToken = "daemon-token-123"
    store.agentsPeerId = "phone"
    val shizuku = ShizukuBridge(ctx)
    server = LocalMcpServer(ctx, store, shizuku, onHandoff = { _, _ -> })
    server.start()
    Thread.sleep(300)
  }

  @After fun tearDown() {
    stubRunning = false
    try {
      server.stop()
    } catch (_: Exception) {
    }
    try {
      stub.close()
    } catch (_: Exception) {
    }
    Thread.sleep(100)
  }

  private fun handleStub(sock: Socket) {
    try {
      sock.use { s ->
        val inp = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val headerBuf = ByteArrayOutputStream()
        var prev = intArrayOf(0, 0, 0, 0)
        // Read until \r\n\r\n
        while (true) {
          val b = inp.read()
          if (b == -1) return
          headerBuf.write(b)
          prev = intArrayOf(prev[1], prev[2], prev[3], b)
          if (prev[0] == '\r'.code && prev[1] == '\n'.code && prev[2] == '\r'.code && prev[3] == '\n'.code) break
          if (headerBuf.size() > 65536) break
        }
        val raw = headerBuf.toString(StandardCharsets.UTF_8.name())
        val lines = raw.split("\r\n")
        val reqLine = lines.firstOrNull()?.split(" ") ?: emptyList()
        val method = reqLine.getOrNull(0)?.uppercase() ?: "GET"
        val path = reqLine.getOrNull(1) ?: "/"
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
          val idx = line.indexOf(':')
          if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
          val r = inp.read(buf, read, len - read)
          if (r == -1) break
          read += r
        }
        lastAuth = headers["authorization"]
        lastPath = path.substringBefore("?")
        lastMethod = method
        lastBody = if (read > 0) String(buf, 0, read, StandardCharsets.UTF_8) else ""
        val bodyBytes = nextBody.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (nextCode) {
          200 -> "OK"
          403 -> "Forbidden"
          404 -> "Not Found"
          else -> "Error"
        }
        val hdr = "HTTP/1.1 $nextCode $statusText\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(StandardCharsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
      }
    } catch (_: Exception) {
      try {
        sock.close()
      } catch (_: Exception) {
      }
    }
  }

  private fun post(path: String, body: String, bearer: String?): Pair<Int, String> {
    val c = URL("http://127.0.0.1:${LocalMcpServer.PORT}$path").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.connectTimeout = 5000
    c.readTimeout = 15000
    c.setRequestProperty("Content-Type", "application/json")
    if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
    OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
    val code = c.responseCode
    val stream = if (code in 200..299) c.inputStream else c.errorStream
    val text = stream?.bufferedReader()?.readText() ?: ""
    c.disconnect()
    return code to text
  }

  private fun rpc(method: String, params: JSONObject, id: Int = 1): String {
    return JSONObject()
      .put("jsonrpc", "2.0").put("id", id)
      .put("method", method).put("params", params)
      .toString()
  }

  @Test fun agentsMcpRequiresToken() {
    val body = rpc("tools/list", JSONObject())
    val (noAuthCode, _) = post("/agents-mcp", body, null)
    assertEquals(401, noAuthCode)
    val (wrongCode, _) = post("/agents-mcp", body, "wrong-token")
    assertEquals(401, wrongCode)
  }

  @Test fun toolsListReturnsExactlyAgentsTools() {
    val body = rpc("tools/list", JSONObject())
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    val tools = JSONObject(text).getJSONObject("result").getJSONArray("tools")
    assertEquals(2, tools.length())
    val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
    assertEquals(setOf("agents_list", "agents_ask"), names)
    val byName = (0 until tools.length()).associate { tools.getJSONObject(it).getString("name") to tools.getJSONObject(it) }
    // Shapes mirror the daemon's own MCP.
    val listSchema = byName.getValue("agents_list").getJSONObject("inputSchema")
    assertEquals("object", listSchema.getString("type"))
    assertEquals(0, listSchema.getJSONObject("properties").length())
    val askSchema = byName.getValue("agents_ask").getJSONObject("inputSchema")
    val props = askSchema.getJSONObject("properties")
    for (k in listOf("to", "prompt", "sessionId", "wait", "taskId")) {
      assertTrue("ask schema has $k", props.has(k))
    }
  }

  @Test fun agentsListForwardsDaemonToken() {
    nextCode = 200
    nextBody = """{"agents":[{"id":"opencode","kind":"acp","status":"ready"}]}"""
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_list")
      .put("arguments", JSONObject()))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    assertEquals("Bearer daemon-token-123", lastAuth)
    assertEquals("/agents", lastPath)
    assertTrue(text.contains("opencode"))
  }

  @Test fun agentsAskForwardsAuthAndFrom() {
    nextCode = 200
    nextBody = """{"text":"hi","taskId":"t1","trace":[]}"""
    lastAuth = null
    lastBody = null
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_ask")
      .put("arguments", JSONObject().put("to", "opencode").put("prompt", "hello")))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    assertEquals("Bearer daemon-token-123", lastAuth)
    assertEquals("/ask", lastPath)
    val sent = JSONObject(lastBody ?: "{}")
    assertEquals("opencode", sent.getString("to"))
    assertEquals("hello", sent.getString("prompt"))
    assertEquals("phone", sent.getString("from"))
    assertEquals(0, sent.getJSONArray("trace").length())
    assertTrue(text.contains("hi"))
  }

  @Test fun agentsAskTaskIdChecksTask() {
    nextCode = 200
    nextBody = """{"task":{"id":"t1","status":"done","text":"done-text"}}"""
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_ask")
      .put("arguments", JSONObject().put("taskId", "t1")))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    assertEquals("Bearer daemon-token-123", lastAuth)
    assertEquals("/tasks/t1", lastPath)
    assertTrue(text.contains("done-text"))
  }

  @Test fun daemon403SurfacesVerbatim() {
    nextCode = 403
    nextBody = """{"error":"forbidden: phone may not ask opencode"}"""
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_ask")
      .put("arguments", JSONObject().put("to", "opencode").put("prompt", "hello")))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    val err = JSONObject(text).getJSONObject("error")
    assertTrue(err.getString("message").contains("forbidden: phone may not ask opencode"))
  }

  @Test fun notLinkedReturnsError() {
    store.agentsUrl = null
    store.agentsToken = null
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_ask")
      .put("arguments", JSONObject().put("to", "opencode").put("prompt", "hello")))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    val err = JSONObject(text).getJSONObject("error")
    assertTrue(err.getString("message").contains("not linked to an agents daemon"))
  }

  @Test fun selfAskReturnsError() {
    store.agentsPeerId = "phone"
    lastPath = null
    val body = rpc("tools/call", JSONObject()
      .put("name", "agents_ask")
      .put("arguments", JSONObject().put("to", "phone").put("prompt", "hello")))
    val (code, text) = post("/agents-mcp", body, server.localToken)
    assertEquals(200, code)
    val err = JSONObject(text).getJSONObject("error")
    assertTrue(err.getString("message").contains("cannot ask yourself"))
  }
}
