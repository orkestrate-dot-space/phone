package space.orkestrate.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.primitives.PhAvatar
import space.orkestrate.phone.ui.primitives.PhAvatarGroup
import space.orkestrate.phone.ui.primitives.PhButton
import space.orkestrate.phone.ui.primitives.PhButtonSize
import space.orkestrate.phone.ui.primitives.PhButtonVariant

/**
 * Small build set showcase: Avatar + Button on OpenCode tokens.
 * Reference: docs/design/tokens.md + docs/app-reference/phone-01/02/05.
 */
@Composable
fun BricksShowcase() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Bricks", style = MaterialTheme.typography.headlineSmall, color = PhText)
      Text(
        "Avatar · Button on OpenCode tokens",
        style = MaterialTheme.typography.bodySmall,
      )

      Text("Avatar", style = MaterialTheme.typography.titleMedium, color = PhText)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        PhAvatar(name = "Opencode Android", size = 24.dp)
        PhAvatar(name = "Opencode Android", size = 32.dp)
        PhAvatar(name = "Opencode Android", size = 48.dp)
        PhAvatar(name = "Design", size = 32.dp)
      }
      PhAvatarGroup(
        members = listOf("Opencode Android", "Kiro Gateway", "Design", "Agent WebMCP", "Greeting"),
        size = 32.dp,
      )

      Text("Button", style = MaterialTheme.typography.titleMedium, color = PhText)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhButton(label = "Copy", onClick = {}, size = PhButtonSize.Small)
        PhButton(label = "Cancel", onClick = {}, variant = PhButtonVariant.Tertiary, size = PhButtonSize.Small)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhButton(label = "Upload", onClick = {}, variant = PhButtonVariant.Secondary)
        PhButton(label = "Delete Project", onClick = {}, variant = PhButtonVariant.Error)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhButton(label = "Saving", onClick = {}, loading = true)
        PhButton(label = "Disabled", onClick = {}, enabled = false)
      }
    }
  }
}

@Preview
@Composable
private fun BricksPreview() {
  PhoneTheme { BricksShowcase() }
}
