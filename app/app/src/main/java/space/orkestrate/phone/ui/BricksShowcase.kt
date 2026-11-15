package space.orkestrate.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.primitives.PhAvatar
import space.orkestrate.phone.ui.primitives.PhAvatarGroup
import space.orkestrate.phone.ui.primitives.PhButton
import space.orkestrate.phone.ui.primitives.PhButtonSize
import space.orkestrate.phone.ui.primitives.PhButtonVariant
import space.orkestrate.phone.ui.primitives.PhCard
import space.orkestrate.phone.ui.primitives.PhChevron
import space.orkestrate.phone.ui.primitives.PhInput
import space.orkestrate.phone.ui.primitives.PhOptionList
import space.orkestrate.phone.ui.primitives.PhOtpInput
import space.orkestrate.phone.ui.primitives.PhRow
import space.orkestrate.phone.ui.primitives.PhSearchInput
import space.orkestrate.phone.ui.primitives.PhSheetLayout
import space.orkestrate.phone.ui.primitives.PhUnreadDot

/**
 * Full brick set on OpenCode tokens.
 * Reference: docs/design/tokens.md + docs/app-reference/phone-01/02/05.
 * Sections double as Roborazzi baselines (see BricksFullShot).
 */
@Composable
fun BricksShowcase() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Bricks", style = MaterialTheme.typography.headlineSmall, color = PhText)
      AvatarBricks()
      ButtonBricks()
      InputBricks()
      CardBricks()
      SheetBricks()
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
fun AvatarBricks() {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
  }
}

@Composable
fun ButtonBricks() {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
fun InputBricks() {
  var name by remember { mutableStateOf("phone") }
  var query by remember { mutableStateOf("Opencode") }
  var code by remember { mutableStateOf("4291") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Input", style = MaterialTheme.typography.titleMedium, color = PhText)
    PhInput(
      value = name,
      onValueChange = { name = it },
      label = "Device Name",
      placeholder = "phone",
      helper = "How agents call this phone.",
      validateOnBlur = { if (it.isBlank()) "Device name is required." else null },
      onSubmit = {},
    )
    PhSearchInput(value = query, onValueChange = { query = it }, placeholder = "Search sessions")
    PhOtpInput(code = code, onCodeChange = { code = it })
  }
}

@Composable
fun CardBricks() {
  var auto by remember { mutableStateOf(true) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Card", style = MaterialTheme.typography.titleMedium, color = PhText)
    PhCard {
      PhRow(
        title = "Relay",
        subtitle = "https://6ace…xyz",
        first = true,
        icon = { PhAvatar(name = "Relay", size = 36.dp) },
        trailing = { PhChevron() },
        onClick = {},
      )
      PhRow(
        title = "This device",
        subtitle = "dev_qT_dyBVkKbkw",
        icon = { PhAvatar(name = "This device", size = 36.dp) },
        trailing = { PhUnreadDot() },
        onClick = {},
      )
      PhRow(
        title = "Auto-approve",
        subtitle = "Recon commands run without a tap",
        icon = { PhAvatar(name = "Auto-approve", size = 36.dp) },
        trailing = {
          Switch(
            checked = auto,
            onCheckedChange = { auto = it },
            colors = SwitchDefaults.colors(
              checkedTrackColor = PhToggle,
              checkedThumbColor = PhText,
            ),
          )
        },
      )
    }
  }
}

@Composable
fun SheetBricks() {
  var selected by remember { mutableStateOf("Opencode Android") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Sheet", style = MaterialTheme.typography.titleMedium, color = PhText)
    PhSheetLayout(
      title = "Choose session",
      subtitle = "Applies to the next run.",
      actions = {
        PhButton(label = "Cancel", onClick = {}, variant = PhButtonVariant.Tertiary)
        PhButton(label = "Open Session", onClick = {})
      },
    ) {
      PhOptionList(
        options = listOf("Opencode Android", "Kiro Gateway", "Design", "Agent WebMCP"),
        selected = selected,
        onSelect = { selected = it },
        showSearchThreshold = 3,
      )
    }
  }
}

@Preview
@Composable
private fun BricksPreview() {
  PhoneTheme { BricksShowcase() }
}
