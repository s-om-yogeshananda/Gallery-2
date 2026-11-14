package com.yogesh.gallery

import android.app.WallpaperManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Lets the user choose WHERE the photo becomes the wallpaper (home / lock / both) and how it is zoomed
 * and positioned, instead of applying it blindly.
 */
@Composable
fun WallpaperScreen(vm: GalleryVM, id: Long) {
    val m = vm.byId[id]
    if (m == null) { LaunchedEffect(Unit) { vm.pop() }; return }
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) { try { decodeBitmap(ctx, m.uri, 2800) } catch (e: Exception) { null } }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    // how far the photo may be dragged so the screen is always fully covered
    fun clampOffset(b: Bitmap, z: Float, o: Offset): Offset {
        if (box.width == 0) return Offset.Zero
        val s0 = max(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
        val maxX = ((b.width * s0 * z) - box.width).coerceAtLeast(0f) / 2f
        val maxY = ((b.height * s0 * z) - box.height).coerceAtLeast(0f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun commit(which: Int) {
        val b = bmp ?: return
        if (busy || box.width == 0) return
        busy = true
        val s0 = max(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
        val k = s0 * zoom
        val cx = b.width / 2f
        val cy = b.height / 2f
        val left = ((0f - box.width / 2f - offset.x) / k + cx).toInt().coerceIn(0, b.width - 1)
        val top = ((0f - box.height / 2f - offset.y) / k + cy).toInt().coerceIn(0, b.height - 1)
        val right = ((box.width - box.width / 2f - offset.x) / k + cx).toInt().coerceIn(left + 1, b.width)
        val bottom = ((box.height - box.height / 2f - offset.y) / k + cy).toInt().coerceIn(top + 1, b.height)
        val cropped = try {
            Bitmap.createBitmap(b, left, top, right - left, bottom - top)
        } catch (e: Exception) { b }
        vm.applyWallpaper(cropped, which) { ok -> busy = false; if (ok) vm.pop() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    val b = bmp ?: return@detectTransformGestures
                    val nz = (zoom * z).coerceIn(1f, 5f)
                    zoom = nz
                    offset = clampOffset(b, nz, offset + pan)
                }
            }
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        else Image(
            b.asImageBitmap(), null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Crop
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ vm.pop() }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Set wallpaper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Pinch to zoom · drag to position",
                color = Color(0xE6FFFFFF), fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Reset", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x44FFFFFF))
                        .clickable { zoom = 1f; offset = Offset.Zero }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperButton("Home screen", Modifier.weight(1f), busy || bmp == null) {
                    commit(WallpaperManager.FLAG_SYSTEM)
                }
                WallpaperButton("Lock screen", Modifier.weight(1f), busy || bmp == null) {
                    commit(WallpaperManager.FLAG_LOCK)
                }
                WallpaperButton("Both", Modifier.weight(1f), busy || bmp == null) {
                    commit(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                }
            }
        }
    }
}

@Composable
private fun WallpaperButton(text: String, modifier: Modifier, disabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(GalleryGradient)
            .clickable(enabled = !disabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}
