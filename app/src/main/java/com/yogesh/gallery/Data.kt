package com.yogesh.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as MC
import java.io.File
import kotlin.math.max

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long,
    val mime: String,
    val isVideo: Boolean,
    val bucket: String,
    val path: String,
    val width: Int,
    val height: Int,
    val duration: Long,
    val mod: Long = 0L
)

fun Media.isScreenshot(): Boolean =
    bucket.contains("Screenshot", true) || name.startsWith("Screenshot", true) || path.contains("Screenshots", true)

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

enum class SortBy { DATE, NAME, SIZE }
enum class Filter(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), GIF("GIF"), SHOTS("Screenshots")
}

object MediaRepo {
    fun load(ctx: Context, trashed: Boolean): List<Media> {
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID, MC.DISPLAY_NAME, MC.SIZE, MC.DATE_TAKEN, MC.DATE_MODIFIED,
            MC.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE, MC.BUCKET_DISPLAY_NAME,
            MC.RELATIVE_PATH, MC.WIDTH, MC.HEIGHT, MC.DURATION
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)"
            )
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
            )
        }
        val out = ArrayList<Media>()
        ctx.contentResolver.query(uri, proj, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(MC.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MC.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MC.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MC.DATE_MODIFIED)
            val iMime = c.getColumnIndexOrThrow(MC.MIME_TYPE)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iBucket = c.getColumnIndexOrThrow(MC.BUCKET_DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(MC.RELATIVE_PATH)
            val iW = c.getColumnIndexOrThrow(MC.WIDTH)
            val iH = c.getColumnIndexOrThrow(MC.HEIGHT)
            val iDur = c.getColumnIndexOrThrow(MC.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val taken = c.getLong(iTaken)
                val mod = c.getLong(iMod) * 1000
                out.add(
                    Media(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        name = c.getString(iName) ?: "file_$id",
                        size = c.getLong(iSize),
                        date = if (taken > 0) taken else mod,
                        mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                        isVideo = isVideo,
                        bucket = c.getString(iBucket) ?: "Unknown",
                        path = c.getString(iPath) ?: "",
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        duration = c.getLong(iDur),
                        mod = mod
                    )
                )
            }
        }
        return out
    }

    fun trashIntent(ctx: Context, uris: List<Uri>, trash: Boolean): IntentSender =
        MediaStore.createTrashRequest(ctx.contentResolver, uris, trash).intentSender

    fun deleteIntent(ctx: Context, uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender

    fun writeIntent(ctx: Context, uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(ctx.contentResolver, uris).intentSender

    /** Copies a media file into an album folder. Returns the new uri or null. */
    fun copyTo(ctx: Context, m: Media, album: String, existingPath: String?): Uri? {
        val res = ctx.contentResolver
        val base = if (m.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val def = (if (m.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + album
        for (rel in listOfNotNull(existingPath?.trimEnd('/')?.takeIf { it.isNotBlank() }, def)) {
            try {
                val cv = ContentValues().apply {
                    put(MC.DISPLAY_NAME, m.name)
                    put(MC.MIME_TYPE, m.mime)
                    put(MC.RELATIVE_PATH, rel)
                    put(MC.IS_PENDING, 1)
                }
                val dst = res.insert(base, cv) ?: continue
                res.openInputStream(m.uri)?.use { i -> res.openOutputStream(dst)?.use { o -> i.copyTo(o) } }
                val done = ContentValues().apply { put(MC.IS_PENDING, 0) }
                res.update(dst, done, null, null)
                return dst
            } catch (e: Exception) {
            }
        }
        return null
    }

    fun saveBitmap(ctx: Context, bmp: Bitmap, folder: String, prefix: String): Uri? {
        val cv = ContentValues().apply {
            put(MC.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
            put(MC.MIME_TYPE, "image/jpeg")
            put(MC.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder)
            put(MC.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val done = ContentValues().apply { put(MC.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        return uri
    }

    /** Inserts a file (image or video) into the MediaStore, e.g. when restoring from the vault. */
    fun insertFile(ctx: Context, file: File, name: String, mime: String, folder: String): Uri? {
        val isVideo = mime.startsWith("video")
        val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val cv = ContentValues().apply {
            put(MC.DISPLAY_NAME, name)
            put(MC.MIME_TYPE, mime)
            put(MC.RELATIVE_PATH, "$dir/$folder")
            put(MC.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(base, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        val done = ContentValues().apply { put(MC.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        return uri
    }
}

fun decodeBitmap(ctx: Context, uri: Uri, maxDim: Int): Bitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = max(info.size.width, info.size.height)
        if (m > maxDim) {
            val s = maxDim.toFloat() / m
            dec.setTargetSize(
                (info.size.width * s).toInt().coerceAtLeast(1),
                (info.size.height * s).toInt().coerceAtLeast(1)
            )
        }
    }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    private val ai = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var columns: Int
        get() = p.getInt("cols", 3)
        set(v) { p.edit().putInt("cols", v).apply() }

    var interval: Int
        get() = p.getInt("interval", 3)
        set(v) { p.edit().putInt("interval", v).apply() }

    var transition: Int
        get() = p.getInt("transition", 0)
        set(v) { p.edit().putInt("transition", v).apply() }

    var appLock: Boolean
        get() = p.getBoolean("lock", false)
        set(v) { p.edit().putBoolean("lock", v).apply() }

    var favorites: Set<Long>
        get() = p.getStringSet("fav", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) { p.edit().putStringSet("fav", v.map { it.toString() }.toSet()).apply() }

    var backedUp: Set<Long>
        get() = p.getStringSet("bk", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) { p.edit().putStringSet("bk", v.map { it.toString() }.toSet()).apply() }

    fun aiAll(): Map<Long, String> =
        ai.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to (v as? String ?: "") } }.toMap()

    fun aiPut(map: Map<Long, String>) {
        val e = ai.edit()
        map.forEach { (k, v) -> e.putString(k.toString(), v) }
        e.apply()
    }
}
