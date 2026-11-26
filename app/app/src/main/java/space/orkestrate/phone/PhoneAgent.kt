package space.orkestrate.phone

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled `fx` binary (libfx.so) on-device against LocalMcpServer (127.0.0.1:19879).
 */
class PhoneAgent(
  private val ctx: Context,
  private val store: Store,
  private val mcp: LocalMcpServer,
) {
  private val homeDir = File(ctx.filesDir, "fxhome")
  private val fxDir = File(homeDir, ".fx")
  private val caBundle = File(ctx.cacheDir, "cacerts.pem")

  @Volatile private var activeProc: Process? = null

  /** Forcibly stops the running fx process (Stop button). Safe to call anytime. */
  fun cancel() {
    try {
      activeProc?.destroyForcibly()
    } catch (_: Exception) {
    }
  }

  /**
   * Prepares on-device fx home for ACP (`acp --auto`) mode.
   * Starts LocalMcpServer, points the LLM proxy at the configured upstream,
   * writes fxhome/.fx/{mcp.json,settings.json,AGENTS.md}, ensures cacerts.pem.
   * fx runs with `permission_mode: auto` (see FxConfig): its reviewer model
   * approves risky actions including MCP tool calls; fx's built-in shell is
   * denied and only the `phone` MCP `shell` tool runs commands.
   * fx authenticates to the loopback server with the per-process local token
   * (never the real API key): mcp.json uses `bearer_token_env` (fx refuses an
   * Authorization entry in `headers`) pointing at PHONE_AGENT_API_KEY, and the
   * LLM proxy swaps that bearer for the real key upstream.
   * @return homeDir + local loopback token (null only if something failed).
   */
  fun prepareForAcp(): Pair<File, String?> {
    mcp.start()
    val baseUrl = store.agentBaseUrl.ifBlank { "https://openrouter.ai/api/v1" }.trimEnd('/')
    val model = store.agentModel.ifBlank { "google/gemini-2.5-flash" }
    mcp.llmUpstreamBaseUrl = baseUrl
    mcp.llmSessionId = "ses_${System.currentTimeMillis()}"
    // Empty skill dirs so fx acp emits zero skill-discovery warnings.
    for (d in listOf(".fx/skills", ".agents/skills", ".claude/skills", ".codex/skills", ".config/opencode/skills")) {
      try {
        File(homeDir, d).mkdirs()
      } catch (_: Exception) {
      }
    }
    prepareHome(model)
    ensureCaBundle()
    return homeDir to mcp.localToken
  }

  fun runTask(prompt: String, sessionId: String? = null, overrides: JSONObject? = null): JSONObject {
    try {
      return runTaskInner(prompt, sessionId, overrides)
    } catch (e: Exception) {
      return try {
        JSONObject().put("ok", false).put("error", e.message ?: "failed")
      } catch (_: Exception) {
        JSONObject()
      }
    }
  }

  private fun runTaskInner(prompt: String, sessionId: String? = null, overrides: JSONObject? = null): JSONObject {
    val t0 = SystemClock.elapsedRealtime()
    mcp.start()

    val fxBin = File(ctx.applicationInfo.nativeLibraryDir, "libfx.so")
    if (!fxBin.exists()) {
      return JSONObject().put("ok", false).put("error", "libfx.so missing at ${fxBin.absolutePath}")
    }

    if (prompt == "__diag__") {
      return execFx(fxBin, listOf(fxBin.absolutePath, "--version"), 10L, t0)
    }

    val apiKey = overrides?.optString("apiKey", "")?.takeIf { it.isNotBlank() } ?: store.agentApiKey
    if (apiKey.isNullOrBlank()) {
      return JSONObject().put("ok", false).put("error", "No agent API key configured on phone (set in Settings or pass apiKey)")
    }
    val baseUrl = overrides?.optString("baseUrl", "")?.takeIf { it.isNotBlank() }
      ?: store.agentBaseUrl.ifBlank { "https://openrouter.ai/api/v1" }
    val model = overrides?.optString("model", "")?.takeIf { it.isNotBlank() }
      ?: store.agentModel.ifBlank { "google/gemini-2.5-flash" }

    mcp.llmUpstreamBaseUrl = baseUrl.trimEnd('/')
    mcp.llmSessionId = sessionId?.takeIf { it.isNotBlank() } ?: "ses_${System.currentTimeMillis()}"
    prepareHome(model)
    ensureCaBundle()

    val cmd = FxConfig.askArgv(fxBin.absolutePath, sessionId?.takeIf { it.isNotBlank() }, prompt).toMutableList()

    val timeoutSec = try {
      overrides?.optLong("timeoutSec", 180L) ?: 180L
    } catch (_: Exception) {
      180L
    }
    val res = execFx(fxBin, cmd, timeoutSec, t0)
    if (!res.optBoolean("ok", false)) return res

    val stdout = try { res.optString("stdout", "") } catch (_: Exception) { "" }
    val stderr = try { res.optString("stderr", "") } catch (_: Exception) { "" }
    val parsed = try { JSONObject(stdout) } catch (_: Exception) { null }
    val output = try {
      parsed?.optString("final_output", "")?.takeIf { it.isNotBlank() } ?: stdout
    } catch (_: Exception) {
      stdout
    }
    // Capture fx session_id from stdout JSON so multi-turn --resume works.
    val fxSessionId = try {
      parsed?.optString("session_id", "")?.takeIf { it.isNotBlank() }
        ?: parsed?.optJSONObject("session")?.optString("id", "")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
    return try {
      JSONObject()
        .put("ok", true)
        .put("output", output)
        .put("durationMs", res.optLong("durationMs"))
        .apply {
          if (stderr.isNotBlank()) put("stderr", stderr)
          if (fxSessionId != null) put("fxSessionId", fxSessionId)
        }
    } catch (_: Exception) {
      JSONObject().put("ok", false).put("error", "failed")
    }
  }

  private fun execFx(
    fxBin: File,
    cmd: List<String>,
    timeoutSec: Long,
    t0: Long,
  ): JSONObject {
    val pb = try {
      ProcessBuilder(cmd)
    } catch (e: Exception) {
      return JSONObject().put("ok", false).put("error", e.message ?: "failed")
    }
    try {
      pb.directory(homeDir.apply { mkdirs() })
    } catch (_: Exception) {
    }
    try {
      pb.environment()["HOME"] = homeDir.absolutePath
      pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
      pb.environment()["SSL_CERT_FILE"] = caBundle.absolutePath
      pb.environment()["SHELL"] = "/system/bin/sh"
      pb.environment()["PATH"] = "/system/bin:/system/xbin"
      // Local loopback token only. The real API key never enters fx's
      // environment: the LLM proxy swaps this bearer for the real key.
      pb.environment()["PHONE_AGENT_API_KEY"] = mcp.localToken
    } catch (_: Exception) {
    }

    val proc = try {
      pb.start()
    } catch (e: Exception) {
      return try {
        JSONObject().put("ok", false).put("error", e.message ?: "failed")
      } catch (_: Exception) {
        JSONObject()
      }
    }
    activeProc = proc
    try { proc.outputStream.close() } catch (_: Exception) {}

    val stdoutBuf = StringBuilder()
    val stderrBuf = StringBuilder()
    val tOut = Thread {
      try { proc.inputStream.bufferedReader().forEachLine { stdoutBuf.append(it).append('\n') } } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }
    val tErr = Thread {
      try { proc.errorStream.bufferedReader().forEachLine { stderrBuf.append(it).append('\n') } } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }

    val finished = try {
      proc.waitFor(timeoutSec, TimeUnit.SECONDS)
    } catch (_: Exception) {
      false
    }
    if (!finished) {
      try { proc.destroyForcibly() } catch (_: Exception) {}
      try { tOut.join(500) } catch (_: Exception) {}
      try { tErr.join(500) } catch (_: Exception) {}
      activeProc = null
      return JSONObject()
        .put("ok", false)
        .put("error", "fx timed out after ${timeoutSec}s")
        .put("stdout", stdoutBuf.toString().trim())
        .put("stderr", stderrBuf.toString().trim())
        .put("durationMs", SystemClock.elapsedRealtime() - t0)
    }

    try { tOut.join(1000) } catch (_: Exception) {}
    try { tErr.join(1000) } catch (_: Exception) {}
    activeProc = null
    val stdout = stdoutBuf.toString().trim()
    val stderr = stderrBuf.toString().trim()
    val durationMs = SystemClock.elapsedRealtime() - t0

    val exitCode = try {
      proc.exitValue()
    } catch (e: Exception) {
      return try {
        JSONObject().put("ok", false).put("error", e.message ?: "failed")
      } catch (_: Exception) {
        JSONObject()
      }
    }
    if (exitCode != 0) {
      return JSONObject()
        .put("ok", false)
        .put("exit", exitCode)
        .put("error", stderr.ifBlank { stdout }.ifBlank { "fx exited $exitCode" })
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("durationMs", durationMs)
    }

    return JSONObject()
      .put("ok", true)
      .put("exit", 0)
      .put("stdout", stdout)
      .put("stderr", stderr)
      .put("durationMs", durationMs)
  }

  private fun prepareHome(model: String) {
    try {
      fxDir.mkdirs()

      val phoneEntry = JSONObject()
        .put("type", "http")
        .put("url", LocalMcpServer.URL)
        // fx refuses an Authorization entry in `headers`; the loopback token
        // rides as a bearer via this env var (== the same PHONE_AGENT_API_KEY
        // value the LLM proxy auth uses).
        .put("bearer_token_env", "PHONE_AGENT_API_KEY")
      val mcpServers = JSONObject().put("phone", phoneEntry)
      if (!store.agentsUrl.isNullOrBlank() && !store.agentsToken.isNullOrBlank()) {
        mcpServers.put("agents", JSONObject()
          .put("type", "http")
          .put("url", LocalMcpServer.AGENTS_MCP_URL)
          .put("bearer_token_env", "PHONE_AGENT_API_KEY"))
      }
      val mcpJson = JSONObject().put("mcp", mcpServers)
      File(fxDir, "mcp.json").writeText(mcpJson.toString(2))

      File(fxDir, "settings.json").writeText(FxConfig.buildSettings(model, LocalMcpServer.LLM_URL, FxConfig.reviewerFor(mcp.llmUpstreamBaseUrl, model)).toString(2))

      File(fxDir, "AGENTS.md").writeText(
        """
        You are the on-device Android agent running inside Orkestrate Phone.
        Use the `phone` MCP tools (`observe`, `act`, `handoff`, `shell`) to complete the user's or caller agent's request on this phone.
        Use the `agents` MCP (`agents_list`, `agents_ask`) to ask other agents for work that belongs on another machine; do not ask the agent that asked you unless the task needs it.
        Rules:
        - Always call `observe` before tapping; never guess coordinates.
        - Batch multi-step UI actions with `act(steps: [...])` when possible.
        - If login, payment, 2FA, or a risky confirmation is needed, call `handoff(reason)`.
        - If an action is held or denied, do not retry it unchanged; choose a safer action or call `handoff`.
        - Return a concise, factual final answer.
        """.trimIndent()
      )
    } catch (_: Exception) {
    }
  }

  private fun ensureCaBundle() {
    try {
      if (caBundle.exists() && caBundle.length() > 1024) return
      val sysCerts = File("/system/etc/security/cacerts")
      if (!sysCerts.isDirectory) return
      caBundle.outputStream().buffered().use { out ->
        sysCerts.listFiles()?.forEach { f ->
          if (f.isFile) {
            try {
              out.write(f.readBytes())
              out.write('\n'.code)
            } catch (_: Exception) {
            }
          }
        }
      }
    } catch (_: Exception) {
    }
  }
}
