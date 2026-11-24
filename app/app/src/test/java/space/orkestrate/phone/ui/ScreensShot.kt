package space.orkestrate.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screens A–G against the Orkestrate design system. Record: recordRoborazziDebug --tests *ScreensShot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xxhdpi")
class ScreensShot {
  @get:Rule val rule = RoborazziRule()

  private fun shot(name: String, content: @Composable () -> Unit) =
    captureRoboImage("screens/$name.png") { OrkTheme(useCustomFonts = false) { content() } }

  private val home = HomeState(
    name = "Pixel 8",
    link = DotState.On,
    linkText = "Connected to 6ace59c9…opentunnel.xyz",
    paused = false,
    shizuku = true,
    files = true,
    camera = true,
    recent = listOf(
      RecentJob("screen.observe", "Just now"),
      RecentJob("files.op", "4 min ago"),
      RecentJob("human.handoff", "18 min ago"),
    ),
  )

  @Test fun a_unpaired() = shot("a-unpaired") { UnpairedScreen({}, {}) }

  @Test fun b_setup() = shot("b-setup") {
    SetupScreen(SetupState("6ace59c9…opentunnel.xyz", true, true, true, false, "Get Shizuku", false), {}, {}, {}, {}, {})
  }

  @Test fun c_enterCode() = shot("c-enter-code") {
    EnterCodeScreen(
      EnterCodeState(
        "6ace59c9…opentunnel.xyz", "K7QM-4XWE", Probe.Answered,
        "The relay didn't accept this code. It was mistyped, used or expired.", false,
      ),
      {}, {}, {}, {}, {},
    )
  }

  @Test fun d_home() = shot("d-home") { HomeScreen(home, {}, {}, {}, {}, {}) }

  @Test fun e_approval() = shot("e-approval") {
    Box(Modifier.fillMaxSize()) {
      HomeScreen(home, {}, {}, {}, {}, {})
      ApprovalSheet(
        ApprovalCard(
          "j1", "Take a photo?", "Nothing is captured until you take it.",
          listOf("Tool" to "photo.capture", "Size" to "1280 px, JPEG"), "Open camera", true,
        ),
        {}, {},
      )
    }
  }

  @Test fun f_handoff() = shot("f-handoff") {
    Box(Modifier.fillMaxSize()) {
      HomeScreen(home, {}, {}, {}, {}, {})
      ApprovalSheet(
        ApprovalCard(
          "j2", "Agent needs you.", "Sign in to your bank app, then tap Continue.",
          listOf("Tool" to "human.handoff"), "Continue", false,
        ),
        {}, {},
      )
    }
  }

  @Test fun g_settings() = shot("g-settings") {
    SettingsScreen(
      SettingsState(
        name = "Pixel 8",
        deviceId = "dev_qT_dyBVkKbkw",
        relayUrl = "https://6ace59c9.opentunnel.xyz",
        autoApprove = false,
        blockedCount = 6,
        shizukuStatus = "Install Shizuku (free on Google Play) for screen & shell control.",
        shizukuActionLabel = "Get Shizuku",
        buildInfo = "v0.20.0 (build 44)",
        logs = listOf("connected — Pixel 8", "screen.observe · job_123"),
      ),
      {}, {}, {}, {}, {}, {}, {},
    )
  }

  @Test fun h_scan() = shot("h-scan") {
    ScanScreen(
      cameraGranted = true,
      error = null,
      onBack = {},
      onPaste = {},
      onEnterCode = {},
      onGrantCamera = {},
      onScanned = {},
      previewSlot = { Box(Modifier.fillMaxSize().background(Color(0xFF111111))) },
    )
  }
}
