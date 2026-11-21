package local.files.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Format
import local.files.explorer.ui.adaptive.LocalAdaptive
import local.files.explorer.ui.adaptive.TopBottomPane
import local.files.explorer.ui.adaptive.TwoPane
import local.files.explorer.ui.browser.BrowserActions
import local.files.explorer.ui.browser.BrowserContent
import local.files.explorer.ui.browser.BrowserUi
import kotlin.math.roundToInt

/** Side rail for wide screens (the open Fold); same tabs as the phone's bottom bar. */
@Composable
fun FileManagerRail(tabs: List<Tab>, current: Tab, onTab: (Tab) -> Unit) {
    NavigationRail(Modifier.testTag("navRail")) {
        Spacer(Modifier.height(8.dp))
        tabs.forEach { t ->
            NavigationRailItem(selected = current == t, onClick = { onTab(t) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
        }
    }
}

// ---- drag between panes ----------------------------------------------------

/** A drag of files from one pane to the other. Positions are in root coordinates. */
class PaneDrag {
    var items by mutableStateOf<List<FileEntry>>(emptyList()); private set
    var fromMain by mutableStateOf(true); private set
    var pos by mutableStateOf(Offset.Zero); private set
    var mainBounds by mutableStateOf(Rect.Zero)
    var otherBounds by mutableStateOf(Rect.Zero)
    val active: Boolean get() = items.isNotEmpty()

    /** Whether the pointer is over the pane the files would land in. */
    val overTarget: Boolean get() = active && (if (fromMain) otherBounds else mainBounds).contains(pos)

    fun start(items: List<FileEntry>, fromMain: Boolean, at: Offset) { this.items = items; this.fromMain = fromMain; pos = at }
    fun moveBy(d: Offset) { pos += d }
    fun cancel() { items = emptyList() }

    /** Ends the drag; returns the files and direction if they were dropped on the other pane. */
    fun end(): Pair<Boolean, List<FileEntry>>? {
        val hit = overTarget
        val r = if (hit) fromMain to items else null
        items = emptyList()
        return r
    }
}

/** Lets a browser row be dragged sideways to the other pane (null = no dual pane, rows behave as before). */
fun interface ItemDrag {
    fun modifier(e: FileEntry, selected: Boolean): Modifier
}

val LocalItemDrag = staticCompositionLocalOf<ItemDrag?> { null }

private fun rowDrag(drag: PaneDrag, fromMain: Boolean, selection: () -> List<FileEntry>, onDrop: (Boolean, List<FileEntry>) -> Unit) =
    ItemDrag { e, selected ->
        Modifier.composed {
        val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
        Modifier
            .onGloballyPositioned { holder[0] = it }
            .pointerInput(e.path, selected) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val items = if (selected) selection().ifEmpty { listOf(e) } else listOf(e)
                        drag.start(items, fromMain, (holder[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero) + o)
                    },
                    onHorizontalDrag = { ch, dx -> ch.consume(); drag.moveBy(Offset(dx, 0f)) },
                    onDragEnd = { drag.end()?.let { (toOther, items) -> onDrop(toOther, items) } },
                    onDragCancel = { drag.cancel() },
                )
            }
        }
    }

// ---- wide browser: folder tree + contents, or two panes ----------------------

class WideBrowserActions(
    val main: BrowserActions,
    val other: BrowserActions,
    val tree: (Crumb) -> Unit,
    val treeOpen: (FileEntry) -> Unit,
    val setDual: (Boolean) -> Unit,
    val otherHere: () -> Unit,
    val transfer: (toOther: Boolean, move: Boolean) -> Unit,
    val drop: (toOther: Boolean, items: List<FileEntry>) -> Unit,
    val mainSelection: () -> List<FileEntry>,
    val otherSelection: () -> List<FileEntry>,
)

/**
 * The browser on a wide screen. Single: a folder tree (the path down to this folder and its subfolders) beside the
 * folder's contents. Dual: two independent folders; select files in either and Copy/Move them across, or drag them
 * sideways onto the other pane. Half-folded, panes split at the hinge (side by side for a vertical fold, top and
 * bottom for tabletop) so nothing sits on the crease.
 */
