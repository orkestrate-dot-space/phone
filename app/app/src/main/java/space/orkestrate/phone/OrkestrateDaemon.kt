package space.orkestrate.phone

import android.app.UiAutomation
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/**
 * Persistent uid-2000 Binder daemon hosted via Shizuku.bindUserService.
 * Uses UiAutomation + IInputManager + IClipboard directly in-process (no shell forks).
 */
class OrkestrateDaemon : IOrkestrateDaemon.Stub() {
  private var thread: HandlerThread? = null
  private var uiAuto: UiAutomation? = null
  private var uiInitTried = false
  private val lastNodes = mutableMapOf<String, Rect>()

  override fun destroy() {
    synchronized(this) {
      try {
        uiAuto?.javaClass?.getMethod("disconnect")?.invoke(uiAuto)
      } catch (_: Throwable) {}
      uiAuto = null
      try { thread?.quitSafely() } catch (_: Throwable) {}
      thread = null
    }
    exitProcess(0)
  }

  override fun call(reqJson: String): String {
    return try {
      val req = JSONObject(reqJson)
      when (req.optString("op")) {
        "ping" -> JSONObject()
          .put("ok", true)
          .put("uid", Process.myUid())
          .put("uiAutomation", ensureUiAutomation() != null)
          .toString()
        "screen" -> handleScreen(req).toString()
        "act" -> handleAct(req).toString()
        "clipboard" -> handleClipboard(req).toString()
        else -> JSONObject().put("ok", false).put("error", "unknown op: ${req.optString("op")}").toString()
      }
    } catch (e: Throwable) {
      JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
    }
  }

