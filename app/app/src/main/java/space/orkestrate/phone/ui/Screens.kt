package space.orkestrate.phone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.R

// Screens A–E from /tmp/opencode/design/brand/app.src.html. Stateless: state in, events out.

@Composable
private fun Screen(
  topBar: @Composable () -> Unit,
  foot: (@Composable () -> Unit)? = null,
  body: @Composable ColumnScope.() -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Ork.canvas)) {
    topBar()
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState())
        .padding(start = Ork.gutter, end = Ork.gutter, top = 12.dp),
    ) { body() }
    if (foot != null) {
      Column(
        Modifier.fillMaxWidth().padding(start = Ork.gutter, end = Ork.gutter, top = 16.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) { foot() }
    }
  }
}

/** A · first open, unpaired. */
@Composable
fun UnpairedScreen(onOpenCamera: () -> Unit, onEnterCode: () -> Unit) {
  Screen(
    topBar = { OrkTopBar() },
    foot = {
      OrkPill("Open camera", onOpenCamera, wide = true, icon = OrkIcons.camera)
      OrkLink("Enter code instead", onEnterCode)
    },
  ) {
    OrkHeading("Pair with your agent.", "It takes one sentence and one scan.")
    Image(
      painterResource(R.drawable.ork_mobile), contentDescription = null,
      modifier = Modifier.padding(top = 8.dp).size(250.dp).align(Alignment.CenterHorizontally),
    )
    OrkRow(first = true, lead = { OrkStepNum(1, false) }) {
      OrkRowText("On your computer, tell your agent", "Set up my phone with mobile.orkestrate.space/phone")
    }
    OrkRow(first = false, lead = { OrkStepNum(2, false) }) {
      OrkRowText("Scan the code it shows", "Use this phone's camera. The app opens and pairs.")
    }
  }
}

data class SetupState(
  val relayHost: String,
  val notifications: Boolean,
  val background: Boolean,
  val tried: Boolean,
) {
  val complete get() = notifications && background && tried
}

/** B · just paired: the device grants that keep it working, then a live try-it. */
@Composable
fun JustPairedScreen(
  s: SetupState,
  onAllowNotifications: () -> Unit,
  onAllowBackground: () -> Unit,
  onFinish: () -> Unit,
) {
  Screen(
    topBar = { OrkTopBar() },
    foot = {
      if (s.complete) OrkPill("Done", onFinish, wide = true)
      else OrkLink("Skip for now", onFinish)
    },
  ) {
    OrkHeading("Paired.", "Two settings keep it working.")
    Spacer(Modifier.height(28.dp))
    OrkRow(first = true, lead = { OrkStepNum(1, true) }) {
      OrkRowText("Paired with your agent", "Relay at ${s.relayHost}")
    }
    OrkRow(
      first = false, lead = { OrkStepNum(2, s.notifications) },
      trailing = if (s.notifications) null else ({ OrkPill("Allow", onAllowNotifications, kind = PillKind.Quiet, small = true) }),
    ) { OrkRowText("Notifications", "Approval cards arrive here.") }
    OrkRow(
      first = false, lead = { OrkStepNum(3, s.background) },
      trailing = if (s.background) null else ({ OrkPill("Allow", onAllowBackground, kind = PillKind.Quiet, small = true) }),
    ) { OrkRowText("Background", "Keeps the link up when the screen is off.") }
    OrkRow(first = false, lead = { OrkStepNum(4, s.tried) }) {
      Text("Try it", style = OrkType.rowTitle)
      if (s.tried) {
        Text("Your agent's first request arrived.", style = OrkType.label, modifier = Modifier.padding(top = 2.dp))
      } else {
        OrkStatusLine(DotState.Wait, "Waiting for your agent's first request…", Modifier.padding(top = 4.dp))
      }
    }
  }
}

enum class Probe { Idle, Checking, Answered, NoAnswer }

data class EnterCodeState(
  val relay: String,
  val code: String,
  val probe: Probe,
  val error: String?,
  val pending: Boolean,
)

