package space.orkestrate.phone.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.orkestrate.phone.R

/** Lucide icons (ISC), stroke 2, round joins; drawn from their published path data. */
object OrkIcons {
  private fun lucide(name: String, vararg d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
      d.forEach {
        addPath(
          addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
          strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
      }
    }.build()

  val camera by lazy {
    lucide(
      "camera",
      "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
      "M15 13a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
    )
  }
  val check by lazy { lucide("check", "M20 6 9 17l-5-5") }
  val arrowLeft by lazy { lucide("arrow-left", "m12 19-7-7 7-7", "M19 12H5") }
  val sliders by lazy {
    lucide(
      "sliders-horizontal", "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
      "M14 2v4", "M8 10v4", "M16 18v4",
    )
  }
  val clipboard by lazy {
    lucide(
      "clipboard",
      "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z",
      "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
    )
  }
  val alert by lazy {
    lucide("circle-alert", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0", "M12 8v4", "M12 16h.01")
  }
  val qrCode by lazy {
    lucide(
      "qr-code",
      "M3 3h5v5H3z", "M16 3h5v5h-5z", "M3 16h5v5H3z",
      "M16 16h2v2h-2z", "M19 19h2v2h-2z", "M16 21h2", "M21 16v2",
      "M12 7v3a2 2 0 0 1-2 2H7", "M12 3h.01", "M12 16v.01", "M3 12h.01", "M21 12h.01",
    )
  }
  val eye by lazy {
    lucide("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
  }
  val folder by lazy {
    lucide("folder", "M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z")
  }
  val bell by lazy {
    lucide("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
  }
  val shield by lazy {
    lucide("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10")
  }
  val pause by lazy {
    lucide("pause", "M10 4H6v16h4V4z", "M18 4h-4v16h4V4z")
  }
  val play by lazy {
    lucide("play", "M6 3l14 9-14 9V3z")
  }
  val terminal by lazy {
    lucide("terminal", "m4 17 6-6-6-6", "M12 19h8")
  }
  val chevronRight by lazy {
    lucide("chevron-right", "m9 18 6-6-6-6")
  }
  val trash by lazy {
    lucide("trash-2", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
  }
}

/** Two-tone heading: the lead in ink, the elaboration at 50%. */
@Composable
fun OrkHeading(lead: String, rest: String?, modifier: Modifier = Modifier, style: TextStyle = OrkType.title) {
  Text(
    buildAnnotatedString {
      append(lead)
      if (rest != null) {
        append("\n")
        withStyle(SpanStyle(color = Ork.caption)) { append(rest) }
      }
    },
    style = style,
    modifier = modifier,
  )
}

/** Mark + "Orkestrate Phone" + optional round icon action on the right. */
@Composable
fun OrkTopBar(trailing: (@Composable () -> Unit)? = null) {
  Row(
    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Ork.gutter),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(painterResource(R.drawable.ork_mark), contentDescription = null, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(8.dp))
    Text("Orkestrate", style = OrkType.brand)
    Spacer(Modifier.width(6.dp))
    Text("Phone", style = OrkType.rowTitle.copy(color = Ork.caption))
    Spacer(Modifier.weight(1f))
    trailing?.invoke()
  }
}

@Composable
fun OrkIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
  Box(
    Modifier.size(40.dp).clip(CircleShape).background(Ork.fillQuiet)
      .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription, tint = Ork.control, modifier = Modifier.size(16.dp))
  }
}

enum class PillKind { Solid, Quiet }

/** Every button is a pill. One Solid per screen. */
@Composable
fun OrkPill(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  kind: PillKind = PillKind.Solid,
  small: Boolean = false,
  wide: Boolean = false,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val bg = if (kind == PillKind.Solid) Ork.ink else Ork.fillQuiet
  val style = if (kind == PillKind.Solid) OrkType.strong else OrkType.control
  val m = modifier
    .then(if (wide) Modifier.fillMaxWidth() else Modifier)
    .height(if (small) 36.dp else 44.dp)
    .clip(CircleShape)
    .background(bg)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    .alpha(if (enabled) 1f else 0.4f)
    .padding(horizontal = if (small) 14.dp else 20.dp)
  Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    if (icon != null) {
      Icon(icon, null, tint = style.color, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
    }
    Text(label, style = if (small) style.copy(fontSize = 14.sp) else style, maxLines = 1)
  }
}

@Composable
fun OrkLink(label: String, onClick: () -> Unit) {
  Text(
    label, style = OrkType.label,
    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

/** Divider-separated row. Dividers over boxes. */
@Composable
fun OrkRow(
  first: Boolean,
  modifier: Modifier = Modifier,
  vertical: Dp = 16.dp,
  lead: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  body: @Composable () -> Unit,
) {
  Column(modifier) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Ork.rule))
    Row(Modifier.fillMaxWidth().padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically) {
      if (lead != null) {
        lead()
        Spacer(Modifier.width(16.dp))
      }
      Column(Modifier.weight(1f)) { body() }
      if (trailing != null) {
        Spacer(Modifier.width(16.dp))
        trailing()
      }
    }
  }
}

@Composable
fun OrkRowText(title: String, sub: String?) {
  Text(title, style = OrkType.rowTitle)
  if (sub != null) Text(sub, style = OrkType.label, modifier = Modifier.padding(top = 2.dp))
}

/** Step number in a ring; a done step is a filled white disc with a check. */
@Composable
fun OrkStepNum(n: Int, done: Boolean) {
  Box(
    Modifier.size(24.dp).clip(CircleShape)
      .then(if (done) Modifier.background(Ork.ink) else Modifier.border(1.dp, Ork.controlEdge, CircleShape)),
    contentAlignment = Alignment.Center,
  ) {
    if (done) Icon(OrkIcons.check, "Done", tint = Ork.canvas, modifier = Modifier.size(14.dp))
    else Text("$n", style = OrkType.label.copy(fontSize = 12.sp, color = Ork.control))
  }
}

enum class DotState { On, Off, Wait }

/** State by shape and words, never hue. */
@Composable
fun OrkDot(state: DotState) {
  val pulse = if (state == DotState.Wait) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    a
  } else 1f
  Box(
    Modifier.size(8.dp).alpha(pulse).clip(CircleShape).then(
      if (state == DotState.Off) Modifier.border(1.5.dp, Ork.caption, CircleShape)
      else Modifier.background(Ork.ink),
    ),
  )
}

