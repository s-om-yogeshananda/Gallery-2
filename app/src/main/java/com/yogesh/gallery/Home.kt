package com.yogesh.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------- grid model ----------------

sealed interface GridEntry {
    val key: String

    class Header(val title: String, id: String) : GridEntry { override val key: String = "h_$id" }
    class Cell(val m: Media) : GridEntry { override val key: String = "m_${m.id}" }
}

class Built(val sorted: List<Media>, val entries: List<GridEntry>, val index: Map<Long, Int>)

class GridModel {
    var built by mutableStateOf(Built(emptyList(), emptyList(), emptyMap()))
}

fun buildEntries(src: List<Media>, sortBy: SortBy, asc: Boolean): Built {
    val cmp: Comparator<Media> = when (sortBy) {
        SortBy.DATE -> compareBy<Media>({ it.date }, { it.id })
        SortBy.NAME -> compareBy<Media>({ it.name.lowercase() }, { it.id })
        SortBy.SIZE -> compareBy<Media>({ it.size }, { it.id })
    }
    val sorted = src.sortedWith(if (asc) cmp else cmp.reversed())
    val entries = ArrayList<GridEntry>(sorted.size + 64)
    if (sortBy == SortBy.DATE) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val locale = Locale.getDefault()
        val sameYear = DateTimeFormatter.ofPattern("EEE, d MMMM", locale)
        val otherYear = DateTimeFormatter.ofPattern("EEE, d MMMM yyyy", locale)
        var lastDay: LocalDate? = null
        var lastLabel = ""
        for (m in sorted) {
            val d = Instant.ofEpochMilli(m.date).atZone(zone).toLocalDate()
            if (d != lastDay) {
                lastDay = d
                lastLabel = when {
                    d == today -> "Today"
                    d == today.minusDays(1) -> "Yesterday"
                    d.year == today.year -> d.format(sameYear)
                    else -> d.format(otherYear)
                }
                // key is the ISO day so it is always unique inside one list
                entries.add(GridEntry.Header(lastLabel, d.toString()))
            }
            entries.add(GridEntry.Cell(m))
        }
    } else sorted.forEach { entries.add(GridEntry.Cell(it)) }
    return Built(sorted, entries, sorted.withIndex().associate { it.value.id to it.index })
}

/** Pinch with two fingers to change the number of columns (limited to MIN_COLS..MAX_COLS). */
fun Modifier.pinchColumns(vm: GalleryVM): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.size >= 2) {
                zoom *= ev.calculateZoom()
                if (zoom > 1.3f) { vm.updateColumns(vm.columns - 1); zoom = 1f }
                else if (zoom < 0.77f) { vm.updateColumns(vm.columns + 1); zoom = 1f }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

// ---------------- home ----------------

@Composable
fun HomeScreen(vm: GalleryVM) {
    val selecting = vm.selection.isNotEmpty()
    BackHandler(selecting) { vm.selection = emptySet() }
    BackHandler(!selecting && vm.searching) { vm.searching = false; vm.query = "" }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { if (selecting) SelectionTopBar(vm) else HomeTopBar(vm) },
        bottomBar = { if (!selecting) HomeBottomBar(vm) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                0 -> PhotosTab(vm)
                1 -> AlbumsTab(vm)
                2 -> FavoritesTab(vm)
                else -> ExploreTab(vm)
            }
        }
    }
}

