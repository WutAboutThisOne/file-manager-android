package local.files.explorer.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Backend
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Format
import local.files.explorer.settings.ViewMode
import local.files.explorer.ui.common.FileIcon
import local.files.explorer.ui.theme.MonoStyle

data class StorageSummary(val label: String, val usedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class ClipboardUi(val count: Int, val cut: Boolean, val extract: Boolean = false)

data class BrowserUi(
    val crumbs: List<Crumb> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val backend: Backend = Backend.LOCAL,
    val loading: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.LIST,
    val selection: Set<String> = emptySet(),
    /** Paths of folders that get the .nomedia badge. */
    val nomediaBadges: Set<String> = emptySet(),
    /** Whether the folder being browsed already contains a `.nomedia` file. */
    val hasNomedia: Boolean = false,
    /** Paths whose thumbnails are suppressed by "Respect .nomedia". */
    val noThumbs: Set<String> = emptySet(),
    val readOnly: Boolean = false,
    val storage: StorageSummary? = null,
    val clipboard: ClipboardUi? = null,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val canPaste: Boolean = true,
    /** path -> "Internal storage/Documents" for search results. */
    val locations: Map<String, String> = emptyMap(),
    /** Path to scroll to and flash briefly (after "Go to file location"). */
    val highlight: String? = null,
    /** Non-null while the search bar is open. */
    val search: SearchUi? = null,
    /** Non-null while browsing inside an archive. */
    val archive: local.files.explorer.ui.archive.ArchiveUi? = null,
)

data class SearchUi(
    val recursive: Boolean,
    val running: Boolean,
    val found: Int,
    val limitHit: Boolean,
    val limit: Int,
    val supportsRecursive: Boolean = true,
)

interface BrowserActions {
    fun open(entry: FileEntry) {}
    fun longPress(entry: FileEntry) {}
    fun menu(entry: FileEntry) {}
    fun crumb(crumb: Crumb) {}
    fun paste() {}
    fun clearClipboard() {}
    fun retry() {}
    fun setSearchRecursive(recursive: Boolean) {}
    fun extractArchive() {}
    fun unlockArchive() {}
    fun archiveAppInfo() {}
}

@Composable
fun BrowserContent(ui: BrowserUi, actions: BrowserActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        if (ui.crumbs.isNotEmpty()) Breadcrumbs(ui.crumbs, actions::crumb)
        if (ui.storage != null && ui.crumbs.size <= 1 && ui.searchQuery.isEmpty()) StorageBar(ui.storage, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        ui.search?.let { SearchScopeBar(it, ui.searchQuery, actions::setSearchRecursive) }
        if (ui.archive != null) local.files.explorer.ui.archive.ArchiveBar(ui.archive, actions::extractArchive, actions::unlockArchive, actions::archiveAppInfo)
        else if (ui.readOnly) ReadOnlyBanner()
        if (ui.loading || ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.error != null -> EmptyState(Icons.Rounded.FolderOff, "Can't open this folder", ui.error, "Try again", actions::retry)
                ui.search?.running == true && ui.entries.isEmpty() -> Unit
                !ui.loading && ui.entries.isEmpty() && ui.searchQuery.isNotEmpty() && !ui.searching ->
                    EmptyState(Icons.Rounded.SearchOff, "No matches", "Nothing here matches \u201c${ui.searchQuery}\u201d")
                !ui.loading && ui.entries.isEmpty() -> EmptyState(Icons.Rounded.FolderOff, "This folder is empty", null)
                ui.viewMode == ViewMode.GRID -> FileGrid(ui, actions)
                else -> FileList(ui, actions)
            }
            androidx.compose.animation.AnimatedVisibility(
                ui.clipboard != null,
                Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ui.clipboard?.let { PasteBar(it, ui.canPaste && !ui.readOnly, actions) }
            }
        }
    }
}