@Composable
fun OrkStatusLine(state: DotState, text: String, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    OrkDot(state)
    Spacer(Modifier.width(8.dp))
    Text(text, style = OrkType.label.copy(color = Ork.control))
  }
}

@Composable
fun OrkSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
  Box(
    Modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (checked) Ork.ink else Ork.switchOff)
      .clickable(role = Role.Switch, onClickLabel = label) { onChange(!checked) }
      .padding(3.dp),
    contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) Ork.canvas else Ork.caption))
  }
}

/** 48dp field, 12dp radius, control edge; red edge only with an [OrkErrorLine] below. */
@Composable
fun OrkField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  error: Boolean = false,
  keyboard: KeyboardOptions = KeyboardOptions.Default,
  trailing: (@Composable RowScope.() -> Unit)? = null,
) {
  val shape = RoundedCornerShape(Ork.rControl)
  Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(Ork.fieldFill)
      .border(1.dp, if (error) Ork.destructive else Ork.controlEdge, shape)
      .padding(start = 16.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
      if (value.isEmpty()) Text(placeholder, style = OrkType.mono.copy(color = Ork.caption))
      BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = OrkType.mono, cursorBrush = SolidColor(Ork.ink), keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    trailing?.invoke(this)
  }
}

@Composable
fun OrkErrorLine(text: String, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.Top) {
    Icon(OrkIcons.alert, null, tint = Ork.destructive, modifier = Modifier.padding(top = 2.dp).size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = OrkType.label.copy(color = Ork.ink))
  }
}
