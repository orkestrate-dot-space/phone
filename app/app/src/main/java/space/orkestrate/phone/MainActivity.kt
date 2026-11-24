package space.orkestrate.phone

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import rikka.shizuku.Shizuku
import space.orkestrate.phone.ui.ApprovalCard
import space.orkestrate.phone.ui.DotState
import space.orkestrate.phone.ui.HomeState
import space.orkestrate.phone.ui.OrkTheme
import space.orkestrate.phone.ui.PhoneRoot
import space.orkestrate.phone.ui.PhoneUiActions
import space.orkestrate.phone.ui.PhoneUiModel
import space.orkestrate.phone.ui.Probe
import space.orkestrate.phone.ui.RecentJob
import space.orkestrate.phone.ui.Route
import space.orkestrate.phone.ui.SettingsState
import space.orkestrate.phone.ui.SetupState
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), Relay.Listener {

  private lateinit var app: PhoneApp
  private lateinit var store: Store
  private lateinit var relay: Relay
  private lateinit var shizuku: ShizukuBridge

  private val ui = PhoneUiModel()
  private var navMode: Route? = null
  private var tried = false
  private var lastRelayState = "not paired"
  private val recent = ArrayDeque<Pair<String, Long>>()
  private val logLines = ArrayDeque<String>()
  private val cardActions = mutableMapOf<String, Pair<() -> Unit, () -> Unit>>()
  @Volatile private var probeSeq = 0

  private var pendingShot: Uri? = null
  private var pendingPhotoJob: String? = null
  private var pendingMaxPx = 1280
  private var pendingQuality = 80

  private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
    runOnUiThread { relay.sendState(); syncUi() }
  }

  private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
    runOnUiThread { relay.sendState(); syncUi() }
  }

  private val reqNotifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    syncUi()
  }

  private val reqCameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    ui.cameraGranted = granted
    val job = pendingPhotoJob
    if (job != null) {
      pendingPhotoJob = null
      if (granted) launchCamera(job)
      else { relay.reject(job, "camera denied"); cancelNotif(job); log("camera denied") }
    }
    syncUi()
  }

  private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      try {
        contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        store.safTreeUri = uri.toString()
        relay.sendState()
      } catch (e: Exception) {
        toast("Folder permission failed: ${e.message}")
      }
    }
    syncUi()
  }

  private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val id = pendingPhotoJob ?: return@registerForActivityResult
    val uri = pendingShot
    pendingPhotoJob = null
    pendingShot = null
    cancelNotif(id)
    if (!ok || uri == null) {
      relay.reject(id, "shot cancelled")
      log("shot cancelled")
      return@registerForActivityResult
    }
    try {
      val dataOut = downscale(uri, pendingMaxPx, pendingQuality)
      relay.approve(id, JSONObject().put("mime", "image/jpeg").put("bytes_b64", dataOut))
      log("approved $id (${dataOut.length / 1024} KB b64)")
    } catch (e: Exception) {
      relay.reject(id, "shot failed: ${e.message}")
      log("shot failed: ${e.message}")
    }
  }

  private val back = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      if (navMode != null) {
        navMode = null
        ui.scanError = null
        syncUi()
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.BLACK),
      navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
    )
    super.onCreate(savedInstanceState)
    app = application as PhoneApp
    store = app.store
    relay = app.relay
    shizuku = app.shizuku
    app.uiListener = this
    app.updater.onChange = { runOnUiThread { syncUi() } }

    try {
      Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
      Shizuku.addRequestPermissionResultListener(shizukuPermListener)
    } catch (_: Throwable) {}

    onBackPressedDispatcher.addCallback(this, back)
    setContent { OrkTheme { PhoneRoot(ui, actions) } }

    handleDeepLink(intent)
    lastRelayState = if (store.paired) "connecting…" else "not paired"
    if (store.paired) app.ensureService()

    intent.getStringExtra("approval")?.let { id ->
      if (relay.pendingApproval(id)) onApproval(id, capabilityOf(id))
    }
    syncUi()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDeepLink(intent)
    intent.getStringExtra("approval")?.let { id ->
      if (relay.pendingApproval(id)) onApproval(id, capabilityOf(id))
    }
  }

  override fun onResume() {
    super.onResume()
    app.uiListener = this
    app.updater.onChange = { runOnUiThread { syncUi() } }
    ui.cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    relay.sendState()
    app.updater.maybeAutoCheck()
    syncUi()
  }

  override fun onDestroy() {
    app.updater.onChange = null
    try {
      Shizuku.removeBinderReceivedListener(shizukuBinderListener)
      Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
    } catch (_: Throwable) {}
    if (app.uiListener === this) app.uiListener = null
    super.onDestroy()
  }

  private fun hapticConfirm() = window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
  private fun hapticReject() = window.decorView.performHapticFeedback(HapticFeedbackConstants.REJECT)

  private fun handleDeepLink(i: Intent?) {
    val d = i?.data ?: return
    val parsed = PairingUri.parse(d.toString()) ?: return
    navMode = Route.EnterCode
    ui.enter = ui.enter.copy(relay = parsed.relay, code = parsed.code, error = null)
    syncUi()
    doPair(parsed.relay, parsed.code)
  }

  private fun doPair(r: String, code: String) {
    val normRelay = PairingUri.normalizeRelay(r)
    val normCode = PairingUri.normalizeCode(code)
    ui.enter = ui.enter.copy(pending = true, error = null)
    relay.pair(normRelay, normCode) { ok, msg ->
      runOnUiThread {
        log(msg)
        if (ok) {
          hapticConfirm()
          navMode = null
          tried = false
          ui.enter = ui.enter.copy(code = "", pending = false, error = null)
          app.ensureService()
          syncUi()
        } else {
          hapticReject()
          ui.enter = ui.enter.copy(pending = false, error = pairError(msg))
        }
      }
    }
  }

  private fun pairError(msg: String): String {
    val status = Regex("""^pair (\d{3})""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    return when {
      status == null -> "The relay didn't answer. Check the address and try again."
      status in 400..499 -> "The relay didn't accept this code. It was mistyped, used or expired."
      else -> "The relay had a problem ($status). Try again."
    }
  }

  private fun scheduleProbe(raw: String) {
    val seq = ++probeSeq
    val base = PairingUri.normalizeRelay(raw)
    if (base.isEmpty()) { ui.enter = ui.enter.copy(probe = Probe.Idle); return }
    ui.enter = ui.enter.copy(probe = Probe.Checking)
    Thread {
      Thread.sleep(500)
      if (seq != probeSeq) return@Thread
      val ok = try {
        val c = OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .readTimeout(5, TimeUnit.SECONDS).build()
        listOf("/version", "/health").any { path ->
          try { c.newCall(Request.Builder().url(base + path).build()).execute().use { it.isSuccessful } }
          catch (_: Exception) { false }
        }
      } catch (_: Exception) { false }
      runOnUiThread { if (seq == probeSeq) ui.enter = ui.enter.copy(probe = if (ok) Probe.Answered else Probe.NoAnswer) }
    }.start()
  }

  private fun shortHost(url: String?): String {
    val h = (url ?: "").substringAfter("://").trimEnd('/')
    return if (h.length > 28) h.take(8) + "…" + h.takeLast(15) else h
  }

  private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun bgAllowed() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

  private fun shizukuSummary(): Pair<String, String?> = when {
    shizuku.hasPermission() -> "Connected (uid 2000)" to null
    shizuku.isRunning() -> "Shizuku is running — tap Allow to grant access." to "Allow"
    shizuku.isInstalled() -> "Shizuku is installed — open it and tap Start." to "Open Shizuku"
    else -> "Install Shizuku (free on Google Play) for screen & shell control." to "Get Shizuku"
  }

  private fun syncUi() {
    val route = when {
      !store.paired -> when (navMode) {
        Route.Scan -> Route.Scan
        Route.EnterCode -> Route.EnterCode
        else -> Route.Unpaired
      }
      navMode == Route.Settings -> Route.Settings
      !store.setupDone -> Route.Setup
      else -> Route.Home
    }
    ui.route = route
    back.isEnabled = navMode != null

    val shizukuOk = shizuku.hasPermission()
    val filesOk = store.safTreeUri != null || shizukuOk
    val camOn = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ui.cameraGranted = camOn
    val (shizukuStatus, shizukuAction) = shizukuSummary()

    ui.setup = SetupState(
      relayHost = shortHost(store.relayHttp),
      notifications = notificationsAllowed(),
      background = bgAllowed(),
      files = filesOk,
      shizuku = shizukuOk,
      shizukuActionLabel = shizukuAction,
      tried = tried,
    )

    val relayLive = lastRelayState.startsWith("connected")
    val now = System.currentTimeMillis()
    ui.home = HomeState(
      name = store.name,
      link = when {
        store.paused -> DotState.Off
        relayLive -> DotState.On
        lastRelayState.startsWith("connecting") -> DotState.Wait
        else -> DotState.Off
      },
      linkText = when {
        store.paused -> "Agent paused"
        relayLive -> "Connected to ${shortHost(store.relayHttp)}"
        else -> lastRelayState.replaceFirstChar { it.uppercase() }
      },
      paused = store.paused,
      shizuku = shizukuOk,
      files = filesOk,
      camera = camOn,
      recent = recent.map { (tool, t) ->
        RecentJob(
          tool,
          if (now - t < 60_000) "Just now"
          else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
      },
      updateBanner = when (app.updater.state.phase) {
        UpdatePhase.Available, UpdatePhase.Downloading, UpdatePhase.Installing -> app.updater.state.message
        else -> null
      },
      updateProgress = when (app.updater.state.phase) {
        UpdatePhase.Downloading, UpdatePhase.Installing -> app.updater.state.progress
        else -> null
      },
    )

    val pkg = packageManager.getPackageInfo(packageName, 0)
    val vc = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
    val u = app.updater.state
    ui.settings = SettingsState(
      name = store.name,
      deviceId = store.deviceId ?: "",
      relayUrl = store.relayHttp ?: "",
      autoApprove = store.autoApprove,
      blockedCount = store.blockedPackages.size,
      shizukuStatus = shizukuStatus,
      shizukuActionLabel = shizukuAction,
      buildInfo = "v${pkg.versionName} (build $vc)",
      logs = logLines.toList(),
      autoUpdate = store.autoUpdate,
      updateStatus = u.message.ifBlank { "v${pkg.versionName} (build $vc)" },
      updateProgress = if (u.phase == UpdatePhase.Downloading) u.progress else null,
      updateIndeterminate = u.phase == UpdatePhase.Checking || u.phase == UpdatePhase.Installing,
      updateBusy = u.phase == UpdatePhase.Checking || u.phase == UpdatePhase.Downloading || u.phase == UpdatePhase.Installing,
      updateActionLabel = when (u.phase) {
        UpdatePhase.Available -> if (shizukuOk) "Install v${u.latestVersionName}" else "Download v${u.latestVersionName}"
        UpdatePhase.Checking -> "Checking..."
        UpdatePhase.Downloading -> "Downloading..."
        UpdatePhase.Installing -> "Installing..."
        else -> "Check for updates"
      },
    )
  }

  private val actions = object : PhoneUiActions {
    override fun openScan() { navMode = Route.Scan; ui.scanError = null; syncUi() }
    override fun enterCode() { navMode = Route.EnterCode; if (ui.enter.relay.isNotBlank()) scheduleProbe(ui.enter.relay); syncUi() }
    override fun back() { navMode = null; ui.scanError = null; syncUi() }
    override fun grantCamera() { reqCameraPerm.launch(Manifest.permission.CAMERA) }
    override fun onQrScanned(raw: String) {
      val parsed = PairingUri.parse(raw)
      if (parsed == null) {
        hapticReject()
        ui.scanError = "That QR code is not a Phone MCP pairing link."
        return
      }
      hapticConfirm()
      navMode = Route.EnterCode
      ui.enter = ui.enter.copy(relay = parsed.relay, code = parsed.code, error = null)
      syncUi()
      doPair(parsed.relay, parsed.code)
    }
    override fun relayChanged(v: String) { ui.enter = ui.enter.copy(relay = v, error = null); scheduleProbe(v) }
    override fun codeChanged(v: String) { ui.enter = ui.enter.copy(code = v.uppercase(), error = null) }
    override fun paste() {
      val cm = getSystemService(ClipboardManager::class.java)
      val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim() ?: return
      val parsed = PairingUri.parse(text)
      if (parsed != null) {
        navMode = Route.EnterCode
        ui.enter = ui.enter.copy(relay = parsed.relay, code = parsed.code, error = null)
        scheduleProbe(parsed.relay)
        syncUi()
      } else if (ui.route == Route.EnterCode) {
        codeChanged(text.take(16))
      } else {
        ui.scanError = "Clipboard does not contain a pairing link."
      }
    }
    override fun connect() { if (!ui.enter.pending) doPair(ui.enter.relay, ui.enter.code) }
    override fun allowNotifications() {
      if (Build.VERSION.SDK_INT >= 33) reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
      else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    override fun allowBackground() = requestBgExemption()
    override fun allowFiles() { pickFolder.launch(null) }
    override fun shizukuAction() {
      when {
        shizuku.hasPermission() -> toast("Shizuku is connected")
        shizuku.isRunning() -> shizuku.requestPermission(101)
        shizuku.isInstalled() -> {
          packageManager.getLaunchIntentForPackage(ShizukuBridge.PKG)?.let { startActivity(it) }
            ?: toast("Open Shizuku app")
        }
        else -> {
          try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.PKG}")))
          } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuBridge.PKG}")))
          }
        }
      }
    }
    override fun finishSetup() { store.setupDone = true; syncUi() }
    override fun togglePause() {
      store.paused = !store.paused
      relay.sendState()
      app.ensureService()
      syncUi()
    }
    override fun openCameraPermission() { reqCameraPerm.launch(Manifest.permission.CAMERA) }
    override fun openSettings() { navMode = Route.Settings; syncUi() }
    override fun saveName(name: String) { store.name = name; log("name → $name"); syncUi() }
    override fun saveRelay(url: String) {
      relay.disconnect()
      store.relayHttp = PairingUri.normalizeRelay(url)
      app.ensureService()
      log("relay → ${store.relayHttp}")
      syncUi()
    }
    override fun autoApprove(on: Boolean) { store.autoApprove = on; syncUi() }
    override fun unpair() {
      relay.disconnect(); store.clearRelay()
      stopService(Intent(this@MainActivity, RelayService::class.java))
      navMode = null; syncUi()
    }
    override fun forgetAll() {
      relay.disconnect(); store.clear()
      stopService(Intent(this@MainActivity, RelayService::class.java))
      navMode = null; recent.clear(); syncUi()
    }
    override fun autoUpdate(on: Boolean) {
      store.autoUpdate = on
      if (on) app.updater.checkForUpdate(autoInstall = true)
      syncUi()
    }
    override fun updateAction() {
      app.updater.installOrOpen { url ->
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
      }
      syncUi()
    }
    override fun approve(jobId: String) { hapticConfirm(); ui.approval = null; cardActions.remove(jobId)?.first?.invoke() }
    override fun reject(jobId: String) { hapticReject(); ui.approval = null; cardActions.remove(jobId)?.second?.invoke() }
  }

  private fun requestBgExemption() {
    try {
      startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (_: Exception) {
      try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
      catch (_: Exception) {}
    }
  }

  private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

  private fun capabilityOf(jobId: String): String {
    val p = relay.jobParams(jobId) ?: return "photo.capture"
    return when {
      p.has("message") -> "human.handoff"
      p.optString("action") == "delete" -> "files.op"
      p.has("argv") -> "exec.run"
      else -> "photo.capture"
    }
  }

  private fun showCard(card: ApprovalCard, onApprove: () -> Unit) {
    cardActions[card.jobId] = onApprove to {
      relay.reject(card.jobId)
      cancelNotif(card.jobId)
      log("rejected ${card.jobId}")
    }
    ui.approval = card
  }

  override fun onApproval(jobId: String, capability: String) {
    runOnUiThread {
      if (!relay.pendingApproval(jobId)) return@runOnUiThread
      when (capability) {
        "human.handoff" -> {
          val msg = relay.jobParams(jobId)?.optString("message", "Complete this step on your phone.") ?: "Complete this step on your phone."
          showCard(ApprovalCard(jobId, "Agent needs you.", msg, listOf("Tool" to "human.handoff"), "Continue", false)) {
            cancelNotif(jobId)
            relay.approve(jobId, JSONObject().put("continued", true))
          }
        }
        "files.op" -> {
          val path = relay.jobParams(jobId)?.optString("path", "") ?: ""
          showCard(ApprovalCard(jobId, "Delete this file?", "This cannot be undone.", listOf("Tool" to "files.op", "Path" to path), "Delete file", false)) {
            cancelNotif(jobId)
            Thread {
              try {
                val res = relay.deleteFile(relay.jobParams(jobId) ?: JSONObject())
                relay.approve(jobId, res)
              } catch (e: Exception) {
                relay.reject(jobId, e.message ?: "delete failed")
              }
            }.start()
          }
        }
        "exec.run" -> {
          val argv = relay.jobParams(jobId)?.optJSONArray("argv")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
          val tier = if (shizuku.hasPermission()) "shizuku" else "app sandbox"
          showCard(ApprovalCard(jobId, "Run a command?", "Runs as $tier.", listOf("Tool" to "exec.run", "Command" to argv.joinToString(" ")), "Run command", false)) {
            ExecRunner(this, relay, shizuku, store).runExec(jobId, argv, object : ExecRunner.Sink {
              override fun note(s: String) = runOnUiThread { log(s) }
            })
          }
        }
        else -> {
          val p = relay.jobParams(jobId)
          pendingMaxPx = p?.optInt("maxPx", 1280) ?: 1280
          pendingQuality = p?.optInt("quality", 80) ?: 80
          showCard(ApprovalCard(jobId, "Take a photo?", "Nothing is captured until you take it.", listOf("Tool" to "photo.capture", "Size" to "$pendingMaxPx px, JPEG"), "Open camera", true)) {
            startShot(jobId)
          }
        }
      }
    }
  }

  private fun startShot(jobId: String) {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      pendingPhotoJob = jobId
      reqCameraPerm.launch(Manifest.permission.CAMERA)
      return
    }
    launchCamera(jobId)
  }

  private fun launchCamera(jobId: String) {
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "phone_${System.currentTimeMillis()}.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/phone")
    }
    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) { relay.reject(jobId, "camera store failed"); cancelNotif(jobId); return }
    pendingShot = uri
    pendingPhotoJob = jobId
    takePicture.launch(uri)
  }

  private fun downscale(uri: Uri, maxPx: Int, quality: Int): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
      ?: throw IllegalStateException("decode failed")
    val scale = minOf(1f, maxPx / maxOf(bmp.width, bmp.height).toFloat())
    if (scale < 1f) {
      val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
      val s = Bitmap.createScaledBitmap(bmp, w, h, true)
      if (s !== bmp) bmp.recycle()
      bmp = s
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), out)
    bmp.recycle()
    contentResolver.delete(uri, null, null)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  private fun cancelNotif(jobId: String) {
    getSystemService(android.app.NotificationManager::class.java).cancel(jobId.hashCode())
  }

  override fun onState(s: String) {
    runOnUiThread { lastRelayState = s; log(s); syncUi() }
  }

  override fun onJob(desc: String) {
    runOnUiThread {
      tried = true
      recent.addFirst(desc.substringBefore(" · ") to System.currentTimeMillis())
      while (recent.size > 8) recent.removeLast()
      syncUi()
    }
  }

  override fun onLog(s: String) {
    runOnUiThread { log(s) }
  }

  private fun log(s: String) {
    logLines.addFirst(s)
    while (logLines.size > 40) logLines.removeLast()
  }
}
