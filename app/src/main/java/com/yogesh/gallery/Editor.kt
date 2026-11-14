package com.yogesh.gallery

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.graphics.Color as CColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

enum class EFilter(val label: String, val m: FloatArray?) {
    NONE("Original", null),
    BW("B&W", floatArrayOf(.33f, .59f, .11f, 0f, 0f, .33f, .59f, .11f, 0f, 0f, .33f, .59f, .11f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    SEPIA("Sepia", floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    VINTAGE("Vintage", floatArrayOf(.9f, .2f, .05f, 0f, 15f, .1f, .8f, .1f, 0f, 10f, .1f, .15f, .6f, 0f, 20f, 0f, 0f, 0f, 1f, 0f)),
    COOL("Cool", floatArrayOf(.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.2f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)),
    WARM("Warm", floatArrayOf(1.2f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, .85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    FADE("Fade", floatArrayOf(.8f, 0f, 0f, 0f, 40f, 0f, .8f, 0f, 0f, 40f, 0f, 0f, .8f, 0f, 40f, 0f, 0f, 0f, 1f, 0f)),
    VIVID("Vivid", floatArrayOf(1.3f, -.15f, -.15f, 0f, 0f, -.15f, 1.3f, -.15f, 0f, 0f, -.15f, -.15f, 1.3f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
}

class InkStroke(val pts: SnapshotStateList<Offset>, val color: Int, val w: Float)

class TextItem(val text: String, val color: Int, sz: Float) {
    var x by mutableFloatStateOf(0.5f)
    var y by mutableFloatStateOf(0.5f)
    var size by mutableFloatStateOf(sz)
}

class EditParams(
    val filter: EFilter, val bright: Float, val contrast: Float, val sat: Float,
    val strokes: List<InkStroke>, val texts: List<TextItem>
)

fun buildMatrix(p: EditParams): ColorMatrix {
    val m = ColorMatrix()
    m.setSaturation(p.sat)
    val t = (1 - p.contrast) * 128f + p.bright
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                p.contrast, 0f, 0f, 0f, t, 0f, p.contrast, 0f, 0f, t,
                0f, 0f, p.contrast, 0f, t, 0f, 0f, 0f, 1f, 0f
            )
        )
    )
    p.filter.m?.let { m.postConcat(ColorMatrix(it)) }
    return m
}

fun drawEdited(c: android.graphics.Canvas, base: Bitmap, dst: RectF, p: EditParams) {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    paint.colorFilter = ColorMatrixColorFilter(buildMatrix(p))
    c.drawBitmap(base, null, dst, paint)
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    for (st in p.strokes) {
        if (st.pts.isEmpty()) continue
        sp.color = st.color
        sp.strokeWidth = st.w * dst.width()
        val path = Path()
        st.pts.forEachIndexed { i, o ->
            val x = dst.left + o.x * dst.width()
            val y = dst.top + o.y * dst.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (st.pts.size == 1) {
            val o = st.pts[0]
            path.lineTo(dst.left + o.x * dst.width() + 0.1f, dst.top + o.y * dst.height())
        }
        c.drawPath(path, sp)
    }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    for (t in p.texts) {
        tp.color = t.color
        tp.textSize = t.size * dst.width()
        tp.setShadowLayer(4f, 0f, 2f, android.graphics.Color.argb(160, 0, 0, 0))
        c.drawText(t.text, dst.left + t.x * dst.width(), dst.top + t.y * dst.height(), tp)
    }
}

fun sharpen(src: Bitmap, a: Float): Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val out = px.copyOf()
    fun ch(c: Int, u: Int, d: Int, l: Int, r: Int, sh: Int): Int {
        val v = ((c shr sh) and 255) * (1 + 4 * a) -
            a * (((u shr sh) and 255) + ((d shr sh) and 255) + ((l shr sh) and 255) + ((r shr sh) and 255))
        return v.toInt().coerceIn(0, 255)
    }
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val i = y * w + x
            val c = px[i]; val u = px[i - w]; val d = px[i + w]; val l = px[i - 1]; val r = px[i + 1]
            out[i] = (c and (0xFF shl 24)) or (ch(c, u, d, l, r, 16) shl 16) or (ch(c, u, d, l, r, 8) shl 8) or ch(c, u, d, l, r, 0)
        }
    }
    val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    res.setPixels(out, 0, w, 0, 0, w, h)
    return res
}

private fun fit(cs: IntSize, b: Bitmap): RectF {
    val s = min(cs.width.toFloat() / b.width, cs.height.toFloat() / b.height)
    val w = b.width * s; val h = b.height * s
    val l = (cs.width - w) / 2f; val t = (cs.height - h) / 2f
    return RectF(l, t, l + w, t + h)
}

private val inkColors = listOf(
    android.graphics.Color.WHITE, android.graphics.Color.BLACK, android.graphics.Color.RED,
    0xFFFF9800.toInt(), android.graphics.Color.YELLOW, android.graphics.Color.GREEN,
    android.graphics.Color.CYAN, android.graphics.Color.BLUE, android.graphics.Color.MAGENTA
)
private val stickers = listOf("😀", "😍", "😎", "🥳", "❤️", "🔥", "⭐", "🎉", "👍", "🌈", "🌸", "☀️", "🌙", "🎈", "💯", "✨")

@Composable
fun EditorScreen(vm: GalleryVM, id: Long) {
    val m = vm.byId[id]
    val ctx = LocalContext.current
    var base by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) {
        base = withContext(Dispatchers.IO) {
            try { m?.let { decodeBitmap(ctx, it.uri, 2400) } } catch (e: Exception) { null }
        }
    }
    val b = base
    if (m == null || b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = CColor.White) }
        return
    }
    EditorContent(vm, m, b)
}

