package space.orkestrate.phone

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File operations via:
 * 1) User-picked SAF folder (works on 5G with zero MANAGE_EXTERNAL_STORAGE), OR
 * 2) Shizuku uid-2000 /sdcard access when no SAF folder is picked.
 */
class SafFileStore(
  private val ctx: Context,
  private val store: Store,
  private val shizuku: ShizukuBridge,
) {
  fun hasAccess(): Boolean = store.safTreeUri != null || shizuku.hasPermission()

  fun exec(params: JSONObject): JSONObject {
    val treeStr = store.safTreeUri
    if (treeStr != null) {
      val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeStr))
        ?: throw IllegalStateException("saf-folder-invalid")
      return execSaf(tree, params)
    }
    if (shizuku.hasPermission()) {
      return execShizuku(params)
    }
    throw IllegalStateException("files-permission-required: pick a folder in Orkestrate Phone or enable Shizuku")
  }

  private fun resolveSaf(root: DocumentFile, relPath: String, createDirs: Boolean = false): DocumentFile? {
    val parts = relPath.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) throw SecurityException("path escapes folder: $relPath")
    var cur: DocumentFile = root
    for ((i, p) in parts.withIndex()) {
      val next = cur.findFile(p)
      if (next != null) {
        cur = next
      } else if (createDirs && i < parts.lastIndex) {
        cur = cur.createDirectory(p) ?: return null
      } else {
        return null
      }
    }
    return cur
  }

  private fun execSaf(root: DocumentFile, params: JSONObject): JSONObject {
    val action = params.optString("action", "")
    return when (action) {
      "list" -> {
        val path = params.optString("path", "")
        val dir = if (path.isBlank()) root else resolveSaf(root, path)
          ?: throw IllegalArgumentException("not found: $path")
        val arr = JSONArray()
        for (f in dir.listFiles().take(200)) {
          arr.put(
            JSONObject()
              .put("name", f.name ?: "")
              .put("dir", f.isDirectory)
              .put("size", if (f.isFile) f.length() else 0L)
              .put("mtime", f.lastModified()),
          )
        }
        JSONObject().put("path", path).put("entries", arr)
      }
      "read" -> {
        val path = params.optString("path", "")
        val f = resolveSaf(root, path) ?: throw IllegalArgumentException("file not found: $path")
        val maxBytes = params.optInt("maxBytes", 262144).coerceIn(1, 1024 * 1024)
        val bytes = ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: ByteArray(0)
        val truncated = bytes.size > maxBytes
        val slice = if (truncated) bytes.copyOf(maxBytes) else bytes
        JSONObject()
          .put("path", path)
          .put("size", bytes.size)
          .put("truncated", truncated)
          .put("content", String(slice, Charsets.UTF_8))
      }
      "write", "push" -> {
        val path = params.optString("path", "")
        if (path.isBlank()) throw IllegalArgumentException("path is required")
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." } || parts.isEmpty()) throw SecurityException("invalid path: $path")
        val parentRel = parts.dropLast(1).joinToString("/")
        val parent = if (parentRel.isEmpty()) root else resolveSaf(root, parentRel, createDirs = true)
          ?: throw IllegalStateException("could not create parent directory")
        val name = parts.last()
        parent.findFile(name)?.delete()
        val f = parent.createFile("application/octet-stream", name)
          ?: throw IllegalStateException("could not create file: $name")
        val bytes = if (action == "push") {
          Base64.decode(params.optString("bytes_b64", ""), Base64.DEFAULT)
        } else if (params.optString("encoding", "utf8") == "base64") {
          Base64.decode(params.optString("content", ""), Base64.DEFAULT)
        } else {
          params.optString("content", "").toByteArray(Charsets.UTF_8)
        }
        ctx.contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
        JSONObject().put("path", path).put("bytes", bytes.size)
      }
      "pull" -> {
        val path = params.optString("path", "")
        val f = resolveSaf(root, path) ?: throw IllegalArgumentException("file not found: $path")
        val bytes = ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: ByteArray(0)
        JSONObject()
          .put("path", path)
          .put("bytes", bytes.size)
          .put("bytes_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
      }
      "delete" -> {
        val path = params.optString("path", "")
        val f = resolveSaf(root, path) ?: throw IllegalArgumentException("not found: $path")
        if (!f.delete()) throw IllegalStateException("delete failed")
        JSONObject().put("deleted", path)
      }
      "search" -> {
        val q = params.optString("query", "").trim().lowercase()
        if (q.isEmpty()) throw IllegalArgumentException("query is required")
        val matches = JSONArray()
        for (f in root.listFiles()) {
          if ((f.name ?: "").lowercase().contains(q)) {
            matches.put(JSONObject().put("name", f.name ?: "").put("dir", f.isDirectory).put("size", f.length()))
          }
        }
        JSONObject().put("query", q).put("matches", matches)
      }
      else -> throw IllegalArgumentException("unsupported SAF files action: $action")
    }
  }

  private fun safeSdcardPath(rel: String): String {
    val root = File("/sdcard").canonicalPath
    val target = if (rel.isBlank()) File(root) else File(root, rel.removePrefix("/sdcard/").removePrefix("/"))
    val canon = target.canonicalPath
    if (canon != root && !canon.startsWith("$root/")) throw SecurityException("path escapes /sdcard: $rel")
    return canon
  }

  private fun execShizuku(params: JSONObject): JSONObject {
    val action = params.optString("action", "")
    return when (action) {
      "list" -> {
        val p = safeSdcardPath(params.optString("path", ""))
        val r = shizuku.exec(listOf("ls", "-la", p), 10)
        if (r.exit != 0) throw IllegalArgumentException(r.stderr.ifBlank { "ls failed" })
        JSONObject().put("path", p).put("listing", r.stdout)
      }
      "read" -> {
        val p = safeSdcardPath(params.optString("path", ""))
        val r = shizuku.exec(listOf("head", "-c", "262144", p), 10)
        if (r.exit != 0) throw IllegalArgumentException(r.stderr.ifBlank { "read failed" })
        JSONObject().put("path", p).put("content", r.stdout)
      }
      "delete" -> {
        val p = safeSdcardPath(params.optString("path", ""))
        val r = shizuku.exec(listOf("rm", "-rf", p), 10)
        if (r.exit != 0) throw IllegalStateException(r.stderr.ifBlank { "delete failed" })
        JSONObject().put("deleted", p)
      }
      else -> throw IllegalArgumentException("pick a folder in Orkestrate Phone for $action")
    }
  }
}
