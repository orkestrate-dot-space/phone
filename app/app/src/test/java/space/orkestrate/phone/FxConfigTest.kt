package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fx v0.0.18 wiring: auto-mode settings shape and spawn argv.
 * Pure FxConfig builders, no device needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FxConfigTest {
  @Test fun settingsUsesAutoPermissionMode() {
    val s = FxConfig.buildSettings("google/gemini-2.5-flash", LocalMcpServer.LLM_URL)
    assertEquals("custom", s.getString("provider"))
    assertEquals("auto", s.getString("permission_mode"))
    assertEquals("google/gemini-2.5-flash", s.getJSONObject("models").getString("custom"))
  }

  @Test fun settingsDeniesBuiltinBash() {
    val s = FxConfig.buildSettings("google/gemini-2.5-flash", LocalMcpServer.LLM_URL)
    assertEquals("deny", s.getJSONObject("permission").getJSONObject("bash").getString("*"))
  }

  @Test fun settingsReviewerIsConfiguredModel() {
    val model = "google/gemini-2.5-flash"
    val s = FxConfig.buildSettings(model, LocalMcpServer.LLM_URL)
    val custom = s.getJSONObject("providers").getJSONObject("custom")
    assertEquals(model, custom.getString("reviewer_model"))
    assertEquals(LocalMcpServer.LLM_URL, custom.getString("base_url"))
    assertTrue(custom.getJSONObject("model_metadata").has(model))
  }

  @Test fun reviewerIsFastModelOnOpencodeGo() {
    assertEquals("gpt-5.6-luna", FxConfig.reviewerFor("https://opencode.ai/zen/go/v1", "muse-spark-1.3-contributor"))
    assertEquals("m", FxConfig.reviewerFor("https://openrouter.ai/api/v1", "m"))
    val s = FxConfig.buildSettings("muse-spark-1.3-contributor", LocalMcpServer.LLM_URL, "gpt-5.6-luna")
    val custom = s.getJSONObject("providers").getJSONObject("custom")
    assertEquals("gpt-5.6-luna", custom.getString("reviewer_model"))
    assertTrue(custom.getJSONObject("model_metadata").has("gpt-5.6-luna"))
  }

  @Test fun askArgvUsesAuto() {
    val cmd = FxConfig.askArgv("/lib/libfx.so", null, "do it")
    assertTrue("--auto" in cmd)
    assertFalse("--full-access" in cmd)
    assertEquals(listOf("/lib/libfx.so", "ask", "--json", "--auto", "do it"), cmd)
  }

  @Test fun askArgvResumesSession() {
    val cmd = FxConfig.askArgv("/lib/libfx.so", "ses_1", "do it")
    assertEquals(listOf("/lib/libfx.so", "ask", "--json", "--auto", "--resume", "ses_1", "do it"), cmd)
  }

  @Test fun acpArgvUsesAuto() {
    assertEquals(listOf("/lib/libfx.so", "acp", "--auto"), FxConfig.acpArgv("/lib/libfx.so"))
  }

  @Test fun shellDecisionFxDecidesNeedsNoCard() {
    // fx-decides mode: reviewer already cleared the call, even for non-allowlisted argv.
    assertFalse(LocalAuth.shellNeedsCard(false, listOf("rm", "-rf", "/"), fxDecides = true))
    assertFalse(LocalAuth.shellNeedsCard(false, listOf("date"), fxDecides = true))
    assertFalse(LocalAuth.shellNeedsCard(true, listOf("rm", "-rf", "/"), fxDecides = true))
  }

  @Test fun shellDecisionAlwaysAskKeepsTodayResult() {
    // always-ask mode: today's rule (autoApprove + exact allowlist only).
    assertFalse(LocalAuth.shellNeedsCard(true, listOf("date"), fxDecides = false))
    assertTrue(LocalAuth.shellNeedsCard(false, listOf("date"), fxDecides = false))
    assertTrue(LocalAuth.shellNeedsCard(true, listOf("rm", "-rf", "/"), fxDecides = false))
  }
}
