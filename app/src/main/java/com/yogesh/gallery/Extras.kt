package com.yogesh.gallery

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun GScaffold(
    title: String, vm: GalleryVM,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton({ vm.pop() }) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        content = content
    )
}

@Composable
fun GButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(GalleryGradient).clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold) }
}

// ---------------- slideshow ----------------

private const val SLIDE_PX = 1600

@Composable
fun SlideshowScreen(vm: GalleryVM, s: Screen.Slideshow) {
    val items = remember(s.ids, vm.byId) { s.ids.mapNotNull { vm.byId[it] }.filter { !it.isVideo } }
    if (items.isEmpty()) { LaunchedEffect(Unit) { vm.pop() }; return }
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(s.start.coerceIn(0, items.lastIndex)) }
    LaunchedEffect(items.size, vm.interval) {
        while (true) {
            // decode the NEXT photo while the current one is on screen, so the change never waits for loading
            val next = (idx + 1) % items.size
            val t0 = System.currentTimeMillis()
            ctx.imageLoader.execute(fullRequest(ctx, items[next], 0L, SLIDE_PX))
            val rest = vm.interval * 1000L - (System.currentTimeMillis() - t0)
            if (rest > 0) delay(rest)
            idx = next
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { vm.pop() }) {
        AnimatedContent(
            targetState = idx.coerceIn(0, items.lastIndex),
            transitionSpec = {
                when (vm.transition) {
                    0 -> fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                    1 -> slideInHorizontally(tween(600)) { it } togetherWith slideOutHorizontally(tween(600)) { -it }
                    else -> (scaleIn(tween(700), initialScale = 0.8f) + fadeIn(tween(700))) togetherWith fadeOut(tween(500))
                }
            },
            label = "slideshow"
        ) { i ->
            AsyncImage(
                fullRequest(ctx, items[i], 0L, SLIDE_PX), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit
            )
        }
    }
}

// ---------------- trash ----------------

@Composable
fun TrashScreen(vm: GalleryVM) {
    var sel by remember { mutableStateOf(setOf<Long>()) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.reload() }
    val target = vm.trashed.filter { sel.isEmpty() || it.id in sel }
    GScaffold("Recycle bin", vm) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Deleted items stay here for 30 days, then Android removes them automatically.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFFCCC5E6), fontSize = 13.sp
            )
            if (vm.trashed.isEmpty()) Box(Modifier.weight(1f)) { CenterText("Recycle bin is empty") }
            else LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(vm.trashed, key = { it.id }) { m ->
                    Box(
                        Modifier.aspectRatio(1f).clickable { sel = if (m.id in sel) sel - m.id else sel + m.id }
                    ) {
                        AsyncImage(
                            ImageRequest.Builder(ctx).data(m.uri).size(320).build(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                        if (m.id in sel) {
                            Box(Modifier.fillMaxSize().background(Color(0x667C4DFF)))
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(5.dp), tint = Color.White)
                        }
                    }
                }
            }
            if (vm.trashed.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button({ vm.restore(target); sel = emptySet() }, Modifier.weight(1f)) {
                    Text(if (sel.isEmpty()) "Restore all" else "Restore (${sel.size})")
                }
                OutlinedButton({ vm.deleteForever(target); sel = emptySet() }, Modifier.weight(1f)) {
                    Text(if (sel.isEmpty()) "Empty bin" else "Delete forever")
                }
            }
        }
    }
}

// ---------------- vault ----------------

@Composable
fun VaultScreen(vm: GalleryVM) {
    var open by remember { mutableStateOf<File?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshVault() }
    GScaffold("Private vault", vm) { pad ->
        if (vm.vaultFiles.isEmpty()) Box(Modifier.padding(pad)) {
            CenterText("Vault is empty.\nSelect photos and choose “Hide in private vault”.")
        } else LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.padding(pad).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(vm.vaultFiles, key = { it.name }) { f ->
                AsyncImage(
                    ImageRequest.Builder(ctx).data(f).size(320).build(), null,
                    Modifier.aspectRatio(1f).clickable { open = f }, contentScale = ContentScale.Crop
                )
            }
        }
    }
    open?.let { f ->
        Dialog({ open = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val isVideo = f.extension.lowercase() in listOf("mp4", "mkv", "webm", "3gp", "mov")
                    if (isVideo) VideoPlayer(android.net.Uri.fromFile(f), autoPlay = true, withVolume = false, Modifier.fillMaxSize())
                    else AsyncImage(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ vm.unhide(f); open = null }, Modifier.weight(1f)) { Text("Restore to gallery") }
                    OutlinedButton({ vm.deleteVaultFile(f); open = null }, Modifier.weight(1f)) { Text("Delete") }
                    TextButton({ open = null }) { Text("Close") }
                }
            }
        }
    }
}

