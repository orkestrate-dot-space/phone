package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.PhDivider
import space.orkestrate.phone.ui.PhLink
import space.orkestrate.phone.ui.PhMuted
import space.orkestrate.phone.ui.PhSurface
import space.orkestrate.phone.ui.PhText

/**
 * Grouped card + rows. Matches the official Settings ref
 * (docs/app-reference/phone-05-settings.png): borderless surface card,
 * 20dp radius, dividers inside the card, 16dp row padding, title
 * 16/Medium + secondary line, right slot (chevron / dot / control).
 */
@Composable
fun PhCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .background(PhSurface)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    content = content,
  )
}

@Composable
fun PhRow(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  icon: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  first: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  Column(modifier = modifier) {
    if (!first) HorizontalDivider(color = PhDivider)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = onClick != null) { onClick?.invoke() }
        .padding(vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon?.let {
        it()
        Spacer(Modifier.width(12.dp))
      }
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(color = PhText))
        subtitle?.let {
          Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = PhMuted))
        }
      }
      trailing?.let {
        Spacer(Modifier.width(12.dp))
        it()
      }
    }
  }
}

/** Right-slot helpers. */
@Composable
fun PhChevron() {
  Text("›", style = MaterialTheme.typography.titleLarge.copy(color = PhMuted))
}

@Composable
fun PhUnreadDot() {
  Box(
    Modifier
      .size(8.dp)
      .clip(CircleShape)
      .background(PhLink),
  )
}
