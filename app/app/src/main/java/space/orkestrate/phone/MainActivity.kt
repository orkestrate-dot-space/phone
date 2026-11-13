package space.orkestrate.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream

/**
 * v0 shell: status header, name, pairing, last job, collapsed advanced.
 * Plus the approval surface: notification + dialog + system camera.
 * Visual language mirrors the browse-extension settings ref (single dark
 * card, lowercase section labels, control right) — nothing more.
 */
class MainActivity : Activity(), Relay.Listener {

  private lateinit var store: Store
  private lateinit var relay: Relay
  private lateinit var adb: AdbShell

  private lateinit var dot: View
  private lateinit var status: TextView
  private lateinit var identity: TextView
  private lateinit var nameIn: EditText
  private lateinit var relayIn: EditText
  private lateinit var codeIn: EditText
  private lateinit var pairingBox: LinearLayout
  private lateinit var lastJob: TextView
  private lateinit var advBox: LinearLayout
  private lateinit var advRelay: TextView
  private lateinit var advDevice: TextView
  private lateinit var logView: TextView
  private val logLines = ArrayDeque<String>()

  private var pendingShot: Uri? = null
  private var pendingJob: String? = null
  private var pendingMaxPx = 1280
  private var pendingQuality = 80

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    setContentView(R.layout.activity_main)
    val app = application as PhoneApp
    store = app.store
    relay = app.relay
    adb = AdbShell(this)
    relay.listener = this
    store.adbSerial?.let { adb.setSerial(it) }

    dot = findViewById(R.id.dot)
    status = findViewById(R.id.status)
    identity = findViewById(R.id.identity)
    nameIn = findViewById(R.id.nameIn)
    relayIn = findViewById(R.id.relayIn)
    codeIn = findViewById(R.id.codeIn)
    pairingBox = findViewById(R.id.pairingBox)
    lastJob = findViewById(R.id.lastJob)
    advBox = findViewById(R.id.advBox)
    advRelay = findViewById(R.id.advRelay)
    advDevice = findViewById(R.id.advDevice)
    logView = findViewById(R.id.logView)

