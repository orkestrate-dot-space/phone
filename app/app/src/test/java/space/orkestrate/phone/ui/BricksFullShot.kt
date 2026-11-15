package space.orkestrate.phone.ui

import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Full brick baselines: record with recordRoborazziDebug, check with verify. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BricksFullShot {
  @get:Rule
  val roborazziRule = RoborazziRule()

  @Test
  fun inputs() {
    captureRoboImage("bricks-inputs.png") {
      PhoneTheme(useCustomFonts = false) { InputBricks() }
    }
  }

  @Test
  fun cards() {
    captureRoboImage("bricks-cards.png") {
      PhoneTheme(useCustomFonts = false) { CardBricks() }
    }
  }

  @Test
  fun sheet() {
    captureRoboImage("bricks-sheet.png") {
      PhoneTheme(useCustomFonts = false) { SheetBricks() }
    }
  }
}
