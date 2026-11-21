package local.files.explorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.BuildConfig
import local.files.explorer.core.Backend
import local.files.explorer.core.Changelog
import local.files.explorer.core.ChangelogEntry
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Format
import local.files.explorer.core.SortField
import local.files.explorer.install.ApkInstaller
import local.files.explorer.ops.OperationManager
import local.files.explorer.settings.ThemeMode
import local.files.explorer.settings.ViewMode
import local.files.explorer.core.SortOrder
import local.files.explorer.ui.browser.BrowserActions
import local.files.explorer.ui.browser.BrowserContent
import local.files.explorer.ui.browser.CheckRow
import local.files.explorer.ui.browser.ConfirmDialog
import local.files.explorer.ui.browser.ConflictDialog
import local.files.explorer.ui.browser.ItemAction
import local.files.explorer.ui.browser.ItemActionsContent
import local.files.explorer.ui.browser.NameDialog
import local.files.explorer.ui.browser.PropertiesDialog
import local.files.explorer.ui.browser.actionsFor
import local.files.explorer.ui.common.PermissionContent
import local.files.explorer.ui.common.PermissionExplainerDialog
import local.files.explorer.ui.browser.contextActionsFor
import local.files.explorer.ui.home.FileListActions
import local.files.explorer.ui.home.FileListContent
import local.files.explorer.ui.home.FolderRowUi
import local.files.explorer.ui.home.FolderSizesActions
import local.files.explorer.ui.home.FolderSizesContent
import local.files.explorer.ui.home.DuplicatesActions
import local.files.explorer.ui.home.DuplicatesContent
import local.files.explorer.ui.home.DuplicateGroupUi
import local.files.explorer.ui.home.HomeActions
import local.files.explorer.ui.home.HomeContent
import local.files.explorer.core.Category
import local.files.explorer.core.SizeFilter
import local.files.explorer.storage.StorageAccess
import androidx.compose.material.icons.rounded.FolderOpen
import local.files.explorer.ui.install.ApkInstallContent
import local.files.explorer.ui.ops.OperationCard
import local.files.explorer.ui.settings.AboutContent
import local.files.explorer.ui.settings.LicencesContent
import local.files.explorer.ui.settings.LicenceDetailContent
import local.files.explorer.ui.settings.Licences
import local.files.explorer.ui.settings.SettingsActions
import local.files.explorer.ui.settings.SettingsContent
import local.files.explorer.ui.volumes.SafFolder
import local.files.explorer.ui.volumes.VolumeActions
import local.files.explorer.ui.volumes.VolumesContent
import local.files.explorer.core.VolumeInfo
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.settings.AppSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.VerticalSplit
import local.files.explorer.ui.adaptive.LocalAdaptive

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Root composable. Chooses the compact (bottom bar) or wide/fold layout from the window size and hinge,
 * routes between Home, the folder browser, archives, Root, Storage and Settings, and shows the dialogs,
 * sheets and permission explainers driven by [MainViewModel].
 */
