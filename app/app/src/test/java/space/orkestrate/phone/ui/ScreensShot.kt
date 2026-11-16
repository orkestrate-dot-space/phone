package space.orkestrate.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screens A–E against the brand mock. Record: recordRoborazziDebug --tests *ScreensShot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xxhdpi")
class ScreensShot {
  @get:Rule val rule = RoborazziRule()

  private fun shot(name: String, content: @Composable () -> Unit) =
    captureRoboImage("screens/$name.png") { OrkTheme(useCustomFonts = false) { content() } }

  private val home = HomeState(
    name = "Pixel 8", link = DotState.On, linkText = "Relay and shell connected",
    recent = listOf(
      RecentJob("battery.get", "2 min ago"),
      RecentJob("photo.capture", "14 min ago"),
      RecentJob("exec.run", "1 h ago"),
    ),
    autoApprove = true, selfUpdate = false,
  )

  @Test fun a_unpaired() = shot("a-unpaired") { UnpairedScreen({}, {}) }

  @Test fun b_justPaired() = shot("b-just-paired") {
    JustPairedScreen(SetupState("6ace59c9…opentunnel.xyz", true, false, false), {}, {}, {})
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

  @Test fun d_home() = shot("d-home") { HomeScreen(home, {}, {}, {}) }

  @Test fun e_approval() = shot("e-approval") {
    Box(Modifier.fillMaxSize()) {
      HomeScreen(home, {}, {}, {})
      ApprovalSheet(
        ApprovalCard(
          "j1", "Your agent wants a photo.", "Nothing is captured until you take it.",
          listOf("Tool" to "photo.capture", "Size" to "1280 px, JPEG"), "Open camera", true,
        ),
        {}, {},
      )
    }
  }
}
