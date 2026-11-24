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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.orkestrate.phone.R

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
fun UnpairedScreen(onScanQr: () -> Unit, onEnterCode: () -> Unit) {
  Screen(
    topBar = { OrkTopBar() },
    foot = {
      OrkPill("Scan QR code", onScanQr, wide = true, icon = OrkIcons.qrCode)
      OrkLink("Enter code instead", onEnterCode)
    },
  ) {
    OrkHeading("Pair with your agent.", "One scan connects this phone.")
    Image(
      painterResource(R.drawable.ork_mobile), contentDescription = null,
      modifier = Modifier.padding(top = 8.dp).size(230.dp).align(Alignment.CenterHorizontally),
    )
    OrkRow(first = true, lead = { OrkStepNum(1, false) }) {
      OrkRowText("On your computer, tell your agent", "Set up my phone: read https://mobile.orkestrate.space/phone/agent.md")
    }
    OrkRow(first = false, lead = { OrkStepNum(2, false) }) {
      OrkRowText("Scan the code it shows", "Point this app at the QR code to pair.")
    }
  }
}

data class SetupState(
  val relayHost: String,
  val notifications: Boolean,
  val background: Boolean,
  val files: Boolean,
  val shizuku: Boolean,
  val shizukuActionLabel: String?,
  val tried: Boolean,
) {
  val complete get() = notifications && background && files && shizuku && tried
}

/** B · post-pair live checklist. Auto-checks off as permissions are granted. */
@Composable
fun SetupScreen(
  s: SetupState,
  onAllowNotifications: () -> Unit,
  onAllowBackground: () -> Unit,
  onAllowFiles: () -> Unit,
  onShizukuAction: () -> Unit,
  onFinish: () -> Unit,
) {
  Screen(
    topBar = { OrkTopBar() },
    foot = {
      if (s.complete) OrkPill("Done", onFinish, wide = true)
      else OrkLink("Skip for now", onFinish)
    },
  ) {
    OrkHeading("Paired.", "Grant what your agent needs.")
    Spacer(Modifier.height(24.dp))
    OrkRow(first = true, lead = { OrkStepNum(1, true) }) {
      OrkRowText("Paired with your agent", "Relay at ${s.relayHost}")
    }
    OrkRow(
      first = false, lead = { OrkStepNum(2, s.notifications && s.background) },
      trailing = when {
        !s.notifications -> ({ OrkPill("Allow", onAllowNotifications, kind = PillKind.Quiet, small = true) })
        !s.background -> ({ OrkPill("Unrestrict", onAllowBackground, kind = PillKind.Quiet, small = true) })
        else -> null
      },
    ) {
      OrkRowText("Notifications & background", "Keeps the link up and delivers handoff cards.")
    }
    OrkRow(
      first = false, lead = { OrkStepNum(3, s.files) },
      trailing = if (s.files) null else ({ OrkPill("Pick folder", onAllowFiles, kind = PillKind.Quiet, small = true) }),
    ) {
      OrkRowText("Folder access", "Lets your agent read and write the folder you pick.")
    }
    OrkRow(
      first = false, lead = { OrkStepNum(4, s.shizuku) },
      trailing = if (s.shizuku || s.shizukuActionLabel == null) null else ({ OrkPill(s.shizukuActionLabel, onShizukuAction, kind = PillKind.Quiet, small = true) }),
    ) {
      OrkRowText("Screen & shell (Shizuku)", "Optional: lets your agent see/tap apps and run shell commands.")
    }
    OrkRow(first = false, lead = { OrkStepNum(5, s.tried) }) {
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

/** C · manual code entry. */
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
  val paused: Boolean,
  val shizuku: Boolean,
  val files: Boolean,
  val camera: Boolean,
  val recent: List<RecentJob>,
  val updateBanner: String? = null,
  val updateProgress: Float? = null,
)

/** D · home, paired. */
@Composable
fun HomeScreen(
  s: HomeState,
  onTogglePause: () -> Unit,
  onShizukuAction: () -> Unit,
  onPickFolder: () -> Unit,
  onOpenCameraPerm: () -> Unit,
  onSettings: () -> Unit,
  onUpdateBanner: () -> Unit = {},
) {
  Screen(topBar = { OrkTopBar { OrkIconButton(OrkIcons.sliders, "Settings", onSettings) } }) {
    OrkHeading(
      when {
        s.paused -> "Paused."
        s.link == DotState.On -> "Connected."
        s.link == DotState.Wait -> "Connecting."
        else -> "Offline."
      },
      "Agents call it ${s.name}.",
    )
    Row(
      Modifier.fillMaxWidth().padding(top = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OrkStatusLine(if (s.paused) DotState.Off else s.link, s.linkText, Modifier.weight(1f))
      OrkPill(
        if (s.paused) "Resume" else "Pause",
        onTogglePause,
        kind = if (s.paused) PillKind.Solid else PillKind.Quiet,
        small = true,
        icon = if (s.paused) OrkIcons.play else OrkIcons.pause,
      )
    }
    if (s.updateBanner != null) {
      Spacer(Modifier.height(14.dp))
      OrkRow(first = true, modifier = Modifier.clickable(role = Role.Button, onClick = onUpdateBanner)) {
        OrkRowText("App update", s.updateBanner)
        if (s.updateProgress != null) {
          Spacer(Modifier.height(8.dp))
          Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Ork.rule)) {
            Box(Modifier.fillMaxWidth(s.updateProgress.coerceIn(0f, 1f)).height(3.dp).background(Ork.ink))
          }
        }
      }
    }

    Text("Capabilities", style = OrkType.label, modifier = Modifier.padding(top = 28.dp, bottom = 4.dp))
    CapRow(first = true, icon = OrkIcons.eye, title = "Screen & shell (Shizuku)", ok = s.shizuku, onClick = onShizukuAction)
    CapRow(first = false, icon = OrkIcons.folder, title = "Folder access", ok = s.files, onClick = onPickFolder)
    CapRow(first = false, icon = OrkIcons.camera, title = "Camera", ok = s.camera, onClick = onOpenCameraPerm)

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
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun CapRow(
  first: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  ok: Boolean,
  onClick: () -> Unit,
) {
  OrkRow(
    first = first,
    vertical = 14.dp,
    modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    lead = { Icon(icon, null, tint = if (ok) Ork.ink else Ork.caption, modifier = Modifier.size(18.dp)) },
    trailing = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "Ready" else "Off", style = OrkType.label.copy(color = if (ok) Ork.control else Ork.caption))
        if (!ok) {
          Spacer(Modifier.width(4.dp))
          Icon(OrkIcons.chevronRight, null, tint = Ork.caption, modifier = Modifier.size(16.dp))
        }
      }
    },
  ) {
    Text(title, style = OrkType.rowTitle)
  }
}

