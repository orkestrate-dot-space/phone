package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.ui.PhDivider
import space.orkestrate.phone.ui.PhSurfaceInset
import space.orkestrate.phone.ui.PhText

/**
 * One-time code entry with paste support (ui-sdk OTP behavior spec):
 * non-digits are stripped on input AND on paste, slots fill sequentially,
 * focus advances automatically, [onComplete] fires when full.
 * Single hidden field + drawn boxes: paste fills all slots by construction.
 * SMS autofill wires at the screen level later (needs the platform
 * retriever); keyboard is numeric here.
 */
@Composable
fun PhOtpInput(
  code: String,
  onCodeChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  length: Int = 6,
  onComplete: ((String) -> Unit)? = null,
  enabled: Boolean = true,
) {
  val focusRequester = remember { FocusRequester() }
  BasicTextField(
    value = code,
    onValueChange = {
      val digits = it.filter(Char::isDigit).take(length)
      onCodeChange(digits)
      if (digits.length == length) onComplete?.invoke(digits)
    },
    modifier = modifier.focusRequester(focusRequester),
    enabled = enabled,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    singleLine = true,
    cursorBrush = SolidColor(Color.Transparent),
    textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.Transparent),
    decorationBox = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(length) { i ->
          val ch = code.getOrNull(i)?.toString()
          val shape = RoundedCornerShape(12.dp)
          Box(
            modifier = Modifier
              .size(width = 48.dp, height = 56.dp)
              .clip(shape)
              .background(PhSurfaceInset)
              .border(
                1.dp,
                if (i == code.length && enabled) PhDivider else Color.Transparent,
                shape,
              )
              .clickable(enabled = enabled) { focusRequester.requestFocus() },
            contentAlignment = Alignment.Center,
          ) {
            if (ch != null) {
              Text(ch, style = MaterialTheme.typography.titleLarge.copy(color = PhText))
            }
          }
        }
      }
    },
  )
}
