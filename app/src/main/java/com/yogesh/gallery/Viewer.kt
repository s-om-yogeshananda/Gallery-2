package com.yogesh.gallery

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.imageLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

@Composable
fun ViewerScreen(vm: GalleryVM, s: Screen.Viewer) {
    val items = remember(s.ids, vm.byId) { s.ids.mapNotNull { vm.byId[it] } }
    LaunchedEffect(items.isEmpty(), vm.loaded) { if (items.isEmpty() && vm.loaded) vm.pop() }
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = s.index.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // clockwise degrees the user rotated each photo (shown instantly, written to the file on "Save")
    val rots = remember { mutableStateMapOf<Long, Int>() }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy  ·  HH:mm") }
    val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    LaunchedEffect(pager.currentPage) {
        zoomed = false
        // warm the cache for the neighbours so swiping never shows an empty page
        for (d in intArrayOf(1, -1, 2)) {
            val n = items.getOrNull(pager.currentPage + d) ?: continue
            if (!n.isVideo) ctx.imageLoader.enqueue(fullRequest(ctx, n, vm.stamps[n.id] ?: 0L))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondBoundsPageCount = 1,
            pageSpacing = 12.dp,
            key = { items.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val m = items.getOrNull(page) ?: return@HorizontalPager
            val isCur = pager.currentPage == page
            val stamp = vm.stamps[m.id] ?: 0L
            if (m.isVideo) VideoPage(m, isCur, onZoomed = { z -> if (isCur) zoomed = z })
            else key(stamp) {
                ZoomImage(
                    m, stamp, rots[m.id] ?: 0, isCur,
                    onTap = { chrome = !chrome },
                    onZoomed = { z -> if (isCur) zoomed = z }
                )
            }
        }

        val show = chrome || cur.isVideo

        AnimatedVisibility(
            show, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(120))
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ vm.pop() }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(cur.name, color = Color.White, maxLines = 1, fontSize = 15.sp)
                    Text(
                        dateFmt.format(Instant.ofEpochMilli(cur.date).atZone(ZoneId.systemDefault())),
                        color = Color(0xE6FFFFFF), fontSize = 12.sp
                    )
                }
                val fav = cur.id in vm.favorites
                IconButton({ vm.toggleFav(cur.id) }) {
                    Icon(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite",
                        tint = if (fav) Color(0xFFFF4081) else Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            show, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(120))
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val pending = rots[cur.id] ?: 0
                if (!cur.isVideo && pending % 360 != 0) {
                    Row(
                        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(50))
                            .background(Color(0xE61B1035)).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Undo", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .clickable { rots.remove(cur.id) }.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        Text(
                            "Save rotation", color = Color(0xFFFF80AB), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                val target = cur
                                vm.saveRotation(target, pending) { rots.remove(target.id) }
                            }.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton({ vm.share(listOf(cur)) }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                    if (cur.isVideo) IconButton({ vm.push(Screen.Trim(cur.id)) }) {
                        Icon(Icons.Rounded.ContentCut, "Trim", tint = Color.White)
                    } else {
                        IconButton({ vm.push(Screen.Editor(cur.id)) }) {
                            Icon(Icons.Rounded.Edit, "Edit", tint = Color.White)
                        }
                        IconButton({ rots[cur.id] = (rots[cur.id] ?: 0) + 90 }) {
                            Icon(Icons.Rounded.RotateRight, "Rotate", tint = Color.White)
                        }
                    }
                    IconButton({ vm.trash(listOf(cur)) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
                    IconButton({ vm.infoTarget = cur }) { Icon(Icons.Rounded.Info, "Details", tint = Color.White) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { vm.renameTarget = cur; menu = false })
                            DropdownMenuItem(
                                text = { Text("Copy to album") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = { vm.albumAction = AlbumAction(listOf(cur), false); menu = false })
                            DropdownMenuItem(
                                text = { Text("Move to album") }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                                onClick = { vm.albumAction = AlbumAction(listOf(cur), true); menu = false })
                            DropdownMenuItem(
                                text = { Text("Hide in private vault") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                                onClick = { menu = false; vm.authenticate("Use private vault") { vm.hide(listOf(cur)) } })
                            if (!cur.isVideo) {
                                DropdownMenuItem(
                                    text = { Text("Set as wallpaper") }, leadingIcon = { Icon(Icons.Rounded.Wallpaper, null) },
                                    onClick = { vm.push(Screen.Wallpaper(cur.id)); menu = false })
                                DropdownMenuItem(
                                    text = { Text("Scan text / QR") }, leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, null) },
                                    onClick = { vm.scanText(cur); menu = false })
                            }
                            DropdownMenuItem(
                                text = { Text("Slideshow") }, leadingIcon = { Icon(Icons.Rounded.Slideshow, null) },
                                onClick = {
                                    menu = false
                                    val photos = items.filter { !it.isVideo }
                                    vm.startSlideshow(photos.map { it.id }, photos.indexOfFirst { it.id == cur.id }.coerceAtLeast(0))
                                })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomImage(
    m: Media, stamp: Long, rotation: Int, isCurrent: Boolean,
    onTap: () -> Unit, onZoomed: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val curTap by rememberUpdatedState(onTap)
    val curZoomed by rememberUpdatedState(onZoomed)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var img by remember { mutableStateOf(IntSize.Zero) }   // decoded picture size (for fitting a rotated photo)
    LaunchedEffect(isCurrent) { if (!isCurrent) { scale = 1f; offset = Offset.Zero } }
    val req = remember(m.id, m.size, m.mod, stamp) { fullRequest(ctx, m, stamp) }

    // animate the quarter turns, and shrink/grow so a rotated photo still fits the screen
    val odd = (rotation / 90) % 2 != 0
    val fit = if (odd && img.width > 0 && box.width > 0) {
        val before = min(box.width.toFloat() / img.width, box.height.toFloat() / img.height)
        val after = min(box.width.toFloat() / img.height, box.height.toFloat() / img.width)
        after / before
    } else 1f
    val rotAnim by animateFloatAsState(rotation.toFloat(), tween(260), label = "rot")
    val fitAnim by animateFloatAsState(fit, tween(260), label = "fit")

    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(Unit) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var multi = false
                    var upTime = down.uptimeMillis
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        if (ev.changes.size > 1) multi = true
                        if (multi || scale > 1.01f) {
                            val ns = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                            val maxX = box.width * (ns - 1f) / 2f
                            val maxY = box.height * (ns - 1f) / 2f
                            var no = offset + ev.calculatePan()
                            no = Offset(no.x.coerceIn(-maxX, maxX), no.y.coerceIn(-maxY, maxY))
                            if (ns <= 1.01f) no = Offset.Zero
                            scale = ns; offset = no
                            curZoomed(ns > 1.01f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        ev.changes.firstOrNull()?.let { c ->
                            upTime = c.uptimeMillis
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        }
                    } while (ev.changes.any { it.pressed })

                    if (!moved && !multi && upTime - down.uptimeMillis < 350) {
                        val isDouble = upTime - lastTapTime < 300 &&
                            (down.position - lastTapPos).getDistance() < 120f
                        if (isDouble) {
                            lastTapTime = 0L
                            if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                            else {
                                val ns = 2.5f
                                val c = Offset(box.width / 2f, box.height / 2f)
                                val want = (down.position - c) * (1f - ns)
                                val maxX = box.width * (ns - 1f) / 2f
                                val maxY = box.height * (ns - 1f) / 2f
                                scale = ns
                                offset = Offset(want.x.coerceIn(-maxX, maxX), want.y.coerceIn(-maxY, maxY))
                            }
                            curZoomed(scale > 1.01f)
                            curTap()          // the first tap already toggled the bars: toggle back
                        } else {
                            lastTapTime = upTime
                            lastTapPos = down.position
                            curTap()          // react immediately, no waiting for a possible double tap
                        }
                    }
                }
            }
            .graphicsLayer {
                val k = scale * fitAnim
                scaleX = k; scaleY = k
                rotationZ = rotAnim
                translationX = offset.x; translationY = offset.y
            }
    ) {
        AsyncImage(
            model = req, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onSuccess = { st ->
                val sz = st.painter.intrinsicSize
                if (sz.width > 0f && sz.height > 0f) img = IntSize(sz.width.toInt(), sz.height.toInt())
            }
        )
    }
}

@Composable
fun VideoPage(m: Media, isCurrent: Boolean, onZoomed: (Boolean) -> Unit = {}) {
    if (!isCurrent) {
        val ctx = LocalContext.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(thumbRequest(ctx, m), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(64.dp), tint = Color(0xCCFFFFFF))
        }
    } else VideoPlayer(m.uri, autoPlay = true, withVolume = true, modifier = Modifier.fillMaxSize(), onZoomed = onZoomed)
}

/**
 * ExoPlayer with play/pause, seek bar, a volume slider and pinch-to-zoom / drag-to-pan on the video picture.
 */
@Composable
fun VideoPlayer(
    uri: Uri, autoPlay: Boolean, withVolume: Boolean, modifier: Modifier = Modifier,
    onZoomed: (Boolean) -> Unit = {}
) {
    val ctx = LocalContext.current
    val curZoomed by rememberUpdatedState(onZoomed)
    val exo = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(ExoItem.fromUri(uri)); prepare(); playWhenReady = autoPlay
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    var volume by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onSizeChanged { box = it }) {
        AndroidView(
            factory = { c ->
                (LayoutInflater.from(c).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                    this.player = exo
                }
            },
            update = { pv ->
                // only the picture is scaled; the play/seek controls keep their normal size
                pv.videoSurfaceView?.let { v ->
                    v.scaleX = scale; v.scaleY = scale
                    v.translationX = offset.x; v.translationY = offset.y
                }
            },
            modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 60.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size > 1 || scale > 1.01f) {
                                val ns = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                                val maxX = box.width * (ns - 1f) / 2f
                                val maxY = box.height * (ns - 1f) / 2f
                                var no = offset + ev.calculatePan()
                                no = Offset(no.x.coerceIn(-maxX, maxX), no.y.coerceIn(-maxY, maxY))
                                if (ns <= 1.01f) no = Offset.Zero
                                scale = ns; offset = no
                                curZoomed(ns > 1.01f)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        )
        if (withVolume) Row(
            Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp)
                .clip(RoundedCornerShape(50)).background(Color(0x88000000)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Slider(volume, { volume = it; exo.volume = it }, Modifier.width(120.dp))
        }
    }
}
