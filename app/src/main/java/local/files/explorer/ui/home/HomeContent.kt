package local.files.explorer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Category
import local.files.explorer.core.CategoryStats
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Format
import local.files.explorer.core.SizeFilter
import local.files.explorer.core.SortField
import local.files.explorer.core.SortOrder
import local.files.explorer.storage.ScanProgress
import local.files.explorer.ui.browser.EmptyState
import local.files.explorer.ui.browser.FileRow
import local.files.explorer.ui.browser.SearchScopeBar
import local.files.explorer.ui.browser.SearchUi
import local.files.explorer.ui.browser.StorageBar
import local.files.explorer.ui.browser.StorageSummary

// ---------------------------------------------------------------- overview

data class ScanUi(val scanning: Boolean = false, val progress: ScanProgress = ScanProgress(), val complete: Boolean = false, val cancelled: Boolean = false)

data class HomeUi(
    val stats: Map<Category, CategoryStats> = emptyMap(),
    val storage: List<StorageSummary> = emptyList(),
    val largest: List<FileEntry> = emptyList(),
    val topFolders: List<FolderRowUi> = emptyList(),
    val duplicateHint: String = "",
    val scan: ScanUi = ScanUi(),
)

interface HomeActions {
    fun search() {}
    fun category(c: Category) {}
    fun largest() {}
    fun folderSizes() {}
    fun duplicates() {}
    fun cancelScan() {}
    fun rescan() {}
    fun openFile(e: FileEntry) {}
    fun menu(e: FileEntry) {}
    /** Opens the matching browser tab for a Home storage card (Internal → Internal tab). */
    fun openStorage(s: StorageSummary) {}
}

fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.IMAGES -> Icons.Rounded.Image
    Category.VIDEOS -> Icons.Rounded.Movie
    Category.AUDIO -> Icons.Rounded.MusicNote
    Category.DOCUMENTS -> Icons.Rounded.Description
    Category.APKS -> Icons.Rounded.Android
    Category.ARCHIVES -> Icons.Rounded.Archive
    Category.DOWNLOADS -> Icons.Rounded.Download
}

@Composable
fun categoryTint(c: Category): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = when (c) {
        Category.IMAGES -> Color(0xFF2F9E6A)
        Category.VIDEOS -> Color(0xFFD9534F)
        Category.AUDIO -> Color(0xFF8E5BD8)
        Category.DOCUMENTS -> Color(0xFF3D6FE0)
        Category.APKS -> Color(0xFF4CAF50)
        Category.ARCHIVES -> Color(0xFFE08A1E)
        Category.DOWNLOADS -> Color(0xFF00897B)
    }
    return if (dark) base.copy(alpha = 0.28f) to base.lighten() else base.copy(alpha = 0.14f) to base
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
private fun Color.lighten(): Color = Color(red + (1 - red) * 0.45f, green + (1 - green) * 0.45f, blue + (1 - blue) * 0.45f, alpha)

