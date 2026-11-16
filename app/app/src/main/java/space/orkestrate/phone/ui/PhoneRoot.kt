package space.orkestrate.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class Route { Unpaired, EnterCode, JustPaired, Home }

/** Everything the Compose screens show. The activity writes it; screens only read it. */
class PhoneUiModel {
  var route by mutableStateOf(Route.Unpaired)
  var setup by mutableStateOf(SetupState("", false, false, false))
  var enter by mutableStateOf(EnterCodeState("", "", Probe.Idle, null, false))
  var home by mutableStateOf(HomeState("", DotState.Off, "Not connected", emptyList(), false, false))
  var approval by mutableStateOf<ApprovalCard?>(null)
}

interface PhoneUiActions {
  fun openCamera()
  fun enterCode()
  fun back()
  fun relayChanged(v: String)
  fun codeChanged(v: String)
  fun paste()
  fun connect()
  fun allowNotifications()
  fun allowBackground()
  fun finishSetup()
  fun autoApprove(on: Boolean)
  fun selfUpdate(on: Boolean)
  fun openSettings()
  fun approve(jobId: String)
  fun reject(jobId: String)
}

@Composable
fun PhoneRoot(m: PhoneUiModel, a: PhoneUiActions) {
  Box(Modifier.fillMaxSize().background(Ork.canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
    when (m.route) {
      Route.Unpaired -> UnpairedScreen(a::openCamera, a::enterCode)
      Route.EnterCode -> EnterCodeScreen(m.enter, a::back, a::relayChanged, a::codeChanged, a::paste, a::connect)
      Route.JustPaired -> JustPairedScreen(m.setup, a::allowNotifications, a::allowBackground, a::finishSetup)
      Route.Home -> HomeScreen(m.home, a::autoApprove, a::selfUpdate, a::openSettings)
    }
    m.approval?.let { card -> ApprovalSheet(card, { a.approve(card.jobId) }, { a.reject(card.jobId) }) }
  }
}