    nameIn.setText(store.name)
    findViewById<Button>(R.id.saveName).setOnClickListener {
      store.name = nameIn.text.toString().ifBlank { android.os.Build.MODEL }
      refreshIdentity()
      log("name → ${store.name}")
    }
    findViewById<Button>(R.id.pairBtn).setOnClickListener {
      val r = relayIn.text.toString()
      val c = codeIn.text.toString()
      if (r.isBlank() || c.isBlank()) { log("relay + code required"); return@setOnClickListener }
      log("pairing…")
      relay.pair(r, c) { ok, msg ->
        runOnUiThread {
          log(msg)
          if (ok) { codeIn.setText(""); refreshAll(); (application as PhoneApp).ensureService() }
        }
      }
    }
    findViewById<TextView>(R.id.advToggle).setOnClickListener {
      advBox.visibility = if (advBox.visibility == View.GONE) View.VISIBLE else View.GONE
    }
    findViewById<Button>(R.id.unpairBtn).setOnClickListener {
      try { adb.disconnectAll() } catch (_: Exception) {}
      adb.drop()
      relay.disconnect(); store.clear()
      stopService(Intent(this, RelayService::class.java))
      refreshAll(); refreshShellStatus(); log("unpaired")
    }
    findViewById<Button>(R.id.updateBtn).setOnClickListener { checkUpdate() }
    findViewById<Button>(R.id.bgBtn).setOnClickListener {
      try {
        startActivity(Intent(
          android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          android.net.Uri.parse("package:$packageName")))
      } catch (e: Exception) {
        log("battery prompt failed: ${e.message}")
        try {
          startActivity(Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName")))
        } catch (e2: Exception) { log("settings failed: ${e2.message}") }
      }
    }
    findViewById<Button>(R.id.autoBtn).setOnClickListener {
      store.autoApprove = !store.autoApprove
      refreshAutoStatus()
      log("auto-approve ${if (store.autoApprove) "ON" else "OFF"}")
    }
    findViewById<Button>(R.id.shellScan).setOnClickListener {
      shellNote("scanning…")
      adb.scanPorts { ports ->
        runOnUiThread {
          if (ports.isNotEmpty()) {
            findViewById<EditText>(R.id.shellPort).setText(ports.first().toString())
            shellNote("found: ${ports.joinToString(", ")}")
          } else shellNote("nothing found — is wireless debugging on?")
        }
      }
    }
    findViewById<Button>(R.id.shellConnect).setOnClickListener {
      val port = findViewById<EditText>(R.id.shellPort).text.toString().toIntOrNull()
      if (port == null) { shellNote("enter the connect port (or Scan)"); return@setOnClickListener }
      shellNote("connecting…")
      Thread {
        val ok = adb.connect(port)
        if (ok) {
          store.adbSerial = adb.serial; store.adbPaired = true
          selfGrantShell()
        }
        runOnUiThread { refreshShellStatus(); if (ok) log("shell connected: ${adb.serial}") }
      }.start()
    }
    findViewById<Button>(R.id.shellPair).setOnClickListener {
      val pairPort = findViewById<EditText>(R.id.shellPairPort).text.toString().toIntOrNull()
      val code = findViewById<EditText>(R.id.shellCode).text.toString().trim()
      if (pairPort == null || code.isBlank()) { shellNote("pair port + code from Settings"); return@setOnClickListener }
      shellNote("pairing…")
      Thread {
        val ok = adb.pair("127.0.0.1", pairPort, code)
        runOnUiThread {
          if (!ok) { shellNote("pair failed — check code/port"); return@runOnUiThread }
          store.adbPaired = true
          shellNote("paired — scanning for connect port…")
          adb.scanPorts { ports ->
            runOnUiThread {
              val port = ports.firstOrNull()
              if (port == null) { shellNote("paired — enter connect port manually"); refreshShellStatus(); return@runOnUiThread }
              findViewById<EditText>(R.id.shellPort).setText(port.toString())
              Thread {
                if (adb.connect(port)) {
                  store.adbSerial = adb.serial
                  selfGrantShell()
                }
                runOnUiThread { refreshShellStatus() }
              }.start()
            }
          }
        }
      }.start()
    }
    findViewById<Button>(R.id.relaySave).setOnClickListener {
      val r = findViewById<EditText>(R.id.relayEdit).text.toString().trim()
      if (r.isBlank()) { log("relay address empty"); return@setOnClickListener }
      relay.disconnect()
      store.relayHttp = r.trimEnd('/')
      refreshAll()
      log("relay → ${store.relayHttp}")
      (application as PhoneApp).ensureService()
    }

    // phone://pair?code=XXXXXXXX&relay=http://host:port
    handleDeepLink(intent)
    refreshAll()
    if (store.paired) (application as PhoneApp).ensureService() else onState("not paired")
    // silent ADB-channel restore (keys persist; toggle may need a manual flip)
    if (store.adbPaired && adb.serial != null) {
      Thread {
        val s = adb.serial!!
        val port = s.substringAfterLast(":").toIntOrNull()
        val ok = if (port != null) adb.connect(port) else false
        if (ok) selfGrantShell()
        runOnUiThread { refreshShellStatus() }
      }.start()
    } else {
      refreshShellStatus()
    }

    // opened from the approval notification?
    intent.getStringExtra("approval")?.let { id ->
      if (relay.pendingApproval(id)) showApproval(id)
      else log("approval $id already settled")
    }
  }

  override fun onNewIntent(i: Intent) {
    super.onNewIntent(i)
    handleDeepLink(i)
    i.getStringExtra("approval")?.let { id ->
      if (relay.pendingApproval(id)) showApproval(id)
    }
  }

  private fun handleDeepLink(i: Intent?) {
    val d = i?.data ?: return
    Log.d("phone", "deep link: $d")
    if (d.scheme != "phone" || d.host != "pair") return
    val code = d.getQueryParameter("code") ?: return
    val r = d.getQueryParameter("relay") ?: return
    relayIn.setText(r); codeIn.setText(code)
    log("deep link → pairing…")
    relay.pair(r, code) { ok, msg ->
      runOnUiThread { log(msg); if (ok) { codeIn.setText(""); refreshAll(); (application as PhoneApp).ensureService() } }
    }
  }