@Composable
fun WideBrowser(
    main: BrowserUi, other: BrowserUi, dual: Boolean, otherTitle: String, mainTitle: String, actions: WideBrowserActions,
) {
    val tabletop = LocalAdaptive.current.tabletop
    val drag = remember { PaneDrag() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val mainPane: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().onGloballyPositioned { drag.mainBounds = it.boundsInRoot() }.testTag("paneMain")) {
                PaneColumn(
                    header = null,
                    transfer = if (dual && main.selection.isNotEmpty()) TransferUi(main.selection.size, otherTitle, toward(tabletop, true)) else null,
                    onTransfer = { move -> actions.transfer(true, move) },
                    highlight = drag.active && !drag.fromMain && drag.overTarget,
                ) {
                    CompositionLocalProvider(LocalItemDrag provides if (dual) rowDrag(drag, true, actions.mainSelection, actions.drop) else null) {
                        BrowserContent(main, actions.main)
                    }
                }
            }
        }
        val otherPane: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().onGloballyPositioned { drag.otherBounds = it.boundsInRoot() }.testTag("paneOther")) {
                PaneColumn(
                    header = { OtherHeader(otherTitle, actions.otherHere, { actions.setDual(false) }) },
                    transfer = if (other.selection.isNotEmpty()) TransferUi(other.selection.size, mainTitle, toward(tabletop, false)) else null,
                    onTransfer = { move -> actions.transfer(false, move) },
                    highlight = drag.active && drag.fromMain && drag.overTarget,
                ) {
                    CompositionLocalProvider(LocalItemDrag provides rowDrag(drag, false, actions.otherSelection, actions.drop)) {
                        BrowserContent(other, actions.other)
                    }
                }
            }
        }
        val tree: @Composable () -> Unit = { FolderTree(main, actions.tree, actions.treeOpen, onDual = { actions.setDual(true) }) }
        when {
            tabletop -> TopBottomPane(top = mainPane, bottom = if (dual) otherPane else tree)
            dual -> TwoPane(first = mainPane, second = otherPane, firstFraction = 0.5f)
            else -> TwoPane(first = tree, second = mainPane, firstFraction = 0.32f, firstMinWidth = 240.dp)
        }
        if (drag.active) DragGhost(drag, origin)
    }
}

private fun toward(tabletop: Boolean, fromMain: Boolean) = when {
    tabletop -> if (fromMain) "\u2193" else "\u2191"
    else -> if (fromMain) "\u2192" else "\u2190"
}

private data class TransferUi(val count: Int, val destTitle: String, val arrow: String)

@Composable
private fun PaneColumn(
    header: (@Composable () -> Unit)?, transfer: TransferUi?, onTransfer: (Boolean) -> Unit, highlight: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().then(
            if (highlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) else Modifier
        ),
    ) {
        header?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        if (transfer != null) TransferBar(transfer, onTransfer)
    }
}

@Composable
private fun TransferBar(t: TransferUi, onTransfer: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("transferBar")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Format.items(t.count)} selected", style = MaterialTheme.typography.labelLarge)
                Text(if (t.arrow == "\u2192" || t.arrow == "\u2190") "to ${t.destTitle} \u00b7 or drag sideways" else "to ${t.destTitle}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onTransfer(false) }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy ${t.arrow}")
            }
            TextButton(onClick = { onTransfer(true) }) {
                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Move ${t.arrow}")
            }
        }
    }
}

@Composable
private fun OtherHeader(title: String, onHere: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VerticalSplit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onHere) { Icon(Icons.Rounded.SyncAlt, "Open the left folder here") }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close second pane") }
    }
}

@Composable
private fun DragGhost(drag: PaneDrag, origin: Offset) {
    val p = drag.pos - origin
    Surface(
        shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 6.dp,
        color = if (drag.overTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.offset { IntOffset(p.x.roundToInt() - 60, p.y.roundToInt() - 70) }.testTag("dragGhost"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DragIndicator, null, Modifier.size(18.dp),
                tint = if (drag.overTarget) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                if (drag.items.size == 1) drag.items[0].name else Format.items(drag.items.size),
                color = if (drag.overTarget) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The path from the storage root down to this folder, then this folder's subfolders, as an indented tree. */
@Composable
fun FolderTree(ui: BrowserUi, onCrumb: (Crumb) -> Unit, onOpen: (FileEntry) -> Unit, onDual: () -> Unit) {
    val subs = ui.entries.filter { it.isDirectory }
    val depth = ui.crumbs.size
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("folderTree")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onDual, modifier = Modifier.testTag("openDualPane")) {
                Icon(Icons.Rounded.VerticalSplit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dual pane")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(ui.crumbs, key = { _, c -> "c:" + c.path }) { i, c ->
                TreeRow(c.label, i, open = true, current = i == ui.crumbs.lastIndex) { onCrumb(c) }
            }
            items(subs, key = { "s:" + it.path }) { e -> TreeRow(e.name, depth, open = false, current = false) { onOpen(e) } }
            if (subs.isEmpty() && !ui.loading) item {
                Text("No subfolders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = (16 + depth * 14).dp, top = 6.dp))
            }
        }
    }
}

@Composable
private fun TreeRow(label: String, depth: Int, open: Boolean, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(MaterialTheme.shapes.medium)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(start = (8 + depth.coerceAtMost(8) * 14).dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null, Modifier.size(20.dp),
            tint = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
