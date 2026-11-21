package local.files.explorer.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import local.files.explorer.core.Backend
import local.files.explorer.core.Categories
import local.files.explorer.core.Category
import local.files.explorer.core.FolderLabels
import local.files.explorer.core.FolderNode
import local.files.explorer.core.FolderSizes
import local.files.explorer.core.LargeOp
import local.files.explorer.core.LargestFiles
import local.files.explorer.core.RecursiveSearcher
import local.files.explorer.core.SearchHit
import local.files.explorer.core.SearchMatch
import local.files.explorer.core.SearchProgress
import local.files.explorer.core.SizeFilter
import local.files.explorer.core.SortField
import local.files.explorer.storage.StorageIndex
import local.files.explorer.ui.browser.SearchUi
import local.files.explorer.ui.common.PermissionKind
import local.files.explorer.ui.home.FileListUi
import local.files.explorer.ui.home.FolderRowUi
import local.files.explorer.ui.home.FolderSizesUi
import local.files.explorer.ui.home.DuplicatesUi
import local.files.explorer.ui.home.DuplicateGroupUi
import local.files.explorer.core.DuplicateFinder
import local.files.explorer.core.NOMEDIA
import local.files.explorer.core.listingHasNomedia
import local.files.explorer.ui.home.HomeUi
import local.files.explorer.ui.home.ListKind
import local.files.explorer.ui.home.ScanUi
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.ConflictResolver
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.core.FileType
import local.files.explorer.core.Format
import local.files.explorer.core.Names
import local.files.explorer.core.NomediaPolicy
import local.files.explorer.core.PathRoot
import local.files.explorer.core.Paths
import local.files.explorer.core.RootFindParser
import local.files.explorer.core.SortOrder
import local.files.explorer.core.Sorter
import local.files.explorer.core.VolumeInfo
import local.files.explorer.fs.Backends
import local.files.explorer.fs.ArchiveBackend
import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.archive.CompressSpec
import local.files.explorer.archive.PasswordRequiredException
import local.files.explorer.archive.WrongPasswordException
import local.files.explorer.ui.archive.ArchiveUi
import local.files.explorer.ui.archive.ExtractTarget
import local.files.explorer.install.ApkInfo
import local.files.explorer.install.ApkInstaller
import local.files.explorer.ops.OpKind
import local.files.explorer.ops.OpRequest
import local.files.explorer.ops.OperationManager
import local.files.explorer.root.RootShell
import local.files.explorer.root.RootState
import local.files.explorer.settings.AppSettings
import local.files.explorer.settings.SettingsRepository
import local.files.explorer.settings.ThemeMode
import local.files.explorer.settings.ViewMode
import local.files.explorer.storage.LocalNomediaProbe
import local.files.explorer.storage.MediaScan
import local.files.explorer.storage.Mime
import local.files.explorer.storage.RecentRepository
import local.files.explorer.storage.StorageAccess
import local.files.explorer.storage.VolumeRepository
import local.files.explorer.ui.browser.BrowserUi
import local.files.explorer.ui.browser.ClipboardUi
import local.files.explorer.ui.browser.PropertiesUi
import local.files.explorer.ui.browser.StorageSummary
import local.files.explorer.ui.volumes.SafFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    INTERNAL("Internal", Icons.Rounded.PhoneAndroid),
    STORAGE("Storage", Icons.Rounded.SdStorage),
    ROOT("Root", Icons.Rounded.AdminPanelSettings),
    RECENT("Recent", Icons.Rounded.History),
}

sealed interface HomePage {
    data object Overview : HomePage
    data class CategoryList(val category: Category) : HomePage
    data object Largest : HomePage
    /** null = list of storage roots (or the only root). */
    data class Folders(val path: String?) : HomePage
    data object Duplicates : HomePage
    data object Search : HomePage
}

enum class Screen { MAIN, SETTINGS, ABOUT, LICENCES, LICENCE_DETAIL, APK_INFO }

data class Location(
    val backend: Backend,
    val path: String,
    val rootPath: String,
    val rootLabel: String,
    val safCrumbs: List<Crumb> = emptyList(),
    /** For archives: the folder to return to when going up from the archive's root. */
    val parent: Location? = null,
) {
    val crumbs: List<Crumb>
        get() = when (backend) {
            Backend.SAF -> safCrumbs
            Backend.ARCHIVE -> (parent?.crumbs ?: emptyList()) + Paths.breadcrumbs(path, listOf(PathRoot(rootPath, rootLabel)))
            else -> Paths.breadcrumbs(path, listOf(PathRoot(rootPath, rootLabel)))
        }
    val title: String get() = crumbs.lastOrNull()?.label ?: rootLabel
    val atRoot: Boolean get() = if (backend == Backend.SAF) safCrumbs.size <= 1 else Paths.normalize(path) == Paths.normalize(rootPath)
}

sealed interface Dialog {
    data class NewFolder(val loc: Location) : Dialog
    data class Rename(val entry: FileEntry) : Dialog
    data class Delete(val entries: List<FileEntry>) : Dialog
    data class Conflict(val names: List<String>, val request: OpRequest) : Dialog
    data class Properties(val ui: PropertiesUi) : Dialog
    /** [context] = shown outside the file's folder (Home lists, search results): adds Go to file location. */
    data class ItemMenu(val entry: FileEntry, val context: Boolean = false) : Dialog
    data class Explainer(val kind: PermissionKind, val settingsRoute: Boolean = false) : Dialog
    data object RootWritesWarning : Dialog
    data class RootMount(val volume: VolumeInfo, val mount: Boolean) : Dialog
    data object RemountSystem : Dialog
    data object Sort : Dialog
    data class Password(val root: String, val name: String, val wrong: Boolean = false, val busy: Boolean = false, val then: (() -> Unit)? = null) : Dialog
    /** [entries] null = the whole archive. */
    data class Extract(val root: String, val entries: List<FileEntry>?, val hereLabel: String?, val folderName: String, val decodeXml: Boolean? = null) : Dialog
    data class Compress(val entries: List<FileEntry>, val dest: Location, val defaultName: String) : Dialog
    /** Dual pane: files were dragged onto the other pane; ask copy or move. */
    data class Transfer(val toOther: Boolean, val items: List<FileEntry>, val destLabel: String) : Dialog
}

data class ApkSheet(val file: File, val info: ApkInfo? = null, val loading: Boolean = true, val error: String? = null)

sealed interface UiEvent {
    data class Launch(val intent: Intent, val fallback: Intent? = null) : UiEvent
    data class Message(val text: String) : UiEvent
    data class PickTree(val uuid: String?, val intent: Intent) : UiEvent
    data object RequestLegacyStorage : UiEvent
    data object RequestNotifications : UiEvent
}