@Composable
fun Breadcrumbs(crumbs: List<Crumb>, onClick: (Crumb) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs) { if (crumbs.isNotEmpty()) state.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, c -> c.path }) { i, c ->
            val last = i == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (last) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .combinedClickable(onClick = { onClick(c) })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (!last) Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun StorageBar(s: StorageSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text("${Format.size(s.usedBytes)} used", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "${Format.size(s.totalBytes - s.usedBytes)} free of ${Format.size(s.totalBytes)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { s.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = if (s.fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun ReadOnlyBanner() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Root browsing is read-only. Turn on root writes in Settings to change files here.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

fun subtitleFor(e: FileEntry): String = buildString {
    if (e.backend == Backend.ROOT && e.permissions != null) {
        append(e.permissions); append("  "); append(e.owner ?: "?"); append(':'); append(e.group ?: "?")
        if (!e.isDirectory) { append("  "); append(Format.size(e.size)) }
        return@buildString
    }
    if (e.isDirectory) append(e.childCount?.let { Format.items(it) } ?: "Folder")
    else if (e.backend == Backend.ARCHIVE && e.size <= 0 && e.compressedSize != null) append("${Format.size(e.compressedSize)} compressed")
    else {
        append(Format.size(e.size))
        e.compressedSize?.let { append(" (${Format.size(it)} compressed)") }
    }
    if (e.lastModified > 0) { append("  ·  "); append(Format.date(e.lastModified)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(ui: BrowserUi, actions: BrowserActions) {
    val state = rememberLazyListState()
    LaunchedEffect(ui.highlight, ui.entries) {
        val i = ui.highlight?.let { h -> ui.entries.indexOfFirst { it.path == h } } ?: -1
        if (i >= 0) state.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    val drag = local.files.explorer.ui.LocalItemDrag.current
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = if (ui.clipboard != null) 96.dp else 24.dp)) {
        items(ui.entries, key = { it.path }) { e ->
            FileRow(
                e, selected = e.path in ui.selection, selecting = ui.selection.isNotEmpty(),
                badge = e.path in ui.nomediaBadges, allowThumb = e.path !in ui.noThumbs,
                onClick = { actions.open(e) }, onLongClick = { actions.longPress(e) }, onMenu = { actions.menu(e) },
                location = ui.locations[e.path], highlighted = e.path == ui.highlight,
                modifier = Modifier.animateItemPlacement().then(drag?.modifier(e, e.path in ui.selection) ?: Modifier),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    e: FileEntry, selected: Boolean, selecting: Boolean, badge: Boolean, allowThumb: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onMenu: (() -> Unit)?, modifier: Modifier = Modifier,
    location: String? = null, highlighted: Boolean = false,
) {
    val bg by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = androidx.compose.animation.core.tween(450), label = "rowbg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileIcon(e, allowThumbnail = allowThumb)
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (e.isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.isSymlink) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Link, "Symlink", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                }
                if (badge) {
                    Spacer(Modifier.width(6.dp))
                    NomediaBadge()
                }
            }
            Text(
                subtitleFor(e),
                style = if (e.backend == Backend.ROOT) MonoStyle else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (location != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!selecting && onMenu != null) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Rounded.MoreVert, "More options for ${e.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.width(16.dp))
    }
}

@Composable
fun NomediaBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text("nomedia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(ui: BrowserUi, actions: BrowserActions) {
    val drag = local.files.explorer.ui.LocalItemDrag.current
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (ui.clipboard != null) 96.dp else 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.entries, key = { it.path }) { e ->
            val selected = e.path in ui.selection
            Column(
                Modifier
                    .then(drag?.modifier(e, selected) ?: Modifier)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .combinedClickable(onClick = { actions.open(e) }, onLongClick = { actions.longPress(e) })
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    FileIcon(e, size = 72.dp, allowThumbnail = e.path !in ui.noThumbs)
                    if (selected) Icon(
                        Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                    if (e.path in ui.nomediaBadges) Icon(
                        Icons.Rounded.VisibilityOff, ".nomedia", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    e.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (e.isDirectory) e.childCount?.let { Format.items(it) } ?: "Folder" else Format.size(e.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PasteBar(c: ClipboardUi, enabled: Boolean, actions: BrowserActions) {
    Surface(
        Modifier.padding(12.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.cut) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "${Format.items(c.count)} to ${if (c.extract) "extract" else if (c.cut) "move" else "copy"}",
                Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = actions::clearClipboard) { Text("Cancel", color = MaterialTheme.colorScheme.inverseOnSurface) }
            Button(onClick = actions::paste, enabled = enabled) { Text(if (c.extract) "Extract here" else if (c.cut) "Move here" else "Paste here") }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}


@Composable
fun SearchScopeBar(s: SearchUi, query: String, onRecursive: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (s.supportsRecursive) {
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !s.recursive, onClick = { onRecursive(false) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("This folder") }
                SegmentedButton(
                    selected = s.recursive, onClick = { onRecursive(true) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Subfolders") }
            }
        }
        if (query.isNotBlank()) {
            Text(
                searchStatusText(s),
                style = MaterialTheme.typography.labelMedium,
                color = if (s.limitHit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}

fun searchStatusText(s: SearchUi): String = when {
    s.running -> "Searching\u2026 ${s.found} found"
    s.limitHit -> "Showing the first ${s.limit} matches. Type more to narrow it down."
    s.found == 1 -> "1 result"
    else -> "${s.found} results"
}
