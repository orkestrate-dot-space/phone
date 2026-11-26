package space.orkestrate.phone

import org.json.JSONObject

/**
 * Pure fx spawn/config builders (no Android: unit-testable on the JVM).
 *
 * fx v0.0.18 modes: ask | auto | full-access. The on-device agent runs in
 * `auto`: every risky action (including every MCP tool call) goes to a
 * reviewer model before it runs. The reviewer is the custom connection's
 * `reviewer_model` (see [buildSettings]); fx holds the action when the
 * reviewer cannot decide (`tool_review_held`) and the turn continues.
 * fx's own built-in shell stays disabled via the `bash` deny rule: only the
 * `phone` MCP `shell` tool (gated by Store.fxApproval) runs commands.
 */
object FxConfig {
  /** `fx ask --json --auto [--resume <id>] <prompt>`. */
  fun askArgv(fxBin: String, sessionId: String?, prompt: String): List<String> {
    val cmd = mutableListOf(fxBin, "ask", "--json", "--auto")
    if (!sessionId.isNullOrBlank()) {
      cmd.add("--resume")
      cmd.add(sessionId)
    }
    cmd.add(prompt)
    return cmd
  }

  /** `fx acp --auto` (native ACP-over-WebSocket bridge). */
  fun acpArgv(fxBin: String): List<String> = listOf(fxBin, "acp", "--auto")

  /**
   * fxhome/.fx/settings.json: custom OpenAI-compatible provider pointed at
   * the loopback LLM proxy, `permission_mode: auto`, built-in shell denied,
   * and `reviewer_model` set to [reviewerModel] (defaults to the main model).
   */
  fun buildSettings(model: String, llmUrl: String, reviewerModel: String = model): JSONObject {
    val modelMeta = JSONObject()
    for (m in setOf(model, reviewerModel)) {
      modelMeta.put(m, JSONObject()
        .put("context_window", 128000)
        .put("max_output_tokens", 8192)
        .put("supports_tool_use", true))
    }
    return JSONObject()
      .put("provider", "custom")
      .put("permission_mode", "auto")
      .put("permission", JSONObject().put("bash", JSONObject().put("*", "deny")))
      .put("providers", JSONObject().put("custom", JSONObject()
        .put("protocol", "openai-chat-completions")
        .put("base_url", llmUrl)
        // Local loopback token (NOT the real key): proxyLlm swaps this
        // bearer for the real key when forwarding upstream.
        .put("auth", JSONObject().put("type", "bearer").put("env", "PHONE_AGENT_API_KEY"))
        .put("reviewer_model", reviewerModel)
        .put("model_metadata", modelMeta)))
      .put("models", JSONObject().put("custom", model))
  }

  /**
   * fx caps a review at 2048 output tokens and holds the action when the
   * reply is cut off, so thinking models make poor reviewers. Use a fast
   * model on upstreams we know; elsewhere fall back to the main model.
   */
  fun reviewerFor(baseUrl: String, model: String): String =
    if (baseUrl.contains("opencode.ai/zen/go")) "gpt-5.6-luna" else model
}