  private fun refreshAll() {
    refreshIdentity()
    advRelay.text = store.relayHttp ?: "—"
    findViewById<EditText>(R.id.relayEdit).setText(store.relayHttp ?: "")
    advDevice.text = store.deviceId ?: "—"
    pairingBox.visibility = if (store.paired) View.GONE else View.VISIBLE
    refreshBgStatus()
    refreshAutoStatus()
    if (!store.paired) { onState("not paired"); lastJob.text = "no jobs yet" }
  }

  private fun refreshBgStatus() {
    val pm = getSystemService(android.os.PowerManager::class.java)
    val exempt = pm.isIgnoringBatteryOptimizations(packageName)
    findViewById<TextView>(R.id.bgStatus).text =
      if (exempt) "unrestricted ✓ — link survives the screen off"
      else "restricted — tap Allow background"
  }

  private fun refreshAutoStatus() {
    val on = store.autoApprove
    findViewById<TextView>(R.id.autoStatus).text =
      if (on) "ON — listed commands run without a tap, everything else asks"
      else "OFF — every command asks"
    findViewById<Button>(R.id.autoBtn).text = if (on) "Turn off" else "Turn on"
  }

  private fun shellNote(s: String) {
    findViewById<TextView>(R.id.shellStatus).text = s
    log("shell: $s")
  }

  private fun refreshShellStatus() {
    findViewById<TextView>(R.id.shellStatus).text = when {
      adb.serial != null -> "connected — ${adb.serial} (shell-UID)"
      store.adbPaired -> "paired — tap Connect (enable wireless debugging first)"
      else -> "not paired — enable wireless debugging, then Pair"
    }
  }

  /** Grant ourselves WRITE_SECURE_SETTINGS through our own shell (one-time). */
  private fun selfGrantShell() {
    try {
      if (packageManager.checkPermission(
          "android.permission.WRITE_SECURE_SETTINGS", packageName) ==
          PackageManager.PERMISSION_GRANTED) return
      val r = adb.exec(listOf("pm", "grant", packageName,
        "android.permission.WRITE_SECURE_SETTINGS"), 15)
      Log.d("phone", "self-grant: ${r.exit}")
    } catch (e: Exception) { Log.d("phone", "self-grant fail: $e") }
  }

  override fun onResume() {
    super.onResume()
    relay.listener = this
    refreshAll()
  }

  private fun refreshIdentity() {
    identity.text = "${store.name} is how agents call this phone."
  }

  // ---- approval surface ----
  override fun onApproval(jobId: String, capability: String) {
    runOnUiThread {
      val argv = relay.jobParams(jobId)?.optJSONArray("argv")
        ?.let { a -> (0 until a.length()).map { a.getString(it) } }
      if (capability == "exec.run" && argv != null && store.autoApprove && ExecPolicy.auto(argv)) {
        log("auto-run (allowlisted): ${argv.joinToString(" ")}")
        runExec(jobId, argv)
      } else {
        notifyApproval(jobId, capability)
        showApproval(jobId)
      }
    }
  }

  private fun notifyApproval(jobId: String, capability: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
    }
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("jobs", "agent jobs", NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(
      this, jobId.hashCode(),
      Intent(this, MainActivity::class.java).putExtra("approval", jobId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val (title, text) = if (capability == "exec.run") {
      "Agent wants to run a command" to (relay.jobParams(jobId)?.optJSONArray("argv")
        ?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } }
        ?.take(120) ?: "…")
    } else {
      "Agent requests a photo" to "Tap to approve or reject"
    }
    val n = android.app.Notification.Builder(this, "jobs")
      .setSmallIcon(if (capability == "exec.run") android.R.drawable.ic_menu_manage else android.R.drawable.ic_menu_camera)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    nm.notify(jobId.hashCode(), n)
  }

  private fun showApproval(jobId: String) {
    val cap = if (relay.jobParams(jobId)?.has("argv") == true) "exec.run" else "photo.capture"
    if (cap == "exec.run") showExecApproval(jobId) else showPhotoApproval(jobId)
  }

