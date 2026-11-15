package space.orkestrate.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.orkestrate.phone.R

// Tokens sampled from the official app (see docs/design/tokens.md).
val PhBg = Color(0xFF161616)
val PhSurface = Color(0xFF2E2E2E)
val PhSurfaceInset = Color(0xFF1F1F1F)
val PhDivider = Color(0xFF373737)
val PhText = Color(0xFFEDEDED)
val PhMuted = Color(0xFF8A8A8A)
val PhWarm = Color(0xFFF6BF6A)
val PhLink = Color(0xFF9DB2FF)
val PhToggle = Color(0xFF4A6CF7)
val PhOk = Color(0xFF4ADE80)
val PhOnLight = Color(0xFF161616)
val PhErr = Color(0xFFF87171)

val Inter = FontFamily(Font(R.font.inter_family, FontWeight.Normal))
val PlexMono = FontFamily(
  Font(R.font.plex_mono_family, FontWeight.Normal),
  Font(R.font.plex_mono_medium, FontWeight.Medium),
)

// Robolectric cannot load bundled fonts ("Could not load font"), so screenshot
// tests render with platform fonts. Production always uses the custom ones.
private val InterFallback = FontFamily.Default
private val PlexMonoFallback = FontFamily.Monospace

private val PhColorScheme = darkColorScheme(
  background = PhBg,
  surface = PhSurface,
  surfaceVariant = PhSurfaceInset,
  outline = PhDivider,
  onBackground = PhText,
  onSurface = PhText,
  onSurfaceVariant = PhMuted,
  primary = PhText,
  onPrimary = PhOnLight,
  secondary = PhLink,
  tertiary = PhWarm,
)

@Composable
fun PhoneTheme(useCustomFonts: Boolean = true, content: @Composable () -> Unit) {
  val inter = if (useCustomFonts) Inter else InterFallback
  val mono = if (useCustomFonts) PlexMono else PlexMonoFallback
  val typography = Typography(
    headlineSmall = TextStyle(fontFamily = inter, fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = inter, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = inter, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = inter, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = inter, fontSize = 12.sp, color = PhMuted),
    labelMedium = TextStyle(fontFamily = inter, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 12.sp),
  )
  MaterialTheme(
    colorScheme = PhColorScheme,
    typography = typography,
    content = content,
  )
}
