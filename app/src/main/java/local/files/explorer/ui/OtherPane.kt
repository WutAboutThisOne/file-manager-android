package local.files.explorer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.files.explorer.core.Backend
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.core.SortOrder
import local.files.explorer.core.Sorter
import local.files.explorer.settings.ViewMode
import local.files.explorer.ui.browser.BrowserUi

/**
 * The second folder in dual-pane mode (open Fold). It browses on its own (folder, selection, listing) and is the
 * copy/move target for the main pane's selection, and vice versa. Archives aren't browsed here; they open in the
 * main pane.
 */
class OtherPane(
    private val scope: CoroutineScope,
    private val showHidden: () -> Boolean,
    private val sort: () -> SortOrder,
    private val viewMode: () -> ViewMode,
    private val readOnly: (Location) -> Boolean,
    private val lister: suspend (Location) -> List<FileEntry>,
) {
    var location by mutableStateOf<Location?>(null); private set
    var ui by mutableStateOf(BrowserUi()); private set
    private var raw: List<FileEntry> = emptyList()
    private var selection: Set<String> = emptySet()
    private var job: Job? = null

    fun navigate(loc: Location) {
        location = loc
        selection = emptySet()
        load(loc)
    }

    fun reload() { location?.let { load(it, keepSelection = true) } }

    /** Returns true if it handled the tap (a folder or selection toggle); files are left to the caller. */
    fun open(e: FileEntry): Boolean {
        if (selection.isNotEmpty()) { toggleSelect(e); return true }
        val loc = location ?: return false
        if (!e.isDirectory) return false
        navigate(if (e.backend == Backend.SAF) loc.copy(path = e.path, safCrumbs = loc.safCrumbs + Crumb(e.name, e.path)) else loc.copy(path = e.path))
        return true
    }

    fun crumb(c: Crumb) {
        val loc = location ?: return
        if (loc.backend == Backend.SAF) {
            val i = loc.safCrumbs.indexOfFirst { it.path == c.path }
            if (i >= 0) navigate(loc.copy(path = c.path, safCrumbs = loc.safCrumbs.take(i + 1)))
        } else navigate(loc.copy(path = c.path))
    }

    fun toggleSelect(e: FileEntry) {
        selection = if (e.path in selection) selection - e.path else selection + e.path
        recompute()
    }

    fun clearSelection() { selection = emptySet(); recompute() }
    fun selectedEntries(): List<FileEntry> = raw.filter { it.path in selection }

    private fun load(loc: Location, keepSelection: Boolean = false) {
        job?.cancel()
        ui = ui.copy(loading = true, error = null, crumbs = loc.crumbs, backend = loc.backend)
        job = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { lister(loc) } }
            if (location != loc) return@launch
            r.onSuccess { raw = it; ui = ui.copy(error = null) }
                .onFailure { raw = emptyList(); ui = ui.copy(error = it.message ?: "Can't open this folder") }
            selection = if (keepSelection) selection.filter { p -> raw.any { it.path == p } }.toSet() else emptySet()
            ui = ui.copy(loading = false)
            recompute()
        }
    }

    fun recompute() {
        val loc = location
        val list = Sorter.sort(if (showHidden()) raw else raw.filter { !it.isHidden }, sort())
        ui = ui.copy(
            entries = list, crumbs = loc?.crumbs ?: emptyList(), backend = loc?.backend ?: Backend.LOCAL,
            viewMode = viewMode(), selection = selection, readOnly = loc?.let(readOnly) ?: false,
            canPaste = loc != null && loc.backend != Backend.ARCHIVE,
        )
    }
}
