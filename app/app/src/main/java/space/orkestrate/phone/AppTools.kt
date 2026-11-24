package space.orkestrate.phone

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AppTools {
  fun permissionsMap(ctx: Context, store: Store, shizuku: ShizukuBridge): JSONObject {
    val shizukuGranted = shizuku.hasPermission()
    return JSONObject()
      .put("shizuku", shizukuGranted)
      .put("shizukuInstalled", shizuku.isInstalled())
      .put("shizukuRunning", shizuku.isRunning())
      .put("files", store.safTreeUri != null || shizukuGranted)
      .put("postNotifications", Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
      .put("background", ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
      .put("camera", ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }

  fun batteryInfo(ctx: Context): JSONObject {
    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return JSONObject()
      .put("level", if (level >= 0) (level * 100 / scale) else -1)
      .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
      .put("voltageMv", b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
      .put("tempC", ((b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)) / 10.0)
  }

  fun deviceInfo(ctx: Context, store: Store, shizuku: ShizukuBridge): JSONObject {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val km = ctx.getSystemService(KeyguardManager::class.java)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    val net = when {
      caps == null -> "offline"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
      else -> "other"
    }
    val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val vcode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt()
    else @Suppress("DEPRECATION") pkg.versionCode

    return JSONObject()
      .put("name", store.name)
      .put("model", Build.MODEL)
      .put("manufacturer", Build.MANUFACTURER)
      .put("androidVersion", Build.VERSION.RELEASE)
      .put("sdk", Build.VERSION.SDK_INT)
      .put("appVersion", vcode)
      .put("paused", store.paused)
      .put("battery", batteryInfo(ctx))
      .put("screen", JSONObject().put("interactive", pm.isInteractive).put("locked", km.isKeyguardLocked))
      .put("network", net)
      .put("permissions", permissionsMap(ctx, store, shizuku))
  }

  fun appsManage(ctx: Context, params: JSONObject): JSONObject {
    val action = params.optString("action", "list")
    val pm = ctx.packageManager
    return when (action) {
      "list" -> {
        val query = params.optString("query", "").trim().lowercase()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val list = mutableListOf<Pair<String, String>>()
        for (ri in resolved) {
          val pkg = ri.activityInfo.packageName
          if (!seen.add(pkg)) continue
          val label = ri.loadLabel(pm)?.toString() ?: pkg
          if (query.isNotEmpty() && !label.lowercase().contains(query) && !pkg.lowercase().contains(query)) continue
          list.add(pkg to label)
        }
        list.sortBy { it.second.lowercase() }
        val arr = JSONArray()
        for ((pkg, label) in list.take(200)) {
          arr.put(JSONObject().put("package", pkg).put("label", label))
        }
        JSONObject().put("apps", arr).put("truncated", list.size > 200)
      }
      "open" -> {
        val url = params.optString("url", "").trim()
        val pkg = params.optString("package", "").trim()
        val intent = when {
          url.isNotEmpty() -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
          pkg.isNotEmpty() -> pm.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("app not launchable: $pkg")
          else -> throw IllegalArgumentException("package or url is required")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        JSONObject().put("opened", true)
      }
      else -> throw IllegalArgumentException("unknown apps action: $action")
    }
  }

  fun clipboardOp(ctx: Context, params: JSONObject): JSONObject {
    val action = params.optString("action", "get")
    val latch = CountDownLatch(1)
    var out: JSONObject? = null
    var err: Exception? = null
    Handler(Looper.getMainLooper()).post {
      try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        when (action) {
          "get" -> {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
            out = JSONObject().put("text", text)
          }
          "set" -> {
            val text = params.optString("text", "")
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            out = JSONObject().put("set", true).put("length", text.length)
          }
          else -> throw IllegalArgumentException("unknown clipboard action: $action")
        }
      } catch (e: Exception) {
        err = e
      } finally {
        latch.countDown()
      }
    }
    latch.await(3, TimeUnit.SECONDS)
    err?.let { throw it }
    return out ?: throw IllegalStateException("clipboard timeout")
  }
}