@Composable
fun EditorContent(vm: GalleryVM, m: Media, initial: Bitmap) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf(initial) }
    val undo = remember { mutableStateListOf<Bitmap>() }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var csize by remember { mutableStateOf(IntSize.Zero) }

    var filter by remember { mutableStateOf(EFilter.NONE) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var sharp by remember { mutableFloatStateOf(0f) }

    val strokes = remember { mutableStateListOf<InkStroke>() }
    val texts = remember { mutableStateListOf<TextItem>() }
    var selText by remember { mutableIntStateOf(-1) }
    var brushColor by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var brushW by remember { mutableFloatStateOf(0.01f) }
    var textColor by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var showTextDialog by remember { mutableStateOf(false) }

    // crop
    var ratio by remember { mutableStateOf<Float?>(null) }
    var cs by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.5f) }
    var fx by remember { mutableStateOf(0f..1f) }
    var fy by remember { mutableStateOf(0f..1f) }

    fun cropRect(): RectF {
        val r = ratio
        return if (r == null) RectF(fx.start, fy.start, fx.endInclusive, fy.endInclusive) else {
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            val wMax = min(1f, r * bh / bw)
            val w = wMax * cs
            val h = w * bw / (r * bh)
            val l = (cx - w / 2).coerceIn(0f, 1f - w)
            val t = (cy - h / 2).coerceIn(0f, 1f - h)
            RectF(l, t, l + w, t + h)
        }
    }

    fun resetCrop() { cs = 1f; cx = 0.5f; cy = 0.5f; fx = 0f..1f; fy = 0f..1f }
    fun pushBitmap(nb: Bitmap) {
        undo.add(bmp); bmp = nb
        strokes.clear(); texts.clear(); selText = -1
        resetCrop()
    }
    fun transform(f: (Matrix) -> Unit) {
        val mat = Matrix(); f(mat)
        pushBitmap(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true))
    }

    fun params() = EditParams(filter, bright, contrast, sat, strokes.toList(), texts.toList())

    val dst = remember(csize, bmp) { if (csize.width > 0) fit(csize, bmp) else RectF() }

    Column(Modifier.fillMaxSize().background(CColor(0xFF0E0B1A))) {
        // top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ vm.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = CColor.White) }
            IconButton(
                { if (undo.isNotEmpty()) { bmp = undo.removeAt(undo.lastIndex); strokes.clear(); texts.clear(); resetCrop() } }
            ) { Icon(Icons.Rounded.Undo, "Undo", tint = CColor.White) }
            Box(Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(GalleryGradient)
                    .clickable(enabled = !saving) {
                        saving = true
                        val p = params(); val src = bmp; val sh = sharp
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                                drawEdited(android.graphics.Canvas(out), src, RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), p)
                                val fin = if (sh > 0.01f) sharpen(out, sh * 0.8f) else out
                                MediaRepo.saveBitmap(ctx, fin, "Edited", "EDIT")
                            }
                            vm.toast("Saved to Pictures/Edited")
                            vm.reload(); vm.pop()
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) { Text(if (saving) "Saving…" else "Save", color = CColor.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            Box(Modifier.size(8.dp))
        }

        // canvas
        Canvas(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { csize = it }
                .pointerInput(tab, bmp, csize, brushColor, brushW) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val d = fit(csize, bmp)
                            val n = Offset((o.x - d.left) / d.width(), (o.y - d.top) / d.height())
                            when (tab) {
                                6 -> strokes.add(InkStroke(mutableStateListOf(n), brushColor, brushW))
                                4, 5 -> {
                                    var best = -1; var bd = 0.15f
                                    texts.forEachIndexed { i, t ->
                                        val dd = hypot(t.x - n.x, t.y - n.y)
                                        if (dd < bd) { bd = dd; best = i }
                                    }
                                    if (best >= 0) selText = best
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val d = fit(csize, bmp)
                            when (tab) {
                                6 -> strokes.lastOrNull()?.pts?.add(
                                    Offset((change.position.x - d.left) / d.width(), (change.position.y - d.top) / d.height())
                                )
                                4, 5 -> texts.getOrNull(selText)?.let {
                                    it.x = (it.x + drag.x / d.width()).coerceIn(0f, 1f)
                                    it.y = (it.y + drag.y / d.height()).coerceIn(0f, 1f)
                                }
                                0 -> if (ratio != null) {
                                    cx = (cx + drag.x / d.width()).coerceIn(0f, 1f)
                                    cy = (cy + drag.y / d.height()).coerceIn(0f, 1f)
                                }
                            }
                        }
                    )
                }
        ) {
            if (dst.width() <= 0f) return@Canvas
            drawIntoCanvas { c -> drawEdited(c.nativeCanvas, bmp, dst, params()) }
            if (tab == 0) {
                val r = cropRect()
                val l = dst.left + r.left * dst.width(); val t = dst.top + r.top * dst.height()
                val rr = dst.left + r.right * dst.width(); val bb = dst.top + r.bottom * dst.height()
                val dim = CColor(0x99000000)
                drawRect(dim, Offset(dst.left, dst.top), Size(dst.width(), t - dst.top))
                drawRect(dim, Offset(dst.left, bb), Size(dst.width(), dst.bottom - bb))
                drawRect(dim, Offset(dst.left, t), Size(l - dst.left, bb - t))
                drawRect(dim, Offset(rr, t), Size(dst.right - rr, bb - t))
                drawRect(CColor.White, Offset(l, t), Size(rr - l, bb - t), style = DrawStroke(2.dp.toPx()))
            }
        }

        // tools
        Column(
            Modifier.fillMaxWidth().background(CColor(0xFF171326)).navigationBarsPadding().padding(top = 8.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 12.dp)) {
                when (tab) {
                    0 -> Column {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val opts = listOf<Pair<String, Float?>>(
                                "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f
                            )
                            items(opts) { (label, r) ->
                                FilterChip(selected = ratio == r, onClick = { ratio = r; resetCrop() }, label = { Text(label) })
                            }
                        }
                        if (ratio == null) {
                            RangeSlider(fx, { fx = it }, valueRange = 0f..1f)
                            RangeSlider(fy, { fy = it }, valueRange = 0f..1f)
                        } else {
                            Text("Drag on the photo to move the crop box", fontSize = 12.sp, color = CColor(0xFFCCC5E6))
                            Slider(cs, { cs = it }, valueRange = 0.2f..1f)
                        }
                        // Apply button
                    }
                    1 -> Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton({ transform { it.postRotate(-90f) } }) { Icon(Icons.Rounded.RotateLeft, "Rotate left", tint = CColor.White, modifier = Modifier.size(36.dp)) }
                        IconButton({ transform { it.postRotate(90f) } }) { Icon(Icons.Rounded.RotateRight, "Rotate right", tint = CColor.White, modifier = Modifier.size(36.dp)) }
                        IconButton({ transform { it.preScale(-1f, 1f) } }) { Icon(Icons.Rounded.Flip, "Flip horizontal", tint = CColor.White, modifier = Modifier.size(36.dp)) }
                        IconButton({ transform { it.preScale(1f, -1f) } }) {
                            Icon(Icons.Rounded.Flip, "Flip vertical", tint = CColor.White, modifier = Modifier.size(36.dp))
                        }
                    }
                    2 -> LazyRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EFilter.values().toList()) { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                    3 -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Brightness", Modifier.size(90.dp, 24.dp), fontSize = 12.sp); Slider(bright, { bright = it }, valueRange = -100f..100f, modifier = Modifier.weight(1f).height(28.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Contrast", Modifier.size(90.dp, 24.dp), fontSize = 12.sp); Slider(contrast, { contrast = it }, valueRange = 0.5f..1.5f, modifier = Modifier.weight(1f).height(28.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Saturation", Modifier.size(90.dp, 24.dp), fontSize = 12.sp); Slider(sat, { sat = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f).height(28.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Sharpness", Modifier.size(90.dp, 24.dp), fontSize = 12.sp); Slider(sharp, { sharp = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(28.dp)) }
                    }
                    4 -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ showTextDialog = true }) { Text("Add text") }
                            if (selText in texts.indices) TextButton({ texts.removeAt(selText); selText = -1 }) { Text("Delete selected") }
                        }
                        ColorRow(textColor) { textColor = it }
                        texts.getOrNull(selText)?.let { t ->
                            Slider(t.size, { t.size = it }, valueRange = 0.03f..0.25f, modifier = Modifier.height(28.dp))
                        }
                    }
                    5 -> Column {
                        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(stickers) { e ->
                                Text(e, fontSize = 30.sp, modifier = Modifier.clickable {
                                    texts.add(TextItem(e, android.graphics.Color.WHITE, 0.14f)); selText = texts.lastIndex
                                })
                            }
                        }
                        Text("Drag a sticker on the photo to move it", fontSize = 12.sp, color = CColor(0xFFCCC5E6))
                        texts.getOrNull(selText)?.let { t ->
                            Slider(t.size, { t.size = it }, valueRange = 0.05f..0.4f, modifier = Modifier.height(28.dp))
                        }
                    }
                    else -> Column {
                        ColorRow(brushColor) { brushColor = it }
                        Slider(brushW, { brushW = it }, valueRange = 0.004f..0.05f, modifier = Modifier.height(28.dp))
                        TextButton({ if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo stroke") }
                    }
                }
                if (tab == 0) Button(
                    {
                        val r = cropRect()
                        val x = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                        val y = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                        val w = ((r.right - r.left) * bmp.width).toInt().coerceIn(1, bmp.width - x)
                        val h = ((r.bottom - r.top) * bmp.height).toInt().coerceIn(1, bmp.height - y)
                        pushBitmap(Bitmap.createBitmap(bmp, x, y, w, h))
                    },
                    Modifier.align(Alignment.BottomEnd)
                ) { Text("Apply crop") }
            }
            val tabs = listOf(
                "Crop" to Icons.Rounded.Crop, "Rotate" to Icons.Rounded.RotateRight, "Filters" to Icons.Rounded.Palette,
                "Adjust" to Icons.Rounded.Tune, "Text" to Icons.Rounded.TextFields,
                "Stickers" to Icons.Rounded.EmojiEmotions, "Draw" to Icons.Rounded.Brush
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items(tabs.size) { i ->
                    Column(
                        Modifier.clickable { tab = i }.padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(tabs[i].second, null, tint = if (tab == i) CColor(0xFFFF80AB) else CColor.White)
                        Text(tabs[i].first, fontSize = 11.sp, color = if (tab == i) CColor(0xFFFF80AB) else CColor.White)
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Add text") },
            text = { OutlinedTextField(txt, { txt = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    if (txt.isNotBlank()) { texts.add(TextItem(txt, textColor, 0.08f)); selText = texts.lastIndex }
                    showTextDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ showTextDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(inkColors) { c ->
            Box(
                Modifier.size(if (c == selected) 30.dp else 24.dp).clip(CircleShape).background(CColor(c))
                    .clickable { onPick(c) }
            )
        }
    }
}

// ---------------------- video trim ----------------------

@Composable
fun TrimScreen(vm: GalleryVM, id: Long) {
    val m = vm.byId[id]
    if (m == null) { LaunchedEffect(Unit) { vm.pop() }; return }
    val ctx = LocalContext.current
    val dur = m.duration.coerceAtLeast(1000L).toFloat()
    var range by remember { mutableStateOf(0f..dur) }
    var busy by remember { mutableStateOf(false) }
    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(ExoItem.fromUri(m.uri)); prepare() }
    }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    LaunchedEffect(range) {
        while (true) {
            delay(150)
            if (exo.isPlaying && exo.currentPosition >= range.endInclusive.toLong()) {
                exo.pause(); exo.seekTo(range.start.toLong())
            }
        }
    }
    Column(Modifier.fillMaxSize().background(CColor.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = CColor.White) }
            Text("Trim video", color = CColor.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        }
        AndroidView(
            factory = { c -> PlayerView(c).apply { player = exo; useController = true } },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Column(Modifier.padding(16.dp)) {
            Text(
                "Start ${fmtDur(range.start.toLong())}   End ${fmtDur(range.endInclusive.toLong())}   " +
                    "Length ${fmtDur((range.endInclusive - range.start).toLong())}",
                color = CColor.White
            )
            RangeSlider(range, { range = it; exo.seekTo(it.start.toLong()) }, valueRange = 0f..dur)
            Button(
                onClick = {
                    exo.pause(); busy = true
                    vm.trimVideo(m, range.start.toLong(), range.endInclusive.toLong()) { ok ->
                        busy = false
                        if (ok) vm.pop()
                    }
                },
                enabled = !busy && range.endInclusive - range.start > 500f,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Trimming…" else "Save trimmed copy") }
        }
    }
}