  private fun showPhotoApproval(jobId: String) {
    AlertDialog.Builder(this)
      .setTitle("Agent requests a photo")
      .setMessage("Approve opens your camera. The shot goes to the waiting agent. Nothing captures until you take it.")
      .setPositiveButton("Approve") { _, _ -> startShot(jobId) }
      .setNegativeButton("Reject") { _, _ ->
        relay.reject(jobId); cancelNotif(jobId); log("rejected $jobId")
      }
      .setOnCancelListener { relay.reject(jobId); cancelNotif(jobId) }
      .show()
  }

  private fun showExecApproval(jobId: String) {
    val argv = relay.jobParams(jobId)?.optJSONArray("argv")
      ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    AlertDialog.Builder(this)
      .setTitle("Agent wants to run a command")
      .setMessage("Runs as this app (no root). Approve runs exactly:\n\n${argv.joinToString(" ")}")
      .setPositiveButton("Approve & run") { _, _ -> runExec(jobId, argv) }
      .setNegativeButton("Reject") { _, _ ->
        relay.reject(jobId); cancelNotif(jobId); log("rejected $jobId")
      }
      .setOnCancelListener { relay.reject(jobId); cancelNotif(jobId) }
      .show()
  }

  private val EXEC_ALLOW = setOf("ls", "cat", "echo", "date", "uname", "getprop", "df", "du", "head")
  /** Shell tier: the full adb-shell surface (still argv-array, still approved). */
  private val SHELL_ALLOW = EXEC_ALLOW + setOf(
    "dumpsys", "cmd", "pm", "settings", "input", "logcat", "am", "wm",
    "appops", "service", "svc", "getevent", "screencap", "screenrecord")

  private fun runExec(jobId: String, argv: List<String>) {
    Thread {
      try {
        val cmd = argv.toMutableList()
        val bin = cmd[0]
        val allowed = if (adb.serial != null) SHELL_ALLOW else EXEC_ALLOW
        if ("/" !in bin) {
          if (bin !in allowed) throw IllegalArgumentException("not allowed: $bin")
          cmd[0] = "/system/bin/$bin"
        } else {
          if (!bin.startsWith("/system/bin/")) throw IllegalArgumentException("only /system/bin: $bin")
        }
        val timeoutSec = (relay.jobParams(jobId)?.optInt("timeoutSec", 30) ?: 30).coerceIn(5, 120)
        // Tier S (shell-UID) when the embedded ADB channel is up, else app-UID.
        val sh = adb.serial?.let { adb.exec(cmd, timeoutSec) }
        if (sh != null && !adb.looksDead(sh)) {
          finishExec(jobId, sh.exit, sh.stdout.toByteArray(), sh.stderr.toByteArray(), "shell")
          return@Thread
        }
        if (sh != null) adb.drop()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val t1 = Thread { try { proc.inputStream.copyTo(out) } catch (_: Exception) {} }
        val t2 = Thread { try { proc.errorStream.copyTo(err) } catch (_: Exception) {} }
        t1.start(); t2.start()
        val done = proc.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        t1.join(2000); t2.join(2000)
        cancelNotif(jobId)
        if (!done) {
          proc.destroyForcibly()
          relay.reject(jobId, "timed out after ${timeoutSec}s")
          runOnUiThread { log("exec $jobId timed out") }
          return@Thread
        }
        val cap = 65536
        val so = out.toByteArray(); val se = err.toByteArray()
        finishExec(jobId, proc.exitValue(), so, se, "app")
      } catch (e: Exception) {
        Log.d("phone", "exec fail: $e")
        cancelNotif(jobId)
        relay.reject(jobId, "exec failed: ${e.message}")
        runOnUiThread { log("exec failed: ${e.message}") }
      }
    }.start()
  }

  private fun finishExec(jobId: String, exit: Int, so: ByteArray, se: ByteArray, tier: String) {
    cancelNotif(jobId)
    val cap = 65536
    val data = org.json.JSONObject()
      .put("exit", exit)
      .put("tier", tier)
      .put("stdout", String(so, 0, minOf(so.size, cap)))
      .put("stderr", String(se, 0, minOf(se.size, cap)))
      .put("truncated", so.size > cap || se.size > cap)
    relay.approve(jobId, data)
    runOnUiThread { log("exec $jobId [$tier] exit=$exit") }
  }