@Composable
fun HomeContent(ui: HomeUi, actions: HomeActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SearchPill(actions::search) }
        if (ui.storage.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.storage.forEach { s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { actions.openStorage(s) }
                                .padding(vertical = 2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open ${s.label}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            StorageBar(s)
                        }
                    }
                }
            }
        }
        if (ui.scan.scanning) item { ScanStatus(ui.scan, actions::cancelScan) }
        else if (ui.scan.cancelled) item { PartialNotice(actions::rescan) }
        item { Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
        Category.entries.chunked(2).forEach { pair ->
            item(key = pair.first().name) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { c -> CategoryCard(c, ui.stats[c], ui.scan.complete || !ui.scan.scanning, { actions.category(c) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            ToolCard(
                Icons.Rounded.Straighten, "Largest files",
                if (ui.largest.isEmpty()) "Nothing over 10 MB yet" else "Biggest first, with size filters",
                actions::largest,
            ) {
                ui.largest.take(3).forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { actions.openFile(e) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(12.dp))
                        Text(Format.size(e.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            ToolCard(Icons.Rounded.PieChart, "Folder sizes", "See which folders take up the most space", actions::folderSizes) {
                val max = ui.topFolders.maxOfOrNull { it.bytes } ?: 0
                ui.topFolders.take(3).forEach { f ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Format.size(f.bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        SizeBar(if (max > 0) f.bytes.toFloat() / max else 0f)
                    }
                }
            }
        }
        item {
            ToolCard(
                Icons.Rounded.ContentCopy, "Find duplicates",
                if (ui.duplicateHint.isEmpty()) "Same size, then same contents — fully offline" else ui.duplicateHint,
                actions::duplicates,
            ) {}
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text("Search all storage", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ScanStatus(scan: ScanUi, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scanning storage\u2026", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${"%,d".format(scan.progress.files)} files \u00b7 ${Format.size(scan.progress.bytes)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    }
}

@Composable
fun PartialNotice(onRescan: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scan was stopped, so these numbers are partial.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRescan) { Text("Rescan") }
        }
    }
}

@Composable
private fun CategoryCard(c: Category, stats: CategoryStats?, ready: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val (bg, fg) = categoryTint(c)
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(c), null, tint = fg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(c.label, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    stats == null -> if (ready) "\u2014" else "Counting\u2026"
                    else -> "${"%,d".format(stats.count)} \u00b7 ${Format.size(stats.bytes)}"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SizeBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

// ---------------------------------------------------------------- file lists

enum class ListKind { CATEGORY, LARGEST, SEARCH }

data class FileListUi(
    val kind: ListKind,
    val entries: List<FileEntry> = emptyList(),
    val locations: Map<String, String> = emptyMap(),
    val query: String = "",
    val sort: SortOrder = SortOrder(SortField.DATE, ascending = false, foldersFirst = false),
    val sizeFilter: SizeFilter = SizeFilter.OVER_10MB,
    val totalBytes: Long = 0,
    val scan: ScanUi = ScanUi(),
    val search: SearchUi? = null,
    val emptyTitle: String = "Nothing here",
    val emptyBody: String? = null,
    val noThumbs: Set<String> = emptySet(),
    val selection: Set<String> = emptySet(),
)

interface FileListActions {
    fun open(e: FileEntry) {}
    fun longPress(e: FileEntry) {}
    fun menu(e: FileEntry) {}
    fun query(q: String) {}
    fun sort(o: SortOrder) {}
    fun sizeFilter(f: SizeFilter) {}
    fun cancelScan() {}
    fun rescan() {}
}

@Composable
fun FileListContent(ui: FileListUi, actions: FileListActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        when (ui.kind) {
            ListKind.SEARCH -> ui.search?.let { SearchScopeBar(it, ui.query) {} }
            else -> {
                OutlinedTextField(
                    ui.query, actions::query, singleLine = true,
                    placeholder = { Text("Filter by name") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (ui.query.isNotEmpty()) IconButton(onClick = { actions.query("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.kind == ListKind.LARGEST) {
                        SizeFilter.entries.forEach { f -> FilterChip(ui.sizeFilter == f, { actions.sizeFilter(f) }, { Text(f.label) }) }
                    } else {
                        listOf(SortField.DATE to "Newest", SortField.SIZE to "Largest", SortField.NAME to "Name").forEach { (f, label) ->
                            FilterChip(ui.sort.field == f, {
                                actions.sort(SortOrder(f, ascending = f == SortField.NAME, foldersFirst = false))
                            }, { Text(label) })
                        }
                    }
                }
                Text(
                    "${"%,d".format(ui.entries.size)} files \u00b7 ${Format.size(ui.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                if (ui.scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
        if (ui.kind == ListKind.SEARCH && ui.search?.running == true) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f)) {
            if (ui.entries.isEmpty()) {
                if (!(ui.kind == ListKind.SEARCH && (ui.search?.running == true || ui.query.isBlank())) && !ui.scan.scanning) {
                    EmptyState(Icons.Rounded.FolderOpen, ui.emptyTitle, ui.emptyBody)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                    items(ui.entries, key = { it.path }) { e ->
                        FileRow(
                            e, selected = e.path in ui.selection, selecting = ui.selection.isNotEmpty(), badge = false,
                            allowThumb = e.path !in ui.noThumbs,
                            onClick = { actions.open(e) }, onLongClick = { actions.longPress(e) }, onMenu = { actions.menu(e) },
                            location = ui.locations[e.path],
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- folder sizes

data class FolderRowUi(
    val name: String,
    val path: String,
    val bytes: Long,
    val files: Int,
    val hasNomedia: Boolean = false,
) {
    fun toEntry(): FileEntry = FileEntry(
        name = name, path = path, backend = local.files.explorer.core.Backend.LOCAL,
        isDirectory = true, size = bytes, hasNomedia = hasNomedia, childCount = files,
    )
}

data class FolderSizesUi(
    val title: String = "",
    /** Absolute path of the folder being shown; null when listing multiple storage roots. */
    val path: String? = null,
    val folders: List<FolderRowUi> = emptyList(),
    /** Files that sit directly in [path] (not in subfolders), biggest first. */
    val files: List<FileEntry> = emptyList(),
    val totalBytes: Long = 0,
    val directBytes: Long = 0,
    val directFiles: Int = 0,
    val scan: ScanUi = ScanUi(),
    val selection: Set<String> = emptySet(),
)

interface FolderSizesActions {
    fun drill(f: FolderRowUi) {}
    fun openFolder(f: FolderRowUi) {}
    /** Opens the current folder (or a storage root) in the normal browser. */
    fun openCurrent() {}
    fun longPress(f: FolderRowUi) {}
    fun menu(f: FolderRowUi) {}
    fun openFile(e: FileEntry) {}
    fun longPressFile(e: FileEntry) {}
    fun menuFile(e: FileEntry) {}
    fun cancelScan() {}
    fun rescan() {}
}

@Composable
fun FolderSizesContent(ui: FolderSizesUi, actions: FolderSizesActions, modifier: Modifier = Modifier) {
    val selecting = ui.selection.isNotEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.scan.scanning) item { ScanStatus(ui.scan, actions::cancelScan) }
        else if (ui.scan.cancelled) item { PartialNotice(actions::rescan) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ui.title} · ${Format.size(ui.totalBytes)}",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ui.path != null && !selecting) {
                        Text(
                            "Tap a folder to drill into sizes, or open it in the browser",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (ui.path != null && !selecting) {
                    TextButton(onClick = actions::openCurrent) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                }
            }
        }
        if (ui.folders.isEmpty() && ui.files.isEmpty() && !ui.scan.scanning) item {
            Text("Nothing to show here.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
        }
        if (ui.folders.isNotEmpty()) {
            item {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        val max = ui.folders.maxOfOrNull { it.bytes } ?: 0
        items(ui.folders, key = { "d:" + it.path }) { f ->
            val selected = f.path in ui.selection
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                label = "folder-row",
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = bg),
                modifier = Modifier.combinedClickable(
                    onClick = { if (selecting) actions.longPress(f) else actions.drill(f) },
                    onLongClick = { actions.longPress(f) },
                ),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        if (selected) {
                            Icon(
                                Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Format.size(f.bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "${"%,d".format(f.files)} files · ${percent(f.bytes, ui.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        SizeBar(if (max > 0) f.bytes.toFloat() / max else 0f)
                    }
                    if (!selecting) {
                        IconButton(onClick = { actions.menu(f) }) {
                            Icon(Icons.Rounded.MoreVert, "More options for ${f.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { actions.openFolder(f) }) {
                            Icon(Icons.Rounded.FolderOpen, "Open ${f.name} in browser", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (ui.files.isNotEmpty()) {
            item {
                Text(
                    "Files in this folder · ${"%,d".format(ui.files.size)} · ${Format.size(ui.directBytes)}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            items(ui.files, key = { "f:" + it.path }) { e ->
                FileRow(
                    e, selected = e.path in ui.selection, selecting = selecting, badge = false, allowThumb = true,
                    onClick = { actions.openFile(e) }, onLongClick = { actions.longPressFile(e) },
                    onMenu = if (selecting) null else ({ actions.menuFile(e) }),
                )
            }
        } else if (ui.directFiles > 0 && ui.path != null) {
            // Index still counting; keep a hint so the view isn't blank about loose files.
            item {
                Text(
                    "${"%,d".format(ui.directFiles)} files sit in this folder (${Format.size(ui.directBytes)})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- duplicates

data class DuplicateGroupUi(
    val hash: String,
    val size: Long,
    val files: List<FileEntry>,
    val locations: Map<String, String> = emptyMap(),
) {
    val wastedBytes: Long get() = size * (files.size - 1L).coerceAtLeast(0)
}

data class DuplicatesUi(
    val hashing: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val groups: List<DuplicateGroupUi> = emptyList(),
    val selection: Set<String> = emptySet(),
    val scan: ScanUi = ScanUi(),
    val ready: Boolean = false,
)

interface DuplicatesActions {
    fun open(e: FileEntry) {}
    fun longPress(e: FileEntry) {}
    fun menu(e: FileEntry) {}
    fun selectExtras(group: DuplicateGroupUi) {}
    fun rescan() {}
    fun cancelScan() {}
}

@Composable
fun DuplicatesContent(ui: DuplicatesUi, actions: DuplicatesActions, modifier: Modifier = Modifier) {
    val selecting = ui.selection.isNotEmpty()
    Column(modifier.fillMaxSize()) {
        when {
            ui.scan.scanning && !ui.ready -> {
                Box(Modifier.padding(16.dp)) { ScanStatus(ui.scan, actions::cancelScan) }
            }
            ui.hashing -> {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Comparing file contents\u2026", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (ui.total > 0) "${ui.progress} of ${ui.total} candidates" else "Looking for same-sized files\u2026",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { if (ui.total > 0) ui.progress.toFloat() / ui.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> {
                val waste = ui.groups.sumOf { it.wastedBytes }
                Text(
                    if (ui.groups.isEmpty()) "No duplicates found"
                    else "${"%,d".format(ui.groups.size)} groups \u00b7 ${Format.size(waste)} reclaimable",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        if (!ui.hashing && ui.groups.isEmpty() && (ui.ready || ui.scan.complete)) {
            EmptyState(Icons.Rounded.ContentCopy, "No duplicates", "Nothing on your storage shares both size and contents.")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                ui.groups.forEach { g ->
                    item(key = "hdr-${g.hash}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${g.files.size} copies \u00b7 ${Format.size(g.size)} each",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Keep one to free ${Format.size(g.wastedBytes)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { actions.selectExtras(g) }) { Text("Select extras") }
                        }
                    }
                    items(g.files, key = { it.path }) { e ->
                        FileRow(
                            e, selected = e.path in ui.selection, selecting = selecting, badge = false, allowThumb = true,
                            onClick = { actions.open(e) }, onLongClick = { actions.longPress(e) }, onMenu = { actions.menu(e) },
                            location = g.locations[e.path],
                        )
                    }
                }
            }
        }
    }
}

fun percent(part: Long, total: Long): String = if (total <= 0) "0%" else {
    val p = part * 100.0 / total
    if (p < 1) "<1%" else "${p.toInt()}%"
}
