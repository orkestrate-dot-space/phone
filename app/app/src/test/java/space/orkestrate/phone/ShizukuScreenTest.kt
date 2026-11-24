package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuScreenTest {
  @Test fun parsesUiAutomatorXmlAndIndexesRefs() {
    val xml = """
      <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
      <hierarchy rotation="0">
        <node index="0" text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false" enabled="true" focused="false" scrollable="false" checked="false">
          <node index="0" text="Send" resource-id="com.example:id/send_btn" class="android.widget.Button" bounds="[900,1800][1040,1920]" clickable="true" enabled="true" focused="false" scrollable="false" checked="false" />
          <node index="1" text="" content-desc="Message" resource-id="com.example:id/input" class="android.widget.EditText" bounds="[40,1800][880,1920]" clickable="true" enabled="true" focused="true" scrollable="false" checked="false" />
        </node>
      </hierarchy>
    """.trimIndent()

    val raw = ShizukuScreen.parseUiDumpXml(xml)
    val indexed = ScreenTree.pruneAndIndex(raw)
    assertEquals(2, indexed.size)
    assertEquals("@e1", indexed[0].ref)
    assertEquals("Send", indexed[0].node.text)
    assertEquals("@e2", indexed[1].ref)
    assertTrue(indexed[1].node.editable)
  }
}