class MainViewModel(app: Application, initial: AppSettings) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val repo = SettingsRepository(app)
    private val volumesRepo = VolumeRepository(app)
    private val recentRepo = RecentRepository(app)

    var settings by mutableStateOf(initial); private set
    var tab by mutableStateOf(Tab.INTERNAL); private set
    var screen by mutableStateOf(Screen.MAIN); private set
    var rootState by mutableStateOf(RootState.CHECKING); private set
    var hasAllFiles by mutableStateOf(StorageAccess.hasAllFilesAccess(app)); private set
    var askedForAccess by mutableStateOf(false); private set
    var canInstallUnknown by mutableStateOf(ApkInstaller.canInstall(app)); private set

    private val locations = mutableMapOf<Tab, Location?>()
    var location by mutableStateOf<Location?>(null); private set
    var browser by mutableStateOf(BrowserUi()); private set
    var viewMode by mutableStateOf(initial.viewMode); private set
    var sort by mutableStateOf(initial.sort); private set
    var searchOpen by mutableStateOf(false); private set

    var volumes by mutableStateOf<List<VolumeInfo>>(emptyList()); private set
    var safFolders by mutableStateOf<List<SafFolder>>(emptyList()); private set
    var recent by mutableStateOf<List<FileEntry>>(emptyList()); private set

    var dialog by mutableStateOf<Dialog?>(null)
    var apkSheet by mutableStateOf<ApkSheet?>(null); private set

    /** Two folders side by side on a wide screen. Kept while folded so unfolding brings both panes back. */
    var dualPane by mutableStateOf(false); private set
    val other = OtherPane(
        viewModelScope, showHidden = { settings.showHidden }, sort = { sort }, viewMode = { viewMode },
        readOnly = { (it.backend == Backend.ROOT && !settings.rootWrites) || it.backend == Backend.ARCHIVE },
        lister = { Backends.get(it.backend).list(it.path) },
    )

    /** The APK info screen (Screen.APK_INFO). */
    var apkInfo by mutableStateOf<local.files.explorer.ui.apk.ApkInfoUi?>(null); private set
    /** What the APK info screen's buttons act on: an entry, or the archive session being browsed. */
    private var apkInfoEntry: FileEntry? = null
    private var apkInfoRoot: String? = null

    private var raw: List<FileEntry> = emptyList()
    private var searchResults: List<FileEntry>? = null
    private var searchHits: Map<String, SearchHit> = emptyMap()
    private var searchProgress = SearchProgress(done = true)
    private var searchLimit = RecursiveSearcher.DEFAULT_LIMIT
    private var highlight: String? = null
    private var highlightJob: Job? = null
    private var pendingApk: FileEntry? = null
    private var pendingOp: OpRequest? = null
    private var notificationsAskedThisSession = false

    // ---- Home state ------------------------------------------------------------
    val index = StorageIndex(viewModelScope)
    var homeStack by mutableStateOf<List<HomePage>>(listOf(HomePage.Overview)); private set
    val homePage: HomePage get() = homeStack.last()
    var home by mutableStateOf(HomeUi()); private set
    var fileList by mutableStateOf(FileListUi(ListKind.CATEGORY)); private set
    var folderSizes by mutableStateOf(FolderSizesUi()); private set
    var duplicates by mutableStateOf(DuplicatesUi()); private set
    var homeQuery by mutableStateOf(""); private set
    private var listQuery = ""
    private var listSort = SortOrder(SortField.DATE, ascending = false, foldersFirst = false)
    private var sizeFilter = SizeFilter.OVER_10MB
    private var folderTrees: List<FolderNode> = emptyList()
    private var treesVersion = -1
    private var homeJob: Job? = null
    private var duplicateJob: Job? = null
    private var globalSearchJob: Job? = null
    private var globalHits: List<FileEntry> = emptyList()
    private var globalProgress = SearchProgress(done = true)
    private var selection: Set<String> = emptySet()
    private var clipboard: Pair<List<FileEntry>, Boolean>? = null
    private var archiveInfo: ArchiveUi? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private fun emit(e: UiEvent) { _events.trySend(e) }
    private fun toast(s: String) = emit(UiEvent.Message(s))

    init {
        Backends.init(app)
        Backends.rootWrites = initial.rootWrites
        viewModelScope.launch {
            repo.settings.collect { s ->
                val old = settings
                settings = s
                Backends.rootWrites = s.rootWrites
                safFolders = s.safTrees.map { it.substringAfter('|') }.distinct().map { SafFolder(treeLabel(it), it) }
                if (old.showHidden != s.showHidden || old.respectNomedia != s.respectNomedia) { recompute(); refreshHome() }
                if (old.recursiveSearch != s.recursiveSearch && searchOpen) search(browser.searchQuery)
                if (old.safTrees != s.safTrees) refreshVolumes()
            }
        }
        viewModelScope.launch { rootState = RootShell.detect() }
        viewModelScope.launch {
            var last = OperationManager.finished.value
            OperationManager.finished.collect {
                if (it != last) {
                    last = it; reload(); other.reload(); if (tab == Tab.STORAGE) refreshVolumes(); rescan()
                    if (tab == Tab.HOME && homePage == HomePage.Duplicates) startDuplicateScan()
                }
            }
        }
        viewModelScope.launch {
            var lastVersion = -1
            var lastScanning = false
            index.state.collect { st ->
                if (st.version != lastVersion || st.scanning != lastScanning) {
                    lastVersion = st.version; lastScanning = st.scanning
                    refreshHome()
                } else updateScanUi()
            }
        }
        if (Backends.archive.isEmpty()) viewModelScope.launch(Dispatchers.IO) { File(ctx.cacheDir, "archives").deleteRecursively() }
        switchTab(Tab.HOME)
        if (hasAllFiles) rescan()
        else if (!initial.allFilesExplained) dialog = Dialog.Explainer(PermissionKind.ALL_FILES)
    }

    // ---- tabs & navigation ------------------------------------------------

    val tabs: List<Tab> get() = Tab.entries.filter { it != Tab.ROOT || rootState.isGranted }

    fun switchTab(t: Tab) {
        if (t == Tab.HOME) {
            if (tab == Tab.HOME && screen == Screen.MAIN) homeStack = listOf(HomePage.Overview)
            tab = Tab.HOME; screen = Screen.MAIN
            selection = emptySet(); searchOpen = false; searchResults = null; searchJob?.cancel()
            browser = browser.copy(searchQuery = "", search = null)
            location = null
            refreshHome()
            return
        }
        if (tab == t && location != null && t != Tab.RECENT) {
            // re-tapping a tab jumps to its root
            location?.let { if (!it.atRoot) navigate(rootLocationFor(t) ?: it) }
            return
        }
        tab = t
        screen = Screen.MAIN
        selection = emptySet(); searchOpen = false; searchResults = null
        browser = browser.copy(searchQuery = "", search = null)
        val loc = locations[t] ?: rootLocationFor(t)
        location = loc
        when (t) {
            Tab.RECENT -> loadRecent()
            Tab.STORAGE -> { refreshVolumes(); if (loc != null) load(loc) }
            else -> loc?.let(::load)
        }
    }

    private fun rootLocationFor(t: Tab): Location? = when (t) {
        Tab.INTERNAL -> StorageAccess.internalRoot().let { Location(Backend.LOCAL, it, it, "Internal storage") }
        Tab.ROOT -> Location(Backend.ROOT, "/", "/", "/")
        else -> null
    }

    fun navigate(loc: Location?) {
        locations[tab] = loc
        location = loc
        selection = emptySet(); searchOpen = false; searchResults = null; searchJob?.cancel()
        browser = browser.copy(searchQuery = "", selection = emptySet(), search = null, searching = false, locations = emptyMap())
        if (loc != null) load(loc) else refreshVolumes()
    }

    fun openScreen(s: Screen) { screen = s }

    /** Name of the component shown on the licence detail page (Screen.LICENCE_DETAIL). */
    var openLicenceName by mutableStateOf<String?>(null); private set

    /** Opens one component's licence on its own page. */
    fun openLicence(name: String) { openLicenceName = name; screen = Screen.LICENCE_DETAIL }

    /** Returns true if back was handled inside the app. */
    fun back(): Boolean {
        when {
            apkSheet != null -> closeApk()
            selection.isNotEmpty() -> clearSelection()
            dualPane && other.ui.selection.isNotEmpty() -> other.clearSelection()
            searchOpen -> closeSearch()
            screen == Screen.APK_INFO -> closeApkInfo()
            screen == Screen.LICENCE_DETAIL -> { openLicenceName = null; screen = Screen.LICENCES }
            screen == Screen.LICENCES -> screen = Screen.ABOUT
            screen == Screen.ABOUT -> screen = Screen.SETTINGS
            screen == Screen.SETTINGS -> screen = Screen.MAIN
            tab == Tab.HOME && homeStack.size > 1 -> homeBack()
            tab == Tab.HOME -> return false
            location?.let { it.atRoot && it.parent != null } == true -> leaveArchive()
            location != null && !location!!.atRoot -> up()
            tab == Tab.STORAGE && location != null -> navigate(null)
            else -> switchTab(Tab.HOME)
        }
        return true
    }

    private fun up() {
        val loc = location ?: return
        if (loc.backend == Backend.SAF) {
            val c = loc.safCrumbs.dropLast(1)
            navigate(loc.copy(path = c.last().path, safCrumbs = c))
        } else Paths.parent(loc.path)?.let { navigate(loc.copy(path = it)) }
    }

    private fun leaveArchive() {
        val loc = location ?: return
        val parent = loc.parent ?: return
        val name = Backends.archive.session(loc.path)?.displayName
        val hl = if (name != null && parent.backend != Backend.SAF && parent.backend != Backend.ARCHIVE) Paths.child(parent.path, name) else null
        jumpTo(tab, parent, hl)
    }

    fun crumb(c: Crumb) {
        val loc = location ?: return
        if (loc.backend == Backend.ARCHIVE && !(c.path == loc.rootPath || Paths.isAncestorOrSelf(loc.rootPath, c.path) && c.path.startsWith(loc.rootPath))) {
            // A crumb above the archive: hand it to the folder that holds the archive.
            val p = loc.parent ?: return
            location = p
            if (p.crumbs.lastOrNull()?.path == c.path) navigate(p) else crumb(c)
            return
        }
        if (loc.backend == Backend.SAF) {
            val i = loc.safCrumbs.indexOfFirst { it.path == c.path }
            if (i >= 0) navigate(loc.copy(path = c.path, safCrumbs = loc.safCrumbs.take(i + 1)))
        } else navigate(loc.copy(path = c.path))
    }

    // ---- loading ----------------------------------------------------------

    fun reload() {
        if (dualPane) other.reload()
        when {
            tab == Tab.HOME -> refreshHome()
            tab == Tab.RECENT -> loadRecent()
            location != null -> load(location!!, keepSelection = true)
            tab == Tab.STORAGE -> refreshVolumes()
        }
    }

    private fun load(loc: Location, keepSelection: Boolean = false) {
        loadJob?.cancel()
        if (!keepSelection) selection = emptySet()
        browser = browser.copy(loading = true, error = null, crumbs = loc.crumbs, backend = loc.backend)
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (loc.backend == Backend.LOCAL && !hasAllFiles && !File(loc.path).canRead()) {
                        throw SecurityException("File Manager needs All files access to read this folder.")
                    }
                    Backends.get(loc.backend).list(loc.path)
                }
            }
            if (location != loc) return@launch
            archiveInfo = if (loc.backend == Backend.ARCHIVE) withContext(Dispatchers.IO) { archiveUiFor(loc.rootPath) } else null
            result.onSuccess { raw = it; browser = browser.copy(error = null) }
                .onFailure {
                    raw = emptyList()
                    browser = browser.copy(error = it.message ?: "Unknown error")
                    if (it is PasswordRequiredException) askPassword(loc.rootPath, then = null)
                }
            browser = browser.copy(loading = false)
            if (keepSelection) selection = selection.filter { p -> raw.any { it.path == p } }.toSet()
            recompute()
        }
    }

    private fun policy() = NomediaPolicy(
        LocalNomediaProbe, settings.respectNomedia,
        stopAt = volumes.mapNotNull { it.path } + StorageAccess.internalRoot(),
    )

    private fun recompute() {
        if (tab == Tab.RECENT) { recomputeRecent(); return }
        val loc = location
        val q = browser.searchQuery.trim()
        val base = searchResults ?: raw
        var list = if (settings.showHidden) base else base.filter { !it.isHidden || it.path == highlight }
        if (q.isNotEmpty() && searchResults == null) list = list.filter { SearchMatch.matches(it.name, q) }
        list = if (searchResults != null) list else Sorter.sort(list, sort)
        val pol = policy()
        val local = loc?.backend == Backend.LOCAL
        val badges = if (local && pol.respect) list.filter { pol.showBadge(it) }.map { it.path }.toSet() else emptySet()
        val noThumbs = if (local && pol.respect) list.filter { !it.isDirectory && !pol.allowThumbnail(it) }.map { it.path }.toSet() else emptySet()
        val storage = loc?.takeIf { it.backend == Backend.LOCAL && it.atRoot }?.let {
            runCatching { StatFs(it.rootPath) }.getOrNull()?.takeIf { s -> s.totalBytes > 0 }?.let { s -> StorageSummary(it.rootLabel, s.totalBytes - s.availableBytes, s.totalBytes) }
        }
        browser = browser.copy(
            entries = list,
            crumbs = loc?.crumbs ?: emptyList(),
            backend = loc?.backend ?: Backend.LOCAL,
            viewMode = viewMode,
            selection = selection,
            nomediaBadges = badges,
            hasNomedia = loc?.backend == Backend.LOCAL && listingHasNomedia(raw),
            noThumbs = noThumbs,
            readOnly = (loc?.backend == Backend.ROOT && !settings.rootWrites) || loc?.backend == Backend.ARCHIVE,
            archive = if (loc?.backend == Backend.ARCHIVE) archiveInfo else null,
            storage = storage,
            clipboard = clipboard?.let { ClipboardUi(it.first.size, it.second, extract = it.first.all { e -> e.backend == Backend.ARCHIVE }) },
            canPaste = loc != null && loc.backend != Backend.ARCHIVE,
            locations = if (searchResults != null) searchHits.mapValues { it.value.folderLabel } else emptyMap(),
            highlight = highlight,
            search = if (searchOpen) searchUi(list.size) else null,
        )
        if (dualPane) other.recompute()
    }

    private fun searchUi(visible: Int): SearchUi {
        val recursiveCapable = tab != Tab.RECENT && location != null
        val streaming = searchResults != null
        return SearchUi(
            recursive = settings.recursiveSearch && recursiveCapable,
            running = streaming && !searchProgress.done,
            found = visible,
            limitHit = streaming && searchProgress.limitHit,
            limit = searchLimit,
            supportsRecursive = recursiveCapable,
        )
    }

    private fun loadRecent() {
        browser = browser.copy(loading = true, crumbs = emptyList(), error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { recentRepo.recent(policy(), settings.showHidden) }
            }
            recent = r.getOrDefault(emptyList())
            browser = browser.copy(loading = false, error = r.exceptionOrNull()?.message)
            recomputeRecent()
        }
    }

    private fun recomputeRecent() {
        val q = browser.searchQuery.trim()
        val list = recent.filter { q.isEmpty() || SearchMatch.matches(it.name, q) }
        browser = browser.copy(
            search = if (searchOpen) searchUi(list.size) else null, locations = emptyMap(), highlight = null,
            entries = list, crumbs = emptyList(), backend = Backend.LOCAL, viewMode = viewMode, selection = selection,
            nomediaBadges = emptySet(), hasNomedia = false, noThumbs = emptySet(), readOnly = false, storage = null, archive = null,
            clipboard = clipboard?.let { ClipboardUi(it.first.size, it.second) }, canPaste = false,
        )
    }

    fun onResume() {
        val had = hasAllFiles
        hasAllFiles = StorageAccess.hasAllFilesAccess(ctx)
        if (hasAllFiles && !had) rescan()
        val couldInstall = canInstallUnknown
        canInstallUnknown = ApkInstaller.canInstall(ctx)
        pendingApk?.let { apk ->
            pendingApk = null
            if (canInstallUnknown && !couldInstall) showApkSheet(apk)
            else if (!canInstallUnknown) { toast("Installing from File Manager is still off. You can allow it from the install sheet."); showApkSheet(apk) }
        }
        pendingOp?.let { pendingOp = null; OperationManager.enqueue(ctx, it) }
        reload()
        if (tab == Tab.STORAGE) refreshVolumes()
    }

    // ---- view options -----------------------------------------------------

    fun toggleView() {
        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        recompute()
    }

    fun setSortOrder(o: SortOrder) { sort = o; recompute() }

    fun openSearch() {
        searchOpen = true
        if (tab == Tab.HOME) { pushHome(HomePage.Search); searchOpen = false; return }
        recompute()
    }

    fun closeSearch() {
        searchOpen = false; searchResults = null; searchHits = emptyMap(); searchJob?.cancel()
        browser = browser.copy(searchQuery = "", searching = false, search = null)
        recompute()
    }

    fun setSearchRecursive(v: Boolean) {
        if (v == settings.recursiveSearch) return
        settings = settings.copy(recursiveSearch = v)
        viewModelScope.launch { repo.setRecursiveSearch(v) }
        search(browser.searchQuery)
    }

    fun search(q: String) {
        browser = browser.copy(searchQuery = q)
        searchJob?.cancel()
        val loc = location
        val needle = q.trim()
        if (!settings.recursiveSearch || needle.isEmpty() || loc == null || tab == Tab.RECENT) {
            searchResults = null; searchHits = emptyMap(); searchProgress = SearchProgress(done = true)
            browser = browser.copy(searching = false)
            recompute(); return
        }
        searchResults = emptyList(); searchHits = emptyMap()
        searchProgress = SearchProgress(done = false)
        recompute()
        searchJob = viewModelScope.launch {
            delay(200)
            val updates = Channel<Pair<List<SearchHit>, SearchProgress>>(Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                try {
                    if (loc.backend == Backend.ROOT) {
                        searchLimit = RootFindParser.DEFAULT_LIMIT
                        val found = RootFindParser.parse(RootShell.run(RootFindParser.script(loc.path, needle)).out)
                            .filter { SearchMatch.matches(it.name, needle) && (settings.showHidden || !it.isHidden) }
                        val roots = listOf(PathRoot(loc.rootPath, loc.rootLabel))
                        val hits = found.map { SearchHit(it, FolderLabels.parentLabel(it.path, roots)) }
                        updates.send(hits to SearchProgress(hits.size, 0, done = true, limitHit = found.size >= RootFindParser.DEFAULT_LIMIT))
                    } else {
                        searchLimit = RecursiveSearcher.DEFAULT_LIMIT
                        val be = Backends.get(loc.backend)
                        val lister: (FileEntry) -> List<FileEntry> =
                            if (loc.backend == Backend.LOCAL) ::fastList else { d -> be.list(d.path) }
                        val root = FileEntry(loc.title, loc.path, loc.backend, true)
                        RecursiveSearcher(lister).search(
                            root, loc.title, needle, recursive = true, showHidden = settings.showHidden,
                            rootCrumbs = loc.crumbs,
                            isCancelled = { !isActive },
                        ) { hits, progress ->
                            val full = if (loc.backend == Backend.LOCAL) hits.map { h -> h.copy(entry = be.stat(h.entry.path) ?: h.entry) } else hits
                            updates.trySend(full to progress)
                        }
                    }
                } finally { updates.close() }
            }
            for ((hits, progress) in updates) {
                searchHits = searchHits + hits.associateBy { it.entry.path }
                searchResults = (searchResults ?: emptyList()) + hits.map { it.entry }
                searchProgress = progress
                recompute()
            }
            worker.join()
            if (!searchProgress.done) searchProgress = searchProgress.copy(done = true)
            recompute()
        }
    }

    /** Cheap listing for walking (no child counts); hits are re-stat'ed for display. */
    private fun fastList(dir: FileEntry): List<FileEntry> =
        File(dir.path).listFiles()?.map { f ->
            val link = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
            FileEntry(f.name, f.path, Backend.LOCAL, f.isDirectory, size = if (f.isDirectory) 0 else f.length(), lastModified = f.lastModified(), isSymlink = link)
        } ?: emptyList()

    // ---- go to file location -------------------------------------------------

    /** Opens the folder holding [e] in the right tab and briefly highlights the file. */
    fun goToLocation(e: FileEntry) {
        val hit = searchHits[e.path]
        val target: Pair<Tab, Location> = when (e.backend) {
            Backend.SAF -> {
                val crumbs = hit?.parentCrumbs ?: location?.safCrumbs ?: run { toast("Can't find the folder for ${e.name}"); return }
                Tab.STORAGE to Location(Backend.SAF, crumbs.last().path, crumbs.first().path, crumbs.first().label, crumbs)
            }
            Backend.ROOT -> Tab.ROOT to Location(Backend.ROOT, Paths.parent(e.path) ?: "/", "/", "/")
            Backend.LOCAL -> localLocationFor(Paths.parent(e.path) ?: e.path)
            Backend.ARCHIVE -> {
                val loc = location?.takeIf { it.backend == Backend.ARCHIVE } ?: run { toast("Can't find the folder for ${e.name}"); return }
                tab to loc.copy(path = Paths.parent(e.path) ?: loc.rootPath)
            }
        }
        jumpTo(target.first, target.second, highlightPath = e.path)
    }

    /** Opens a folder found outside the browser (Home lists, Folder sizes). */
    fun openFolderInBrowser(path: String) {
        val (t, loc) = localLocationFor(path)
        jumpTo(t, loc, highlightPath = null)
    }

    /** Home storage card → matching browser tab (Internal storage → Internal). */
    fun openHomeStorage(s: local.files.explorer.ui.browser.StorageSummary) {
        if (s.label == "Internal storage") {
            switchTab(Tab.INTERNAL)
            return
        }
        val vol = volumes.firstOrNull { it.label == s.label && it.path != null }
        if (vol != null) {
            switchTab(Tab.STORAGE)
            openVolume(vol)
            return
        }
        // Fallback: try to match a scanned root by label.
        indexRoots().firstOrNull { it.label == s.label }?.let { openFolderInBrowser(it.path) }
            ?: toast("Can't open ${s.label}")
    }

    private fun localLocationFor(folder: String): Pair<Tab, Location> {
        val internal = StorageAccess.internalRoot()
        if (Paths.isAncestorOrSelf(internal, folder)) return Tab.INTERNAL to Location(Backend.LOCAL, folder, internal, "Internal storage")
        volumes.firstOrNull { v -> v.path != null && Paths.isAncestorOrSelf(v.path, folder) }?.let { v ->
            return Tab.STORAGE to Location(Backend.LOCAL, folder, v.path!!, v.label)
        }
        return if (rootState.isGranted) Tab.ROOT to Location(Backend.ROOT, folder, "/", "/")
        else Tab.STORAGE to Location(Backend.LOCAL, folder, folder, Paths.name(folder))
    }

    private fun jumpTo(t: Tab, loc: Location, highlightPath: String?) {
        val target = if (t == Tab.ROOT && !rootState.isGranted) Tab.INTERNAL else t
        tab = target; screen = Screen.MAIN
        if (target == Tab.STORAGE) refreshVolumes()
        highlightJob?.cancel()
        highlight = highlightPath
        navigate(loc)
        if (highlightPath != null) highlightJob = viewModelScope.launch {
            delay(2600)
            highlight = null
            recompute()
        }
    }

    // ---- selection --------------------------------------------------------

    private fun onHomeList(): Boolean = tab == Tab.HOME && when (homePage) {
        is HomePage.CategoryList, HomePage.Largest, HomePage.Search, is HomePage.Folders, HomePage.Duplicates -> true
        else -> false
    }

    private fun currentEntries(): List<FileEntry> = when {
        tab == Tab.HOME && (homePage is HomePage.CategoryList || homePage == HomePage.Largest || homePage == HomePage.Search) -> fileList.entries
        tab == Tab.HOME && homePage is HomePage.Folders -> folderSizes.folders.map { it.toEntry() } + folderSizes.files
        tab == Tab.HOME && homePage == HomePage.Duplicates -> duplicates.groups.flatMap { it.files }
        else -> browser.entries
    }

    private fun publishSelection() {
        browser = browser.copy(selection = selection)
        if (tab == Tab.HOME) {
            fileList = fileList.copy(selection = selection)
            folderSizes = folderSizes.copy(selection = selection)
            duplicates = duplicates.copy(selection = selection)
        }
    }

    fun toggleSelect(e: FileEntry) {
        selection = if (e.path in selection) selection - e.path else selection + e.path
        publishSelection()
    }

    fun selectAll() { selection = currentEntries().map { it.path }.toSet(); publishSelection() }
    fun clearSelection() { selection = emptySet(); publishSelection() }
    fun selectedEntries(): List<FileEntry> {
        val byPath = currentEntries().associateBy { it.path }
        // Keep order of selection where possible; fall back to path lookup across lists.
        return selection.mapNotNull { byPath[it] }
    }

    // ---- opening ----------------------------------------------------------

    fun open(e: FileEntry) {
        if (selection.isNotEmpty()) { toggleSelect(e); return }
        val loc = location
        if (e.isDirectory) {
            when {
                loc == null -> Unit
                e.backend == Backend.SAF -> navigate(loc.copy(path = e.path, safCrumbs = loc.safCrumbs + Crumb(e.name, e.path)))
                else -> navigate(loc.copy(path = e.path))
            }
            return
        }
        if (e.backend == Backend.ARCHIVE) {
            whenUnlocked(e) {
                when {
                    ArchiveFormat.isArchiveName(e.name) -> openArchive(e, loc) // archive inside an archive
                    e.type == FileType.APK || e.type == FileType.APK_BUNDLE -> openApk(e)
                    else -> openFile(e, chooser = false)
                }
            }
            return
        }
        if (ArchiveFormat.isArchiveName(e.name)) { openArchive(e, loc); return }
        if (e.type == FileType.APK || e.type == FileType.APK_BUNDLE) { openApk(e); return }
        openFile(e, chooser = false)
    }

    /** "Open" from a Home list or context menu: folders open in the browser, files in their app. */
    fun openEntry(e: FileEntry) {
        when {
            e.isDirectory && e.backend == Backend.LOCAL -> openFolderInBrowser(e.path)
            e.isDirectory -> goToLocation(e)
            ArchiveFormat.isArchiveName(e.name) -> openArchive(e, null)
            e.type == FileType.APK || e.type == FileType.APK_BUNDLE -> openApk(e)
            else -> openFile(e, chooser = false)
        }
    }

    /** Move from a list outside the browser: put it on the clipboard and open the browser to pick a destination. */
    fun moveFromList(e: FileEntry) {
        clipboard = listOf(e) to true
        if (tab == Tab.HOME) {
            val (t, loc) = when (e.backend) {
                Backend.LOCAL -> localLocationFor(Paths.parent(e.path) ?: e.path)
                else -> Tab.INTERNAL to (rootLocationFor(Tab.INTERNAL)!!)
            }
            jumpTo(t, loc, null)
        } else recompute()
        toast("Open the destination folder and tap Move here.")
    }

    private suspend fun shareableUri(e: FileEntry): Uri = when (e.backend) {
        Backend.SAF -> Uri.parse(e.path)
        Backend.LOCAL -> FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(e.path))
        Backend.ROOT, Backend.ARCHIVE -> FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", copyToCache(e))
    }

    private suspend fun copyToCache(e: FileEntry): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "view").apply { mkdirs() }
        // An APK's resources.arsc opens as a readable listing of its resources.
        if (e.backend == Backend.ARCHIVE) Backends.archive.resourcesDump(e.path)?.let { text ->
            return@withContext File(dir, e.name + ".txt").also { it.writeText(text) }
        }
        val out = File(dir, e.name)
        Backends.get(e.backend).openInput(e.path).use { i -> out.outputStream().use { i.copyTo(it) } }
        out
    }

    fun openFile(e: FileEntry, chooser: Boolean) {
        if (e.backend == Backend.ARCHIVE) whenUnlocked(e) { doOpenFile(e, chooser) } else doOpenFile(e, chooser)
    }

    private fun doOpenFile(e: FileEntry, chooser: Boolean) {
        viewModelScope.launch {
            runCatching {
                val uri = shareableUri(e)
                val dumped = e.backend == Backend.ARCHIVE && e.name == "resources.arsc" && uri.lastPathSegment?.endsWith(".txt") == true
                val mime = if (dumped) "text/plain" else e.mimeType ?: Mime.of(e.name)
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val generic = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                emit(UiEvent.Launch(if (chooser) Intent.createChooser(view, "Open with") else view, Intent.createChooser(generic, "Open with")))
            }.onFailure { toast("Can't open ${e.name}: ${it.message}") }
        }
    }

    fun share(entries: List<FileEntry>) {
        val inArchive = entries.firstOrNull { it.backend == Backend.ARCHIVE }
        if (inArchive != null) whenUnlocked(inArchive) { doShare(entries) } else doShare(entries)
    }

    private fun doShare(entries: List<FileEntry>) {
        viewModelScope.launch {
            runCatching {
                val files = entries.filter { !it.isDirectory }
                if (files.isEmpty()) { toast("Folders can't be shared directly"); return@launch }
                val uris = files.map { shareableUri(it) }
                val mimes = files.map { it.mimeType ?: Mime.of(it.name) }.distinct()
                val type = mimes.singleOrNull()?.takeIf { it != "*/*" } ?: "*/*"
                val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                send.type = type
                send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                emit(UiEvent.Launch(Intent.createChooser(send, "Share ${Format.items(uris.size)}")))
            }.onFailure { toast("Can't share: ${it.message}") }
        }
    }

    // ---- clipboard & operations --------------------------------------------

    fun copy(entries: List<FileEntry>, cut: Boolean) {
        if (entries.isEmpty()) return
        clipboard = entries to cut
        clearSelection()
        recompute()
        toast("${Format.items(entries.size)} ready to ${if (cut) "move" else "copy"}. Open a folder and tap ${if (cut) "Move here" else "Paste here"}.")
    }

    fun clearClipboard() { clipboard = null; recompute() }

    fun paste() {
        val (items, cut) = clipboard ?: return
        val loc = location ?: return
        transferTo(items, cut, loc, clearClipboard = true)
    }

    /** Copies or moves [items] into [loc] (conflict dialog when names clash). Returns false if refused. */
    private fun transferTo(items: List<FileEntry>, cut: Boolean, loc: Location, clearClipboard: Boolean = false): Boolean {
        if (loc.backend == Backend.ROOT && !settings.rootWrites) { toast("Root locations are read-only. Turn on root writes in Settings."); return false }
        if (loc.backend != Backend.SAF) {
            ConflictResolver.validatePaste(items.filter { it.backend == loc.backend }.map { it.path }, loc.path) { p -> items.first { it.path == p }.isDirectory }
                ?.let { toast(it); return false }
        }
        if (loc.backend == Backend.ARCHIVE) { toast("Archives are read-only. Open a normal folder to paste or extract."); return false }
        val req = OpRequest(
            kind = if (items.all { it.backend == Backend.ARCHIVE }) OpKind.EXTRACT else if (cut) OpKind.MOVE else OpKind.COPY, sources = items,
            destBackend = loc.backend, destDir = loc.path, destLabel = loc.title,
        )
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                val be = Backends.get(loc.backend)
                val names = runCatching { be.list(loc.path).map { it.name }.toSet() }.getOrDefault(emptySet())
                // pasting a copy into its own folder auto-renames; not a "conflict"
                items.filter { it.name in names && FileOperationParent.parent(it) != loc.path }.map { it.name }
            }
            if (existing.isNotEmpty()) dialog = Dialog.Conflict(existing, req)
            else start(req)
            if (clearClipboard) clipboard = null
            recompute()
        }
        return true
    }

    // ---- dual pane (open Fold) ----------------------------------------------

    fun showDualPane(on: Boolean) {
        dualPane = on
        if (on && other.location == null) {
            other.navigate(location?.takeIf { it.backend != Backend.ARCHIVE } ?: rootLocationFor(Tab.INTERNAL)!!)
        }
        if (!on) other.clearSelection()
    }

    /** Points the other pane at the folder open in the main pane. */
    fun otherPaneHere() { location?.takeIf { it.backend != Backend.ARCHIVE }?.let(other::navigate) }

    /** A tap in the other pane: folders and selection stay there; files open in their app like anywhere else. */
    fun openInOther(e: FileEntry) { if (!other.open(e)) openEntry(e) }

    /**
     * Copy or move between panes. [toOther] = main pane's selection into the other pane's folder; otherwise the other
     * pane's selection into the main pane's folder. [items] overrides the selection (a dragged row).
     */
    fun transfer(toOther: Boolean, move: Boolean, items: List<FileEntry>? = null) {
        val src = items ?: if (toOther) selectedEntries() else other.selectedEntries()
        val dest = (if (toOther) other.location else location) ?: return
        if (src.isEmpty()) return
        if (src.all { Paths.parent(it.path) == dest.path && it.backend == dest.backend } && move) { toast("Already in ${dest.title}"); return }
        if (transferTo(src, move, dest)) {
            if (toOther) clearSelection() else other.clearSelection()
            toast("${if (move) "Moving" else "Copying"} ${Format.items(src.size)} to ${dest.title}")
        }
    }

    /** Files dropped on the other pane: ask whether to copy or move. */
    fun dropOnOther(toOther: Boolean, items: List<FileEntry>) {
        val dest = (if (toOther) other.location else location) ?: return
        if (items.isNotEmpty()) dialog = Dialog.Transfer(toOther, items, dest.title)
    }

    fun confirmTransfer(move: Boolean) {
        val d = dialog as? Dialog.Transfer ?: return
        dialog = null
        transfer(d.toOther, move, d.items)
    }

    fun resolveConflict(policy: ConflictPolicy) {
        val d = dialog as? Dialog.Conflict ?: return
        dialog = null
        start(d.request.copy(policy = policy))
    }

    private fun start(req: OpRequest) {
        if (needsNotificationExplainer(req)) {
            notificationsAskedThisSession = true
            pendingOp = req
            dialog = Dialog.Explainer(PermissionKind.NOTIFICATIONS, settingsRoute = settings.notificationDenials >= 2)
            return
        }
        OperationManager.enqueue(ctx, req)
    }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun needsNotificationExplainer(req: OpRequest): Boolean =
        req.kind != OpKind.DELETE && !notificationsAskedThisSession && !notificationsGranted() && LargeOp.isLarge(req.sources)

    fun requestDelete(entries: List<FileEntry>) { if (entries.isNotEmpty()) dialog = Dialog.Delete(entries) }

    fun confirmDelete() {
        val d = dialog as? Dialog.Delete ?: return
        dialog = null
        clearSelection()
        start(OpRequest(OpKind.DELETE, d.entries))
    }

    fun rename(e: FileEntry, newName: String) {
        dialog = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backends.get(e.backend).rename(e.path, newName) }.getOrNull() }
            if (r == null) toast("Couldn't rename ${e.name}") else {
                if (e.backend == Backend.LOCAL) MediaScan.scan(ctx, listOf(e.path, r))
                clearSelection(); reload()
            }
        }
    }

    fun newFolder(name: String) {
        val loc = (dialog as? Dialog.NewFolder)?.loc ?: location ?: return
        dialog = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backends.get(loc.backend).mkdir(loc.path, name) } }
            r.onFailure { toast(it.message ?: "Couldn't create folder") }.onSuccess { reload() }
        }
    }

    fun nameExists(name: String): Boolean = browser.entries.any { it.name == name } || raw.any { it.name == name }

    fun validateName(name: String, current: String? = null): String? =
        Names.validate(name.trim()) ?: if (name.trim() != current && nameExists(name.trim())) "Something with that name already exists" else null

    fun setCurrentNomedia(present: Boolean) {
        val loc = location ?: return
        if (loc.backend != Backend.LOCAL) return
        setNomedia(
            FileEntry(name = loc.title, path = loc.path, backend = Backend.LOCAL, isDirectory = true, hasNomedia = browser.hasNomedia),
            present,
        )
    }

    fun setNomedia(e: FileEntry, present: Boolean) {
        dialog = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MediaScan.setNomedia(ctx, e.path, present) } }
            r.onSuccess {
                toast(if (present) "Added .nomedia: gallery apps will skip \u201c${e.name}\u201d after the rescan" else "Removed .nomedia from \u201c${e.name}\u201d")
                reload()
            }.onFailure { toast(it.message ?: "Failed") }
        }
    }

    fun properties(e: FileEntry) {
        dialog = Dialog.Properties(PropertiesUi(e, sizeBytes = if (e.isDirectory) null else e.size))
        viewModelScope.launch {
            val ui = withContext(Dispatchers.IO) {
                var files = 0; var folders = 0; var bytes = 0L
                if (e.isDirectory) {
                    val be = Backends.get(e.backend)
                    fun walk(p: String, depth: Int) {
                        if (depth > 64) return
                        for (c in runCatching { be.list(p) }.getOrDefault(emptyList())) {
                            if (c.isDirectory && !c.isSymlink) { folders++; walk(c.path, depth + 1) } else { files++; bytes += c.size }
                        }
                    }
                    walk(e.path, 0)
                } else bytes = e.size
                val created = if (e.backend == Backend.LOCAL) runCatching {
                    Files.readAttributes(File(e.path).toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
                }.getOrNull()?.takeIf { it > 0 } else null
                val extra = buildList {
                    if (e.backend == Backend.ROOT) {
                        RootShell.run("stat -c %C ${Paths.shellQuote(e.path)}").out.firstOrNull()?.let { add("SELinux" to it) }
                        if (e.isSymlink) RootShell.run("readlink ${Paths.shellQuote(e.path)}").out.firstOrNull()?.let { add("Points to" to it) }
                    }
                    if (e.backend == Backend.LOCAL) {
                        val f = File(e.path)
                        add("Access" to listOfNotNull("read".takeIf { f.canRead() }, "write".takeIf { f.canWrite() }).joinToString(" + ").ifEmpty { "none" })
                    }
                }
                PropertiesUi(e, bytes, if (e.isDirectory) files else null, if (e.isDirectory) folders else null, created, extra)
            }
            if ((dialog as? Dialog.Properties)?.ui?.entry == e) dialog = Dialog.Properties(ui)
        }
    }

    // ---- APK --------------------------------------------------------------

    fun openApk(e: FileEntry) {
        canInstallUnknown = ApkInstaller.canInstall(ctx)
        if (!canInstallUnknown && !rootState.isGranted) {
            pendingApk = e
            dialog = Dialog.Explainer(PermissionKind.INSTALL_APPS)
            return
        }
        showApkSheet(e)
    }

    private fun showApkSheet(e: FileEntry) {
        ApkInstaller.reset()
        viewModelScope.launch {
            val file = if (e.backend == Backend.LOCAL) File(e.path) else runCatching { copyToCache(e) }.getOrElse {
                toast("Can't read ${e.name}"); return@launch
            }
            apkSheet = ApkSheet(file)
            canInstallUnknown = ApkInstaller.canInstall(ctx)
            val r = runCatching { ApkInstaller.inspect(ctx, file) }
            apkSheet = ApkSheet(file, r.getOrNull(), false, r.exceptionOrNull()?.message)
        }
    }

    fun installApk(root: Boolean) {
        val s = apkSheet ?: return
        viewModelScope.launch {
            if (root) ApkInstaller.installWithRoot(ctx, s.file) else ApkInstaller.install(ctx, s.file)
            val info = runCatching { ApkInstaller.inspect(ctx, s.file) }.getOrNull()
            if (info != null && apkSheet?.file == s.file) apkSheet = apkSheet?.copy(info = info)
        }
    }

    fun closeApk() { apkSheet = null; ApkInstaller.reset() }

    // ---- APK contents & info (0.4.0) ------------------------------------------

    /** Browse an APK or split bundle like a zip (compiled XML is shown decoded). */
    fun browseApk(e: FileEntry) = openArchive(e, location?.takeIf { it.backend != Backend.ARCHIVE || e.backend == Backend.ARCHIVE }, ArchiveFormat.ZIP)

    /** Opens the APK info screen for an .apk/.apks/.xapk/.apkm entry. */
    fun showApkInfo(e: FileEntry) {
        apkInfoEntry = e; apkInfoRoot = null
        loadApkInfo(e.name, canBrowse = true) { readableFile(e) }
    }

    /** APK info for the APK whose contents are being browsed. */
    fun showCurrentApkInfo() {
        val root = location?.takeIf { it.backend == Backend.ARCHIVE }?.rootPath ?: return
        val s = Backends.archive.session(root) ?: return
        apkInfoEntry = null; apkInfoRoot = root
        loadApkInfo(s.displayName, canBrowse = false) { s.file to null }
    }

    private fun loadApkInfo(name: String, canBrowse: Boolean, file: () -> Pair<File, (() -> Unit)?>) {
        apkInfo = local.files.explorer.ui.apk.ApkInfoUi(name, canBrowse = canBrowse)
        screen = Screen.APK_INFO
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (f, release) = file()
                    try {
                        val d = local.files.explorer.apk.ApkParser.parse(f, name)
                        val icon = d.iconBytes?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
                            // Vector / adaptive icons: let the system render them (still offline).
                            ?: runCatching { ApkInstaller.inspect(ctx, f, d.bundleBase != null).icon }.getOrNull()
                        d to icon
                    } finally {
                        runCatching { release?.invoke() }
                        if (f.path.startsWith(File(ctx.cacheDir, "archives").path) && Backends.archive.session(f.path + ArchiveBackend.SEP) == null) f.parentFile?.deleteRecursively()
                    }
                }
            }
            if (apkInfo?.fileName != name || screen != Screen.APK_INFO) return@launch
            apkInfo = r.fold(
                { (d, icon) -> apkInfo?.copy(loading = false, details = d, icon = icon?.asImageBitmap()) },
                { apkInfo?.copy(loading = false, error = if (it is local.files.explorer.storage.NotEnoughSpaceException) it.message else "Couldn't read this package: ${it.message ?: it.javaClass.simpleName}") },
            )
        }
    }

    fun closeApkInfo() { apkInfo = null; apkInfoEntry = null; apkInfoRoot = null; screen = Screen.MAIN }

    fun apkInfoInstall() {
        val e = apkInfoEntry ?: apkInfoRoot?.let { Backends.archive.session(it) }?.let { s ->
            FileEntry(s.displayName, s.file.path, Backend.LOCAL, isDirectory = false, size = s.file.length(), lastModified = s.file.lastModified())
        } ?: return
        openApk(e)
    }

    fun apkInfoBrowse() {
        val e = apkInfoEntry
        closeApkInfo()
        if (e != null) browseApk(e)
    }

    fun apkInfoExtractAll() {
        val e = apkInfoEntry
        val root = apkInfoRoot
        closeApkInfo()
        if (e != null) requestExtract(e) else if (root != null) requestExtract(null)
    }

    /** Extract dialog: keep compiled XML as-is or decode it to readable text (APK sessions). */
    fun setExtractDecode(decode: Boolean) {
        (dialog as? Dialog.Extract)?.let { dialog = it.copy(decodeXml = decode) }
    }

    fun openUnknownSources() = emit(UiEvent.Launch(ApkInstaller.unknownSourcesIntent(ctx)))

    // ---- permissions --------------------------------------------------------

    // Every system permission screen is preceded by an in-app explainer (Continue / Not now).

    fun explainAllFiles() { dialog = Dialog.Explainer(PermissionKind.ALL_FILES) }

    fun explainerContinue() {
        val d = dialog as? Dialog.Explainer ?: return
        dialog = null
        when (d.kind) {
            PermissionKind.ALL_FILES -> { viewModelScope.launch { repo.setExplained(allFiles = true) }; requestAllFiles() }
            PermissionKind.INSTALL_APPS -> {
                viewModelScope.launch { repo.setExplained(install = true) }
                openUnknownSources()
            }
            PermissionKind.NOTIFICATIONS -> {
                viewModelScope.launch { repo.setExplained(notifications = true) }
                if (d.settingsRoute) {
                    emit(UiEvent.Launch(notificationSettingsIntent()))
                } else emit(UiEvent.RequestNotifications)
            }
        }
    }

    fun explainerNotNow() {
        val d = dialog as? Dialog.Explainer ?: return
        dialog = null
        when (d.kind) {
            PermissionKind.ALL_FILES -> { askedForAccess = true; viewModelScope.launch { repo.setExplained(allFiles = true) } }
            PermissionKind.INSTALL_APPS -> pendingApk?.let { pendingApk = null; showApkSheet(it) }
            PermissionKind.NOTIFICATIONS -> pendingOp?.let { pendingOp = null; OperationManager.enqueue(ctx, it) }
        }
    }

    /** Result of the system notification prompt; the operation starts either way. */
    fun onNotificationResult(granted: Boolean) {
        if (!granted) viewModelScope.launch { repo.addNotificationDenial() }
        pendingOp?.let { pendingOp = null; OperationManager.enqueue(ctx, it) }
    }

    private fun notificationSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun requestAllFiles() {
        askedForAccess = true
        if (Build.VERSION.SDK_INT >= 30) emit(UiEvent.Launch(StorageAccess.manageAllFilesIntent(ctx), StorageAccess.manageAllFilesFallback()))
        else emit(UiEvent.RequestLegacyStorage)
    }

    fun onLegacyResult() {
        hasAllFiles = StorageAccess.hasAllFilesAccess(ctx); askedForAccess = true
        if (hasAllFiles) rescan()
        reload()
    }

    // ---- volumes & SAF ------------------------------------------------------

    fun refreshVolumes() {
        viewModelScope.launch {
            val before = volumes.mapNotNull { v -> v.path.takeIf { v.directAccess } }
            volumes = runCatching { volumesRepo.volumes(settings.safTrees) }.getOrDefault(volumes)
            if (volumes.mapNotNull { v -> v.path.takeIf { v.directAccess } } != before && index.state.value.complete) rescan()
        }
    }

    fun openVolume(v: VolumeInfo) {
        when {
            v.directAccess && v.path != null -> navigate(Location(Backend.LOCAL, v.path, v.path, v.label))
            v.safTree != null -> openTree(v.safTree, v.label)
            else -> grant(v)
        }
    }

    fun grant(v: VolumeInfo?) = emit(UiEvent.PickTree(v?.uuid, if (v != null) volumesRepo.openTreeIntent(v.uuid) else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)))

    fun onTreePicked(uuid: String?, uri: Uri?) {
        if (uri == null) return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        viewModelScope.launch {
            repo.addSafTree(uuid ?: treeUuid(uri), uri.toString())
            if (tab != Tab.STORAGE) switchTab(Tab.STORAGE)
            openTree(uri.toString(), treeLabel(uri.toString()))
        }
    }

    private fun treeUuid(uri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringBefore(':').takeIf { it != "primary" && it.isNotEmpty() }
    }.getOrNull()

    fun openTree(tree: String, label: String) {
        val treeUri = Uri.parse(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)).toString()
        if (tab != Tab.STORAGE) { tab = Tab.STORAGE; screen = Screen.MAIN }
        navigate(Location(Backend.SAF, doc, doc, label, listOf(Crumb(label, doc))))
    }

    fun forgetTree(f: SafFolder) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        viewModelScope.launch { repo.removeSafTree(f.uri) }
    }

    private fun treeLabel(tree: String): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val v = if (vol == "primary") "Internal" else volumes.firstOrNull { it.uuid == vol }?.label ?: vol
        if (path.isEmpty()) v else "$v \u203A ${path.replace("/", " \u203A ")}"
    }.getOrDefault("Folder")

    fun eject(v: VolumeInfo) {
        emit(UiEvent.Launch(volumesRepo.ejectSettingsIntent()))
        toast("Tap \u201c${v.label}\u201d, then Eject. Android only lets Settings unmount drives without root.")
    }

    fun confirmRootMount() {
        val d = dialog as? Dialog.RootMount ?: return
        dialog = null
        val id = d.volume.rootVolumeId ?: return
        viewModelScope.launch {
            val r = volumesRepo.rootMount(id, d.mount)
            toast(if (r.ok) "${d.volume.label} ${if (d.mount) "mounted" else "unmounted"}" else "sm failed: ${r.err.firstOrNull() ?: r.out.firstOrNull() ?: "unknown error"}")
            delay(600)
            refreshVolumes()
        }
    }

    // ---- root ----------------------------------------------------------------

    fun recheckRoot() {
        viewModelScope.launch {
            rootState = RootState.CHECKING
            rootState = RootShell.detect(force = true)
            toast(when (rootState) { RootState.GRANTED -> "Root access granted"; RootState.DENIED -> "Root was denied"; else -> "Root not available on this phone" })
        }
    }

    fun setRootWrites(on: Boolean) {
        if (on) dialog = Dialog.RootWritesWarning
        else viewModelScope.launch { repo.setRootWrites(false); recompute() }
    }

    fun confirmRootWrites() {
        dialog = null
        viewModelScope.launch { repo.setRootWrites(true); recompute() }
    }

    fun remountSystem() {
        dialog = null
        viewModelScope.launch {
            val r = RootShell.run("mount -o rw,remount /system 2>&1 || mount -o rw,remount / 2>&1")
            toast(if (r.ok) "System is mounted read-write until reboot" else "Remount failed (common on Android 10+ with dynamic partitions): ${(r.out + r.err).lastOrNull() ?: ""}")
        }
    }


    // ---- archives --------------------------------------------------------------

    private fun archiveUiFor(root: String): ArchiveUi? {
        val s = Backends.archive.session(root) ?: return null
        val tree = runCatching { s.tree() }.getOrNull()
        return ArchiveUi(
            name = s.displayName, format = s.format, files = tree?.fileCount ?: 0, totalBytes = tree?.totalSize ?: 0,
            archiveBytes = s.file.length(), encrypted = tree?.anyEncrypted ?: true, unlocked = s.password != null,
            skippedUnsafe = tree?.skippedUnsafe ?: 0, suspicious = tree != null && runCatching { s.suspicious }.getOrDefault(false),
            apk = when { s.isApkBundle -> ArchiveUi.ApkKind.BUNDLE; s.isApk -> ArchiveUi.ApkKind.APK; else -> null },
        )
    }

    /**
     * A readable file for [e] without copying when possible: the real path (local or readable root
     * path), a held /proc fd path for SAF documents, or, failing those, a cache copy made only after
     * checking there's room. The second value releases whatever was held open (null for real paths).
     */
    private fun readableFile(e: FileEntry): Pair<File, (() -> Unit)?> {
        val direct = File(e.path)
        if ((e.backend == Backend.LOCAL || e.backend == Backend.ROOT) && direct.isFile && direct.canRead()) return direct to null
        if (e.backend == Backend.SAF && local.files.explorer.storage.DirectAccess.enabled) {
            val pfd = runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(e.path), "r") }.getOrNull()
            pfd?.let { local.files.explorer.storage.DirectAccess.fromDescriptor(it) }?.let { o ->
                return o.file to (if (o.viaDescriptor) o::release else null)
            }
        }
        val dir = File(ctx.cacheDir, "archives/${java.util.UUID.randomUUID()}")
        val size = e.size.takeIf { it > 0 } ?: runCatching { Backends.get(e.backend).stat(e.path)?.size }.getOrNull() ?: -1L
        local.files.explorer.storage.DirectAccess.ensureSpace(ctx.cacheDir, size, "open \u201c${e.name}\u201d")
        dir.mkdirs()
        val out = File(dir, e.name)
        try {
            Backends.get(e.backend).openInput(e.path).use { i -> out.outputStream().use { i.copyTo(it) } }
        } catch (t: Throwable) {
            dir.deleteRecursively()
            val free = local.files.explorer.storage.DirectAccess.freeSpace(ctx.cacheDir)
            if (free < local.files.explorer.storage.DirectAccess.MARGIN) throw local.files.explorer.storage.NotEnoughSpaceException(
                local.files.explorer.storage.DirectAccess.spaceMessage("open \u201c${e.name}\u201d", size.coerceAtLeast(out.length()), free))
            throw t
        }
        return out to null
    }

    /** Registers [e] as a browsable archive (APKs and bundles open as zips). */
    private suspend fun registerArchive(e: FileEntry, around: Location?, forced: ArchiveFormat? = null): String? = withContext(Dispatchers.IO) {
        var release: (() -> Unit)? = null
        runCatching {
            val (file, rel) = readableFile(e)
            release = rel
            val format = forced ?: ArchiveFormat.of(e.name) ?: file.inputStream().use { i -> ArchiveFormat.sniff(i.readNBytesCompat(600)) }
                ?: throw java.io.IOException("${e.name} isn't an archive File Manager can open")
            val origin = when (e.backend) {
                Backend.LOCAL, Backend.ROOT -> Paths.parent(e.path)?.let { ArchiveBackend.Origin(e.backend, it, around?.title ?: Paths.name(it)) }
                Backend.SAF -> around?.takeIf { it.backend == Backend.SAF }?.let { ArchiveBackend.Origin(Backend.SAF, it.path, it.title) }
                Backend.ARCHIVE -> Backends.archive.session(e.path)?.origin
            }
            Backends.archive.register(file, format, e.name, origin, rel)
        }.onFailure {
            runCatching { release?.invoke() }
            toast(if (it is local.files.explorer.storage.NotEnoughSpaceException) it.message ?: "Not enough space" else "Can't open ${e.name}: ${it.message}")
        }.getOrNull()
    }

    /** Opens an archive like a folder. [around] = the folder it was tapped in (null from Home / other apps). */
    fun openArchive(e: FileEntry, around: Location?, forced: ArchiveFormat? = null) {
        viewModelScope.launch {
            val parent: Location? = around ?: when (e.backend) {
                Backend.LOCAL -> localLocationFor(Paths.parent(e.path) ?: e.path).let { (t, l) -> tab = if (t == Tab.ROOT && !rootState.isGranted) Tab.INTERNAL else t; l }
                Backend.ROOT -> { tab = if (rootState.isGranted) Tab.ROOT else Tab.INTERNAL; Location(Backend.ROOT, Paths.parent(e.path) ?: "/", "/", "/") }
                else -> null
            }
            if (tab == Tab.HOME) tab = Tab.INTERNAL
            screen = Screen.MAIN
            val root = registerArchive(e, around, forced) ?: return@launch
            highlight = null
            navigate(Location(Backend.ARCHIVE, root, root, e.name, parent = parent))
        }
    }

    /** An archive handed to the app by another app (already copied to our cache). */
    fun openIncomingArchive(path: String, name: String) {
        val f = File(path)
        if (!f.exists()) { toast("That archive is no longer available"); return }
        (dialog as? Dialog.Explainer)?.takeIf { it.kind == PermissionKind.ALL_FILES }?.let { dialog = null }
        viewModelScope.launch {
            val format = ArchiveFormat.of(name) ?: withContext(Dispatchers.IO) { f.inputStream().use { ArchiveFormat.sniff(it.readNBytesCompat(600)) } }
            if (format == null) { toast("$name isn't an archive File Manager can open"); return@launch }
            // In-place files (a held descriptor) are released when the archive closes; never deleted.
            val inPlace = local.files.explorer.install.IncomingInstall.isOpenedInPlace(path)
            val root = Backends.archive.register(f, format, name, origin = null,
                onClose = if (inPlace) ({ local.files.explorer.install.IncomingInstall.cleanup(ctx, f) }) else null)
            tab = Tab.INTERNAL; screen = Screen.MAIN
            navigate(Location(Backend.ARCHIVE, root, root, name, parent = null))
        }
    }

    private fun askPassword(root: String, then: (() -> Unit)?, wrong: Boolean = false) {
        val s = Backends.archive.session(root) ?: return
        dialog = Dialog.Password(root, s.displayName, wrong = wrong, then = then)
    }

    fun unlockCurrentArchive() { location?.takeIf { it.backend == Backend.ARCHIVE }?.let { askPassword(it.rootPath, then = null) } }

    fun submitPassword(pw: CharArray) {
        val d = dialog as? Dialog.Password ?: return
        val s = Backends.archive.session(d.root) ?: run { dialog = null; return }
        dialog = d.copy(busy = true)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { s.unlock(pw) } }
            r.onSuccess {
                dialog = null
                reload()
                d.then?.invoke()
            }.onFailure {
                if (it is WrongPasswordException || it is PasswordRequiredException) dialog = d.copy(wrong = true, busy = false)
                else { dialog = null; toast(it.message ?: "Couldn't open the archive") }
            }
        }
    }

    /** Runs [action] once the archive holding [e] can be read (asks for the password first if needed). */
    private fun whenUnlocked(e: FileEntry, action: () -> Unit) {
        val s = Backends.archive.session(e.path) ?: run { action(); return }
        viewModelScope.launch {
            val needs = withContext(Dispatchers.IO) { runCatching { s.password == null && (s.tree().get(innerOf(e.path))?.encrypted ?: s.tree().anyEncrypted) }.getOrDefault(true) }
            if (needs) askPassword(s.root, action) else action()
        }
    }

    private fun innerOf(path: String) = Backends.archive.split(path)?.second ?: ""

    private fun extractFolderName(name: String): String {
        val n = name.replace(Regex("\\.(tar\\.gz|tar\\.xz|tar\\.bz2|tgz|txz|tbz2?|zip|7z|tar|gz|xz|bz2|apk|apks|xapk|apkm)$", RegexOption.IGNORE_CASE), "")
        return n.ifEmpty { "Extracted" }
    }

    /** Extract dialog for a whole archive (file in a folder, or the one being browsed) or for selected entries in it. */
    fun requestExtract(archive: FileEntry?, entries: List<FileEntry>? = null) {
        viewModelScope.launch {
            val root = if (archive != null) registerArchive(archive, location) ?: return@launch
            else location?.takeIf { it.backend == Backend.ARCHIVE }?.rootPath
                ?: entries?.firstOrNull()?.let { Backends.archive.session(it.path)?.root } ?: return@launch
            val s = Backends.archive.session(root) ?: return@launch
            val hereLabel = s.origin?.takeIf { originWritable(it) }?.parentLabel
            val apk = withContext(Dispatchers.IO) { runCatching { s.isApk }.getOrDefault(false) }
            dialog = Dialog.Extract(root, entries, hereLabel, extractFolderName(s.displayName), decodeXml = if (apk) s.decodeXml else null)
        }
    }

    /** "Extract here" straight from an archive's menu. */
    fun extractHere(archive: FileEntry) {
        viewModelScope.launch {
            val root = registerArchive(archive, location) ?: return@launch
            confirmExtract(ExtractTarget.HERE, Dialog.Extract(root, null, null, extractFolderName(archive.name)))
        }
    }

    private fun originWritable(o: ArchiveBackend.Origin) = o.backend != Backend.ROOT || settings.rootWrites

    fun confirmExtract(target: ExtractTarget, given: Dialog.Extract? = null) {
        val d = given ?: dialog as? Dialog.Extract ?: return
        dialog = null
        val s = Backends.archive.session(d.root) ?: return
        d.decodeXml?.let { s.decodeXml = it }
        viewModelScope.launch {
            val prep = withContext(Dispatchers.IO) {
                runCatching {
                    val tree = s.tree()
                    val locked = s.password == null && tree.anyEncrypted
                    Triple(locked, s.suspicious, d.entries ?: Backends.archive.list(s.root))
                }
            }
            val (locked, suspicious, entries) = prep.getOrElse {
                if (it is PasswordRequiredException) askPassword(s.root, then = { confirmExtract(target, d) }) else toast(it.message ?: "Can't read the archive")
                return@launch
            }
            if (locked) { askPassword(s.root, then = { confirmExtract(target, d) }); return@launch }
            if (suspicious) {
                toast("Not extracting “${s.displayName}”: it claims to unpack to far more than its own size (possible zip bomb).")
                return@launch
            }
            if (entries.isEmpty()) { toast("This archive is empty"); return@launch }
            val origin = s.origin
            when (target) {
                ExtractTarget.CHOOSE -> {
                    clipboard = entries to false
                    clearSelection()
                    val dest = origin?.takeIf { it.backend == Backend.LOCAL }?.let { localLocationFor(it.parentDir) } ?: (Tab.INTERNAL to rootLocationFor(Tab.INTERNAL)!!)
                    jumpTo(dest.first, dest.second, null)
                    toast("Open the destination folder and tap Extract here.")
                }
                ExtractTarget.HERE, ExtractTarget.FOLDER -> {
                    if (origin == null || !originWritable(origin)) { requestExtractChoose(d); return@launch }
                    val be = Backends.get(origin.backend)
                    var destDir = origin.parentDir
                    var destLabel = origin.parentLabel
                    if (target == ExtractTarget.FOLDER) {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val name = ConflictResolver.uniqueName(d.folderName) { be.exists(origin.parentDir, it) }
                                be.mkdir(origin.parentDir, name) to name
                            }
                        }
                        val (dir, name) = r.getOrElse { toast("Couldn't create folder: ${it.message}"); return@launch }
                        destDir = dir; destLabel = name
                    }
                    val req = OpRequest(OpKind.EXTRACT, entries, origin.backend, destDir, destLabel)
                    clearSelection()
                    if (target == ExtractTarget.HERE) {
                        val existing = withContext(Dispatchers.IO) {
                            val names = runCatching { be.list(destDir).map { it.name }.toSet() }.getOrDefault(emptySet())
                            entries.filter { it.name in names }.map { it.name }
                        }
                        if (existing.isNotEmpty()) { dialog = Dialog.Conflict(existing, req); return@launch }
                    }
                    start(req)
                }
            }
        }
    }

    private fun requestExtractChoose(d: Dialog.Extract) = confirmExtract(ExtractTarget.CHOOSE, d)

    fun requestCompress(entries: List<FileEntry>) {
        val loc = location
        if (entries.isEmpty()) return
        val dest: Location = when {
            loc != null && loc.backend != Backend.ARCHIVE && !(loc.backend == Backend.ROOT && !settings.rootWrites) && tab != Tab.RECENT -> loc
            entries.all { it.backend == Backend.LOCAL } -> localLocationFor(Paths.parent(entries[0].path) ?: "/").second
            else -> { toast("Can't save an archive here. Open a writable folder first."); return }
        }
        val base = if (entries.size == 1) entries[0].let { if (it.isDirectory) it.name else Names.splitExtension(it.name).first.ifEmpty { it.name } }
        else dest.title.takeIf { it.isNotBlank() && it != "/" } ?: "Archive"
        dialog = Dialog.Compress(entries, dest, base)
    }

    fun confirmCompress(spec: CompressSpec) {
        val d = dialog as? Dialog.Compress ?: return
        dialog = null
        clearSelection()
        start(OpRequest(OpKind.COMPRESS, d.entries, d.dest.backend, d.dest.path, d.dest.title, compress = spec))
    }

    // ---- Home ------------------------------------------------------------------

    fun indexRoots(): List<PathRoot> = buildList {
        add(PathRoot(StorageAccess.internalRoot(), "Internal storage"))
        volumes.filter { it.directAccess && it.path != null && it.kind != local.files.explorer.core.VolumeKind.INTERNAL }
            .forEach { add(PathRoot(it.path!!, it.label)) }
    }.distinctBy { Paths.normalize(it.path) }

    fun rescan() {
        if (!hasAllFiles) return
        index.rescan(indexRoots())
    }

    fun cancelScan() = index.cancel()

    fun pushHome(p: HomePage) {
        if (p is HomePage.CategoryList || p == HomePage.Largest) { listQuery = "" }
        if (p is HomePage.CategoryList) listSort = SortOrder(SortField.DATE, ascending = false, foldersFirst = false)
        if (p == HomePage.Search) { homeQuery = ""; globalHits = emptyList(); globalProgress = SearchProgress(done = true) }
        if (p == HomePage.Duplicates) duplicates = DuplicatesUi(scan = scanUi())
        clearSelection()
        tab = Tab.HOME; screen = Screen.MAIN
        homeStack = homeStack + p
        refreshHome()
        if (p == HomePage.Duplicates) startDuplicateScan()
    }

    private fun homeBack() {
        if (homePage == HomePage.Search) globalSearchJob?.cancel()
        if (homePage == HomePage.Duplicates) duplicateJob?.cancel()
        clearSelection()
        homeStack = homeStack.dropLast(1)
        refreshHome()
    }


    private fun folderRow(node: FolderNode): FolderRowUi {
        val has = File(node.path, NOMEDIA).exists()
        return FolderRowUi(node.name, node.path, node.bytes, node.files, hasNomedia = has)
    }

    private fun scanUi(): ScanUi = index.state.value.let { ScanUi(it.scanning, it.progress, it.complete, it.cancelled) }

    private fun updateScanUi() {
        val sc = scanUi()
        home = home.copy(scan = sc)
        fileList = fileList.copy(scan = sc)
        folderSizes = folderSizes.copy(scan = sc)
        duplicates = duplicates.copy(scan = sc)
    }

    private fun nomediaThumbs(entries: List<FileEntry>, files: List<local.files.explorer.core.IndexedFile>): Set<String> =
        if (!settings.respectNomedia) emptySet() else {
            val wanted = entries.mapTo(HashSet()) { it.path }
            files.filter { it.inNomediaTree && it.entry.path in wanted }.mapTo(HashSet()) { it.entry.path }
        }

    /** Recomputes whatever Home page is showing from the storage index, off the main thread. */
    fun refreshHome() {
        if (tab != Tab.HOME) return
        val page = homePage
        val st = index.state.value
        val roots = indexRoots()
        val downloads = StorageAccess.downloads()
        val respect = settings.respectNomedia
        val hidden = settings.showHidden
        val q = listQuery.trim()
        val sortOrder = listSort
        val filter = sizeFilter
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val sc = scanUi()
            when (page) {
                HomePage.Overview -> {
                    val (stats, largest, trees) = withContext(Dispatchers.Default) {
                        Triple(
                            Categories.stats(st.files, downloads, respect, hidden),
                            LargestFiles.select(st.files, SizeFilter.OVER_10MB, hidden, limit = 3),
                            trees(st, roots),
                        )
                    }
                    val storage = roots.mapNotNull { r ->
                        runCatching { StatFs(r.path) }.getOrNull()?.takeIf { it.totalBytes > 0 }
                            ?.let { StorageSummary(r.label, it.totalBytes - it.availableBytes, it.totalBytes) }
                    }
                    val top = (if (trees.size == 1) trees[0].children else trees).take(3).map { folderRow(it) }
                    home = HomeUi(stats = if (st.version == 0 && st.scanning) emptyMap() else stats, storage = storage, largest = largest, topFolders = top, scan = sc)
                }
                is HomePage.CategoryList -> {
                    val (list, thumbs) = withContext(Dispatchers.Default) {
                        val all = Categories.files(st.files, page.category, downloads, respect, hidden)
                        val l = Sorter.sort(all.filter { q.isEmpty() || SearchMatch.matches(it.name, q) }, sortOrder)
                        l to nomediaThumbs(l, st.files)
                    }
                    fileList = FileListUi(
                        ListKind.CATEGORY, list, labels(list, roots), listQuery, sortOrder, filter, list.sumOf { it.size }, sc,
                        emptyTitle = if (q.isEmpty()) "No ${page.category.label.lowercase()} found" else "No matches",
                        emptyBody = if (q.isEmpty() && respect && page.category.isMedia) "Files in folders with .nomedia are hidden here. You can change this in Settings." else null,
                        noThumbs = thumbs, selection = selection,
                    )
                }
                HomePage.Largest -> {
                    val list = withContext(Dispatchers.Default) {
                        LargestFiles.select(st.files, filter, hidden).filter { q.isEmpty() || SearchMatch.matches(it.name, q) }
                    }
                    fileList = FileListUi(
                        ListKind.LARGEST, list, labels(list, roots), listQuery, sortOrder, filter, list.sumOf { it.size }, sc,
                        emptyTitle = "No files ${filter.label.lowercase()}", emptyBody = "Try a smaller size filter.",
                        selection = selection,
                    )
                }
                is HomePage.Folders -> {
                    val trees = withContext(Dispatchers.Default) { trees(st, roots) }
                    val node: FolderNode? = when {
                        page.path == null && trees.size == 1 -> trees[0]
                        page.path == null -> null
                        else -> trees.firstNotNullOfOrNull { FolderSizes.find(it, page.path) }
                    }
                    val hidden = settings.showHidden
                    folderSizes = if (node == null) FolderSizesUi(
                        title = "All storage", path = null, folders = trees.map { folderRow(it) },
                        files = emptyList(), totalBytes = trees.sumOf { it.bytes }, scan = sc, selection = selection,
                    ) else {
                        val direct = withContext(Dispatchers.Default) {
                            FolderSizes.directEntries(st.files.map { it.entry }, node.path, showHidden = hidden)
                        }
                        FolderSizesUi(
                            title = node.name, path = node.path, folders = node.children.map { folderRow(it) },
                            files = direct, totalBytes = node.bytes, directBytes = node.directBytes,
                            directFiles = node.directFiles, scan = sc, selection = selection,
                        )
                    }
                }
                HomePage.Duplicates -> {
                    // Keep hashing progress; only refresh scan banner / drop deleted paths after ops.
                    val alive = st.files.mapTo(HashSet()) { it.entry.path }
                    val pruned = duplicates.groups.mapNotNull { g ->
                        val kept = g.files.filter { it.path in alive }
                        if (kept.size >= 2) g.copy(files = kept) else null
                    }
                    duplicates = duplicates.copy(groups = pruned, scan = sc, selection = selection.filter { it in alive }.toSet())
                }
                HomePage.Search -> recomputeGlobalSearch()
            }
        }
    }

    private fun trees(st: local.files.explorer.storage.IndexState, roots: List<PathRoot>): List<FolderNode> {
        if (treesVersion == st.version && folderTrees.isNotEmpty()) return folderTrees
        val files = st.files.map { it.entry }
        folderTrees = roots.map { FolderSizes.build(it.path, it.label, files) }.filter { it.files > 0 || roots.size == 1 }
        treesVersion = st.version
        return folderTrees
    }

    private fun labels(list: List<FileEntry>, roots: List<PathRoot>): Map<String, String> =
        list.associate { it.path to FolderLabels.parentLabel(it.path, roots) }

    fun folderTitle(): String = (homePage as? HomePage.Folders)?.let { folderSizes.title } ?: "Folder sizes"

    fun setListQuery(q: String) { listQuery = q; fileList = fileList.copy(query = q); refreshHome() }
    fun setListSort(o: SortOrder) { listSort = o; refreshHome() }
    fun setSizeFilter(f: SizeFilter) { sizeFilter = f; refreshHome() }
    fun drillFolder(f: FolderRowUi) = pushHome(HomePage.Folders(f.path))


    fun startDuplicateScan() {
        duplicateJob?.cancel()
        val hidden = settings.showHidden
        duplicates = DuplicatesUi(hashing = true, scan = scanUi(), ready = false, selection = emptySet())
        duplicateJob = viewModelScope.launch {
            val snapshot = index.state.value
            val lastUi = AtomicLong(0L)
            val groups = withContext(Dispatchers.Default) {
                DuplicateFinder.find(
                    snapshot.files, hidden,
                    isCancelled = { !isActive },
                    onProgress = { done, total ->
                        val now = System.nanoTime()
                        val prev = lastUi.get()
                        if (done == total || now - prev > 80_000_000L) {
                            lastUi.set(now)
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                duplicates = duplicates.copy(hashing = true, progress = done, total = total, scan = scanUi())
                            }
                        }
                    },
                )
            }
            if (!isActive) return@launch
            val roots = indexRoots()
            val uiGroups = groups.map { g ->
                DuplicateGroupUi(g.hash, g.size, g.files, labels(g.files, roots))
            }
            duplicates = DuplicatesUi(
                hashing = false, progress = groups.sumOf { it.files.size }, total = groups.sumOf { it.files.size },
                groups = uiGroups, scan = scanUi(), ready = true, selection = emptySet(),
            )
        }
    }

    fun selectDuplicateExtras(group: DuplicateGroupUi) {
        // Keep the first (path-sorted) copy; select the rest for delete/move.
        val extras = group.files.drop(1).map { it.path }
        selection = selection + extras
        publishSelection()
    }

    // ---- global search (Home) ----------------------------------------------------

    fun globalSearch(q: String) {
        homeQuery = q
        globalSearchJob?.cancel()
        val needle = q.trim()
        if (needle.isEmpty()) { globalHits = emptyList(); globalProgress = SearchProgress(done = true); recomputeGlobalSearch(); return }
        val st = index.state.value
        val roots = indexRoots()
        val hidden = settings.showHidden
        globalHits = emptyList(); globalProgress = SearchProgress(done = false)
        recomputeGlobalSearch()
        globalSearchJob = viewModelScope.launch {
            delay(150)
            if (st.complete) {
                val (hits, limitHit) = withContext(Dispatchers.Default) {
                    val out = ArrayList<FileEntry>()
                    for (f in st.files) {
                        if (!hidden && f.inHiddenPath) continue
                        if (SearchMatch.matches(f.entry.name, needle)) { out += f.entry; if (out.size >= RecursiveSearcher.DEFAULT_LIMIT) break }
                    }
                    out to (out.size >= RecursiveSearcher.DEFAULT_LIMIT)
                }
                globalHits = hits
                globalProgress = SearchProgress(hits.size, 0, done = true, limitHit = limitHit)
                recomputeGlobalSearch()
                return@launch
            }
            // Index not ready: walk storage now and stream results in.
            val updates = Channel<Pair<List<SearchHit>, SearchProgress>>(Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                try {
                    var total = 0
                    for (r in roots) {
                        if (!isActive || total >= RecursiveSearcher.DEFAULT_LIMIT) break
                        val p = RecursiveSearcher(::fastList).search(
                            FileEntry(r.label, r.path, Backend.LOCAL, true), r.label, needle, recursive = true, showHidden = hidden,
                            limit = RecursiveSearcher.DEFAULT_LIMIT - total, isCancelled = { !isActive },
                        ) { hits, pr -> updates.trySend(hits to pr.copy(found = total + pr.found, done = false)) }
                        total += p.found
                        if (p.limitHit) { updates.trySend(emptyList<SearchHit>() to SearchProgress(total, 0, done = true, limitHit = true)); break }
                    }
                } finally { updates.close() }
            }
            for ((hits, pr) in updates) {
                globalHits = globalHits + hits.map { it.entry }
                globalProgress = pr
                recomputeGlobalSearch()
            }
            worker.join()
            globalProgress = globalProgress.copy(done = true)
            recomputeGlobalSearch()
        }
    }

    private fun recomputeGlobalSearch() {
        val roots = indexRoots()
        fileList = FileListUi(
            ListKind.SEARCH, globalHits, labels(globalHits, roots), homeQuery,
            search = SearchUi(
                recursive = true, running = !globalProgress.done, found = globalHits.size,
                limitHit = globalProgress.limitHit, limit = RecursiveSearcher.DEFAULT_LIMIT, supportsRecursive = false,
            ),
            emptyTitle = "No matches", emptyBody = "Nothing on your storage matches \u201c${homeQuery.trim()}\u201d",
            scan = scanUi(), selection = selection,
        )
    }

    // ---- settings ------------------------------------------------------------

    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setTheme(m) }
    fun setRespectNomedia(v: Boolean) = viewModelScope.launch { repo.setRespectNomedia(v) }
    fun setShowHidden(v: Boolean) = viewModelScope.launch { repo.setShowHidden(v) }
    fun setDefaultView(v: ViewMode) = viewModelScope.launch { repo.setViewMode(v); viewMode = v; recompute() }
    fun setDefaultSort(v: SortOrder) = viewModelScope.launch { repo.setSort(v); sort = v; recompute() }
    fun setRecursiveSearch(v: Boolean) = viewModelScope.launch { repo.setRecursiveSearch(v) }
}

private object FileOperationParent {
    fun parent(e: FileEntry): String? = if (e.backend == Backend.SAF) null else Paths.parent(e.path)
}

/** InputStream.readNBytes is API 33+ on Android. */
internal fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
    return buf.copyOf(off)
}