data class SettingsState(
  val name: String,
  val deviceId: String,
  val relayUrl: String,
  val autoApprove: Boolean,
  val blockedCount: Int,
  val shizukuStatus: String,
  val shizukuActionLabel: String?,
  val buildInfo: String,
  val logs: List<String>,
  val autoUpdate: Boolean = true,
  val updateStatus: String = "",
  val updateProgress: Float? = null,
  val updateIndeterminate: Boolean = false,
  val updateBusy: Boolean = false,
  val updateActionLabel: String = "Check for updates",
)

/** F · 100% Compose Settings screen. */
@Composable
fun SettingsScreen(
  s: SettingsState,
  onBack: () -> Unit,
  onSaveName: (String) -> Unit,
  onSaveRelay: (String) -> Unit,
  onAutoApprove: (Boolean) -> Unit,
  onShizukuAction: () -> Unit,
  onUnpair: () -> Unit,
  onForgetAll: () -> Unit,
  onAutoUpdate: (Boolean) -> Unit = {},
  onUpdateAction: () -> Unit = {},
) {
  var nameDraft by remember(s.name) { mutableStateOf(s.name) }
  var relayDraft by remember(s.relayUrl) { mutableStateOf(s.relayUrl) }
  var devOpen by remember { mutableStateOf(false) }
  var logOpen by remember { mutableStateOf(false) }
  var confirmForget by remember { mutableStateOf(false) }

  Screen(
    topBar = {
      Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Ork.gutter),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OrkIconButton(OrkIcons.arrowLeft, "Back", onBack)
        Spacer(Modifier.width(12.dp))
        Text("Settings", style = OrkType.brand)
      }
    },
  ) {
    Text("Identity", style = OrkType.label, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    OrkField(nameDraft, { nameDraft = it }, "Device name") {
      if (nameDraft.trim() != s.name && nameDraft.isNotBlank()) {
        OrkPill("Save", { onSaveName(nameDraft.trim()) }, kind = PillKind.Quiet, small = true, modifier = Modifier.height(32.dp))
      }
    }
    Spacer(Modifier.height(10.dp))
    OrkField(relayDraft, { relayDraft = it }, "https://...", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri)) {
      if (relayDraft.trim() != s.relayUrl && relayDraft.isNotBlank()) {
        OrkPill("Save", { onSaveRelay(relayDraft.trim()) }, kind = PillKind.Quiet, small = true, modifier = Modifier.height(32.dp))
      }
    }
    OrkRow(first = false, vertical = 12.dp) {
      Text("Device ID", style = OrkType.label)
      Text(s.deviceId.ifBlank { "—" }, style = OrkType.mono, modifier = Modifier.padding(top = 2.dp))
    }

    Text("Safety", style = OrkType.label, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    OrkRow(first = true, trailing = { OrkSwitch(s.autoApprove, "Auto-approve recon commands", onAutoApprove) }) {
      OrkRowText("Auto-approve recon commands", "Allowlisted read-only shell commands run without a tap.")
    }
    OrkRow(first = false, lead = { Icon(OrkIcons.shield, null, tint = Ork.icon, modifier = Modifier.size(18.dp)) }) {
      OrkRowText("Blocked apps", "${s.blockedCount} sensitive apps protected (wallet, authenticators, password managers).")
    }

    Text("Updates", style = OrkType.label, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    OrkRow(first = true, trailing = { OrkSwitch(s.autoUpdate, "Auto-update", onAutoUpdate) }) {
      OrkRowText("Auto-update", "Checks automatically and installs silently via Shizuku.")
    }
    OrkRow(first = false, vertical = 12.dp) {
      OrkRowText("App version", s.updateStatus.ifBlank { s.buildInfo })
      if (s.updateProgress != null) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Ork.rule)) {
          Box(Modifier.fillMaxWidth(s.updateProgress.coerceIn(0f, 1f)).height(3.dp).background(Ork.ink))
        }
      } else if (s.updateIndeterminate) {
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.LinearProgressIndicator(
          modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
          color = Ork.ink,
          trackColor = Ork.rule,
        )
      }
      Spacer(Modifier.height(10.dp))
      OrkPill(s.updateActionLabel, onUpdateAction, kind = PillKind.Quiet, small = true, enabled = !s.updateBusy)
    }

    Text("Developer", style = OrkType.label, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    OrkRow(
      first = true,
      modifier = Modifier.clickable(role = Role.Button) { devOpen = !devOpen },
      lead = { Icon(OrkIcons.terminal, null, tint = Ork.icon, modifier = Modifier.size(18.dp)) },
      trailing = { Icon(OrkIcons.chevronRight, null, tint = Ork.caption, modifier = Modifier.size(16.dp)) },
    ) {
      OrkRowText("Shell tier & diagnostics", s.buildInfo)
    }
    if (devOpen) {
      OrkRow(first = false, vertical = 12.dp) {
        OrkRowText("Shizuku bridge", s.shizukuStatus)
        if (s.shizukuActionLabel != null) {
          Spacer(Modifier.height(10.dp))
          OrkPill(s.shizukuActionLabel, onShizukuAction, kind = PillKind.Quiet, small = true)
        }
      }
      OrkRow(
        first = false,
        modifier = Modifier.clickable(role = Role.Button) { logOpen = !logOpen },
      ) {
        OrkRowText("Live log", "${s.logs.size} recent entries")
        if (logOpen && s.logs.isNotEmpty()) {
          Spacer(Modifier.height(8.dp))
          Text(s.logs.joinToString("\n"), style = OrkType.mono.copy(color = Ork.caption))
        }
      }
    }

    Text("Pairing", style = OrkType.label, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
    OrkPill("Unpair relay", onUnpair, kind = PillKind.Quiet, wide = true)
    Spacer(Modifier.height(10.dp))
    if (!confirmForget) {
      OrkPill("Forget everything", { confirmForget = true }, kind = PillKind.Quiet, wide = true, icon = OrkIcons.trash)
    } else {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.rControl)).background(Ork.raised)
          .border(1.dp, Ork.destructive, RoundedCornerShape(Ork.rControl)).padding(16.dp),
      ) {
        OrkErrorLine("Clears relay pairing, shell keys, and preferences.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OrkPill("Cancel", { confirmForget = false }, kind = PillKind.Quiet, small = true, modifier = Modifier.weight(1f))
          OrkPill("Confirm forget", { confirmForget = false; onForgetAll() }, kind = PillKind.Solid, small = true, modifier = Modifier.weight(1f))
        }
      }
    }
    Spacer(Modifier.height(32.dp))
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
 * E · approval / handoff card over the current screen. Only the two buttons settle it:
 * scrim taps and back do nothing, so a stray touch can neither approve nor reject.
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
          v, style = if (k == "Tool" || k == "Command" || k == "Path") OrkType.mono else OrkType.label.copy(color = Ork.ink),
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