// ---------------- duplicates ----------------

@Composable
fun DuplicatesScreen(vm: GalleryVM) {
    val ctx = LocalContext.current
    GScaffold("Duplicate finder", vm) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            if (vm.dupBusy) Text("Scanning… this can take a while on big libraries.")
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GButton(if (vm.dupScanned) "Scan again" else "Scan for duplicates", { vm.scanDuplicates() })
                if (vm.dupGroups.isNotEmpty()) OutlinedButton({
                    vm.trash(vm.dupGroups.flatMap { g -> g.sortedBy { it.date }.drop(1) })
                    vm.dupGroups = emptyList()
                }) { Text("Trash all extras") }
            }
            if (vm.dupScanned && vm.dupGroups.isEmpty() && !vm.dupBusy) CenterText("No duplicates found 🎉")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(vm.dupGroups) { g ->
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF241E3A)).padding(10.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(g) { m ->
                                AsyncImage(
                                    thumbRequest(ctx, m), null,
                                    Modifier.size(90.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.push(Screen.Viewer(g.map { it.id }, g.indexOf(m))) },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        TextButton({
                            val extras = g.sortedBy { it.date }.drop(1)
                            vm.trash(extras)
                            vm.dupGroups = vm.dupGroups - setOf(g)
                        }) { Text("Keep oldest, trash ${g.size - 1} extra") }
                    }
                }
            }
        }
    }
}