/** C · manual path. The relay is checked before Connect; the one red is destructive. */
@Composable
fun EnterCodeScreen(
  s: EnterCodeState,
  onBack: () -> Unit,
  onRelay: (String) -> Unit,
  onCode: (String) -> Unit,
  onPaste: () -> Unit,
  onConnect: () -> Unit,
) {
  Screen(
    topBar = {
      Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Ork.gutter), verticalAlignment = Alignment.CenterVertically) {
        OrkIconButton(OrkIcons.arrowLeft, "Back", onBack)
      }
    },
    foot = {
      OrkPill(
        if (s.pending) "Connecting…" else "Connect", onConnect, wide = true,
        enabled = !s.pending && s.relay.isNotBlank() && s.code.isNotBlank(),
      )
    },
  ) {
    OrkHeading("Enter the code.", "Your agent shows it under the scan code.")
    Text("Relay address", style = OrkType.label, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    OrkField(
      s.relay, onRelay, "6ace59c9…opentunnel.xyz",
      keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    val probeText = when (s.probe) {
      Probe.Idle -> null
      Probe.Checking -> DotState.Wait to "Checking the relay…"
      Probe.Answered -> DotState.On to "The relay answered."
      Probe.NoAnswer -> DotState.Off to "The relay hasn't answered. This updates when it does."
    }
    if (probeText != null) OrkStatusLine(probeText.first, probeText.second, Modifier.padding(top = 10.dp))
    Text("Pairing code", style = OrkType.label, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    OrkField(
      s.code, onCode, "XXXX-XXXX", error = s.error != null,
      keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    ) {
      OrkPill("Paste", onPaste, kind = PillKind.Quiet, small = true, icon = OrkIcons.clipboard, modifier = Modifier.height(32.dp))
    }
    if (s.error != null) OrkErrorLine(s.error, Modifier.padding(top = 10.dp))
  }
}

data class RecentJob(val tool: String, val detail: String)

data class HomeState(
  val name: String,
  val link: DotState,
  val linkText: String,
  val recent: List<RecentJob>,
  val autoApprove: Boolean,
  val selfUpdate: Boolean,
)

/** D · home, paired. */
@Composable
fun HomeScreen(
  s: HomeState,
  onAutoApprove: (Boolean) -> Unit,
  onSelfUpdate: (Boolean) -> Unit,
  onSettings: () -> Unit,
) {
  Screen(topBar = { OrkTopBar { OrkIconButton(OrkIcons.sliders, "Settings", onSettings) } }) {
    OrkHeading(
      when (s.link) { DotState.On -> "Connected."; DotState.Wait -> "Connecting."; DotState.Off -> "Offline." },
      "Agents call it ${s.name}.",
    )
    OrkStatusLine(s.link, s.linkText, Modifier.padding(top = 10.dp))
    Text("Recent", style = OrkType.label, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
    if (s.recent.isEmpty()) {
      Text("No requests yet. They show up here as your agent makes them.", style = OrkType.label, modifier = Modifier.padding(vertical = 12.dp))
    }
    s.recent.forEachIndexed { i, j ->
      OrkRow(first = i == 0, vertical = 12.dp) {
        Text(j.tool, style = OrkType.mono)
        Text(j.detail, style = OrkType.label, modifier = Modifier.padding(top = 2.dp))
      }
    }
    Text("Approvals", style = OrkType.label, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
    OrkRow(first = true, trailing = { OrkSwitch(s.autoApprove, "Auto-approve", onAutoApprove) }) {
      OrkRowText("Auto-approve", "Listed commands run without a tap.")
    }
    OrkRow(first = false, trailing = { OrkSwitch(s.selfUpdate, "Agent self-update", onSelfUpdate) }) {
      OrkRowText("Agent self-update", if (s.selfUpdate) "The agent ships updates without a tap." else "Updates still need your tap.")
    }
    Spacer(Modifier.height(24.dp))
  }
}

data class ApprovalCard(
  val jobId: String,
  val title: String,
  val subtitle: String,
  val rows: List<Pair<String, String>>,
  val primary: String,
  val cameraIcon: Boolean,
)

/**
 * E · approval card over the current screen. Only the two buttons settle it: scrim taps and
 * back do nothing, so a stray touch can neither approve nor reject.
 */
@Composable
fun BoxScope.ApprovalSheet(card: ApprovalCard, onApprove: () -> Unit, onReject: () -> Unit) {
  Box(
    Modifier.matchParentSize().background(Ork.scrim)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
  )
  val shape = RoundedCornerShape(topStart = Ork.rPanel, topEnd = Ork.rPanel)
  Column(
    Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(shape).background(Ork.raised)
      .border(1.dp, Ork.windowEdge, shape)
      .padding(start = Ork.gutter, end = Ork.gutter, top = 12.dp, bottom = 28.dp),
  ) {
    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(Ork.grab))
    Spacer(Modifier.height(20.dp))
    OrkHeading(card.title, card.subtitle, style = OrkType.sheetTitle)
    Spacer(Modifier.height(20.dp))
    card.rows.forEach { (k, v) ->
      Box(Modifier.fillMaxWidth().height(1.dp).background(Ork.rule))
      Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(k, style = OrkType.label)
        Spacer(Modifier.width(16.dp))
        Text(
          v, style = if (k == "Tool" || k == "Command") OrkType.mono else OrkType.label.copy(color = Ork.ink),
          textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
      }
    }
    Spacer(Modifier.height(20.dp))
    OrkPill(card.primary, onApprove, wide = true, icon = if (card.cameraIcon) OrkIcons.camera else null)
    Spacer(Modifier.height(10.dp))
    OrkPill("Reject", onReject, kind = PillKind.Quiet, wide = true)
  }
}
