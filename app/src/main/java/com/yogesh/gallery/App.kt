package com.yogesh.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val GalleryGradient = Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFFFF4081), Color(0xFFFFAB40)))
val CoolGradient = Brush.linearGradient(listOf(Color(0xFF40C4FF), Color(0xFF7C4DFF)))
val AppBg = Brush.verticalGradient(listOf(Color(0xFF1B1035), Color(0xFF0E0B1A), Color(0xFF0B1A2E)))
val TilePalette = listOf(
    listOf(Color(0xFF7C4DFF), Color(0xFFB388FF)),
    listOf(Color(0xFFFF4081), Color(0xFFFF9E80)),
    listOf(Color(0xFF00BFA5), Color(0xFF40C4FF)),
    listOf(Color(0xFFFFAB40), Color(0xFFFF5252)),
    listOf(Color(0xFF536DFE), Color(0xFF18FFFF)),
    listOf(Color(0xFFE040FB), Color(0xFF7C4DFF))
)

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB388FF),
            secondary = Color(0xFFFF80AB),
            tertiary = Color(0xFFFFD180),
            background = Color(0xFF0E0B1A),
            surface = Color(0xFF171326),
            surfaceVariant = Color(0xFF241E3A),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun GradientTitle(text: String, size: TextUnit = 24.sp) {
    Text(text, style = TextStyle(brush = GalleryGradient, fontSize = size, fontWeight = FontWeight.ExtraBold))
}

@Composable
fun App(vm: GalleryVM, requestPerm: () -> Unit) {
    // Transparent Scaffolds give no content colour, so Text fell back to black on the dark background.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(AppBg)) {
            when {
                vm.locked -> LockScreen(vm)
                !vm.hasPermission -> PermissionScreen(requestPerm)
                else -> Main(vm)
            }
        }
    }
}

@Composable
fun Main(vm: GalleryVM) {
    BackHandler(vm.stack.isNotEmpty()) { vm.pop() }
    when (val s = vm.stack.lastOrNull()) {
        null -> HomeScreen(vm)
        is Screen.Collection -> CollectionScreen(vm, s)
        is Screen.Viewer -> ViewerScreen(vm, s)
        is Screen.Editor -> EditorScreen(vm, s.id)
        is Screen.Trim -> TrimScreen(vm, s.id)
        is Screen.Slideshow -> SlideshowScreen(vm, s)
        Screen.Trash -> TrashScreen(vm)
        Screen.Vault -> VaultScreen(vm)
        Screen.Duplicates -> DuplicatesScreen(vm)
        Screen.Map -> MapScreen(vm)
        Screen.Memories -> MemoriesScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Developer -> DeveloperScreen(vm)
        Screen.Smart -> SmartScreen(vm)
        Screen.People -> PeopleScreen(vm)
        is Screen.Wallpaper -> WallpaperScreen(vm, s.id)
    }
    GlobalDialogs(vm)
}

@Composable
fun LockScreen(vm: GalleryVM) {
    val t = rememberInfiniteTransition(label = "lock")
    val shift by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Color(0xFF7C4DFF), Color(0xFFFF4081), Color(0xFFFFAB40)),
                start = androidx.compose.ui.geometry.Offset(shift, 0f),
                end = androidx.compose.ui.geometry.Offset(shift + 1200f, 1800f)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(72.dp), tint = Color.White)
            Spacer(Modifier.size(12.dp))
            Text("Gallery is locked", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x55000000))
                    .clickable { vm.authenticate("Unlock Gallery") { vm.locked = false } }
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            ) { Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(GalleryGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(52.dp), tint = Color.White)
        }
        Spacer(Modifier.size(20.dp))
        GradientTitle("Gallery", 30.sp)
        Spacer(Modifier.size(12.dp))
        Text(
            "Allow access to your photos and videos so Gallery can show them.",
            textAlign = TextAlign.Center, color = Color(0xFFCCC5E6)
        )
        Spacer(Modifier.size(24.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(GalleryGradient).clickable { onRequest() }
                .padding(horizontal = 32.dp, vertical = 14.dp)
        ) { Text("Allow access", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun GlobalDialogs(vm: GalleryVM) {
    AlbumDialog(vm)
    RenameDialog(vm)
    InfoDialog(vm)
    NewAlbumDialog(vm)
    OcrDialog(vm)
}

@Composable
fun AlbumDialog(vm: GalleryVM) {
    val a = vm.albumAction ?: return
    var newName by remember { mutableStateOf("") }
    val names = remember(vm.media) { vm.media.map { it.bucket }.distinct().sorted() }
    AlertDialog(
        onDismissRequest = { vm.albumAction = null },
        title = { Text(if (a.move) "Move to album" else "Copy to album") },
        text = {
            Column {
                OutlinedTextField(
                    newName, { newName = it }, label = { Text("New album name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(names) { n ->
                        Text(
                            n,
                            Modifier.fillMaxWidth().clickable { vm.copyMove(a.items, n, a.move); vm.albumAction = null }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = { vm.copyMove(a.items, newName.trim(), a.move); vm.albumAction = null }
            ) { Text("Create") }
        },
        dismissButton = { TextButton({ vm.albumAction = null }) { Text("Cancel") } }
    )
}

@Composable
fun RenameDialog(vm: GalleryVM) {
    val m = vm.renameTarget ?: return
    var name by remember(m.id) { mutableStateOf(m.name.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = { vm.renameTarget = null },
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton({ vm.rename(m, name); vm.renameTarget = null }) { Text("Rename") }
        },
        dismissButton = { TextButton({ vm.renameTarget = null }) { Text("Cancel") } }
    )
}

@Composable
fun InfoDialog(vm: GalleryVM) {
    val m = vm.infoTarget ?: return
    val ctx = LocalContext.current
    val rows by produceState<List<Pair<String, String>>>(emptyList(), m.id) {
        value = withContext(Dispatchers.IO) { vm.details(m) }
    }
    val loc = rows.firstOrNull { it.first == "Location" }?.second
    AlertDialog(
        onDismissRequest = { vm.infoTarget = null },
        title = { Text("Details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(k, color = Color(0xFFB388FF), fontSize = 12.sp)
                        Text(v)
                    }
                }
            }
        },
        confirmButton = {
            if (loc != null) TextButton({
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + loc.replace(" ", "")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Open in Maps") }
        },
        dismissButton = { TextButton({ vm.infoTarget = null }) { Text("Close") } }
    )
}

@Composable
fun NewAlbumDialog(vm: GalleryVM) {
    if (!vm.newAlbumDialog) return
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.newAlbumDialog = false },
        title = { Text("New album") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Album name") }, singleLine = true)
                Spacer(Modifier.size(8.dp))
                Text("Next, select the photos you want to add.", color = Color(0xFFCCC5E6), fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.pendingAlbum = name.trim(); vm.newAlbumDialog = false; vm.tab = 0
                vm.toast("Long-press photos to select, then tap “Add to ${name.trim()}”")
            }) { Text("Next") }
        },
        dismissButton = { TextButton({ vm.newAlbumDialog = false }) { Text("Cancel") } }
    )
}

@Composable
fun OcrDialog(vm: GalleryVM) {
    val t = vm.ocrResult ?: return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.ocrResult = null },
        title = { Text("Text & QR") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(t) } },
        confirmButton = {
            TextButton({
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Gallery", t))
                vm.toast("Copied")
            }) { Text("Copy") }
        },
        dismissButton = { TextButton({ vm.ocrResult = null }) { Text("Close") } }
    )
}
