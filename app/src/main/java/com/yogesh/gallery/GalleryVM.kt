package com.yogesh.gallery

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface Screen {
    data class Collection(val title: String, val ids: List<Long>) : Screen
    data class Viewer(val ids: List<Long>, val index: Int) : Screen
    data class Editor(val id: Long) : Screen
    data class Trim(val id: Long) : Screen
    data class Slideshow(val ids: List<Long>, val start: Int) : Screen
    object Trash : Screen
    object Vault : Screen
    object Duplicates : Screen
    object Map : Screen
    object Memories : Screen
    object Settings : Screen
    object Developer : Screen
    object Smart : Screen
    object People : Screen
    data class Wallpaper(val id: Long) : Screen
}

data class AlbumAction(val items: List<Media>, val move: Boolean)

const val MIN_COLS = 2
const val MAX_COLS = 6

class GalleryVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    val prefs = Prefs(app)

    // hooks provided by the activity
    var launchSender: (IntentSender, (() -> Unit)?) -> Unit = { _, _ -> }
    var authenticate: (String, () -> Unit) -> Unit = { _, a -> a() }
    var pickBackupFolder: () -> Unit = {}
    var pendingAfter: (() -> Unit)? = null

    var hasPermission by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var locked by mutableStateOf(prefs.appLock)

    var media by mutableStateOf<List<Media>>(emptyList())
    var byId by mutableStateOf<Map<Long, Media>>(emptyMap())
    var trashed by mutableStateOf<List<Media>>(emptyList())

    var columns by mutableIntStateOf(prefs.columns.coerceIn(MIN_COLS, MAX_COLS))
    var sortBy by mutableStateOf(SortBy.DATE)
    var ascending by mutableStateOf(false)
    var filter by mutableStateOf(Filter.ALL)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var tab by mutableIntStateOf(0)
    var favorites by mutableStateOf(prefs.favorites)
    var selection by mutableStateOf<Set<Long>>(emptySet())
    var lastVisible by mutableStateOf<List<Long>>(emptyList())
    var pendingAlbum by mutableStateOf<String?>(null)
    var interval by mutableIntStateOf(prefs.interval)
    var transition by mutableIntStateOf(prefs.transition)
    var appLock by mutableStateOf(prefs.appLock)

    val stack = mutableStateListOf<Screen>()
    val gridState = LazyGridState()
    val photosModel = GridModel()

    // dialogs
    var albumAction by mutableStateOf<AlbumAction?>(null)
    var renameTarget by mutableStateOf<Media?>(null)
    var infoTarget by mutableStateOf<Media?>(null)
    var newAlbumDialog by mutableStateOf(false)
    var ocrResult by mutableStateOf<String?>(null)

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun updateColumns(n: Int) {
        val v = n.coerceIn(MIN_COLS, MAX_COLS)
        if (v != columns) { columns = v; prefs.columns = v }
    }

    fun reload() {
        if (!hasPermission) return
        viewModelScope.launch {
            val (a, t) = withContext(Dispatchers.IO) { MediaRepo.load(ctx, false) to MediaRepo.load(ctx, true) }
            media = a
            byId = a.associateBy { it.id }
            trashed = t
            loaded = true
            selection = selection.filter { byId.containsKey(it) }.toSet()
        }
    }

    // ---------- filtering ----------
    fun filterList(src: List<Media>, f: Filter, q: String, labels: Map<Long, String>): List<Media> {
        var l = when (f) {
            Filter.ALL -> src
            Filter.PHOTOS -> src.filter { !it.isVideo }
            Filter.VIDEOS -> src.filter { it.isVideo }
            Filter.GIF -> src.filter { it.mime == "image/gif" }
            Filter.SHOTS -> src.filter { it.isScreenshot() }
        }
        val qq = q.trim().lowercase()
        if (qq.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            val f1 = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val f2 = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
            l = l.filter { m ->
                m.name.lowercase().contains(qq) || m.bucket.lowercase().contains(qq) ||
                    (labels[m.id]?.substringAfter('|')?.contains(qq) == true) ||
                    run {
                        val d = Instant.ofEpochMilli(m.date).atZone(zone).toLocalDate()
                        d.format(f1).contains(qq) || d.format(f2).lowercase().contains(qq)
                    }
            }
        }
        return l
    }

    // ---------- favorites ----------
    fun toggleFav(id: Long) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.favorites = favorites
    }

    fun toggleFavorites(items: List<Media>) {
        val ids = items.map { it.id }.toSet()
        favorites = if (favorites.containsAll(ids)) favorites - ids else favorites + ids
        prefs.favorites = favorites
    }

    // ---------- basic actions ----------
    fun share(items: List<Media>) {
        if (items.isEmpty()) return
        val i = if (items.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = items[0].mime
            putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun trash(items: List<Media>) {
        if (items.isEmpty()) return
        launchSender(MediaRepo.trashIntent(ctx, items.map { it.uri }, true), null)
        selection = emptySet()
    }

    fun restore(items: List<Media>) {
        if (items.isEmpty()) return
        launchSender(MediaRepo.trashIntent(ctx, items.map { it.uri }, false), null)
    }

    fun deleteForever(items: List<Media>) {
        if (items.isEmpty()) return
        launchSender(MediaRepo.deleteIntent(ctx, items.map { it.uri }), null)
    }

    fun copyMove(items: List<Media>, album: String, move: Boolean) {
        val clean = album.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isEmpty() || items.isEmpty()) return
        val existing = media.firstOrNull { it.bucket == album }?.path
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                items.count { MediaRepo.copyTo(ctx, it, clean, existing) != null }
            }
            toast(if (move) "Moved $ok item(s)" else "Copied $ok item(s)")
            selection = emptySet()
            if (move && ok > 0) launchSender(MediaRepo.deleteIntent(ctx, items.map { it.uri }), null)
            else reload()
        }
    }

    fun rename(m: Media, newName: String) {
        val clean = newName.trim().replace("/", "_")
        if (clean.isEmpty()) return
        val ext = m.name.substringAfterLast('.', "")
        val full = if (clean.contains('.') || ext.isEmpty()) clean else "$clean.$ext"
        launchSender(MediaRepo.writeIntent(ctx, listOf(m.uri))) {
            val cv = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, full) }
            try { ctx.contentResolver.update(m.uri, cv, null, null) } catch (e: Exception) { toast("Rename failed") }
        }
    }

    /** Bumped for a photo whenever its file is changed (rotate…), so cached bitmaps are not reused. */
    var stamps by mutableStateOf<Map<Long, Long>>(emptyMap())

    private fun nextCw(o: Int): Int = when (o) {
        ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> ExifInterface.ORIENTATION_ROTATE_90
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_ROTATE_180
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_NORMAL
        else -> throw IllegalStateException("mirrored orientation")
    }

    /**
     * Permanently rotates the photo by [degrees] (multiple of 90, clockwise). The viewer already shows the
     * rotation instantly; this writes it to the file after the system asks for write access once.
     */
    fun saveRotation(m: Media, degrees: Int, onDone: (Boolean) -> Unit) {
        val d = ((degrees % 360) + 360) % 360
        if (m.isVideo || d == 0) { onDone(true); return }
        launchSender(MediaRepo.writeIntent(ctx, listOf(m.uri))) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { rotateFile(m, d) }
                ctx.imageLoader.memoryCache?.clear()
                stamps = stamps + (m.id to System.currentTimeMillis())
                reload()
                if (!ok) toast("Could not rotate this photo")
                onDone(ok)
            }
        }
    }

    private fun rotateFile(m: Media, deg: Int): Boolean {
        if (m.mime == "image/jpeg") {
            // Lossless + fast: only the EXIF orientation tag is changed (all other metadata stays).
            try {
                ctx.contentResolver.openFileDescriptor(m.uri, "rw")?.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    var o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    repeat(deg / 90) { o = nextCw(o) }
                    ex.setAttribute(ExifInterface.TAG_ORIENTATION, o.toString())
                    ex.saveAttributes()
                } ?: throw IllegalStateException("no fd")
                return true
            } catch (e: Exception) {
                // fall through to the pixel based method
            }
        }
        val fmt = when (m.mime) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> return false
        }
        return try {
            val b = decodeBitmap(ctx, m.uri, 8000)
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            // encode to a temp file first so a failure can never leave the original truncated
            val tmp = File(ctx.cacheDir, "rot_${m.id}.tmp")
            tmp.outputStream().use { r.compress(fmt, 95, it) }
            ctx.contentResolver.openOutputStream(m.uri, "wt")?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            tmp.delete()
            true
        } catch (e: Exception) { false }
    }

    /** [which] = WallpaperManager.FLAG_SYSTEM (home), FLAG_LOCK, or both OR-ed together. */
    fun applyWallpaper(bmp: Bitmap, which: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, which); true
                } catch (e: Exception) { false }
            }
            toast(if (ok) "Wallpaper set" else "Could not set wallpaper")
            onResult(ok)
        }
    }

    fun startSlideshow(ids: List<Long>, start: Int = 0) {
        if (ids.isEmpty()) { toast("No photos"); return }
        push(Screen.Slideshow(ids, start))
    }

    // ---------- details ----------
    fun details(m: Media): List<Pair<String, String>> {
        val l = mutableListOf(
            "Name" to m.name,
            "Folder" to m.path.ifBlank { m.bucket },
            "Size" to Formatter.formatFileSize(ctx, m.size),
            "Type" to m.mime,
            "Date" to DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
                .format(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()))
        )
        if (m.width > 0) l.add("Resolution" to "${m.width} × ${m.height}")
        if (m.isVideo) l.add("Duration" to fmtDur(m.duration))
        else try {
            ctx.contentResolver.openInputStream(exifUri(m.uri))?.use { ins ->
                val ex = ExifInterface(ins)
                val cam = listOfNotNull(
                    ex.getAttribute(ExifInterface.TAG_MAKE), ex.getAttribute(ExifInterface.TAG_MODEL)
                ).joinToString(" ")
                if (cam.isNotBlank()) l.add("Camera" to cam)
                ex.latLong?.let { l.add("Location" to "%.5f, %.5f".format(it[0], it[1])) }
            }
        } catch (e: Exception) {
        }
        return l
    }

    private fun exifUri(u: Uri): Uri = try { MediaStore.setRequireOriginal(u) } catch (e: Exception) { u }

    fun locationOf(m: Media): Pair<Double, Double>? = try {
        ctx.contentResolver.openInputStream(exifUri(m.uri))?.use { ins ->
            ExifInterface(ins).latLong?.let { it[0] to it[1] }
        }
    } catch (e: Exception) { null }

    // ---------- OCR / QR ----------
    fun scanText(m: Media) {
        if (m.isVideo) { toast("Open a photo to scan text"); return }
        viewModelScope.launch {
            toast("Scanning…")
            val txt = withContext(Dispatchers.Default) {
                try {
                    val img = InputImage.fromBitmap(decodeBitmap(ctx, m.uri, 1800), 0)
                    val t = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await().text
                    val codes = BarcodeScanning.getClient().process(img).await().mapNotNull { it.rawValue }
                    buildString {
                        if (codes.isNotEmpty()) {
                            append("QR / Barcode:\n"); codes.forEach { append(it).append('\n') }; append('\n')
                        }
                        if (t.isNotBlank()) append(t)
                    }
                } catch (e: Exception) { "" }
            }
            ocrResult = txt.ifBlank { "No text or QR code found." }
        }
    }

    // ---------- collage ----------
    fun collage(items: List<Media>) {
        val imgs = items.filter { !it.isVideo }.take(4)
        if (imgs.size < 2) { toast("Select 2–4 photos"); return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cell = 800; val gap = 12
                val cols = 2
                val rows = (imgs.size + 1) / 2
                val out = Bitmap.createBitmap(cols * cell + (cols + 1) * gap, rows * cell + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(AColor.WHITE)
                imgs.forEachIndexed { i, m ->
                    val b = decodeBitmap(ctx, m.uri, 1000)
                    val side = min(b.width, b.height)
                    val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
                    val x = gap + (i % cols) * (cell + gap)
                    val y = gap + (i / cols) * (cell + gap)
                    c.drawBitmap(b, src, Rect(x, y, x + cell, y + cell), null)
                }
                MediaRepo.saveBitmap(ctx, out, "Collages", "COLLAGE")
            }
            toast("Collage saved in Pictures/Collages")
            selection = emptySet()
            reload()
        }
    }

    // ---------- vault ----------
    private val vaultDir get() = File(ctx.filesDir, "vault").apply { mkdirs() }
    var vaultFiles by mutableStateOf<List<File>>(emptyList())

    fun refreshVault() {
        vaultFiles = vaultDir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun hide(items: List<Media>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { m ->
                    val f = File(vaultDir, "${m.id}_${m.name}")
                    ctx.contentResolver.openInputStream(m.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            refreshVault()
            selection = emptySet()
            toast("Moved to private vault. Confirm to remove originals.")
            launchSender(MediaRepo.deleteIntent(ctx, items.map { it.uri }), null)
        }
    }

    fun unhide(f: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val name = f.name.substringAfter('_')
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "image/jpeg"
                MediaRepo.insertFile(ctx, f, name, mime, "Restored")
                f.delete()
            }
            refreshVault(); reload()
            toast("Restored to gallery")
        }
    }

    fun deleteVaultFile(f: File) { f.delete(); refreshVault() }

    // ---------- duplicates ----------
    var dupGroups by mutableStateOf<List<List<Media>>>(emptyList())
    var dupBusy by mutableStateOf(false)
    var dupScanned by mutableStateOf(false)

    fun scanDuplicates() {
        if (dupBusy) return
        viewModelScope.launch {
            dupBusy = true
            dupGroups = withContext(Dispatchers.Default) {
                val groups = mutableListOf<List<Media>>()
                media.filter { it.size > 0 }.groupBy { it.size }.filter { it.value.size > 1 }.values.forEach { l ->
                    l.groupBy { hashOf(it) }.values.filter { it.size > 1 }.forEach { groups.add(it) }
                }
                groups
            }
            dupScanned = true
            dupBusy = false
        }
    }

    private fun hashOf(m: Media): String = try {
        val md = MessageDigest.getInstance("MD5")
        ctx.contentResolver.openInputStream(m.uri)?.use {
            val buf = ByteArray(65536)
            while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { m.uri.toString() }

    // ---------- smart scan (scene labels + faces) ----------
    var aiData by mutableStateOf(prefs.aiAll())
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
    private var scanJob: Job? = null

    val faceIds: Set<Long>
        get() = aiData.filter { (it.value.substringBefore('|').toIntOrNull() ?: 0) > 0 }.keys

    val faceStore = FaceStore(app)
    var people by mutableStateOf<List<Person>>(emptyList())
    var facesLoaded by mutableStateOf(false)

    fun loadFaces() {
        viewModelScope.launch {
            val ps = withContext(Dispatchers.Default) { FaceTools.cluster(faceStore.loadAll()) }
            people = ps
            facesLoaded = true
        }
    }

    fun scanSmart() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val doneFaces = faceStore.doneIds()
            val todo = media.filter { !it.isVideo && (!aiData.containsKey(it.id) || it.id !in doneFaces) }
            if (todo.isEmpty()) { withContext(Dispatchers.Main) { toast("Everything is already scanned") }; return@launch }
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
            val faces = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(0.08f)
                    .build()
            )
            val batch = HashMap<Long, String>()
            val faceBatch = HashMap<Long, List<FloatArray>>()
            todo.forEachIndexed { i, m ->
                try {
                    val bmp = decodeBitmap(ctx, m.uri, 1024)
                    val img = InputImage.fromBitmap(bmp, 0)
                    val labelStr = if (aiData.containsKey(m.id)) null
                    else labeler.process(img).await().joinToString(",") { it.text.lowercase() }
                    val found = faces.process(img).await()
                    faceBatch[m.id] = FaceTools.extract(faceStore, m.id, bmp, found)
                    if (labelStr != null) batch[m.id] = "${found.size}|$labelStr"
                } catch (e: Exception) {
                    faceBatch[m.id] = emptyList()
                    if (!aiData.containsKey(m.id)) batch[m.id] = "0|"
                }
                if (faceBatch.size >= 20 || i == todo.lastIndex) {
                    prefs.aiPut(batch)
                    faceStore.putAll(faceBatch)
                    val snapshot = aiData + batch
                    batch.clear()
                    faceBatch.clear()
                    withContext(Dispatchers.Main) { aiData = snapshot; scanProgress = (i + 1) to todo.size }
                }
            }
            val ps = FaceTools.cluster(faceStore.loadAll())
            withContext(Dispatchers.Main) {
                people = ps; facesLoaded = true; scanProgress = null; toast("Scan complete")
            }
        }
    }

    fun searchScene(term: String) {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return
        val ids = media.filter { aiData[it.id]?.substringAfter('|')?.contains(t) == true }.map { it.id }
        if (ids.isEmpty()) toast("No matches. Run the scan first, or try another word (English).")
        else push(Screen.Collection("Scene: $t", ids))
    }

    fun openPeople() { push(Screen.People) }

    // ---------- geo ----------
    var geo by mutableStateOf<Map<Long, Pair<Double, Double>>>(emptyMap())
    var geoBusy by mutableStateOf(false)

    fun scanGeo() {
        if (geoBusy) return
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { geoBusy = true }
            val res = HashMap(geo)
            media.filter { !it.isVideo && !res.containsKey(it.id) }.forEach { m ->
                val loc = locationOf(m)
                if (loc != null) res[m.id] = loc
            }
            withContext(Dispatchers.Main) { geo = res; geoBusy = false }
        }
    }

    // ---------- backup ----------
    var backupBusy by mutableStateOf(false)

    fun backupTo(tree: Uri) {
        viewModelScope.launch {
            backupBusy = true
            val n = withContext(Dispatchers.IO) {
                var count = 0
                val done = prefs.backedUp.toMutableSet()
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                for (m in media) {
                    if (m.id in done) continue
                    try {
                        val d = DocumentsContract.createDocument(ctx.contentResolver, root, m.mime, m.name) ?: continue
                        ctx.contentResolver.openInputStream(m.uri)?.use { i ->
                            ctx.contentResolver.openOutputStream(d)?.use { o -> i.copyTo(o) }
                        }
                        done.add(m.id); count++
                    } catch (e: Exception) {
                    }
                }
                prefs.backedUp = done
                count
            }
            backupBusy = false
            toast("Backed up $n new file(s)")
        }
    }

    // ---------- settings ----------
    fun updateAppLock(v: Boolean) { appLock = v; prefs.appLock = v }
    fun updateInterval(v: Int) { interval = v; prefs.interval = v }
    fun updateTransition(v: Int) { transition = v; prefs.transition = v }

    // ---------- video trim ----------
    fun trimVideo(m: Media, startMs: Long, endMs: Long, onDone: (Boolean) -> Unit) {
        val out = File(ctx.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val item = ExoItem.Builder().setUri(m.uri)
            .setClippingConfiguration(
                ExoItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            ).build()
        val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        MediaRepo.insertFile(ctx, out, "TRIM_${System.currentTimeMillis()}.mp4", "video/mp4", "Trimmed")
                        out.delete()
                    }
                    toast("Trimmed video saved in Movies/Trimmed")
                    reload()
                    onDone(true)
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                toast("Trim failed")
                onDone(false)
            }
        }).build()
        t.start(item, out.absolutePath)
    }

    fun roundTo(v: Double) = (v * 10).roundToInt() / 10.0
}