  @Synchronized
  private fun ensureUiAutomation(): UiAutomation? {
    if (uiAuto != null) return uiAuto
    if (uiInitTried) return null
    uiInitTried = true
    return try {
      val t = HandlerThread("ork-uiauto").apply { start() }
      thread = t
      val connCls = Class.forName("android.app.UiAutomationConnection")
      val conn = connCls.getDeclaredConstructor().newInstance()
      val iConnCls = Class.forName("android.app.IUiAutomationConnection")
      val ctor = UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, iConnCls)
      ctor.isAccessible = true
      val ua = ctor.newInstance(t.looper, conn)
      try {
        ua.javaClass.getMethod("connect", Int::class.javaPrimitiveType).invoke(ua, 1) // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
      } catch (_: Throwable) {
        ua.javaClass.getMethod("connect").invoke(ua)
      }
      uiAuto = ua
      ua
    } catch (_: Throwable) {
      null
    }
  }

  private fun handleScreen(req: JSONObject): JSONObject {
    val ua = ensureUiAutomation() ?: return JSONObject().put("ok", false).put("fallback", true)
    val rawNodes = mutableListOf<RawUiNode>()
    var pkg = ""
    try {
      val windows = ua.windows
      for (w in windows) {
        val root = try { w.root } catch (_: Throwable) { null } ?: continue
        val p = root.packageName?.toString() ?: ""
        if (pkg.isEmpty() && p.isNotBlank() && p != "com.android.systemui") pkg = p
        walkNode(root, rawNodes)
      }
    } catch (_: Throwable) {}
    if (rawNodes.isEmpty()) {
      val root = try { ua.rootInActiveWindow } catch (_: Throwable) { null }
      if (root != null) {
        val p = root.packageName?.toString() ?: ""
        if (pkg.isEmpty() && p.isNotBlank()) pkg = p
        walkNode(root, rawNodes)
      }
    }
    if (rawNodes.isEmpty()) {
      return JSONObject().put("ok", false).put("fallback", true)
    }

    val indexed = ScreenTree.pruneAndIndex(rawNodes)
    synchronized(lastNodes) {
      lastNodes.clear()
      for (item in indexed) {
        val b = item.node.bounds
        lastNodes[item.ref] = Rect(b[0], b[1], b[2], b[3])
      }
    }
    val nodesJson = JSONArray()
    for (item in indexed) nodesJson.put(item.toJson())

    val out = JSONObject()
      .put("ok", true)
      .put("package", pkg)
      .put("blocked", false)
      .put("secureScreen", false)
      .put("nodes", nodesJson)

    if (req.optBoolean("screenshot", false)) {
      val maxPx = req.optInt("maxPx", 1080)
      val quality = req.optInt("quality", 75)
      val bmp = try { ua.takeScreenshot() } catch (_: Throwable) { null }
      if (bmp == null) {
        out.put("secureScreen", true)
      } else {
        val b64 = encodeBitmap(bmp, maxPx, quality)
        if (b64 != null) out.put("screenshot_b64", b64) else out.put("secureScreen", true)
      }
    }
    return out
  }

  private fun walkNode(node: AccessibilityNodeInfo, out: MutableList<RawUiNode>) {
    if (out.size >= 400) return
    val r = Rect()
    node.getBoundsInScreen(r)
    if (r.width() > 0 && r.height() > 0) {
      val cls = (node.className?.toString() ?: "View").substringAfterLast('.')
      val text = node.text?.toString() ?: ""
      val desc = node.contentDescription?.toString() ?: ""
      val id = (node.viewIdResourceName ?: "").substringAfter(":id/")
      out.add(
        RawUiNode(
          role = cls,
          text = text,
          desc = desc,
          id = id,
          bounds = intArrayOf(r.left, r.top, r.right, r.bottom),
          visible = node.isVisibleToUser && node.isEnabled,
          clickable = node.isClickable,
          editable = node.isEditable,
          scrollable = node.isScrollable,
          checked = node.isChecked,
          focused = node.isFocused,
        ),
      )
    }
    for (i in 0 until node.childCount) {
      val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
      walkNode(child, out)
    }
  }

  private fun encodeBitmap(src: Bitmap, maxPx: Int, quality: Int): String? {
    var bmp = src
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

  private fun handleAct(req: JSONObject): JSONObject {
    val action = req.optString("action", "")
    when (action) {
      "tap" -> {
        val (x, y) = resolvePoint(req)
        if (!injectTap(x.toFloat(), y.toFloat(), 40L)) return JSONObject().put("ok", false).put("fallback", true)
      }
      "long_press" -> {
        val (x, y) = resolvePoint(req)
        if (!injectTap(x.toFloat(), y.toFloat(), 600L)) return JSONObject().put("ok", false).put("fallback", true)
      }
      "swipe" -> {
        val x1 = req.getDouble("x").toFloat()
        val y1 = req.getDouble("y").toFloat()
        val x2 = req.getDouble("x2").toFloat()
        val y2 = req.getDouble("y2").toFloat()
        if (!injectSwipe(x1, y1, x2, y2, 260L)) return JSONObject().put("ok", false).put("fallback", true)
      }
      "scroll" -> {
        val (x1, y1, x2, y2) = when (req.optString("direction", "down")) {
          "up" -> listOf(540f, 800f, 540f, 1600f)
          "left" -> listOf(200f, 1200f, 880f, 1200f)
          "right" -> listOf(880f, 1200f, 200f, 1200f)
          else -> listOf(540f, 1600f, 540f, 800f)
        }
        if (!injectSwipe(x1, y1, x2, y2, 240L)) return JSONObject().put("ok", false).put("fallback", true)
      }
      "key" -> {
        val code = when (req.optString("key", "")) {
          "back" -> KeyEvent.KEYCODE_BACK
          "home" -> KeyEvent.KEYCODE_HOME
          "recents" -> KeyEvent.KEYCODE_APP_SWITCH
          "enter" -> KeyEvent.KEYCODE_ENTER
          else -> return JSONObject().put("ok", false).put("error", "unknown key")
        }
        if (!injectKey(code)) return JSONObject().put("ok", false).put("fallback", true)
      }
      "type" -> {
        val text = req.optString("text", "")
        if (req.has("ref") || (req.has("x") && req.has("y"))) {
          val (x, y) = resolvePoint(req)
          injectTap(x.toFloat(), y.toFloat(), 30L)
          Thread.sleep(60)
        }
        val ua = ensureUiAutomation()
        val focused = try { ua?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
        if (focused != null) {
          val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
          val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
          if (ok) {
            if (req.optBoolean("submit", false)) injectKey(KeyEvent.KEYCODE_ENTER)
            return JSONObject().put("ok", true).put("action", action)
          }
        }
        return JSONObject().put("ok", false).put("fallback", true)
      }
      else -> return JSONObject().put("ok", false).put("error", "unknown action: $action")
    }
    return JSONObject().put("ok", true).put("action", action)
  }

  private fun resolvePoint(req: JSONObject): Pair<Int, Int> {
    val ref = req.optString("ref", "")
    if (ref.isNotBlank()) {
      val rect = synchronized(lastNodes) { lastNodes[ref] }
        ?: throw IllegalArgumentException("unknown ref: $ref")
      return rect.centerX() to rect.centerY()
    }
    return req.getDouble("x").toInt() to req.getDouble("y").toInt()
  }

  private fun inputManager(): Pair<Any, java.lang.reflect.Method>? {
    return try {
      val sm = Class.forName("android.os.ServiceManager")
      val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
      val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
      val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
      val inject = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
      im to inject
    } catch (_: Throwable) {
      null
    }
  }

  private fun injectTap(x: Float, y: Float, holdMs: Long): Boolean {
    val (im, inject) = inputManager() ?: return false
    val down = SystemClock.uptimeMillis()
    val evDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    val ok1 = inject.invoke(im, evDown, 2) as Boolean
    evDown.recycle()
    Thread.sleep(holdMs)
    val up = SystemClock.uptimeMillis()
    val evUp = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    val ok2 = inject.invoke(im, evUp, 2) as Boolean
    evUp.recycle()
    return ok1 && ok2
  }

  private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
    val (im, inject) = inputManager() ?: return false
    val down = SystemClock.uptimeMillis()
    val evDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    inject.invoke(im, evDown, 2)
    evDown.recycle()
    val steps = 10
    for (i in 1..steps) {
      Thread.sleep(durationMs / steps)
      val t = i / steps.toFloat()
      val evMove = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
      }
      inject.invoke(im, evMove, 2)
      evMove.recycle()
    }
    val evUp = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    val ok = inject.invoke(im, evUp, 2) as Boolean
    evUp.recycle()
    return ok
  }

  private fun injectKey(keyCode: Int): Boolean {
    val (im, inject) = inputManager() ?: return false
    val now = SystemClock.uptimeMillis()
    val evDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
    val evUp = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
    val ok1 = inject.invoke(im, evDown, 2) as Boolean
    val ok2 = inject.invoke(im, evUp, 2) as Boolean
    return ok1 && ok2
  }

  private fun handleClipboard(req: JSONObject): JSONObject {
    return try {
      val sm = Class.forName("android.os.ServiceManager")
      val binder = sm.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
      val stub = Class.forName("android.content.IClipboard\$Stub")
      val cb = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
      val action = req.optString("action", "get")
      if (action == "set") {
        val clip = ClipData.newPlainText("phone", req.optString("text", ""))
        val m = cb.javaClass.methods.first { it.name == "setPrimaryClip" }
        val args = when (m.parameterCount) {
          2 -> arrayOf(clip, "com.android.shell")
          3 -> arrayOf(clip, "com.android.shell", 0)
          else -> arrayOf(clip, "com.android.shell", null, 0)
        }
        m.invoke(cb, *args)
        JSONObject().put("ok", true)
      } else {
        val m = cb.javaClass.methods.first { it.name == "getPrimaryClip" }
        val args = when (m.parameterCount) {
          1 -> arrayOf("com.android.shell")
          2 -> arrayOf("com.android.shell", 0)
          else -> arrayOf("com.android.shell", null, 0)
        }
        val clip = m.invoke(cb, *args) as? ClipData
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
        JSONObject().put("ok", true).put("text", text)
      }
    } catch (_: Throwable) {
      JSONObject().put("ok", false).put("fallback", true)
    }
  }
}