  private fun cancelNotif(jobId: String) {
    getSystemService(NotificationManager::class.java).cancel(jobId.hashCode())
  }

  private fun startShot(jobId: String) {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      pendingJob = jobId
      requestPermissions(arrayOf(Manifest.permission.CAMERA), 42)
      return
    }
    launchCamera(jobId)
  }

  override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
    if (rc == 42) {
      val id = pendingJob ?: return
      pendingJob = null
      if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchCamera(id)
      else { relay.reject(id, "camera denied"); cancelNotif(id); log("camera denied") }
    }
  }

  private fun launchCamera(jobId: String) {
    pendingMaxPx = 1280; pendingQuality = 80
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "phone_${System.currentTimeMillis()}.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/phone")
    }
    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) { relay.reject(jobId, "camera store failed"); cancelNotif(jobId); return }
    pendingShot = uri; pendingJob = jobId
    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
      .putExtra(MediaStore.EXTRA_OUTPUT, uri)
      .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    val resolved = packageManager.queryIntentActivities(cam, PackageManager.MATCH_DEFAULT_ONLY)
    if (resolved.isEmpty()) { relay.reject(jobId, "no camera app"); cancelNotif(jobId); return }
    startActivityForResult(cam, 43)
  }

  @Deprecated("framework activity result")
  override fun onActivityResult(rc: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(rc, resultCode, data)
    if (rc != 43) return
    val id = pendingJob ?: return
    val uri = pendingShot
    pendingJob = null; pendingShot = null
    cancelNotif(id)
    if (resultCode != RESULT_OK || uri == null) {
      relay.reject(id, "shot cancelled"); log("shot cancelled"); return
    }
    try {
      val dataOut = downscale(uri, pendingMaxPx, pendingQuality)
      relay.approve(id, org.json.JSONObject()
        .put("mime", "image/jpeg").put("bytes_b64", dataOut))
      log("approved $id (${dataOut.length / 1024} KB b64)")
    } catch (e: Exception) {
      Log.d("phone", "shot fail: $e")
      relay.reject(id, "shot failed: ${e.message}"); log("shot failed")
    }
  }

  private fun downscale(uri: Uri, maxPx: Int, quality: Int): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
      ?: throw IllegalStateException("decode failed")
    // exact scale to fit maxPx
    val scale = minOf(1f, maxPx / maxOf(bmp.width, bmp.height).toFloat())
    if (scale < 1f) {
      val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
      val s = Bitmap.createScaledBitmap(bmp, w, h, true)
      if (s !== bmp) bmp.recycle()
      bmp = s
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    bmp.recycle()
    contentResolver.delete(uri, null, null) // keep the glass clean; bytes already flow to the agent
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  // ---- self-update: version check + PackageInstaller session ----
  private fun checkUpdate() {
    val base = store.relayHttp ?: run { updateNote("not paired"); return }
    val token = store.token ?: run { updateNote("not paired"); return }
    updateNote("checking…")
    Thread {
      val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
      var lastErr = "unknown"
      for (attempt in 1..3) {
        try {
          if (tryUpdate(client, base, token)) return@Thread
          else return@Thread // definitive answer (up to date), no retry
        } catch (e: Exception) {
          lastErr = e.message ?: "failed"
          Log.d("phone", "update attempt $attempt: $e")
          updateNote("retrying… ($attempt/3)")
          Thread.sleep(2000)
        }
      }
      updateNote("check failed: $lastErr")
    }.start()
  }

  /** Returns true if an update flow completed; false if already up to date. Throws on transport failure. */
  private fun tryUpdate(client: okhttp3.OkHttpClient, base: String, token: String): Boolean {
      val req = okhttp3.Request.Builder()
        .url(base.trimEnd('/') + "/app/version")
        .header("Authorization", "Bearer $token")
        .build()
      client.newCall(req).execute().use { res ->
        val body = res.body?.string() ?: ""
        if (!res.isSuccessful) { updateNote("check failed: ${res.code}"); return false }
        val v = org.json.JSONObject(body)
        val latest = v.getInt("versionCode")
        val mine = packageManager.getPackageInfo(packageName, 0).let {
          if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
        }
        if (latest <= mine) { updateNote("up to date (${v.optString("versionName")})"); return false }
        val notes = v.optString("notes", "")
        updateNote("downloading ${v.optString("versionName")}${if (notes.isNotBlank()) " — $notes" else ""}…")
        downloadAndInstall(client, base, token)
        return true
    }
  }

  private fun updateNote(s: String) = runOnUiThread {
    findViewById<TextView>(R.id.updateStatus).text = s
    log("update: $s")
  }

  /** Throws on transport failure so the caller retries; install problems are reported, not retried. */
  private fun downloadApk(client: okhttp3.OkHttpClient, base: String, token: String): java.io.File? {
    val req = okhttp3.Request.Builder()
      .url(base.trimEnd('/') + "/app.apk")
      .header("Authorization", "Bearer $token")
      .build()
    val tmp = java.io.File(cacheDir, "update.apk")
    client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) { updateNote("download: ${res.code}"); return null }
      res.body!!.byteStream().use { input ->
        tmp.outputStream().use { input.copyTo(it) }
      }
    }
    return tmp
  }

  private fun downloadAndInstall(client: okhttp3.OkHttpClient, base: String, token: String) {
    val tmp = downloadApk(client, base, token) ?: return
    try {
      updateNote("downloaded ${(tmp.length() / 1024)} KB, installing…")
      val statusFilter = IntentFilter("space.orkestrate.phone.UPDATE_STATUS")
      val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
          val st = i?.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
          updateNote("install status=$st ${i?.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
          if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
              i?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
              @Suppress("DEPRECATION") i?.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            try {
              confirm?.let { startActivity(it) } ?: updateNote("installer UI missing")
            } catch (e: Exception) { updateNote("installer UI failed: ${e.message}") }
          } else if (st != null && st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            try { unregisterReceiver(this) } catch (_: Exception) {}
          } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            updateNote("installed — reopen the app")
            try { unregisterReceiver(this) } catch (_: Exception) {}
          }
        }
      }
      if (android.os.Build.VERSION.SDK_INT >= 33) {
        registerReceiver(statusReceiver, statusFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION") registerReceiver(statusReceiver, statusFilter)
      }
      val pi = packageManager.packageInstaller
      val params = android.content.pm.PackageInstaller.SessionParams(
        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
      val id = pi.createSession(params)
      pi.openSession(id).use { session ->
        tmp.inputStream().use { input ->
          session.openWrite("app", 0, tmp.length()).use { out ->
            input.copyTo(out)
            session.fsync(out)
          }
        }
        val piFlags = if (android.os.Build.VERSION.SDK_INT >= 31) {
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
          PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Explicit intent (setPackage): Android 14+ throws on MUTABLE + implicit.
        // Mutable: PackageInstaller writes EXTRA_STATUS back (immutable rejected on 35+).
        val statusIntent = Intent("space.orkestrate.phone.UPDATE_STATUS").apply {
          setPackage(packageName)
        }
        val intent = PendingIntent.getBroadcast(this, id, statusIntent, piFlags)
        session.commit(intent.intentSender)
      }
      tmp.delete()
      // system shows the install prompt; result lands in the broadcast above
    } catch (e: Exception) {
      Log.d("phone", "install fail: $e")
      updateNote("install failed: ${e.message}")
    }
  }

  // ---- Relay.Listener ----
  override fun onState(s: String) = runOnUiThread {
    status.text = s
    dot.setBackgroundColor(if (s.startsWith("connected")) 0xFF34D399.toInt() else 0xFF6B7280.toInt())
  }

  override fun onJob(desc: String) = runOnUiThread { lastJob.text = desc }

  override fun onLog(s: String) = runOnUiThread { log(s) }

  private fun log(s: String) {
    logLines.addLast(s); while (logLines.size > 30) logLines.removeFirst()
    logView.text = logLines.joinToString("\n")
  }
}
