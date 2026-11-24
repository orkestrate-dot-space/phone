package space.orkestrate.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun writeReadListMoveSearchDelete() {
    val fs = FileStore(tmp.root) { true }
    fs.exec(JSONObject().put("action", "write").put("path", "docs/note.txt").put("content", "hello"))
    val r = fs.exec(JSONObject().put("action", "read").put("path", "docs/note.txt"))
    assertEquals("hello", r.getString("content"))

    val l = fs.exec(JSONObject().put("action", "list").put("path", "docs"))
    assertEquals(1, l.getJSONArray("entries").length())

    val s = fs.exec(JSONObject().put("action", "search").put("query", "note"))
    assertEquals(1, s.getJSONArray("matches").length())

    fs.exec(JSONObject().put("action", "move").put("path", "docs/note.txt").put("dest", "docs/moved.txt"))
    fs.exec(JSONObject().put("action", "delete").put("path", "docs/moved.txt"))
    assertTrue(fs.exec(JSONObject().put("action", "list").put("path", "docs")).getJSONArray("entries").length() == 0)
  }

  @Test(expected = SecurityException::class)
  fun blocksPathTraversal() {
    val fs = FileStore(tmp.root) { true }
    fs.resolveSafe("../etc/passwd")
  }

  @Test(expected = IllegalStateException::class)
  fun requiresPermission() {
    val fs = FileStore(tmp.root) { false }
    fs.exec(JSONObject().put("action", "list"))
  }
}
