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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercises the real loopback HTTP server: every /mcp and /llm request needs
 * `Authorization: Bearer <localToken>` (401 without, 200 with), and a denied
 * shell approval surfaces as an MCP tool error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMcpServerAuthTest {
  private lateinit var server: LocalMcpServer
  private var denyShell = true

  @Before fun setUp() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val store = Store(ctx)
    val shizuku = ShizukuBridge(ctx)
    server = LocalMcpServer(ctx, store, shizuku, onHandoff = { _, _ -> })
    server.shellGate = object : LocalMcpServer.ShellGate {
      override fun approveShell(cmd: String, argv: List<String>): Boolean = !denyShell
    }
    server.start()
    Thread.sleep(300)
  }

  @After fun tearDown() {
    server.stop()
  }

  private fun post(path: String, body: String, bearer: String?): Pair<Int, String> {
    val c = URL("http://127.0.0.1:${LocalMcpServer.PORT}$path").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.connectTimeout = 5000
    c.readTimeout = 5000
    c.setRequestProperty("Content-Type", "application/json")
    if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
    OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
    val code = c.responseCode
    val stream = if (code in 200..299) c.inputStream else c.errorStream
    val text = stream?.bufferedReader()?.readText() ?: ""
    c.disconnect()
    return code to text
  }

  @Test fun mcpRequiresToken() {
    val body = JSONObject()
      .put("jsonrpc", "2.0").put("id", 1)
      .put("method", "tools/list").put("params", JSONObject())
      .toString()
    val (noAuthCode, _) = post("/mcp", body, null)
    assertEquals(401, noAuthCode)
    val (wrongCode, _) = post("/mcp", body, "wrong-token")
    assertEquals(401, wrongCode)
    val (okCode, okBody) = post("/mcp", body, server.localToken)
    assertEquals(200, okCode)
    assertTrue(okBody.contains("shell"))
  }

  @Test fun llmRequiresTokenAndNeverReachesUpstream() {
    // No auth -> 401 from the gate, before any upstream connection attempt.
    val (code, _) = post("/llm/v1/chat/completions", """{"model":"x"}""", null)
    assertEquals(401, code)
  }

  @Test fun deniedShellIsToolError() {
    denyShell = true
    val body = JSONObject()
      .put("jsonrpc", "2.0").put("id", 2)
      .put("method", "tools/call")
      .put("params", JSONObject()
        .put("name", "shell")
        .put("arguments", JSONObject().put("cmd", "rm -rf /")))
      .toString()
    val (code, text) = post("/mcp", body, server.localToken)
    assertEquals(200, code)
    val err = JSONObject(text).getJSONObject("error")
    assertEquals("denied by user", err.getString("message"))
  }
}