@Composable
fun FileManagerApp(vm: MainViewModel, snackbar: SnackbarHostState, onExit: () -> Unit) {
    val op by OperationManager.state.collectAsState()
    val wide = LocalAdaptive.current.wide
    BackHandler(enabled = true) { if (!vm.back()) onExit() }

    Scaffold(
        topBar = { TopBar(vm) },
        bottomBar = {
            if (vm.screen == Screen.MAIN) {
                Column {
                    AnimatedVisibility(op != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        op?.let { OperationCard(it, OperationManager::cancel, OperationManager::dismiss) }
                    }
                    // Open Fold: the tabs move to a side rail and the bottom bar goes.
                    if (!wide) NavigationBar {
                        vm.tabs.forEach { t ->
                            NavigationBarItem(
                                selected = vm.tab == t, onClick = { vm.switchTab(t) },
                                icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (wide && vm.screen == Screen.MAIN) FileManagerRail(vm.tabs, vm.tab, vm::switchTab)
            Box(Modifier.weight(1f).fillMaxSize()) {
            AnimatedContent(vm.screen, label = "screen", transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
                when (s) {
                    Screen.SETTINGS -> SettingsContent(vm.settings, vm.rootState, BuildConfig.VERSION_NAME, settingsActions(vm))
                    Screen.ABOUT -> AboutContent(BuildConfig.VERSION_NAME, rememberChangelog(), BuildConfig.VERSION_CODE, onLicences = { vm.openScreen(Screen.LICENCES) })
                    Screen.LICENCES -> LicencesContent(onOpen = { vm.openLicence(it.name) })
                    Screen.LICENCE_DETAIL -> Licences.byName(vm.openLicenceName)?.let { LicenceDetailContent(it, rememberLicenceText()) }
                    Screen.APK_INFO -> vm.apkInfo?.let { local.files.explorer.ui.apk.ApkInfoContent(it, apkInfoActions(vm)) }
                    Screen.MAIN -> MainBody(vm)
                }
            }
            }
        }
    }
    Dialogs(vm)
    ApkSheetHost(vm)
}


@Composable
private fun rememberLicenceText(): (String) -> String {
    val context = LocalContext.current
    val cache = remember { mutableMapOf<String, String>() }
    return { name -> cache.getOrPut(name) { runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("") } }
}

@Composable
private fun rememberChangelog(): List<ChangelogEntry> {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.assets.open("CHANGELOG.md").bufferedReader().use { Changelog.parse(it.readText()) }
        }.getOrDefault(emptyList())
    }
}

private fun apkInfoActions(vm: MainViewModel) = object : local.files.explorer.ui.apk.ApkInfoActions {
    override fun install() = vm.apkInfoInstall()
    override fun browse() = vm.apkInfoBrowse()
    override fun extractAll() = vm.apkInfoExtractAll()
}

private fun settingsActions(vm: MainViewModel) = object : SettingsActions {
    override fun theme(m: ThemeMode) { vm.setTheme(m) }
    override fun respectNomedia(v: Boolean) { vm.setRespectNomedia(v) }
    override fun showHidden(v: Boolean) { vm.setShowHidden(v) }
    override fun viewMode(v: ViewMode) { vm.setDefaultView(v) }
    override fun sort(v: SortOrder) { vm.setDefaultSort(v) }
    override fun recursiveSearch(v: Boolean) { vm.setRecursiveSearch(v) }
    override fun rootWrites(v: Boolean) { vm.setRootWrites(v) }
    override fun recheckRoot() { vm.recheckRoot() }
    override fun about() { vm.openScreen(Screen.ABOUT) }
}

@Composable
private fun MainBody(vm: MainViewModel) {
    val needsAccess = !vm.hasAllFiles && vm.tab in setOf(Tab.HOME, Tab.INTERNAL, Tab.RECENT) &&
        (vm.location?.backend ?: Backend.LOCAL) == Backend.LOCAL
    when {
        needsAccess -> PermissionContent(vm.askedForAccess, onGrant = vm::explainAllFiles, onPicker = { vm.grant(null) })
        vm.tab == Tab.HOME -> HomeBody(vm)
        vm.tab == Tab.STORAGE && vm.location == null -> VolumesContent(vm.volumes, vm.rootState.isGranted, vm.safFolders, volumeActions(vm))
        LocalAdaptive.current.wide && vm.location != null && vm.tab != Tab.RECENT -> WideBrowser(
            main = vm.browser, other = vm.other.ui, dual = vm.dualPane,
            otherTitle = vm.other.location?.title ?: "Second pane", mainTitle = vm.location?.title ?: "",
            actions = wideActions(vm),
        )
        else -> BrowserContent(vm.browser, browserActions(vm))
    }
}

private fun wideActions(vm: MainViewModel) = WideBrowserActions(
    main = browserActions(vm),
    other = object : BrowserActions {
        override fun open(entry: FileEntry) = vm.openInOther(entry)
        override fun longPress(entry: FileEntry) = vm.other.toggleSelect(entry)
        override fun menu(entry: FileEntry) { vm.dialog = Dialog.ItemMenu(entry, context = true) }
        override fun crumb(crumb: local.files.explorer.core.Crumb) = vm.other.crumb(crumb)
        override fun retry() = vm.other.reload()
    },
    tree = vm::crumb,
    treeOpen = { e -> vm.clearSelection(); vm.open(e) },
    setDual = vm::showDualPane,
    otherHere = vm::otherPaneHere,
    transfer = { toOther, move -> vm.transfer(toOther, move) },
    drop = vm::dropOnOther,
    mainSelection = vm::selectedEntries,
    otherSelection = { vm.other.selectedEntries() },
)

@Composable
private fun HomeBody(vm: MainViewModel) {
    AnimatedContent(vm.homePage, label = "home", transitionSpec = { fadeIn() togetherWith fadeOut() }) { page ->
        when (page) {
            HomePage.Overview -> HomeContent(vm.home, homeActions(vm))
            is HomePage.CategoryList, HomePage.Largest, HomePage.Search -> FileListContent(vm.fileList, fileListActions(vm))
            is HomePage.Folders -> FolderSizesContent(vm.folderSizes, folderActions(vm))
            HomePage.Duplicates -> DuplicatesContent(vm.duplicates, duplicatesActions(vm))
        }
    }
}

private fun homeActions(vm: MainViewModel) = object : HomeActions {
    override fun search() = vm.pushHome(HomePage.Search)
    override fun category(c: Category) = vm.pushHome(HomePage.CategoryList(c))
    override fun largest() = vm.pushHome(HomePage.Largest)
    override fun folderSizes() = vm.pushHome(HomePage.Folders(null))
    override fun duplicates() = vm.pushHome(HomePage.Duplicates)
    override fun cancelScan() = vm.cancelScan()
    override fun rescan() = vm.rescan()
    override fun openFile(e: FileEntry) = vm.openEntry(e)
    override fun menu(e: FileEntry) { vm.dialog = Dialog.ItemMenu(e, context = true) }
    override fun openStorage(s: local.files.explorer.ui.browser.StorageSummary) = vm.openHomeStorage(s)
}

private fun fileListActions(vm: MainViewModel) = object : FileListActions {
    override fun open(e: FileEntry) {
        if (vm.fileList.selection.isNotEmpty()) vm.toggleSelect(e) else vm.openEntry(e)
    }
    override fun longPress(e: FileEntry) = vm.toggleSelect(e)
    override fun menu(e: FileEntry) { vm.dialog = Dialog.ItemMenu(e, context = true) }
    override fun query(q: String) = vm.setListQuery(q)
    override fun sort(o: SortOrder) = vm.setListSort(o)
    override fun sizeFilter(f: SizeFilter) = vm.setSizeFilter(f)
    override fun cancelScan() = vm.cancelScan()
    override fun rescan() = vm.rescan()
}

private fun folderActions(vm: MainViewModel) = object : FolderSizesActions {
    override fun drill(f: FolderRowUi) {
        if (vm.folderSizes.selection.isNotEmpty()) vm.toggleSelect(f.toEntry()) else vm.drillFolder(f)
    }
    override fun openFolder(f: FolderRowUi) = vm.openFolderInBrowser(f.path)
    override fun openCurrent() {
        val path = vm.folderSizes.path ?: return
        vm.openFolderInBrowser(path)
    }
    override fun longPress(f: FolderRowUi) = vm.toggleSelect(f.toEntry())
    override fun menu(f: FolderRowUi) {
        // Full browser-style menu (includes Add/Remove .nomedia).
        vm.dialog = Dialog.ItemMenu(f.toEntry(), context = false)
    }
    override fun openFile(e: FileEntry) {
        if (vm.folderSizes.selection.isNotEmpty()) vm.toggleSelect(e) else vm.openEntry(e)
    }
    override fun longPressFile(e: FileEntry) = vm.toggleSelect(e)
    override fun menuFile(e: FileEntry) { vm.dialog = Dialog.ItemMenu(e, context = true) }
    override fun cancelScan() = vm.cancelScan()
    override fun rescan() = vm.rescan()
}

private fun duplicatesActions(vm: MainViewModel) = object : DuplicatesActions {
    override fun open(e: FileEntry) {
        if (vm.duplicates.selection.isNotEmpty()) vm.toggleSelect(e) else vm.openEntry(e)
    }
    override fun longPress(e: FileEntry) = vm.toggleSelect(e)
    override fun menu(e: FileEntry) { vm.dialog = Dialog.ItemMenu(e, context = true) }
    override fun selectExtras(group: DuplicateGroupUi) = vm.selectDuplicateExtras(group)
    override fun rescan() = vm.startDuplicateScan()
    override fun cancelScan() = vm.cancelScan()
}

private fun browserActions(vm: MainViewModel) = object : BrowserActions {
    override fun open(entry: FileEntry) = vm.open(entry)
    override fun longPress(entry: FileEntry) {
        // In search results a long-press opens the context menu (with Go to file location); elsewhere it selects.
        if (vm.browser.locations.containsKey(entry.path) && vm.browser.selection.isEmpty()) vm.dialog = Dialog.ItemMenu(entry, context = true)
        else vm.toggleSelect(entry)
    }
    override fun menu(entry: FileEntry) {
        // Search hits carry a location label; give them the context menu (Go to file location).
        val fromSearch = vm.browser.locations.containsKey(entry.path)
        vm.dialog = Dialog.ItemMenu(entry, context = fromSearch)
    }
    override fun setSearchRecursive(recursive: Boolean) = vm.setSearchRecursive(recursive)
    override fun crumb(crumb: local.files.explorer.core.Crumb) = vm.crumb(crumb)
    override fun paste() = vm.paste()
    override fun clearClipboard() = vm.clearClipboard()
    override fun retry() = vm.reload()
    override fun extractArchive() = vm.requestExtract(null)
    override fun unlockArchive() = vm.unlockCurrentArchive()
    override fun archiveAppInfo() = vm.showCurrentApkInfo()
}

private fun volumeActions(vm: MainViewModel) = object : VolumeActions {
    override fun open(v: VolumeInfo) = vm.openVolume(v)
    override fun eject(v: VolumeInfo) = vm.eject(v)
    override fun rootMount(v: VolumeInfo, mount: Boolean) { vm.dialog = Dialog.RootMount(v, mount) }
    override fun grant(v: VolumeInfo) = vm.grant(v)
    override fun openSaf(f: SafFolder) = vm.openTree(f.uri, f.label)
    override fun forgetSaf(f: SafFolder) = vm.forgetTree(f)
    override fun addFolder() = vm.grant(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(vm: MainViewModel) {
    val selCount = vm.browser.selection.size
    val loc = vm.location
    val readOnly = if (vm.tab == Tab.HOME) false else vm.browser.readOnly
    when {
        vm.screen != Screen.MAIN -> TopAppBar(
            title = { Text(when (vm.screen) { Screen.SETTINGS -> "Settings"; Screen.APK_INFO -> "App info"; Screen.LICENCES -> "Licences"; Screen.LICENCE_DETAIL -> Licences.byName(vm.openLicenceName)?.title ?: "Licence"; else -> "About" }) },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
        selCount > 0 -> TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Clear selection") } },
            title = { Text("$selCount selected") },
            actions = {
                val sel = vm.selectedEntries()
                IconButton(onClick = { vm.copy(sel, cut = false) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                if (!readOnly && vm.tab != Tab.RECENT) IconButton(onClick = { vm.copy(sel, cut = true) }) { Icon(Icons.Rounded.ContentCut, "Cut") }
                if (!readOnly) IconButton(onClick = { vm.requestDelete(sel) }) { Icon(Icons.Rounded.Delete, "Delete") }
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("Select all") }, { more = false; vm.selectAll() }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) })
                    if (sel.none { it.backend == Backend.ROOT }) DropdownMenuItem({ Text("Share") }, { more = false; vm.share(sel) }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                    if (sel.isNotEmpty() && sel.all { it.backend == Backend.ARCHIVE }) DropdownMenuItem({ Text("Extract\u2026") }, { more = false; vm.requestExtract(null, sel) }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) })
                    if (!readOnly && vm.tab != Tab.RECENT) DropdownMenuItem({ Text("Compress\u2026") }, { more = false; vm.requestCompress(sel) }, leadingIcon = { Icon(Icons.Rounded.Archive, null) })
                    if (sel.size == 1 && !readOnly) DropdownMenuItem({ Text("Rename") }, { more = false; vm.dialog = Dialog.Rename(sel[0]) }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) })
                    if (sel.size == 1) DropdownMenuItem({ Text("Properties") }, { more = false; vm.properties(sel[0]) }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                }
            },
        )
        vm.screen == Screen.MAIN && vm.tab == Tab.HOME && vm.homePage == HomePage.Search -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                title = {
                    TextField(
                        vm.homeQuery, vm::globalSearch, singleLine = true,
                        placeholder = { Text("Search all storage") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = { if (vm.homeQuery.isNotEmpty()) IconButton(onClick = { vm.globalSearch("") }) { Icon(Icons.Rounded.Close, "Clear") } },
            )
        }
        vm.screen == Screen.MAIN && vm.tab == Tab.HOME -> TopAppBar(
            navigationIcon = {
                if (vm.homeStack.size > 1) IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            title = {
                Text(
                    when (val p = vm.homePage) {
                        HomePage.Overview -> "File Manager"
                        is HomePage.CategoryList -> p.category.label
                        HomePage.Largest -> "Largest files"
                        is HomePage.Folders -> if (p.path == null) "Folder sizes" else vm.folderSizes.title
                        HomePage.Duplicates -> "Duplicates"
                        HomePage.Search -> "Search"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                val p = vm.homePage
                if (p == HomePage.Overview) IconButton(onClick = { vm.pushHome(HomePage.Search) }) { Icon(Icons.Rounded.Search, "Search all storage") }
                if (p is HomePage.CategoryList && p.category == Category.DOWNLOADS) {
                    IconButton(onClick = { vm.openFolderInBrowser(StorageAccess.downloads()) }) { Icon(Icons.Rounded.FolderOpen, "Open Downloads folder") }
                }
                if (p is HomePage.Folders && p.path != null) {
                    IconButton(onClick = { vm.openFolderInBrowser(p.path) }) { Icon(Icons.Rounded.FolderOpen, "Open this folder") }
                }
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("Rescan storage") }, { more = false; vm.rescan() }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) })
                    if (vm.homePage == HomePage.Duplicates) {
                        DropdownMenuItem({ Text("Scan for duplicates again") }, { more = false; vm.startDuplicateScan() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    }
                    if (vm.homePage is HomePage.CategoryList || vm.homePage == HomePage.Largest || vm.homePage is HomePage.Folders || vm.homePage == HomePage.Duplicates) {
                        DropdownMenuItem({ Text("Select all") }, { more = false; vm.selectAll() }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) })
                    }
                    DropdownMenuItem(
                        { Text(if (vm.settings.showHidden) "Hide hidden files" else "Show hidden files") },
                        { more = false; vm.setShowHidden(!vm.settings.showHidden) },
                        leadingIcon = { Icon(if (vm.settings.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Settings") }, { more = false; vm.openScreen(Screen.SETTINGS) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                }
            },
        )
        vm.searchOpen -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                title = {
                    TextField(
                        vm.browser.searchQuery, vm::search, singleLine = true,
                        placeholder = { Text(if (vm.browser.search?.recursive == true) "Search here and in subfolders" else "Search this folder") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = { if (vm.browser.searchQuery.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Rounded.Close, "Clear") } },
            )
        }
        else -> TopAppBar(
            navigationIcon = {
                if (loc != null && (!loc.atRoot || vm.tab == Tab.STORAGE || loc.parent != null)) IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up") }
            },
            title = {
                Text(
                    when {
                        vm.tab == Tab.RECENT -> "Recent"
                        vm.tab == Tab.STORAGE && loc == null -> "Storage"
                        vm.tab == Tab.ROOT && loc?.atRoot == true -> "Root"
                        else -> loc?.title ?: "File Manager"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                val browsing = vm.tab == Tab.RECENT || loc != null
                if (LocalAdaptive.current.wide && loc != null && vm.tab != Tab.RECENT) {
                    IconButton(onClick = { vm.showDualPane(!vm.dualPane) }, modifier = Modifier.testTag("dualToggle")) {
                        Icon(Icons.Rounded.VerticalSplit, if (vm.dualPane) "Single pane" else "Dual pane",
                            tint = if (vm.dualPane) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
                    }
                }
                if (browsing) {
                    IconButton(onClick = vm::openSearch) { Icon(Icons.Rounded.Search, "Search") }
                    IconButton(onClick = vm::toggleView) {
                        Icon(if (vm.viewMode == ViewMode.LIST) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, "Toggle view")
                    }
                }
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    val items = browserOverflowItems(
                        browsing = browsing,
                        hasLocation = loc != null,
                        backend = loc?.backend,
                        readOnly = readOnly,
                        showHidden = vm.settings.showHidden,
                        hasEntries = vm.browser.entries.isNotEmpty(),
                        hasNomedia = vm.browser.hasNomedia,
                        tab = vm.tab,
                        rootWrites = vm.settings.rootWrites,
                    )
                    BrowserOverflowMenuItems(items) { item ->
                        more = false
                        when (item) {
                            BrowserOverflowItem.NEW_FOLDER -> loc?.let { vm.dialog = Dialog.NewFolder(it) }
                            BrowserOverflowItem.SORT -> vm.dialog = Dialog.Sort
                            BrowserOverflowItem.HIDE_HIDDEN, BrowserOverflowItem.SHOW_HIDDEN -> vm.setShowHidden(!vm.settings.showHidden)
                            BrowserOverflowItem.SELECT_ALL -> vm.selectAll()
                            BrowserOverflowItem.ADD_NOMEDIA -> vm.setCurrentNomedia(true)
                            BrowserOverflowItem.REMOVE_NOMEDIA -> vm.setCurrentNomedia(false)
                            BrowserOverflowItem.REMOUNT_SYSTEM -> vm.dialog = Dialog.RemountSystem
                            BrowserOverflowItem.REFRESH -> vm.reload()
                            BrowserOverflowItem.SETTINGS -> vm.openScreen(Screen.SETTINGS)
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dialogs(vm: MainViewModel) {
    when (val d = vm.dialog) {
        null -> Unit
        is Dialog.ItemMenu -> {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { vm.dialog = null }, sheetState = sheet) {
                val e = d.entry
                val readOnly = (e.backend == Backend.ROOT && !vm.settings.rootWrites) || e.backend == Backend.ARCHIVE
                val list = if (d.context) contextActionsFor(e, readOnly)
                else actionsFor(e, readOnly, searchResult = vm.browser.locations.containsKey(e.path))
                ItemActionsContent(e, list) { a ->
                    vm.dialog = null
                    when (a) {
                        ItemAction.GO_TO_LOCATION -> vm.goToLocation(e)
                        ItemAction.OPEN -> if (d.context) vm.openEntry(e) else vm.open(e)
                        ItemAction.EXTRACT_HERE -> vm.extractHere(e)
                        ItemAction.EXTRACT_TO -> if (e.backend == Backend.ARCHIVE && !local.files.explorer.ui.browser.isArchive(e)) vm.requestExtract(null, listOf(e)) else vm.requestExtract(e)
                        ItemAction.COMPRESS -> vm.requestCompress(listOf(e))
                        ItemAction.MOVE -> vm.moveFromList(e)
                        ItemAction.DETAILS -> vm.properties(e)
                        ItemAction.INSTALL -> vm.openApk(e)
                        ItemAction.APP_INFO -> vm.showApkInfo(e)
                        ItemAction.BROWSE_APK -> vm.browseApk(e)
                        ItemAction.OPEN_WITH -> vm.openFile(e, chooser = true)
                        ItemAction.SHARE -> vm.share(listOf(e))
                        ItemAction.COPY -> vm.copy(listOf(e), false)
                        ItemAction.CUT -> vm.copy(listOf(e), true)
                        ItemAction.RENAME -> vm.dialog = Dialog.Rename(e)
                        ItemAction.ADD_NOMEDIA -> vm.setNomedia(e, true)
                        ItemAction.REMOVE_NOMEDIA -> vm.setNomedia(e, false)
                        ItemAction.PROPERTIES -> vm.properties(e)
                        ItemAction.DELETE -> vm.requestDelete(listOf(e))
                    }
                }
            }
        }
        is Dialog.Explainer -> PermissionExplainerDialog(
            d.kind, vm::explainerContinue, vm::explainerNotNow,
            settingsHint = if (d.settingsRoute) "Android won't ask again, so Continue opens File Manager's notification settings." else d.kind.settingsHint,
        )
        is Dialog.NewFolder -> NameDialog("New folder", "", "Create", { vm.validateName(it) }, vm::newFolder) { vm.dialog = null }
        is Dialog.Rename -> NameDialog("Rename", d.entry.name, "Rename", { vm.validateName(it, d.entry.name) }, { vm.rename(d.entry, it) }) { vm.dialog = null }
        is Dialog.Delete -> ConfirmDialog(
            title = if (d.entries.size == 1) "Delete \u201c${d.entries[0].name}\u201d?" else "Delete ${d.entries.size} items?",
            body = (if (d.entries.any { it.isDirectory }) "Folders are deleted with everything inside. " else "") + "This can't be undone.",
            confirm = "Delete", destructive = true, icon = Icons.Rounded.Delete,
            onConfirm = vm::confirmDelete, onDismiss = { vm.dialog = null },
        )
        is Dialog.Conflict -> ConflictDialog(d.names, vm::resolveConflict) { vm.dialog = null }
        is Dialog.Properties -> PropertiesDialog(d.ui) { vm.dialog = null }
        is Dialog.Password -> local.files.explorer.ui.archive.PasswordDialog(d.name, d.wrong, d.busy, vm::submitPassword) { vm.dialog = null }
        is Dialog.Extract -> local.files.explorer.ui.archive.ExtractDialog(
            title = if (d.entries == null) "Extract \u201c${local.files.explorer.fs.Backends.archive.session(d.root)?.displayName ?: "archive"}\u201d" else "Extract selected",
            hereLabel = d.hereLabel, folderName = d.folderName, count = d.entries?.size,
            onConfirm = { vm.confirmExtract(it) }, onDismiss = { vm.dialog = null },
            decodeXml = d.decodeXml, onDecodeXml = vm::setExtractDecode,
        )
        is Dialog.Compress -> local.files.explorer.ui.archive.CompressDialog(
            d.defaultName, d.entries.size, d.dest.title, nameExists = { n -> vm.browser.entries.any { it.name == n } },
            onConfirm = vm::confirmCompress, onDismiss = { vm.dialog = null },
        )
        Dialog.RootWritesWarning -> ConfirmDialog(
            title = "Allow changes as root?",
            body = "File Manager will be able to create, rename, move and delete anything in root locations, including /system and /data. " +
                "A wrong change can stop apps working or stop your phone from booting. Only turn this on if you know exactly what you're changing.",
            confirm = "I understand, allow", destructive = true, icon = Icons.Rounded.Warning,
            onConfirm = vm::confirmRootWrites, onDismiss = { vm.dialog = null },
        )
        is Dialog.RootMount -> ConfirmDialog(
            title = if (d.mount) "Mount ${d.volume.label}?" else "Unmount ${d.volume.label}?",
            body = if (d.mount) "Runs `sm mount ${d.volume.rootVolumeId}` as root."
            else "Runs `sm unmount ${d.volume.rootVolumeId}` as root. Apps using the drive will lose access and unfinished copies to it may be corrupted.",
            confirm = if (d.mount) "Mount" else "Unmount", destructive = !d.mount, icon = Icons.Rounded.Warning,
            onConfirm = vm::confirmRootMount, onDismiss = { vm.dialog = null },
        )
        Dialog.RemountSystem -> ConfirmDialog(
            title = "Remount /system read-write?",
            body = "This tries `mount -o rw,remount` on /system. On most phones running Android 10+ it will fail because the system partition is read-only by design " +
                "(dm-verity / dynamic partitions). If it works, changes can break OTA updates or prevent booting.",
            confirm = "Try remount", destructive = true, icon = Icons.Rounded.Warning,
            onConfirm = vm::remountSystem, onDismiss = { vm.dialog = null },
        )
        Dialog.Sort -> SortDialog(vm.sort, { vm.setSortOrder(it) }) { vm.dialog = null }
        is Dialog.Transfer -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text("Copy or move ${Format.items(d.items.size)}?") },
            text = { Text(if (d.items.size == 1) "\u201c${d.items[0].name}\u201d to ${d.destLabel}" else "To ${d.destLabel}") },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Cancel") } },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.confirmTransfer(move = false) }) { Text("Copy") }
                    TextButton(onClick = { vm.confirmTransfer(move = true) }) { Text("Move") }
                }
            },
        )
    }
}

@Composable
private fun SortDialog(current: SortOrder, onChange: (SortOrder) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort by") },
        text = {
            Column {
                SortField.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current.field == f, { onChange(current.copy(field = f)) })
                        Text(f.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                CheckRow("Descending", !current.ascending) { onChange(current.copy(ascending = !it)) }
                CheckRow("Folders first", current.foldersFirst) { onChange(current.copy(foldersFirst = it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApkSheetHost(vm: MainViewModel) {
    val s = vm.apkSheet ?: return
    val status by ApkInstaller.status.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = vm::closeApk, sheetState = sheet) {
        ApkInstallContent(
            info = s.info, loading = s.loading, error = s.error, status = status,
            canInstallUnknown = vm.canInstallUnknown, rootAvailable = vm.rootState.isGranted,
            onInstall = { vm.installApk(false) }, onInstallRoot = { vm.installApk(true) },
            onOpenUnknownSources = vm::openUnknownSources, onDismiss = vm::closeApk,
        )
    }
}
