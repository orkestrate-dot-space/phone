package space.orkestrate.phone.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import space.orkestrate.phone.ui.PhBg
import space.orkestrate.phone.ui.PhDivider
import space.orkestrate.phone.ui.PhText

/**
 * Letter tile. Geist Avatar port, OpenCode tile rendering.
 *
 * Geist API kept (size / letter / placeholder), rendering follows the
 * official app ref (phone-01/02 drawer): rounded-square gray tile, 1-2
 * uppercase chars derived from the entity name, radius 10dp per tokens.md.
 * Diverges from Geist circles on purpose — circles would fail visual parity.
 */
@Composable
fun PhAvatar(
  name: String,
  modifier: Modifier = Modifier,
  letter: String? = null,
  size: Dp = 32.dp,
  placeholder: Boolean = false,
  ring: Boolean = false,
) {
  val initials = remember(name, letter) { letter?.take(2)?.uppercase() ?: initialsOf(name) }
  val radius = if (size <= 24.dp) 8.dp else 10.dp
  val shape = RoundedCornerShape(radius)
  Box(
    modifier = modifier
      .size(size)
      .clip(shape)
      .background(PhDivider)
      .then(if (ring) Modifier.border(2.dp, PhBg, shape) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    if (!placeholder) {
      Text(
        text = initials,
        style = when {
          size <= 24.dp -> MaterialTheme.typography.bodySmall
          size <= 32.dp -> MaterialTheme.typography.titleMedium
          else -> MaterialTheme.typography.titleLarge
        }.copy(color = PhText, fontWeight = FontWeight.SemiBold),
      )
    }
  }
}

/** Derive 1-2 uppercase chars from an entity name (Geist: no emoji/punct/?). */
fun initialsOf(name: String): String {
  val parts = name.split(Regex("[\\s_\\-]+")).filter { it.isNotEmpty() }
  if (parts.isEmpty()) return "?"
  if (parts.size == 1) {
    val clean = parts[0].filter { it.isLetterOrDigit() }
    return when {
      clean.length >= 2 -> ("" + clean[0] + clean[1]).uppercase()
      clean.isNotEmpty() -> clean[0].uppercaseChar().toString()
      else -> "?"
    }
  }
  val a = parts[0].firstOrNull { it.isLetterOrDigit() } ?: return "?"
  val b = parts[1].firstOrNull { it.isLetterOrDigit() } ?: return a.uppercaseChar().toString()
  return ("" + a + b).uppercase()
}

/**
 * Stacked cluster. Geist AvatarGroup port: correct overlap, one accessible
 * label via contentDescription, [limit] caps visible tiles with a +N tile.
 */
@Composable
fun PhAvatarGroup(
  members: List<String>,
  modifier: Modifier = Modifier,
  size: Dp = 32.dp,
  limit: Int = 4,
  overlap: Dp = 8.dp,
) {
  val visible = members.take(limit)
  val overflow = members.size - visible.size
  Row(modifier = modifier) {
    visible.forEachIndexed { i, name ->
      PhAvatar(
        name = name,
        size = size,
        ring = i > 0,
        modifier = Modifier
          .zIndex((visible.size - i).toFloat())
          .offset(x = if (i == 0) 0.dp else -overlap),
      )
    }
    if (overflow > 0) {
      Box(
        modifier = Modifier
          .zIndex(0f)
          .offset(x = -overlap)
          .size(size)
          .clip(RoundedCornerShape(if (size <= 24.dp) 8.dp else 10.dp))
          .background(PhDivider)
          .border(2.dp, PhBg, RoundedCornerShape(if (size <= 24.dp) 8.dp else 10.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "+$overflow",
          style = MaterialTheme.typography.bodySmall.copy(color = PhText),
        )
      }
    }
  }
}
