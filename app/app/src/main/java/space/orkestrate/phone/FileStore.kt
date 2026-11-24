package space.orkestrate.phone

import android.os.Build
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared-storage (/sdcard) file manager with strict canonicalPath containment.
 * rootDir is injectable so unit tests can run against a temp directory.
 */
class FileStore(
  private val rootDir: File = Environment.getExternalStorageDirectory(),
  private val permissionCheck: () -> Boolean = {
    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
  },
) {
  private val rootCanon: String by lazy { rootDir.canonicalPath }

  fun resolveSafe(relOrAbs: String?): File {
    val raw = (relOrAbs ?: "").trim()
    val candidate = when {
      raw.isEmpty() || raw == "." || raw == "/" -> rootDir
      raw.startsWith("/sdcard/") -> File(rootDir, raw.removePrefix("/sdcard/"))
      raw == "/sdcard" -> rootDir
      raw.startsWith(rootCanon) -> File(raw)
      raw.startsWith("/") -> File(rootDir, raw.removePrefix("/"))
      else -> File(rootDir, raw)
    }
    val canon = candidate.canonicalPath
    if (canon != rootCanon && !canon.startsWith(rootCanon + File.separator)) {
      throw SecurityException("path escapes storage root: $relOrAbs")
    }
    return candidate
  }

  fun relativePath(f: File): String {
    val c = f.canonicalPath
    return if (c == rootCanon) "" else c.removePrefix(rootCanon + File.separator)
  }

  fun exec(params: JSONObject): JSONObject {
    if (!permissionCheck()) throw IllegalStateException("files-permission-required")
    val action = params.optString("action", "")
    return when (action) {
      "list" -> list(params.optString("path", ""))
      "read" -> read(params.optString("path", ""), params.optInt("maxBytes", 262144).coerceIn(1, 1024 * 1024))
      "write" -> write(params)
      "search" -> search(params.optString("query", ""), params.optString("path", ""))
      "move" -> move(params.optString("path", ""), params.optString("dest", ""))
      "delete" -> delete(params.optString("path", ""))
      "pull" -> pull(params.optString("path", ""), params.optInt("maxBytes", 10 * 1024 * 1024).coerceIn(1, 15 * 1024 * 1024))
      "push" -> push(params)
      else -> throw IllegalArgumentException("unknown files action: $action")
    }
  }

  private fun list(path: String): JSONObject {
    val dir = resolveSafe(path)
    if (!dir.exists()) throw IllegalArgumentException("not found: $path")
    if (!dir.isDirectory) throw IllegalArgumentException("not a directory: $path")
    val entries = JSONArray()
    val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    for (f in children.take(200)) {
      entries.put(
        JSONObject()
          .put("name", f.name)
          .put("path", relativePath(f))
          .put("dir", f.isDirectory)
          .put("size", if (f.isFile) f.length() else 0L)
          .put("mtime", f.lastModified()),
      )
    }
    return JSONObject().put("path", relativePath(dir)).put("entries", entries).put("truncated", children.size > 200)
  }

  private fun read(path: String, maxBytes: Int): JSONObject {
    if (path.isBlank()) throw IllegalArgumentException("path is required")
    val f = resolveSafe(path)
    if (!f.exists() || !f.isFile) throw IllegalArgumentException("file not found: $path")
    val total = f.length()
    val toRead = minOf(total, maxBytes.toLong()).toInt()
    val buf = ByteArray(toRead)
    f.inputStream().use { inp ->
      var off = 0
      while (off < toRead) {
        val n = inp.read(buf, off, toRead - off)
        if (n <= 0) break
        off += n
      }
    }
    return JSONObject()
      .put("path", relativePath(f))
      .put("size", total)
      .put("truncated", total > toRead)
      .put("content", String(buf, Charsets.UTF_8))
  }

  private fun write(params: JSONObject): JSONObject {
    val path = params.optString("path", "")
    if (path.isBlank()) throw IllegalArgumentException("path is required")
    val f = resolveSafe(path)
    f.parentFile?.mkdirs()
    val encoding = params.optString("encoding", "utf8")
    val bytes = if (encoding == "base64") {
      Base64.decode(params.optString("content", ""), Base64.DEFAULT)
    } else {
      params.optString("content", "").toByteArray(Charsets.UTF_8)
    }
    f.writeBytes(bytes)
    return JSONObject().put("path", relativePath(f)).put("bytes", bytes.size)
  }

  private fun search(query: String, path: String): JSONObject {
    val q = query.trim().lowercase()
    if (q.isEmpty()) throw IllegalArgumentException("query is required")
    val start = resolveSafe(path)
    if (!start.exists()) throw IllegalArgumentException("not found: $path")
    val matches = JSONArray()
    val queue = ArrayDeque<File>()
    queue.add(start)
    var visited = 0
    while (queue.isNotEmpty() && matches.length() < 50 && visited < 5000) {
      val cur = queue.removeFirst()
      visited++
      val list = cur.listFiles() ?: continue
      for (f in list) {
        if (f.name.lowercase().contains(q)) {
          matches.put(
            JSONObject()
              .put("name", f.name)
              .put("path", relativePath(f))
              .put("dir", f.isDirectory)
              .put("size", if (f.isFile) f.length() else 0L),
          )
          if (matches.length() >= 50) break
        }
        if (f.isDirectory && !f.name.startsWith(".")) queue.add(f)
      }
    }
    return JSONObject().put("query", query).put("matches", matches)
  }

  private fun move(path: String, dest: String): JSONObject {
    if (path.isBlank() || dest.isBlank()) throw IllegalArgumentException("path and dest are required")
    val src = resolveSafe(path)
    val dst = resolveSafe(dest)
    if (!src.exists()) throw IllegalArgumentException("not found: $path")
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) throw IllegalStateException("move failed")
    return JSONObject().put("from", relativePath(src)).put("to", relativePath(dst))
  }

  private fun delete(path: String): JSONObject {
    if (path.isBlank()) throw IllegalArgumentException("path is required")
    val f = resolveSafe(path)
    if (!f.exists()) throw IllegalArgumentException("not found: $path")
    val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
    if (!ok) throw IllegalStateException("delete failed")
    return JSONObject().put("deleted", relativePath(f))
  }

  private fun pull(path: String, maxBytes: Int): JSONObject {
    if (path.isBlank()) throw IllegalArgumentException("path is required")
    val f = resolveSafe(path)
    if (!f.exists() || !f.isFile) throw IllegalArgumentException("file not found: $path")
    if (f.length() > maxBytes) throw IllegalArgumentException("file exceeds $maxBytes bytes")
    val bytes = f.readBytes()
    return JSONObject()
      .put("path", relativePath(f))
      .put("bytes", bytes.size)
      .put("bytes_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
  }

  private fun push(params: JSONObject): JSONObject {
    val path = params.optString("path", "")
    if (path.isBlank()) throw IllegalArgumentException("path is required")
    val b64 = params.optString("bytes_b64", "")
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    val f = resolveSafe(path)
    f.parentFile?.mkdirs()
    f.writeBytes(bytes)
    return JSONObject().put("path", relativePath(f)).put("bytes", bytes.size)
  }
}
