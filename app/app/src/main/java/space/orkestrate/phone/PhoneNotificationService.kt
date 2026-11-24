package space.orkestrate.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class PhoneNotificationService : NotificationListenerService() {
  companion object {
    @Volatile var instance: PhoneNotificationService? = null
      private set
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    instance = this
  }

  override fun onListenerDisconnected() {
    if (instance === this) instance = null
    super.onListenerDisconnected()
  }

  fun exec(params: JSONObject): JSONObject {
    val action = params.optString("action", "list")
    return when (action) {
      "list" -> {
        val arr = JSONArray()
        for (sbn in activeNotifications ?: emptyArray()) {
          val extras = sbn.notification.extras
          val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
          val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
          val hasReply = findReplyAction(sbn) != null
          arr.put(
            JSONObject()
              .put("key", sbn.key)
              .put("package", sbn.packageName)
              .put("title", title)
              .put("text", text)
              .put("postTime", sbn.postTime)
              .put("ongoing", sbn.isOngoing)
              .put("canReply", hasReply),
          )
        }
        JSONObject().put("notifications", arr)
      }
      "dismiss" -> {
        val key = params.optString("key", "")
        if (key.isBlank()) throw IllegalArgumentException("key is required")
        cancelNotification(key)
        JSONObject().put("dismissed", key)
      }
      "reply" -> {
        val key = params.optString("key", "")
        val text = params.optString("text", "")
        if (key.isBlank() || text.isBlank()) throw IllegalArgumentException("key and text are required")
        val sbn = (activeNotifications ?: emptyArray()).firstOrNull { it.key == key }
          ?: throw IllegalArgumentException("notification not found: $key")
        val pair = findReplyAction(sbn) ?: throw IllegalStateException("notification has no inline reply")
        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(pair.second.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(pair.second), intent, bundle)
        pair.first.actionIntent.send(this, 0, intent)
        JSONObject().put("replied", key)
      }
      else -> throw IllegalArgumentException("unknown notifications action: $action")
    }
  }

  private fun findReplyAction(sbn: StatusBarNotification): Pair<Notification.Action, RemoteInput>? {
    val actions = sbn.notification.actions ?: return null
    for (a in actions) {
      val ri = a.remoteInputs?.firstOrNull() ?: continue
      return a to ri
    }
    return null
  }
}
