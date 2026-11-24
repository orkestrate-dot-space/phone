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

enum class Route { Unpaired, Scan, EnterCode, Setup, Home, Settings }

class PhoneUiModel {
  var route by mutableStateOf(Route.Unpaired)
  var cameraGranted by mutableStateOf(false)
  var scanError by mutableStateOf<String?>(null)
  var setup by mutableStateOf(SetupState("", false, false, false, false, false))
  var enter by mutableStateOf(EnterCodeState("", "", Probe.Idle, null, false))
  var home by mutableStateOf(HomeState("", DotState.Off, "Not connected", false, false, false, false, false, emptyList()))
  var settings by mutableStateOf(SettingsState("", "", "", false, 0, "", null, true, "", emptyList()))
  var approval by mutableStateOf<ApprovalCard?>(null)
}

interface PhoneUiActions {
  fun openScan()
  fun enterCode()
  fun back()
  fun grantCamera()
  fun onQrScanned(raw: String)
  fun relayChanged(v: String)
  fun codeChanged(v: String)
  fun paste()
  fun connect()
  fun allowNotifications()
  fun allowBackground()
  fun allowAccessibility()
  fun allowFiles()
  fun finishSetup()
  fun togglePause()
  fun openAccessibilitySettings()
  fun openFilesSettings()
  fun openNotificationListenerSettings()
  fun openCameraPermission()
  fun openSettings()
  fun saveName(name: String)
  fun saveRelay(url: String)
  fun autoApprove(on: Boolean)
  fun shellAction()
  fun selfUpdate(on: Boolean)
  fun unpair()
  fun forgetAll()
  fun approve(jobId: String)
  fun reject(jobId: String)
}

@Composable
fun PhoneRoot(m: PhoneUiModel, a: PhoneUiActions) {
  Box(Modifier.fillMaxSize().background(Ork.canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
    when (m.route) {
      Route.Unpaired -> UnpairedScreen(a::openScan, a::enterCode)
      Route.Scan -> ScanScreen(
        cameraGranted = m.cameraGranted,
        error = m.scanError,
        onBack = a::back,
        onPaste = a::paste,
        onEnterCode = a::enterCode,
        onGrantCamera = a::grantCamera,
        onScanned = a::onQrScanned,
      )
      Route.EnterCode -> EnterCodeScreen(m.enter, a::back, a::relayChanged, a::codeChanged, a::paste, a::connect)
      Route.Setup -> SetupScreen(
        m.setup,
        a::allowNotifications,
        a::allowBackground,
        a::allowAccessibility,
        a::allowFiles,
        a::finishSetup,
      )
      Route.Home -> HomeScreen(
        m.home,
        a::togglePause,
        a::openAccessibilitySettings,
        a::openFilesSettings,
        a::openNotificationListenerSettings,
        a::openCameraPermission,
        a::openSettings,
      )
      Route.Settings -> SettingsScreen(
        m.settings,
        a::back,
        a::saveName,
        a::saveRelay,
        a::autoApprove,
        a::shellAction,
        a::selfUpdate,
        a::unpair,
        a::forgetAll,
      )
    }
    m.approval?.let { card -> ApprovalSheet(card, { a.approve(card.jobId) }, { a.reject(card.jobId) }) }
  }
}
