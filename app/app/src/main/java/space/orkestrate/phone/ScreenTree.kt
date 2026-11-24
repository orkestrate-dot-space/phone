package space.orkestrate.phone

import org.json.JSONArray
import org.json.JSONObject

data class RawUiNode(
  val role: String,
  val text: String,
  val desc: String,
  val id: String,
  val bounds: IntArray,
  val visible: Boolean,
  val clickable: Boolean,
  val editable: Boolean,
  val scrollable: Boolean,
  val checked: Boolean,
  val focused: Boolean,
)

data class IndexedUiNode(
  val ref: String,
  val node: RawUiNode,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("ref", ref)
    .put("role", node.role)
    .put("text", node.text)
    .put("desc", node.desc)
    .put("id", node.id)
    .put("bounds", JSONArray().apply { node.bounds.forEach { put(it) } })
    .put("clickable", node.clickable)
    .put("editable", node.editable)
    .put("scrollable", node.scrollable)
    .put("checked", node.checked)
    .put("focused", node.focused)
}

/**
 * Pure tree pruning + @eN ref assignment, separated from Android AccessibilityNodeInfo
 * so it can be unit-tested cleanly on JVM.
 */
object ScreenTree {
  const val MAX_NODES = 150

  fun pruneAndIndex(raw: List<RawUiNode>): List<IndexedUiNode> {
    val out = ArrayList<IndexedUiNode>(minOf(raw.size, MAX_NODES))
    var n = 1
    for (item in raw) {
      if (!item.visible) continue
      val w = item.bounds[2] - item.bounds[0]
      val h = item.bounds[3] - item.bounds[1]
      if (w <= 0 || h <= 0) continue
      val hasContent = item.text.isNotBlank() || item.desc.isNotBlank() || item.id.isNotBlank()
      val isInteractive = item.clickable || item.editable || item.scrollable || item.focused
      if (!hasContent && !isInteractive) continue
      out.add(IndexedUiNode("@e$n", item))
      n++
      if (out.size >= MAX_NODES) break
    }
    return out
  }
}
