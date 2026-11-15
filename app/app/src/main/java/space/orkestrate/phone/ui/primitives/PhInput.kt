package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.PhDivider
import space.orkestrate.phone.ui.PhErr
import space.orkestrate.phone.ui.PhMuted
import space.orkestrate.phone.ui.PhSurfaceInset
import space.orkestrate.phone.ui.PhText

/**
 * Geist Input port on OpenCode tokens. Fully rounded, inset fill.
 * Rules (Geist best practices): Title Case label, placeholder shows an
 * EXAMPLE value (never an instruction), validate on blur via
 * [validateOnBlur], trim on submit via [onSubmit].
 */
@Composable
fun PhInput(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  helper: String? = null,
  error: String? = null,
  validateOnBlur: ((String) -> String?)? = null,
  onSubmit: ((String) -> Unit)? = null,
  prefix: (@Composable () -> Unit)? = null,
  suffix: (@Composable () -> Unit)? = null,
  enabled: Boolean = true,
  singleLine: Boolean = true,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  var blurError by remember { mutableStateOf<String?>(null) }
  val shownError = error ?: blurError
  OutlinedTextField(
    value = value,
    onValueChange = {
      onValueChange(it)
      if (blurError != null) blurError = null
    },
    modifier = modifier.onFocusChanged {
      if (!it.isFocused && validateOnBlur != null) blurError = validateOnBlur(value)
    },
    enabled = enabled,
    label = label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
    placeholder = placeholder?.let {
      { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = PhMuted)) }
    },
    prefix = prefix,
    suffix = suffix,
    isError = shownError != null,
    supportingText = (shownError ?: helper)?.let { text ->
      {
        Text(
          text,
          style = MaterialTheme.typography.bodySmall.copy(
            color = if (shownError != null) PhErr else PhMuted,
          ),
        )
      }
    },
    singleLine = singleLine,
    shape = if (singleLine) RoundedCornerShape(percent = 50) else RoundedCornerShape(12.dp),
    textStyle = MaterialTheme.typography.bodyMedium.copy(color = PhText),
    keyboardOptions = keyboardOptions,
    keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke(value.trim()) }),
    visualTransformation = visualTransformation,
    colors = OutlinedTextFieldDefaults.colors(
      focusedContainerColor = PhSurfaceInset,
      unfocusedContainerColor = PhSurfaceInset,
      disabledContainerColor = PhSurfaceInset.copy(alpha = 0.4f),
      focusedBorderColor = PhDivider,
      unfocusedBorderColor = Color.Transparent,
      errorBorderColor = PhErr,
      cursorColor = PhText,
      focusedLabelColor = PhMuted,
      unfocusedLabelColor = PhMuted,
    ),
  )
}

/**
 * Inline search. Placeholder names the SCOPE ("Search projects"); the clear
 * affordance replaces the Esc-clears behavior of the web original.
 * Icon slot reserved for the Material Symbols decision — no emoji, no tofu.
 */
@Composable
fun PhSearchInput(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "Search",
  enabled: Boolean = true,
) {
  PhInput(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    placeholder = placeholder,
    singleLine = true,
    enabled = enabled,
    suffix = if (value.isNotEmpty()) {
      { TextButton(onClick = { onValueChange("") }) { Text("×", color = PhMuted) } }
    } else {
      null
    },
  )
}