// ---------------- map ----------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(vm: GalleryVM) {
    LaunchedEffect(Unit) { vm.scanGeo() }
    val clusters = remember(vm.geo) {
        vm.geo.entries.groupBy { vm.roundTo(it.value.first) to vm.roundTo(it.value.second) }
            .map { (k, v) -> Triple(k, v.map { it.key }, v.size) }
            .sortedByDescending { it.third }
    }
    GScaffold("Map", vm) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.geoBusy) Text("Reading locations…", Modifier.padding(12.dp))
            if (clusters.isEmpty()) {
                Box(Modifier.weight(1f)) { CenterText(if (vm.geoBusy) "" else "No geotagged photos found") }
            } else {
                val html = remember(clusters) {
                    val pts = clusters.joinToString(",") { "[${it.first.first},${it.first.second},${it.third}]" }
                    """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0}</style></head><body><div id="m"></div><script>
var map=L.map('m');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'© OpenStreetMap'}).addTo(map);
var pts=[$pts];var g=[];
pts.forEach(function(p){g.push(L.marker([p[0],p[1]]).addTo(map).bindPopup(p[2]+' photo(s)'));});
map.fitBounds(L.featureGroup(g).getBounds().pad(0.3));
</script></body></html>"""
                }
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
                Text("Places (tap to open photos)", Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f)) {
                    items(clusters) { (k, ids, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.push(Screen.Collection("%.1f, %.1f".format(k.first, k.second), ids)) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("%.1f, %.1f".format(k.first, k.second))
                            Text("$n photos", color = Color(0xFFFF80AB))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- memories ----------------

@Composable
fun MemoriesScreen(vm: GalleryVM) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val groups = remember(vm.media) {
        vm.media.filter {
            val d = Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate()
            d.month == today.month && d.dayOfMonth == today.dayOfMonth && d.year < today.year
        }.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).year }.toList().sortedByDescending { it.first }
    }
    GScaffold("Memories", vm) { pad ->
        if (groups.isEmpty()) Box(Modifier.padding(pad)) { CenterText("No memories for today yet.") }
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups) { (year, list) ->
                val n = today.year - year
                Box(
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp))
                        .clickable { vm.push(Screen.Collection("$n year(s) ago", list.map { it.id })) }
                ) {
                    AsyncImage(
                        thumbRequest(ctx, list.first(), 720), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text("$n year(s) ago", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("$year • ${list.size} items", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ---------------- smart search ----------------

@Composable
fun SmartScreen(vm: GalleryVM) {
    var term by remember { mutableStateOf("") }
    GScaffold("Scene search & people", vm) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Gallery scans your photos on the device (nothing is uploaded) to find scenes such as beach, tree or food, and photos that contain faces. Scene words are in English.",
                color = Color(0xFFCCC5E6)
            )
            val p = vm.scanProgress
            if (p != null) Text("Scanning ${p.first} / ${p.second}…") else
                GButton(if (vm.aiData.isEmpty()) "Scan photos" else "Scan new photos", { vm.scanSmart() })
            Text("${vm.aiData.size} photos scanned • ${vm.faceIds.size} with faces", fontSize = 13.sp)
            OutlinedTextField(
                term, { term = it }, label = { Text("Search a scene (beach, tree, food…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            GButton("Search", { vm.searchScene(term) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("beach", "tree", "food", "sky", "flower", "car", "cat", "dog", "building").forEach { w ->
                    AssistChip({ vm.searchScene(w) }, { Text(w) })
                }
            }
            OutlinedButton({ vm.openPeople() }, Modifier.fillMaxWidth()) { Text("Show photos with people") }
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SettingsScreen(vm: GalleryVM) {
    val ctx = LocalContext.current
    GScaffold("Settings", vm) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Grid size", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (MIN_COLS..MAX_COLS).forEach { n ->
                    FilterChip(vm.columns == n, { vm.updateColumns(n) }, { Text("$n") })
                }
            }
            Text("Tip: pinch the photo grid with two fingers to resize it.", fontSize = 12.sp, color = Color(0xFFCCC5E6))

            Text("Slideshow interval: ${vm.interval}s", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Slider(vm.interval.toFloat(), { vm.updateInterval(it.toInt().coerceIn(2, 10)) }, valueRange = 2f..10f, steps = 7)
            Text("Slideshow transition", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fade", "Slide", "Zoom").forEachIndexed { i, n ->
                    FilterChip(vm.transition == i, { vm.updateTransition(i) }, { Text(n) })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", fontWeight = FontWeight.Bold)
                    Text("Ask for fingerprint / PIN when opening Gallery", fontSize = 12.sp, color = Color(0xFFCCC5E6))
                }
                Switch(vm.appLock, { on ->
                    if (on) vm.authenticate("Enable app lock") { vm.updateAppLock(true) } else vm.updateAppLock(false)
                })
            }

            OutlinedButton({
                ctx.imageLoader.memoryCache?.clear()
                ctx.imageLoader.diskCache?.clear()
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                vm.toast("Cache cleared")
            }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Clear cache") }

            OutlinedButton({ vm.pickBackupFolder() }, Modifier.fillMaxWidth()) {
                Text(if (vm.backupBusy) "Backing up…" else "Backup to a folder")
            }
        }
    }
}

// ---------------- developer ----------------

@Composable
fun DeveloperScreen(vm: GalleryVM) {
    val t = rememberInfiniteTransition(label = "dev")
    val shift by t.animateFloat(0f, 900f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Color(0xFF3D1E8F), Color(0xFFB0245F), Color(0xFFC96F00), Color(0xFF0B5C8F)),
                start = Offset(shift, 0f), end = Offset(shift + 1000f, 2000f)
            )
        )
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            IconButton({ vm.pop() }, Modifier.padding(top = 24.dp)) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Spacer(Modifier.size(8.dp))
            Text("Developer", color = Color(0xCCFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.size(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0x33FFFFFF)).padding(22.dp)
            ) {
                Text("Name", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                Text("S Om Yogeshananda", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.size(16.dp))
                Text("Education", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                Text("BSc in CSE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Teesta University, Rangpur", color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.size(22.dp))
            Text("Built with", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Kotlin", "Jetpack Compose", "Material 3", "Android MediaStore", "Coil", "Media3 ExoPlayer",
                    "Media3 Transformer", "ML Kit", "AndroidX Biometric", "ExifInterface", "Kotlin Coroutines",
                    "OpenStreetMap / Leaflet", "Gradle", "GitHub Actions"
                ).forEach {
                    Text(
                        it, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x44FFFFFF))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
            Text("Gallery • version 1.1", color = Color(0xB3FFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
