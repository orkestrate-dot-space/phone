package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTreeTest {
  @Test fun prunesInvisibleAndEmptyWrappersAndAssignsRefs() {
    val nodes = listOf(
      RawUiNode("FrameLayout", "", "", "", intArrayOf(0, 0, 1080, 2400), true, false, false, false, false, false),
      RawUiNode("TextView", "Hidden", "", "", intArrayOf(0, 0, 100, 50), false, false, false, false, false, false),
      RawUiNode("Button", "Send", "", "send_btn", intArrayOf(10, 20, 200, 100), true, true, false, false, false, false),
      RawUiNode("EditText", "", "Message", "input", intArrayOf(10, 120, 900, 200), true, true, true, false, false, true),
    )
    val indexed = ScreenTree.pruneAndIndex(nodes)
    assertEquals(2, indexed.size)
    assertEquals("@e1", indexed[0].ref)
    assertEquals("Send", indexed[0].node.text)
    assertEquals("@e2", indexed[1].ref)
    assertEquals("input", indexed[1].node.id)
  }
}
