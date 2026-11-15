package space.orkestrate.phone.ui

import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BricksShot {
  @get:Rule
  val roborazziRule = RoborazziRule()

  @Test
  fun bricks() {
    captureRoboImage("bricks.png") {
      PhoneTheme(useCustomFonts = false) { BricksShowcase() }
    }
  }
}