@Composable
fun HomeTopBar(vm: GalleryVM) {
    var sortMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (vm.searching) {
                TextField(
                    value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                    placeholder = { Text("Name, date, album, scene…") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            } else GradientTitle("Gallery")
        },
        navigationIcon = {
            if (vm.searching) IconButton({ vm.searching = false; vm.query = "" }) { Icon(Icons.Rounded.ArrowBack, "Back") }
        },
        actions = {
            if (!vm.searching) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(GalleryGradient)
                        .clickable { vm.push(Screen.Developer) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Developer", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                IconButton({ vm.searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortBy.values().forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { vm.sortBy = s; sortMenu = false },
                                trailingIcon = { if (vm.sortBy == s) Icon(Icons.Rounded.Check, null) }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Ascending") }, onClick = { vm.ascending = true; sortMenu = false },
                            trailingIcon = { if (vm.ascending) Icon(Icons.Rounded.Check, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Descending") }, onClick = { vm.ascending = false; sortMenu = false },
                            trailingIcon = { if (!vm.ascending) Icon(Icons.Rounded.Check, null) }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun HomeBottomBar(vm: GalleryVM) {
    val tabs = listOf(
        Triple("Photos", Icons.Rounded.PhotoLibrary, 0),
        Triple("Albums", Icons.Rounded.Collections, 1),
        Triple("Favorites", Icons.Rounded.Favorite, 2),
        Triple("Explore", Icons.Rounded.Explore, 3)
    )
    NavigationBar(containerColor = Color(0xE6171326)) {
        tabs.forEach { (label, icon, i) ->
            NavigationBarItem(
                selected = vm.tab == i, onClick = { vm.tab = i },
                icon = { Icon(icon, label) }, label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White, selectedTextColor = Color(0xFFFF80AB),
                    indicatorColor = Color(0xFF7C4DFF)
                )
            )
        }
    }
}

@Composable
fun SelectionTopBar(vm: GalleryVM) {
    val sel = vm.selection.mapNotNull { vm.byId[it] }
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("${sel.size} selected") },
        navigationIcon = { IconButton({ vm.selection = emptySet() }) { Icon(Icons.Rounded.Close, "Cancel") } },
        actions = {
            vm.pendingAlbum?.let { name ->
                TextButton({ vm.copyMove(sel, name, false); vm.pendingAlbum = null }) { Text("Add to $name") }
            }
            IconButton({ vm.share(sel) }) { Icon(Icons.Rounded.Share, "Share") }
            IconButton({ vm.toggleFavorites(sel) }) { Icon(Icons.Rounded.Favorite, "Favorite") }
            IconButton({ vm.trash(sel) }) { Icon(Icons.Rounded.Delete, "Delete") }
            Box {
                IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(
                        text = { Text("Select all") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                        onClick = { vm.selection = vm.lastVisible.toSet(); more = false })
                    DropdownMenuItem(
                        text = { Text("Copy to album") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { vm.albumAction = AlbumAction(sel, false); more = false })
                    DropdownMenuItem(
                        text = { Text("Move to album") }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                        onClick = { vm.albumAction = AlbumAction(sel, true); more = false })
                    DropdownMenuItem(
                        text = { Text("Hide in private vault") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                        onClick = { more = false; vm.authenticate("Use private vault") { vm.hide(sel) } })
                    DropdownMenuItem(
                        text = { Text("Create collage (2–4)") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                        onClick = { vm.collage(sel); more = false })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2A1F4D))
    )
}

// ---------------- tabs ----------------

@Composable
fun PhotosTab(vm: GalleryVM) {
    Column(Modifier.fillMaxSize()) {
        vm.pendingAlbum?.let { name ->
            Row(
                Modifier.fillMaxWidth().background(GalleryGradient).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select photos for “$name”", color = Color.White, modifier = Modifier.weight(1f))
                TextButton({ vm.pendingAlbum = null }) { Text("Cancel", color = Color.White) }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Filter.values().toList()) { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(f.label) })
            }
        }
        if (!vm.loaded) CenterText("Loading…")
        else if (vm.media.isEmpty()) CenterText("No photos or videos found")
        else MediaGrid(vm, vm.media, vm.photosModel, vm.gridState, applyFilters = true, trackVisible = true) { list, idx ->
            vm.push(Screen.Viewer(list.map { it.id }, idx))
        }
    }
}

@Composable
fun CenterText(t: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(t, color = Color(0xFFCCC5E6), textAlign = TextAlign.Center)
    }
}

@Composable
fun MediaGrid(
    vm: GalleryVM,
    source: List<Media>,
    model: GridModel,
    state: LazyGridState,
    applyFilters: Boolean,
    trackVisible: Boolean = true,
    onOpen: (List<Media>, Int) -> Unit
) {
    val sortBy = vm.sortBy
    val asc = vm.ascending
    val filter = if (applyFilters) vm.filter else Filter.ALL
    val query = if (applyFilters) vm.query else ""
    val labels = vm.aiData
    LaunchedEffect(source, sortBy, asc, filter, query, labels) {
        val b = withContext(Dispatchers.Default) {
            val list = if (applyFilters) vm.filterList(source, filter, query, labels) else source
            buildEntries(list, sortBy, asc)
        }
        model.built = b
        if (trackVisible) vm.lastVisible = b.sorted.map { it.id }
    }
    val built = model.built
    val selection = vm.selection
    val favs = vm.favorites
    val selecting = selection.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Fixed(vm.columns),
        state = state,
        modifier = Modifier.fillMaxSize().pinchColumns(vm),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            built.entries,
            key = { it.key },
            span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is GridEntry.Header) 0 else 1 }
        ) { e ->
            when (e) {
                is GridEntry.Header -> Text(
                    e.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White,
                    modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp)
                )
                is GridEntry.Cell -> {
                    val m = e.m
                    Thumb(
                        m = m, selected = m.id in selection, selecting = selecting, fav = m.id in favs,
                        onClick = {
                            if (vm.selection.isNotEmpty()) toggleSel(vm, m.id)
                            else onOpen(built.sorted, built.index[m.id] ?: 0)
                        },
                        onLong = { toggleSel(vm, m.id) }
                    )
                }
            }
        }
    }
}

private fun toggleSel(vm: GalleryVM, id: Long) {
    vm.selection = if (id in vm.selection) vm.selection - id else vm.selection + id
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumb(m: Media, selected: Boolean, selecting: Boolean, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val req = remember(m.id, m.size, m.mod) { thumbRequest(ctx, m) }
    Box(
        Modifier.aspectRatio(1f).background(Color(0xFF241E3A))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (m.isVideo) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(50))
                    .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                Text(fmtDur(m.duration), color = Color.White, fontSize = 11.sp)
            }
        }
        if (fav) Icon(
            Icons.Rounded.Favorite, null,
            Modifier.align(Alignment.BottomEnd).padding(5.dp).size(16.dp), tint = Color(0xFFFF4081)
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x667C4DFF)))
            Icon(
                Icons.Rounded.CheckCircle, null,
                Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp), tint = Color.White
            )
        } else if (selecting) {
            Icon(
                Icons.Rounded.RadioButtonUnchecked, null,
                Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp), tint = Color.White
            )
        }
    }
}

