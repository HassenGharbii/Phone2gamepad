package com.phone2gamepad.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Keeps the user's background pictures in app-private storage (no storage permission). A picked image is decoded with
 * [ImageDecoder] (which applies the EXIF orientation), size-capped by [BackgroundLimits], and re-encoded as a plain JPEG
 * under a random file name, so the original (and its metadata) is never kept and a hostile file cannot reach the file system.
 */
class BackgroundStore(context: Context) {
    private val resolver = context.contentResolver
    private val dir = File(context.filesDir, "backgrounds")

    sealed class Result {
        data class Ok(val ref: String) : Result()
        data class Rejected(val message: String) : Result()
    }

    private fun fileFor(ref: String): File? = if (Background.isValidImageRef(ref)) File(dir, ref) else null

    /** Decodes the picture at [uri] and stores it. Never throws. Call from a background thread. */
    fun importImage(uri: Uri): Result {
        try {
            val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            if (size != null && !BackgroundLimits.fileSizeAllowed(size)) return Result.Rejected("That picture is too large (limit 24 MB).")
            val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val target = BackgroundLimits.targetSize(info.size.width, info.size.height)
                if (target == null) throw IOException("image rejected by size limits")
                decoder.setTargetSize(target.first, target.second)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            if (!dir.exists() && !dir.mkdirs()) return Result.Rejected("Could not create the picture folder.")
            val ref = UUID.randomUUID().toString().replace("-", "") + ".jpg"
            val tmp = File(dir, "$ref.tmp")
            tmp.outputStream().use { out -> if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw IOException("compress failed") }
            val dest = File(dir, ref)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return Result.Rejected("Could not save the picture.")
            }
            bmp.recycle()
            return Result.Ok(ref)
        } catch (e: IOException) {
            Log.w("P2G", "background import failed: ${e.message}")
            return Result.Rejected(if (e.message?.contains("size limits") == true) "That picture has unsupported dimensions." else "That file could not be read as a picture.")
        } catch (e: SecurityException) {
            return Result.Rejected("Access to that file was denied.")
        } catch (e: OutOfMemoryError) {
            return Result.Rejected("That picture is too large to open.")
        } catch (e: RuntimeException) {
            Log.w("P2G", "background import failed", e)
            return Result.Rejected("That file could not be read as a picture.")
        }
    }

    /** The stored picture for [ref], or null when missing/unreadable. Call from a background thread. */
    fun load(ref: String): Bitmap? {
        val f = fileFor(ref) ?: return null
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.path)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Deletes stored pictures that no profile uses any more and are older than [graceMs] (so a picture picked in an unsaved editor survives). */
    fun prune(keep: Set<String>, graceMs: Long = 24L * 3600 * 1000) {
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        for (f in files) {
            if (f.name in keep) continue
            if (now - f.lastModified() >= graceMs || f.name.endsWith(".tmp")) f.delete()
        }
    }
}
