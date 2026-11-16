package space.orkestrate.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import space.orkestrate.phone.R

/**
 * Orkestrate brand tokens. Values come only from orkestrate/website/public/design.md
 * (via /tmp/opencode/design/brand/brand.css, verified 2026-11-16). Pure black canvas,
 * white at fixed opacities, no accent hue. The one colour is [Ork.destructive], always
 * paired with an icon and words.
 */
object Ork {
  val canvas = Color(0xFF000000)
  val raised = Color(0xFF0C0C0C)
  val ink = Color.White
  val control = Color.White.copy(alpha = 0.85f)
  val icon = Color.White.copy(alpha = 0.7f)
  val body = Color.White.copy(alpha = 0.6f)
  val caption = Color.White.copy(alpha = 0.5f)
  val fillQuiet = Color.White.copy(alpha = 0.07f)
  val fieldFill = Color.White.copy(alpha = 0.04f)
  val segOn = Color.White.copy(alpha = 0.1f)
  val switchOff = Color.White.copy(alpha = 0.12f)
  val rule = Color.White.copy(alpha = 0.08f)
  val windowEdge = Color.White.copy(alpha = 0.09f)
  val controlEdge = Color.White.copy(alpha = 0.15f)
  val grab = Color.White.copy(alpha = 0.2f)
  val scrim = Color.Black.copy(alpha = 0.6f)
  val destructive = Color(0xFFE2162A)

  val rPanel = 20.dp
  val rTile = 16.dp
  val rControl = 12.dp
  val gutter = 24.dp
}

class OrkFonts(val sans: FontFamily, val sansMedium: FontFamily, val sansStrong: FontFamily, val mono: FontFamily)

@OptIn(ExperimentalTextApi::class)
private fun interAt(weight: Int) = FontFamily(
  Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
  ),
)

private val brandFonts by lazy {
  OrkFonts(
    sans = interAt(400),
    sansMedium = interAt(500),
    sansStrong = interAt(550),
    mono = FontFamily(Font(R.font.geist_mono, FontWeight.Normal)),
  )
}

// Robolectric cannot load bundled fonts, so screenshot tests use platform fonts.
private val platformFonts = OrkFonts(
  FontFamily.Default, FontFamily.Default, FontFamily.Default, FontFamily.Monospace,
)

val LocalOrkFonts = staticCompositionLocalOf { platformFonts }

/** Type roles, mobile scale from the app mock (design.md section 3). */
object OrkType {
  val title @Composable get() = TextStyle(
    fontFamily = LocalOrkFonts.current.sans, fontSize = 28.sp, lineHeight = 34.sp,
    letterSpacing = (-0.02).em, color = Ork.ink,
  )
  val sheetTitle @Composable get() = title.copy(fontSize = 24.sp, lineHeight = 29.sp)
  val rowTitle @Composable get() = TextStyle(
    fontFamily = LocalOrkFonts.current.sans, fontSize = 16.sp, lineHeight = 22.sp, color = Ork.ink,
  )
  val brand @Composable get() = rowTitle.copy(
    fontFamily = LocalOrkFonts.current.sansMedium, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em,
  )
  val label @Composable get() = TextStyle(
    fontFamily = LocalOrkFonts.current.sans, fontSize = 14.sp, lineHeight = 20.sp, color = Ork.caption,
  )
  val control @Composable get() = TextStyle(
    fontFamily = LocalOrkFonts.current.sans, fontSize = 15.sp, lineHeight = 20.sp, color = Ork.control,
  )
  val strong @Composable get() = control.copy(fontFamily = LocalOrkFonts.current.sansStrong, fontWeight = FontWeight(550), color = Color.Black)
  val mono @Composable get() = TextStyle(
    fontFamily = LocalOrkFonts.current.mono, fontSize = 14.sp, lineHeight = 20.sp, color = Ork.ink,
  )
}

@Composable
fun OrkTheme(useCustomFonts: Boolean = true, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalOrkFonts provides if (useCustomFonts) brandFonts else platformFonts) {
    MaterialTheme(
      colorScheme = darkColorScheme(
        background = Ork.canvas, surface = Ork.canvas, onBackground = Ork.ink, onSurface = Ork.ink,
        primary = Ork.ink, onPrimary = Ork.canvas, error = Ork.destructive,
      ),
      content = content,
    )
  }
}
