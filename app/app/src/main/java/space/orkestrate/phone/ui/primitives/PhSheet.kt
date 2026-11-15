package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.PhLink
import space.orkestrate.phone.ui.PhMuted
import space.orkestrate.phone.ui.PhSurface
import space.orkestrate.phone.ui.PhText

/**
 * Geist Modal port for mobile: on a small viewport the modal IS a bottom
 * sheet (Geist mobile-sheet behavior). Rules kept: title is Title Case and
 * never a question; Cancel stays literally "Cancel"; primary is Verb + Noun
 * and pairs 1:1 with its confirmation; destructive sheets gate on typed
 * confirmation at the call site, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhSheet(
  title: String,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  primaryLabel: String? = null,
  onPrimary: (() -> Unit)? = null,
  primaryEnabled: Boolean = true,
  secondaryLabel: String = "Cancel",
  content: @Composable ColumnScope.() -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier,
    containerColor = PhSurface,
    contentColor = PhText,
  ) {
    PhSheetLayout(
      title = title,
      subtitle = subtitle,
      actions = {
        PhButton(label = secondaryLabel, onClick = onDismiss, variant = PhButtonVariant.Tertiary)
        if (primaryLabel != null && onPrimary != null) {
          PhButton(label = primaryLabel, onClick = onPrimary, enabled = primaryEnabled)
        }
      },
      content = content,
    )
  }
}

/** Sheet body without the window — used for static screenshot baselines. */
@Composable
fun PhSheetLayout(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier = modifier.padding(horizontal = 16.dp)) {
    Text(title, style = MaterialTheme.typography.titleLarge.copy(color = PhText))
    subtitle?.let {
      Spacer(Modifier.height(4.dp))
      Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = PhMuted))
    }
    Spacer(Modifier.height(12.dp))
    content()
    actions?.let {
      Spacer(Modifier.height(16.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) { it() }
      Spacer(Modifier.height(16.dp))
    }
  }
}

/** Searchable checkmark list. Search appears for long lists (Geist pattern). */
@Composable
fun PhOptionList(
  options: List<String>,
  selected: String?,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
  showSearchThreshold: Int = 6,
) {
  var query by remember { mutableStateOf("") }
  val filtered = remember(options, query) {
    if (query.isBlank()) options
    else options.filter { it.contains(query, ignoreCase = true) }
  }
  Column(modifier = modifier) {
    if (options.size > showSearchThreshold) {
      PhSearchInput(
        value = query,
        onValueChange = { query = it },
        placeholder = "Search options",
      )
      Spacer(Modifier.height(8.dp))
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
      filtered.forEachIndexed { i, opt ->
        PhRow(
          title = opt,
          first = i == 0,
          trailing = {
            if (opt == selected) {
              Text("✓", style = MaterialTheme.typography.titleMedium.copy(color = PhLink))
            }
          },
          onClick = { onSelect(opt) },
        )
      }
    }
  }
}

/** Option sheet: search + checkmarked selection, Cancel dismisses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhOptionSheet(
  title: String,
  options: List<String>,
  selected: String?,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
) {
  PhSheet(
    title = title,
    subtitle = subtitle,
    onDismiss = onDismiss,
    modifier = modifier,
  ) {
    PhOptionList(options = options, selected = selected, onSelect = onSelect)
  }
}
