package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.PhDivider
import space.orkestrate.phone.ui.PhMuted
import space.orkestrate.phone.ui.PhOnLight
import space.orkestrate.phone.ui.PhSurface
import space.orkestrate.phone.ui.PhText
import space.orkestrate.phone.ui.PhWarm

/** Geist Button port on OpenCode tokens. Title Case label, Verb + Noun. */
enum class PhButtonVariant { Primary, Secondary, Tertiary, Error, Warning }
enum class PhButtonSize(val height: Dp) {
  Small(32.dp),
  Medium(40.dp),
  Large(48.dp),
}

private val PhPrimaryFill = Color(0xFFE6E6E6) // tokens.md primary-button
private val PhErrorFill = Color(0xFFF87171) // colors.xml ph_bad (official red)

@Composable
fun PhButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  variant: PhButtonVariant = PhButtonVariant.Primary,
  size: PhButtonSize = PhButtonSize.Medium,
  enabled: Boolean = true,
  loading: Boolean = false,
  prefix: (@Composable () -> Unit)? = null,
  suffix: (@Composable () -> Unit)? = null,
) {
  val colors = when (variant) {
    PhButtonVariant.Primary -> ButtonDefaults.buttonColors(
      containerColor = PhPrimaryFill, contentColor = PhOnLight,
      disabledContainerColor = PhPrimaryFill.copy(alpha = 0.4f),
      disabledContentColor = PhOnLight.copy(alpha = 0.5f),
    )
    PhButtonVariant.Secondary -> ButtonDefaults.buttonColors(
      containerColor = PhSurface, contentColor = PhText,
      disabledContainerColor = PhSurface.copy(alpha = 0.4f),
      disabledContentColor = PhMuted,
    )
    PhButtonVariant.Tertiary -> ButtonDefaults.buttonColors(
      containerColor = Color.Transparent, contentColor = PhText,
      disabledContainerColor = Color.Transparent, disabledContentColor = PhMuted,
    )
    PhButtonVariant.Error -> ButtonDefaults.buttonColors(
      containerColor = PhErrorFill, contentColor = PhOnLight,
      disabledContainerColor = PhErrorFill.copy(alpha = 0.4f),
      disabledContentColor = PhOnLight.copy(alpha = 0.5f),
    )
    PhButtonVariant.Warning -> ButtonDefaults.buttonColors(
      containerColor = PhWarm, contentColor = PhOnLight,
      disabledContainerColor = PhWarm.copy(alpha = 0.4f),
      disabledContentColor = PhOnLight.copy(alpha = 0.5f),
    )
  }
  val border: BorderStroke? = when (variant) {
    PhButtonVariant.Secondary -> BorderStroke(1.dp, PhDivider)
    else -> null
  }
  Button(
    onClick = onClick,
    modifier = modifier.height(size.height),
    enabled = enabled && !loading,
    colors = colors,
    border = border,
    shape = RoundedCornerShape(percent = 50),
    contentPadding = when (size) {
      PhButtonSize.Small -> PaddingValues(horizontal = 14.dp, vertical = 0.dp)
      PhButtonSize.Medium -> PaddingValues(horizontal = 18.dp, vertical = 0.dp)
      PhButtonSize.Large -> PaddingValues(horizontal = 22.dp, vertical = 0.dp)
    },
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 2.dp,
        color = colors.contentColor,
      )
      Spacer(Modifier.width(8.dp))
    } else if (prefix != null) {
      prefix()
      Spacer(Modifier.width(8.dp))
    }
    Text(
      text = label,
      style = when (size) {
        PhButtonSize.Small -> MaterialTheme.typography.bodySmall
        PhButtonSize.Medium -> MaterialTheme.typography.bodyMedium
        PhButtonSize.Large -> MaterialTheme.typography.titleMedium
      },
    )
    if (suffix != null) {
      Spacer(Modifier.width(8.dp))
      suffix()
    }
  }
}

/**
 * Icon-only button. Geist requires svgOnly + aria-label; here
 * [contentDescription] is mandatory for the same reason (TalkBack).
 */
@Composable
fun PhIconButton(
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  circle: Boolean = true,
  icon: @Composable RowScope.() -> Unit,
) {
  val shape: Shape = if (circle) CircleShape else RoundedCornerShape(10.dp)
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = ButtonDefaults.buttonColors(
      containerColor = PhSurface, contentColor = PhText,
      disabledContainerColor = PhSurface.copy(alpha = 0.4f),
      disabledContentColor = PhMuted,
    ),
    border = BorderStroke(1.dp, PhDivider),
    contentPadding = PaddingValues(0.dp),
  ) {
    icon()
  }
}
