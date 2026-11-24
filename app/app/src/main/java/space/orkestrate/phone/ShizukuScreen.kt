package space.orkestrate.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * Shizuku-backed screen observation, input actuation, and notification reading.
 * Runs as uid-2000 without AccessibilityService or NotificationListenerService.
 */
object ShizukuScreen {
  private val lastRefs = mutableMapOf<String, Rect>()

  fun parseUiDumpXml(xml: String): List<RawUiNode> {
    val out = mutableListOf<RawUiNode>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(StringReader(xml))
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name == "node") {
        val cls = (parser.getAttributeValue(null, "class") ?: "View").substringAfterLast('.')
        val text = parser.getAttributeValue(null, "text") ?: ""
        val desc = parser.getAttributeValue(null, "content-desc") ?: ""
        val id = (parser.getAttributeValue(null, "resource-id") ?: "").substringAfter(":id/")
        val boundsStr = parser.getAttributeValue(null, "bounds") ?: ""
        val bounds = parseBounds(boundsStr)
        val clickable = parser.getAttributeValue(null, "clickable") == "true"
        val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
        val checked = parser.getAttributeValue(null, "checked") == "true"
        val focused = parser.getAttributeValue(null, "focused") == "true"
        val enabled = parser.getAttributeValue(null, "enabled") != "false"
        val editable = cls.contains("EditText", ignoreCase = true)
        if (bounds != null) {
          out.add(
            RawUiNode(
              role = cls,
              text = text,
              desc = desc,
              id = id,
              bounds = bounds,
              visible = enabled,
              clickable = clickable,
              editable = editable,
              scrollable = scrollable,
              checked = checked,
              focused = focused,
            ),
          )
        }
      }
      event = parser.next()
    }
    return out
  }

  private val BOUNDS_RE = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

  private fun parseBounds(s: String): IntArray? {
    val m = BOUNDS_RE.find(s) ?: return null
    return intArrayOf(
      m.groupValues[1].toInt(),
      m.groupValues[2].toInt(),
      m.groupValues[3].toInt(),
      m.groupValues[4].toInt(),
    )
  }

  fun observe(shizuku: ShizukuBridge, store: Store, screenshot: Boolean, maxPx: Int, quality: Int): JSONObject {
    if (!shizuku.hasPermission()) {
      throw IllegalStateException("shizuku-required: install and start Shizuku from Google Play, then grant access in Orkestrate Phone")
    }
    val pkg = foregroundPackage(shizuku)
    if (pkg in store.blockedPackages) {
      return JSONObject()
        .put("blocked", true)
        .put("package", pkg)
        .put("reason", "App is in your blocklist; ask the user with handoff instead.")
    }

    val dump = shizuku.exec(listOf("sh", "-c", "uiautomator dump /data/local/tmp/ork_ui.xml >/dev/null && cat /data/local/tmp/ork_ui.xml"), 15)
    if (dump.exit != 0 || dump.stdout.isBlank()) {
      throw IllegalStateException("uiautomator dump failed: ${dump.stderr.take(200)}")
    }
    val resolvedPkg = pkg.ifEmpty { extractPackageFromXml(dump.stdout) }
    if (resolvedPkg in store.blockedPackages) {
      return JSONObject()
        .put("blocked", true)
        .put("package", resolvedPkg)
        .put("reason", "App is in your blocklist; ask the user with handoff instead.")
    }
    val indexed = ScreenTree.pruneAndIndex(parseUiDumpXml(dump.stdout))
    synchronized(lastRefs) {
      lastRefs.clear()
      for (item in indexed) {
        val b = item.node.bounds
        lastRefs[item.ref] = Rect(b[0], b[1], b[2], b[3])
      }
    }

    val nodesJson = JSONArray()
    for (item in indexed) nodesJson.put(item.toJson())

    val out = JSONObject()
      .put("package", resolvedPkg)
      .put("blocked", false)
      .put("secureScreen", false)
      .put("nodes", nodesJson)

    if (screenshot) {
      val png = shizuku.execBytes(listOf("screencap", "-p"), 10)
      if (png == null || png.isEmpty()) {
        out.put("secureScreen", true)
      } else {
        val b64 = downscalePng(png, maxPx, quality)
        if (b64 != null) out.put("screenshot_b64", b64)
        else out.put("secureScreen", true)
      }
    }
    return out
  }

  private fun foregroundPackage(shizuku: ShizukuBridge): String {
    val pkgRe = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")
    val act = shizuku.exec(listOf("sh", "-c", "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 2"), 5)
    pkgRe.find(act.stdout)?.let { return it.groupValues[1] }
    val win = shizuku.exec(listOf("sh", "-c", "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2"), 5)
    return pkgRe.find(win.stdout)?.groupValues?.get(1) ?: ""
  }

  fun extractPackageFromXml(xml: String): String {
    val re = Regex("""package="([^"]+)"""")
    var fallback = ""
    for (m in re.findAll(xml)) {
      val p = m.groupValues[1]
      if (p.isBlank()) continue
      if (p != "com.android.systemui") return p
      if (fallback.isEmpty()) fallback = p
    }
    return fallback
  }

  private fun downscalePng(png: ByteArray, maxPx: Int, quality: Int): String? {
    var bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
    val scale = minOf(1f, maxPx / maxOf(bmp.width, bmp.height).toFloat())
    if (scale < 1f) {
      val w = maxOf(1, (bmp.width * scale).toInt())
      val h = maxOf(1, (bmp.height * scale).toInt())
      val s = Bitmap.createScaledBitmap(bmp, w, h, true)
      if (s !== bmp) bmp.recycle()
      bmp = s
    }
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), baos)
    bmp.recycle()
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
  }

  fun act(shizuku: ShizukuBridge, params: JSONObject): JSONObject {
    if (!shizuku.hasPermission()) {
      throw IllegalStateException("shizuku-required: install and start Shizuku from Google Play, then grant access in Orkestrate Phone")
    }
    val action = params.optString("action", "")
    val cmd: List<String> = when (action) {
      "tap" -> {
        val (x, y) = resolvePoint(params)
        listOf("input", "tap", x.toString(), y.toString())
      }
      "long_press" -> {
        val (x, y) = resolvePoint(params)
        listOf("input", "swipe", x.toString(), y.toString(), x.toString(), y.toString(), "600")
      }
      "type" -> {
        if (params.has("ref") || (params.has("x") && params.has("y"))) {
          val (x, y) = resolvePoint(params)
          shizuku.exec(listOf("input", "tap", x.toString(), y.toString()), 5)
        }
        val text = params.optString("text", "").replace(" ", "%s")
        val r = shizuku.exec(listOf("input", "text", text), 10)
        if (r.exit != 0) throw IllegalStateException("input text failed: ${r.stderr}")
        if (params.optBoolean("submit", false)) {
          shizuku.exec(listOf("input", "keyevent", "KEYCODE_ENTER"), 5)
        }
        return JSONObject().put("ok", true).put("action", action)
      }
      "scroll" -> {
        val dir = params.optString("direction", "down")
        val (x1, y1, x2, y2) = when (dir) {
          "up" -> listOf(540, 800, 540, 1600)
          "left" -> listOf(200, 1200, 880, 1200)
          "right" -> listOf(880, 1200, 200, 1200)
          else -> listOf(540, 1600, 540, 800)
        }
        listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), "260")
      }
      "swipe" -> {
        val x1 = params.getInt("x")
        val y1 = params.getInt("y")
        val x2 = params.getInt("x2")
        val y2 = params.getInt("y2")
        listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), "280")
      }
      "key" -> {
        val kc = when (params.optString("key", "")) {
          "back" -> "KEYCODE_BACK"
          "home" -> "KEYCODE_HOME"
          "recents" -> "KEYCODE_APP_SWITCH"
          "enter" -> "KEYCODE_ENTER"
          else -> throw IllegalArgumentException("unknown key: ${params.optString("key")}")
        }
        listOf("input", "keyevent", kc)
      }
      else -> throw IllegalArgumentException("unknown action: $action")
    }
    val res = shizuku.exec(cmd, 10)
    if (res.exit != 0) throw IllegalStateException("act failed: ${res.stderr}")
    return JSONObject().put("ok", true).put("action", action)
  }

  private fun resolvePoint(params: JSONObject): Pair<Int, Int> {
    val ref = params.optString("ref", "")
    if (ref.isNotBlank()) {
      val rect = synchronized(lastRefs) { lastRefs[ref] }
        ?: throw IllegalArgumentException("unknown ref: $ref (call screen first)")
      return rect.centerX() to rect.centerY()
    }
    if (!params.has("x") || !params.has("y")) {
      throw IllegalArgumentException("ref or x,y coordinates are required")
    }
    return params.getDouble("x").toInt() to params.getDouble("y").toInt()
  }

  fun notifications(shizuku: ShizukuBridge, params: JSONObject): JSONObject {
    if (!shizuku.hasPermission()) {
      throw IllegalStateException("shizuku-required: install and start Shizuku from Google Play, then grant access in Orkestrate Phone")
    }
    val action = params.optString("action", "list")
    if (action != "list") {
      throw IllegalArgumentException("notifications.$action requires NotificationListener; only list is available via Shizuku")
    }
    val r = shizuku.exec(listOf("dumpsys", "notification", "--noredact"), 10)
    val arr = JSONArray()
    var curPkg = ""
    var curTitle = ""
    var curText = ""
    for (line in r.stdout.lines()) {
      val mPkg = Regex("""NotificationRecord\(.*pkg=(\S+)""").find(line)
      if (mPkg != null) {
        if (curPkg.isNotEmpty() && (curTitle.isNotEmpty() || curText.isNotEmpty())) {
          arr.put(JSONObject().put("package", curPkg).put("title", curTitle).put("text", curText))
          if (arr.length() >= 40) break
        }
        curPkg = mPkg.groupValues[1]
        curTitle = ""
        curText = ""
      } else if (line.contains("android.title=")) {
        curTitle = line.substringAfter("android.title=").trim().take(120)
      } else if (line.contains("android.text=")) {
        curText = line.substringAfter("android.text=").trim().take(240)
      }
    }
    if (curPkg.isNotEmpty() && arr.length() < 40 && (curTitle.isNotEmpty() || curText.isNotEmpty())) {
      arr.put(JSONObject().put("package", curPkg).put("title", curTitle).put("text", curText))
    }
    return JSONObject().put("notifications", arr)
  }
}
