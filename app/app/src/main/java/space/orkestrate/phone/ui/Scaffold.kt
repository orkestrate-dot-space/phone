package space.orkestrate.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val swatches = listOf(
  "bg" to PhBg, "surface" to PhSurface, "inset" to PhSurfaceInset,
  "divider" to PhDivider, "text" to PhText, "muted" to PhMuted,
  "warm" to PhWarm, "link" to PhLink,
)

/** Phase 0 scaffold: theme + token swatches + heartbeat card mock. No app logic. */
@Composable
fun ScaffoldScreen() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Connect your agent (scaffold)", style = MaterialTheme.typography.titleLarge)
      Text(
        "Theme + tokens only. Screens come in Phase 1 after approval.",
        style = MaterialTheme.typography.bodySmall,
      )
      swatches.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Box(
                modifier = Modifier
                  .width(72.dp)
                  .height(48.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(color),
              )
              Text(name, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text("dev_qT_dyBVkKbkw", style = MaterialTheme.typography.titleMedium)
      Text(
        "relay · app v36",
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Preview
@Composable
private fun ScaffoldPreview() {
  PhoneTheme { ScaffoldScreen() }
}