data class Album(val name: String, val items: List<Media>)

@Composable
fun AlbumsTab(vm: GalleryVM) {
    val albums = remember(vm.media) {
        vm.media.groupBy { it.bucket }.map { (k, v) -> Album(k, v.sortedByDescending { it.date }) }
            .sortedWith(
                compareBy<Album> {
                    if (it.name.equals("Camera", true)) 0 else if (it.name.contains("Screenshot", true)) 1 else 2
                }.thenByDescending { it.items.size }
            )
    }
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums, key = { it.name }) { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF241E3A))
                        .clickable { vm.push(Screen.Collection(a.name, a.items.map { it.id })) }
                ) {
                    AsyncImage(
                        thumbRequest(ctx, a.items.first(), 480), null,
                        Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop
                    )
                    Column(Modifier.padding(10.dp)) {
                        Text(a.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${a.items.size} items", fontSize = 12.sp, color = Color(0xFFCCC5E6))
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { vm.newAlbumDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Color(0xFFFF4081), contentColor = Color.White
        ) { Icon(Icons.Rounded.Add, "New album") }
    }
}

@Composable
fun FavoritesTab(vm: GalleryVM) {
    val items = remember(vm.media, vm.favorites) { vm.media.filter { it.id in vm.favorites } }
    val model = remember { GridModel() }
    val state = rememberLazyGridState()
    if (items.isEmpty()) CenterText("No favorites yet.\nTap the heart on a photo.")
    else MediaGrid(vm, items, model, state, applyFilters = false, trackVisible = true) { list, idx ->
        vm.push(Screen.Viewer(list.map { it.id }, idx))
    }
}

class Tile(val title: String, val sub: String, val icon: ImageVector, val action: () -> Unit)

@Composable
fun ExploreTab(vm: GalleryVM) {
    val tiles = listOf(
        Tile("Memories", "On this day", Icons.Rounded.History) { vm.push(Screen.Memories) },
        Tile("Map", "Photos by place", Icons.Rounded.Map) { vm.push(Screen.Map) },
        Tile("People", "Photos with faces", Icons.Rounded.Face) { vm.openPeople() },
        Tile("Scene search", "Beach, tree, food…", Icons.Rounded.AutoAwesome) { vm.push(Screen.Smart) },
        Tile("Duplicates", "Find same photos", Icons.Rounded.ContentCopy) { vm.push(Screen.Duplicates) },
        Tile("Slideshow", "Auto play", Icons.Rounded.Slideshow) {
            vm.startSlideshow(vm.media.filter { !it.isVideo }.sortedByDescending { it.date }.map { it.id })
        },
        Tile("Recycle bin", "Kept 30 days", Icons.Rounded.Delete) { vm.push(Screen.Trash) },
        Tile("Private vault", "Locked folder", Icons.Rounded.Lock) {
            vm.authenticate("Open private vault") { vm.push(Screen.Vault) }
        },
        Tile("Cloud backup", "To a folder", Icons.Rounded.CloudUpload) { vm.pickBackupFolder() },
        Tile("Settings", "Grid, lock, cache", Icons.Rounded.Settings) { vm.push(Screen.Settings) },
        Tile("Developer", "About this app", Icons.Rounded.Code) { vm.push(Screen.Developer) }
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(tiles.size) { i ->
            val t = tiles[i]
            Column(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(TilePalette[i % TilePalette.size]))
                    .clickable { t.action() }.padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Icon(t.icon, null, Modifier.size(22.dp), tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Text(t.sub, color = Color(0xE6FFFFFF), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun CollectionScreen(vm: GalleryVM, s: Screen.Collection) {
    val items = remember(s.ids, vm.byId) { s.ids.mapNotNull { vm.byId[it] } }
    val selecting = vm.selection.isNotEmpty()
    val model = remember { GridModel() }
    val state = rememberLazyGridState()
    BackHandler(selecting) { vm.selection = emptySet() }
    LaunchedEffect(items.isEmpty(), vm.loaded) { if (items.isEmpty() && vm.loaded) vm.pop() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selecting) SelectionTopBar(vm) else TopAppBar(
                title = { Text(s.title, maxLines = 1) },
                navigationIcon = { IconButton({ vm.pop() }) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton({ vm.startSlideshow(items.filter { !it.isVideo }.map { it.id }) }) {
                        Icon(Icons.Rounded.Slideshow, "Slideshow")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            MediaGrid(vm, items, model, state, applyFilters = false, trackVisible = true) { list, idx ->
                vm.push(Screen.Viewer(list.map { it.id }, idx))
            }
        }
    }
}
