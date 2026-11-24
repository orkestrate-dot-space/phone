package space.orkestrate.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneAccessibilityService : AccessibilityService() {
  companion object {
    @Volatile var instance: PhoneAccessibilityService? = null
      private set
  }

  private val lastRefs = mutableMapOf<String, Rect>()

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d("phone", "accessibility connected")
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (instance === this) instance = null
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
  override fun onInterrupt() {}

  fun observe(store: Store, screenshot: Boolean, maxPx: Int, quality: Int): JSONObject {
    val root = rootInActiveWindow ?: throw IllegalStateException("no-active-window")
    val pkg = root.packageName?.toString() ?: ""
    if (pkg in store.blockedPackages) {
      return JSONObject()
        .put("blocked", true)
        .put("package", pkg)
        .put("reason", "App is in your blocklist; ask the user with handoff instead.")
    }

    val raw = mutableListOf<RawUiNode>()
    collect(root, raw)
    val indexed = ScreenTree.pruneAndIndex(raw)
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
      .put("package", pkg)
      .put("blocked", false)
      .put("secureScreen", false)
      .put("nodes", nodesJson)

    if (screenshot) {
      if (Build.VERSION.SDK_INT >= 30) {
        val shot = captureScreen(maxPx, quality)
        if (shot.first != null) out.put("screenshot_b64", shot.first)
        if (shot.second) out.put("secureScreen", true)
      } else {
        out.put("screenshotError", "requires-android-11")
      }
    }
    return out
  }

  private fun collect(node: AccessibilityNodeInfo?, sink: MutableList<RawUiNode>) {
    if (node == null || sink.size >= 400) return
    val r = Rect()
    node.getBoundsInScreen(r)
    val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
    val viewId = node.viewIdResourceName?.substringAfter(":id/") ?: ""
    sink.add(
      RawUiNode(
        role = cls,
        text = node.text?.toString() ?: "",
        desc = node.contentDescription?.toString() ?: "",
        id = viewId,
        bounds = intArrayOf(r.left, r.top, r.right, r.bottom),
        visible = node.isVisibleToUser,
        clickable = node.isClickable,
        editable = node.isEditable,
        scrollable = node.isScrollable,
        checked = node.isChecked,
        focused = node.isFocused,
      ),
    )
    for (i in 0 until node.childCount) {
      collect(node.getChild(i), sink)
    }
  }

  private fun captureScreen(maxPx: Int, quality: Int): Pair<String?, Boolean> {
    if (Build.VERSION.SDK_INT < 30) return null to false
    val latch = CountDownLatch(1)
    var b64: String? = null
    var secure = false
    takeScreenshot(
      Display.DEFAULT_DISPLAY,
      mainExecutor,
      object : TakeScreenshotCallback {
        override fun onSuccess(res: ScreenshotResult) {
          try {
            val hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
            if (hw == null) {
              res.hardwareBuffer.close()
              return
            }
            var bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            res.hardwareBuffer.close()
            val scale = minOf(1f, maxPx / maxOf(bmp.width, bmp.height).toFloat())
            if (scale < 1f) {
              val w = maxOf(1, (bmp.width * scale).toInt())
              val h = maxOf(1, (bmp.height * scale).toInt())
              val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
              if (scaled !== bmp) bmp.recycle()
              bmp = scaled
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), baos)
            bmp.recycle()
            b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
          } catch (e: Exception) {
            Log.d("phone", "screenshot encode fail: $e")
          } finally {
            latch.countDown()
          }
        }

        override fun onFailure(errorCode: Int) {
          if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) secure = true
          Log.d("phone", "takeScreenshot fail code=$errorCode")
          latch.countDown()
        }
      },
    )
    latch.await(5, TimeUnit.SECONDS)
    return b64 to secure
  }

  fun act(store: Store, params: JSONObject): JSONObject {
    val root = rootInActiveWindow ?: throw IllegalStateException("no-active-window")
    val pkg = root.packageName?.toString() ?: ""
    if (pkg in store.blockedPackages) {
      throw IllegalStateException("blocked-app: $pkg")
    }
    val action = params.optString("action", "")
    when (action) {
      "tap", "long_press" -> {
        val pt = resolvePoint(params)
        val ok = dispatchTap(pt.first, pt.second, longPress = action == "long_press")
        if (!ok) throw IllegalStateException("gesture-failed")
      }
      "type" -> {
        val text = params.optString("text", "")
        val target = findTargetNode(root, params)
          ?: throw IllegalArgumentException("no editable/focused target found")
        if (params.optBoolean("clear", false)) {
          target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
          })
        }
        val args = Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) throw IllegalStateException("set-text-failed")
        if (params.optBoolean("submit", false) && Build.VERSION.SDK_INT >= 30) {
          target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
      }
      "scroll" -> {
        val dir = params.optString("direction", "down")
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dy = dm.heightPixels * 0.28f
        val dx = dm.widthPixels * 0.28f
        val (x1, y1, x2, y2) = when (dir) {
          "up" -> listOf(cx, cy - dy, cx, cy + dy)
          "left" -> listOf(cx - dx, cy, cx + dx, cy)
          "right" -> listOf(cx + dx, cy, cx - dx, cy)
          else -> listOf(cx, cy + dy, cx, cy - dy)
        }
        if (!dispatchSwipe(x1, y1, x2, y2, 260)) throw IllegalStateException("scroll-failed")
      }
      "swipe" -> {
        val x1 = params.getDouble("x").toFloat()
        val y1 = params.getDouble("y").toFloat()
        val x2 = params.getDouble("x2").toFloat()
        val y2 = params.getDouble("y2").toFloat()
        if (!dispatchSwipe(x1, y1, x2, y2, 280)) throw IllegalStateException("swipe-failed")
      }
      "key" -> {
        val k = params.optString("key", "")
        val ok = when (k) {
          "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
          "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
          "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
          "enter" -> {
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && Build.VERSION.SDK_INT >= 30) {
              f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else false
          }
          else -> throw IllegalArgumentException("unknown key: $k")
        }
        if (!ok) throw IllegalStateException("key-action-failed")
      }
      else -> throw IllegalArgumentException("unknown action: $action")
    }
    return JSONObject().put("ok", true).put("action", action)
  }

  private fun resolvePoint(params: JSONObject): Pair<Float, Float> {
    val ref = params.optString("ref", "")
    if (ref.isNotBlank()) {
      val rect = synchronized(lastRefs) { lastRefs[ref] }
        ?: throw IllegalArgumentException("unknown ref: $ref (call screen first)")
      return rect.exactCenterX() to rect.exactCenterY()
    }
    if (!params.has("x") || !params.has("y")) {
      throw IllegalArgumentException("ref or x,y coordinates are required")
    }
    return params.getDouble("x").toFloat() to params.getDouble("y").toFloat()
  }

  private fun findTargetNode(root: AccessibilityNodeInfo, params: JSONObject): AccessibilityNodeInfo? {
    val ref = params.optString("ref", "")
    if (ref.isNotBlank()) {
      val rect = synchronized(lastRefs) { lastRefs[ref] }
      if (rect != null) {
        val hit = findNodeByBounds(root, rect)
        if (hit != null) return hit
      }
    }
    return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
  }

  private fun findNodeByBounds(node: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
    if (node == null) return null
    val r = Rect()
    node.getBoundsInScreen(r)
    if (r == target && (node.isEditable || node.isFocusable)) return node
    for (i in 0 until node.childCount) {
      val hit = findNodeByBounds(node.getChild(i), target)
      if (hit != null) return hit
    }
    return null
  }

  private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.isEditable && node.isVisibleToUser) return node
    for (i in 0 until node.childCount) {
      val hit = findFirstEditable(node.getChild(i))
      if (hit != null) return hit
    }
    return null
  }

  private fun dispatchTap(x: Float, y: Float, longPress: Boolean): Boolean {
    val p = Path().apply { moveTo(x, y) }
    val dur = if (longPress) 600L else 60L
    val g = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(p, 0L, dur))
      .build()
    return dispatchGestureBlocking(g)
  }

  private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
    val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
    val g = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(p, 0L, durMs))
      .build()
    return dispatchGestureBlocking(g)
  }

  private fun dispatchGestureBlocking(g: GestureDescription): Boolean {
    val latch = CountDownLatch(1)
    var ok = false
    val started = dispatchGesture(g, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
      override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; latch.countDown() }
    }, null)
    if (!started) return false
    latch.await(3, TimeUnit.SECONDS)
    return ok
  }
}
