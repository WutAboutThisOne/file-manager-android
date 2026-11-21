package local.files.explorer.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import local.files.explorer.core.Changelog
import local.files.explorer.core.FileType
import local.files.explorer.ui.common.LocalThumbnails
import local.files.explorer.ui.common.ThumbnailSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import local.files.explorer.R
import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.core.VolumeInfo
import local.files.explorer.core.VolumeKind
import local.files.explorer.core.VolumeState
import local.files.explorer.fs.OpProgress
import local.files.explorer.install.ApkInfo
import local.files.explorer.install.InstallStatus
import local.files.explorer.ops.OpKind
import local.files.explorer.ops.OpRequest
import local.files.explorer.ops.OpState
import local.files.explorer.ops.OpStatus
import local.files.explorer.root.RootState
import local.files.explorer.settings.AppSettings
import local.files.explorer.settings.ThemeMode
import local.files.explorer.settings.ViewMode
import local.files.explorer.ui.BrowserOverflowItem
import local.files.explorer.ui.BrowserOverflowMenuItems
import local.files.explorer.ui.Tab
import local.files.explorer.ui.browserOverflowItems
import local.files.explorer.ui.browser.BrowserActions
import local.files.explorer.ui.browser.BrowserContent
import local.files.explorer.ui.browser.BrowserUi
import local.files.explorer.ui.browser.ClipboardUi
import local.files.explorer.ui.browser.ConflictDialog
import local.files.explorer.ui.browser.ItemActionsContent
import local.files.explorer.ui.browser.StorageSummary
import local.files.explorer.ui.browser.actionsFor
import local.files.explorer.ui.common.PermissionContent
import local.files.explorer.ui.install.ApkInstallContent
import local.files.explorer.ui.ops.OperationCard
import local.files.explorer.ui.settings.AboutContent
import local.files.explorer.ui.settings.SettingsActions
import local.files.explorer.ui.settings.SettingsContent
import local.files.explorer.ui.theme.FileManagerTheme
import local.files.explorer.ui.volumes.SafFolder
import local.files.explorer.ui.volumes.VolumeActions
import local.files.explorer.ui.volumes.VolumesContent
import local.files.explorer.core.Category
import local.files.explorer.core.CategoryStats
import local.files.explorer.core.SizeFilter
import local.files.explorer.storage.ScanProgress
import local.files.explorer.ui.browser.SearchUi
import local.files.explorer.ui.browser.contextActionsFor
import local.files.explorer.ui.common.PermissionExplainerCard
import local.files.explorer.ui.common.PermissionKind
import local.files.explorer.ui.home.FileListContent
import local.files.explorer.ui.home.FileListActions
import local.files.explorer.ui.home.FileListUi
import local.files.explorer.ui.home.FolderRowUi
import local.files.explorer.ui.home.FolderSizesActions
import local.files.explorer.ui.home.FolderSizesContent
import local.files.explorer.ui.home.FolderSizesUi
import local.files.explorer.ui.home.DuplicatesContent
import local.files.explorer.ui.home.DuplicatesActions
import local.files.explorer.ui.home.DuplicatesUi
import local.files.explorer.ui.home.DuplicateGroupUi
import local.files.explorer.ui.home.HomeActions
import local.files.explorer.ui.home.HomeContent
import local.files.explorer.ui.home.HomeUi
import local.files.explorer.ui.home.ListKind
import local.files.explorer.ui.home.ScanUi
import local.files.explorer.ui.install.ExternalInstallContent
import local.files.explorer.ui.install.ExternalInstallUi
import local.files.explorer.ui.install.ExternalInstallActions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** Roborazzi screenshots at a 392x816dp phone (784x1632 px). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h816dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }

    @Before fun tz() { TimeZone.setDefault(TimeZone.getTimeZone("Europe/London")) }

    private fun t(day: Int, h: Int, m: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val root = "/storage/emulated/0"
    private fun d(name: String, n: Int, day: Int, nomedia: Boolean = false) =
        FileEntry(name, "$root/$name", Backend.LOCAL, true, lastModified = t(day, 9 + n % 10, 14), childCount = n, hasNomedia = nomedia)
    private fun f(name: String, size: Long, day: Int) =
        FileEntry(name, "$root/$name", Backend.LOCAL, false, size = size, lastModified = t(day, 18, 42))

    private val internal = listOf(
        d("DCIM", 3, 20), d("Documents", 14, 19), d("Download", 27, 21),
        d("Music", 118, 7), d("Telegram", 6, 15, nomedia = true),
        f("IMG_20261019_183012.jpg", 3_400_000, 19), f("IMG_20261020_071544.jpg", 2_900_000, 20),
        f("Signal-7.24.2.apk", 98_400_000, 20), f("boarding-pass.pdf", 182_000, 19),
        f("budget-2026.xlsx", 48_300, 14), f("notes.md", 2_100, 21),
        f("trip-video.mp4", 412_000_000, 12), f("backup.zip", 1_240_000_000, 3),
    )
    private val ui = BrowserUi(
        crumbs = listOf(Crumb("Internal storage", root)),
        entries = internal,
        nomediaBadges = setOf("$root/Telegram"),
        storage = StorageSummary("Internal storage", 81_300_000_000, 128_000_000_000),
    )

    /** Stand-in for real photo thumbnails: a little generated "sunset". */
    private val fakeThumbs = ThumbnailSource { e ->
        if (e.type != FileType.IMAGE) null else {
            val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val warm = e.name.hashCode() % 2 == 0
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, 120f,
                    if (warm) 0xFFFF9A62.toInt() else 0xFF6FA8FF.toInt(),
                    if (warm) 0xFF7B3FA0.toInt() else 0xFF1E3A70.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, 120f, 120f, p)
            c.drawCircle(84f, 46f, 16f, Paint().apply { color = 0xFFFFE6A8.toInt(); isAntiAlias = true })
            c.drawRect(0f, 86f, 120f, 120f, Paint().apply { color = 0xFF263238.toInt() })
            b.asImageBitmap()
        }
    }

    @Composable
    private fun Phone(dark: Boolean, title: String, tab: Tab?, back: Boolean = false, actions: Boolean = true, overflow: List<BrowserOverflowItem>? = null, body: @Composable () -> Unit) {
        FileManagerTheme(ThemeMode.SYSTEM, darkOverride = dark) {
            CompositionLocalProvider(LocalThumbnails provides fakeThumbs) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { if (back) IconButton({}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                        actions = {
                            if (actions) {
                                IconButton({}) { Icon(Icons.Rounded.Search, null) }
                                IconButton({}) { Icon(Icons.Rounded.GridView, null) }
                            }
                            Box {
                                IconButton({}) { Icon(Icons.Rounded.MoreVert, null) }
                                if (overflow != null) DropdownMenu(true, {}) { BrowserOverflowMenuItems(overflow) {} }
                            }
                        },
                    )
                    Box(Modifier.weight(1f)) { body() }
                    if (tab != null) NavigationBar {
                        Tab.entries.filter { it != Tab.ROOT }.forEach {
                            NavigationBarItem(it == tab, {}, { Icon(it.icon, null) }, label = { Text(it.label) })
                        }
                    }
                }
            }
            }
        }
    }

    @Composable
    private fun Scrim(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)), contentAlignment = Alignment.BottomCenter) { content() }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, name).absolutePath)
    }

    private val noop = object : BrowserActions {}

    @Test fun browserLight() = shot("browser-light.png") { Phone(false, "Internal storage", Tab.INTERNAL) { BrowserContent(ui, noop) } }
    @Test fun browserDark() = shot("browser-dark.png") { Phone(true, "Internal storage", Tab.INTERNAL) { BrowserContent(ui, noop) } }

    @Test fun browserGridDark() = shot("browser-grid-dark.png") {
        Phone(true, "Internal storage", Tab.INTERNAL) { BrowserContent(ui.copy(viewMode = ViewMode.GRID), noop) }
    }

    @Test fun browserSelectionPasteLight() = shot("browser-selection-light.png") {
        val sub = ui.copy(
            crumbs = listOf(Crumb("Internal storage", root), Crumb("Download", "$root/Download")),
            storage = null,
            selection = setOf("$root/IMG_20261019_183012.jpg", "$root/boarding-pass.pdf", "$root/notes.md"),
        )
        Phone(false, "3 selected", Tab.INTERNAL, back = true) { BrowserContent(sub.copy(entries = internal.drop(3)), noop) }
    }

    @Test fun browserPasteBarDark() = shot("browser-paste-dark.png") {
        val sub = ui.copy(
            crumbs = listOf(Crumb("Internal storage", root), Crumb("Documents", "$root/Documents")),
            storage = null, clipboard = ClipboardUi(3, cut = true),
        )
        Phone(true, "Documents", Tab.INTERNAL, back = true) { BrowserContent(sub.copy(entries = internal.drop(1)), noop) }
    }

    private val rootEntries = listOf(
        FileEntry("apex", "/apex", Backend.ROOT, true, permissions = "drwxr-xr-x", owner = "root", group = "root"),
        FileEntry("bin", "/bin", Backend.ROOT, true, isSymlink = true, permissions = "lrwxrwxrwx", owner = "root", group = "root"),
        FileEntry("data", "/data", Backend.ROOT, true, permissions = "drwxrwx--x", owner = "system", group = "system"),
        FileEntry("dev", "/dev", Backend.ROOT, true, permissions = "drwxr-xr-x", owner = "root", group = "root"),
        FileEntry("odm", "/odm", Backend.ROOT, true, permissions = "drwxr-xr-x", owner = "root", group = "root"),
        FileEntry("proc", "/proc", Backend.ROOT, true, permissions = "dr-xr-xr-x", owner = "root", group = "root"),
        FileEntry("system", "/system", Backend.ROOT, true, permissions = "drwxr-xr-x", owner = "root", group = "root"),
        FileEntry("vendor", "/vendor", Backend.ROOT, true, permissions = "drwxr-xr-x", owner = "root", group = "shell"),
        FileEntry("init", "/init", Backend.ROOT, false, size = 2_412_000, permissions = "-rwxr-x---", owner = "root", group = "shell"),
        FileEntry("default.prop", "/default.prop", Backend.ROOT, false, size = 1_204, permissions = "-rw-r--r--", owner = "root", group = "root"),
    )

    @Test fun rootBrowserDark() = shot("root-browser-dark.png") {
        Phone(true, "Root", null) { BrowserContent(BrowserUi(crumbs = listOf(Crumb("/", "/")), entries = rootEntries, backend = Backend.ROOT, readOnly = true), noop) }
    }

    @Test fun itemMenuLight() = shot("item-menu-light.png") {
        val e = internal.first { it.name == "Telegram" }
        Phone(false, "Internal storage", Tab.INTERNAL) {
            Box {
                BrowserContent(ui, noop)
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    private val settings = AppSettings(theme = ThemeMode.SYSTEM, respectNomedia = true, showHidden = false)
    private val sActions = object : SettingsActions {}

    @Test fun settingsLight() = shot("settings-light.png") {
        Phone(false, "Settings", null, back = true, actions = false) { SettingsContent(settings, RootState.NOT_AVAILABLE, "0.2.0", sActions) }
    }

    @Test fun settingsDark() = shot("settings-dark.png") {
        Phone(true, "Settings", null, back = true, actions = false) { SettingsContent(settings.copy(theme = ThemeMode.DARK), RootState.NOT_AVAILABLE, "0.2.0", sActions) }
    }

    private val changelog = Changelog.parse(
        listOf(File("src/main/assets/CHANGELOG.md"), File("app/src/main/assets/CHANGELOG.md"))
            .first { it.exists() }
            .readText(),
    )

    @Test fun aboutLight() = shot("about-light.png") {
        Phone(false, "About", null, back = true, actions = false) { AboutContent("0.4.4", changelog, 9) }
    }

    @Test fun aboutDark() = shot("about-dark.png") {
        Phone(true, "About", null, back = true, actions = false) { AboutContent("0.4.4", changelog, 9) }
    }

    private fun iconBitmap(): Bitmap? = runCatching {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dr = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!
        Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { b -> dr.setBounds(0, 0, 192, 192); dr.draw(Canvas(b)) }
    }.getOrNull()

    private val apk = ApkInfo(
        fileName = "Signal-7.24.2.apk", filePath = "$root/Download/Signal-7.24.2.apk",
        label = "Signal", packageName = "org.thoughtcrime.securesms", versionName = "7.24.2", versionCode = 151_402,
        minSdk = 26, targetSdk = 34, sizeBytes = 98_400_000,
        permissionCount = 68,
    )

    private fun apkSheet(name: String, dark: Boolean, info: ApkInfo, canInstall: Boolean, root: Boolean, status: InstallStatus = InstallStatus.Idle) = shot(name) {
        Phone(dark, "Download", Tab.INTERNAL, back = true) {
            Box {
                BrowserContent(ui.copy(crumbs = listOf(Crumb("Downloads", "$root/Download")), storage = null), noop)
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 28.dp)) {
                            ApkInstallContent(info, false, null, status, canInstall, root, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
    }

    @Test fun apkInstallLight() = apkSheet("apk-install-light.png", false, apk, canInstall = true, root = false)
    @Test fun apkInstallDark() = apkSheet("apk-install-dark.png", true, apk, canInstall = true, root = false)
    @Test fun apkNeedsPermissionLight() = apkSheet("apk-install-permission-light.png", false, apk, canInstall = false, root = false)
    @Test fun apkBundleRootDark() = apkSheet(
        "apk-bundle-root-dark.png", true,
        apk.copy(label = "File Manager", packageName = "local.files.explorer", fileName = "filemanager.apks", versionName = "0.2.0", versionCode = 2,
            splitCount = 4, sizeBytes = 6_200_000, permissionCount = 9, icon = iconBitmap()),
        canInstall = true, root = true,
    )
    @Test fun apkInstallingLight() = apkSheet("apk-installing-light.png", false, apk, canInstall = true, root = false, status = InstallStatus.Installing(0.62f))

    private val copyReq = OpRequest(OpKind.COPY, internal.take(3) + internal.drop(12).take(2), destLabel = "SanDisk USB", policy = ConflictPolicy.KEEP_BOTH, id = 1)

    private fun progress(name: String, dark: Boolean, st: OpState) = shot(name) {
        Phone(dark, "SanDisk USB", null, back = true) {
            Column {
                Box(Modifier.weight(1f)) {
                    BrowserContent(ui.copy(crumbs = listOf(Crumb("SanDisk USB", "/storage/1A2B-3C4D")), entries = internal.take(6), storage = null), noop)
                }
                OperationCard(st, {}, {})
                NavigationBar {
                    Tab.entries.filter { it != Tab.ROOT }.forEach {
                        NavigationBarItem(it == Tab.STORAGE, {}, { Icon(it.icon, null) }, label = { Text(it.label) })
                    }
                }
            }
        }
    }

    @Test fun copyProgressLight() = progress(
        "copy-progress-light.png", false,
        OpState(copyReq, OpProgress(1_043_000_000, 1_678_400_000, 41, 77, "trip-video.mp4"), OpStatus.RUNNING),
    )
    @Test fun copyProgressDark() = progress(
        "copy-progress-dark.png", true,
        OpState(copyReq, OpProgress(1_043_000_000, 1_678_400_000, 41, 77, "trip-video.mp4"), OpStatus.RUNNING),
    )
    @Test fun copyDoneDark() = progress(
        "copy-done-dark.png", true,
        OpState(copyReq, OpProgress(1_678_400_000, 1_678_400_000, 77, 77), OpStatus.DONE, "Copied 5 items, skipped 1"),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun conflictDialogLight() {
        compose.setContent {
            Phone(false, "Download", Tab.INTERNAL, back = true) {
                Box { BrowserContent(ui.copy(storage = null), noop); ConflictDialog(listOf("IMG_20261019_183012.jpg", "notes.md", "Documents"), {}, {}) }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "conflict-dialog-light.png").absolutePath)
    }

    private val volumes = listOf(
        VolumeInfo("primary", "Internal storage", VolumeKind.INTERNAL, root, VolumeState.MOUNTED, 128_000_000_000, 46_700_000_000, directAccess = true),
        VolumeInfo("1A2B-3C4D", "SanDisk SD card", VolumeKind.SD_CARD, "/storage/1A2B-3C4D", VolumeState.MOUNTED, 256_000_000_000, 201_500_000_000, "1A2B-3C4D", directAccess = true, rootVolumeId = "public:179,65"),
        VolumeInfo("9C33-12FE", "Samsung USB drive", VolumeKind.USB, "/storage/9C33-12FE", VolumeState.MOUNTED, 64_000_000_000, 12_100_000_000, "9C33-12FE", directAccess = false, rootVolumeId = "public:8,1"),
        VolumeInfo("public:8,17", "Removable volume (8,17)", VolumeKind.USB, null, VolumeState.UNMOUNTED, rootVolumeId = "public:8,17"),
    )
    private val saf = listOf(SafFolder("Internal \u203A Documents \u203A Taxes", "content://x/1"))

    @Test fun volumesLight() = shot("volumes-light.png") {
        Phone(false, "Storage", Tab.STORAGE, actions = false) { VolumesContent(volumes.dropLast(1), false, saf, object : VolumeActions {}) }
    }

    @Test fun volumesDark() = shot("volumes-dark.png") {
        Phone(true, "Storage", Tab.STORAGE, actions = false) { VolumesContent(volumes.dropLast(1), false, saf, object : VolumeActions {}) }
    }

    @Test fun volumesRootedDark() = shot("volumes-rooted-dark.png") {
        Phone(true, "Storage", Tab.STORAGE, actions = false) { VolumesContent(volumes, true, emptyList(), object : VolumeActions {}) }
    }

    @Test fun permissionLight() = shot("onboarding-permission-light.png") {
        Phone(false, "Internal storage", Tab.INTERNAL, actions = false) { PermissionContent(false, {}, {}) }
    }


    // ------------------------------------------------------------------ 0.2.0: Home & friends

    private fun g(path: String, size: Long, day: Int) =
        FileEntry(path.substringAfterLast('/'), "$root/$path", Backend.LOCAL, false, size = size, lastModified = t(day, 12 + day % 8, 5 + day))

    private val homeUi = HomeUi(
        stats = mapOf(
            Category.IMAGES to CategoryStats(4_812, 9_640_000_000),
            Category.VIDEOS to CategoryStats(213, 21_300_000_000),
            Category.AUDIO to CategoryStats(1_096, 6_120_000_000),
            Category.DOCUMENTS to CategoryStats(387, 742_000_000),
            Category.APKS to CategoryStats(12, 1_180_000_000),
            Category.ARCHIVES to CategoryStats(31, 4_410_000_000),
            Category.DOWNLOADS to CategoryStats(164, 3_920_000_000),
        ),
        storage = listOf(StorageSummary("Internal storage", 81_300_000_000, 128_000_000_000)),
        largest = listOf(g("Movies/Interstellar.mkv", 4_210_000_000, 2), g("Backups/phone-2026-09.tar", 2_870_000_000, 1), g("DCIM/Camera/VID_20260815_164210.mp4", 1_930_000_000, 15)),
        topFolders = listOf(
            FolderRowUi("Movies", "$root/Movies", 18_400_000_000, 64), FolderRowUi("DCIM", "$root/DCIM", 14_900_000_000, 5_120),
            FolderRowUi("Android", "$root/Android", 9_800_000_000, 22_400),
        ),
        scan = ScanUi(complete = true),
    )
    private val homeNoop = object : HomeActions {}

    @Composable
    private fun HomePhone(dark: Boolean, title: String, back: Boolean = false, body: @Composable () -> Unit) =
        Phone(dark, title, Tab.HOME, back = back, actions = false, body = body)

    @Test fun homeLight() = shot("home-light.png") { HomePhone(false, "File Manager") { HomeContent(homeUi, homeNoop) } }
    @Test fun homeDark() = shot("home-dark.png") { HomePhone(true, "File Manager") { HomeContent(homeUi, homeNoop) } }

    @Test fun homeScanningLight() = shot("home-scanning-light.png") {
        HomePhone(false, "File Manager") {
            HomeContent(homeUi.copy(stats = emptyMap(), largest = emptyList(), topFolders = emptyList(),
                scan = ScanUi(scanning = true, progress = ScanProgress(18_204, 1_920, 23_400_000_000))), homeNoop)
        }
    }

    private val images = listOf(
        g("DCIM/Camera/IMG_20261020_071544.jpg", 2_900_000, 20), g("DCIM/Camera/IMG_20261019_183012.jpg", 3_400_000, 19),
        g("Pictures/Screenshots/Screenshot_20261018-221503.png", 812_000, 18), g("Download/menu-photo.jpg", 1_204_000, 17),
        g("Pictures/WhatsApp/IMG-20261016-WA0007.jpg", 186_000, 16), g("DCIM/Camera/IMG_20261014_120932.jpg", 4_100_000, 14),
        g("Pictures/Wallpapers/aurora.png", 6_820_000, 11), g("Documents/scans/passport.jpg", 1_480_000, 9),
    )
    private fun loc(list: List<FileEntry>) = list.associate { it.path to "Internal storage/" + it.path.removePrefix("$root/").substringBeforeLast('/') }

    @Test fun categoryImagesLight() = shot("category-images-light.png") {
        HomePhone(false, "Images", back = true) {
            FileListContent(FileListUi(ListKind.CATEGORY, images, loc(images), totalBytes = images.sumOf { it.size }, scan = ScanUi(complete = true)), object : FileListActions {})
        }
    }

    private val largest = listOf(
        g("Movies/Interstellar.mkv", 4_210_000_000, 2), g("Backups/phone-2026-09.tar", 2_870_000_000, 1),
        g("DCIM/Camera/VID_20260815_164210.mp4", 1_930_000_000, 15), g("Download/ubuntu-24.04-desktop.iso", 1_610_000_000, 8),
        g("Android/obb/com.game.big/main.obb", 1_240_000_000, 5), g("Movies/trip-video.mp4", 412_000_000, 12),
        g("Download/Signal-7.24.2.apk", 98_400_000, 20), g("Music/Albums/live-set.flac", 71_200_000, 4),
    )

    @Test fun largestFilesDark() = shot("largest-files-dark.png") {
        HomePhone(true, "Largest files", back = true) {
            FileListContent(FileListUi(ListKind.LARGEST, largest, loc(largest), sizeFilter = SizeFilter.OVER_10MB, totalBytes = largest.sumOf { it.size }, scan = ScanUi(complete = true)), object : FileListActions {})
        }
    }

    @Test fun largestFilesLight() = shot("largest-files-light.png") {
        val big = largest.filter { it.size >= SizeFilter.OVER_1GB.minBytes }
        HomePhone(false, "Largest files", back = true) {
            FileListContent(FileListUi(ListKind.LARGEST, big, loc(big), sizeFilter = SizeFilter.OVER_1GB, totalBytes = big.sumOf { it.size }, scan = ScanUi(complete = true)), object : FileListActions {})
        }
    }

    private val folderLooseFiles = listOf(
        g("phone-backup.tar", 980_000_000, 2), g("notes.txt", 12_400, 40), g("todo.md", 3_200, 41),
    )
    private val folders = FolderSizesUi(
        title = "Internal storage", path = root, totalBytes = 81_300_000_000, directBytes = 1_420_000_000, directFiles = 9,
        files = folderLooseFiles,
        folders = listOf(
            FolderRowUi("Movies", "$root/Movies", 18_400_000_000, 64), FolderRowUi("DCIM", "$root/DCIM", 14_900_000_000, 5_120),
            FolderRowUi("Android", "$root/Android", 9_800_000_000, 22_400), FolderRowUi("Music", "$root/Music", 6_100_000_000, 1_096),
            FolderRowUi("Download", "$root/Download", 3_920_000_000, 164), FolderRowUi("Pictures", "$root/Pictures", 2_280_000_000, 2_010),
            FolderRowUi("Telegram", "$root/Telegram", 1_150_000_000, 880), FolderRowUi("Documents", "$root/Documents", 742_000_000, 387),
        ),
    )

    @Test fun folderSizesLight() = shot("folder-sizes-light.png") {
        HomePhone(false, "Folder sizes", back = true) { FolderSizesContent(folders.copy(scan = ScanUi(complete = true)), object : FolderSizesActions {}) }
    }

    @Test fun folderSizesScanningDark() = shot("folder-sizes-scanning-dark.png") {
        HomePhone(true, "Folder sizes", back = true) {
            FolderSizesContent(folders.copy(scan = ScanUi(scanning = true, progress = ScanProgress(41_388, 3_870, 52_100_000_000))), object : FolderSizesActions {})
        }
    }

    @Test fun contextMenuLight() = shot("context-menu-light.png") {
        val e = largest[3]
        HomePhone(false, "Largest files", back = true) {
            Box {
                FileListContent(FileListUi(ListKind.LARGEST, largest, loc(largest), totalBytes = largest.sumOf { it.size }), object : FileListActions {})
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, contextActionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    private fun explainer(name: String, dark: Boolean, kind: PermissionKind, under: @Composable () -> Unit) = shot(name) {
        Phone(dark, "File Manager", Tab.HOME, actions = false) {
            Box {
                under()
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    PermissionExplainerCard(kind, {}, {}, Modifier.padding(horizontal = 24.dp).fillMaxWidth())
                }
            }
        }
    }

    @Test fun explainerAllFilesLight() = explainer("permission-explainer-all-files-light.png", false, PermissionKind.ALL_FILES) { PermissionContent(false, {}, {}) }
    @Test fun explainerInstallDark() = explainer("permission-explainer-install-dark.png", true, PermissionKind.INSTALL_APPS) {
        FileListContent(FileListUi(ListKind.CATEGORY, largest.takeLast(2)), object : FileListActions {})
    }
    @Test fun explainerNotificationsLight() = explainer("permission-explainer-notifications-light.png", false, PermissionKind.NOTIFICATIONS) { BrowserContent(ui, noop) }

    private val hits = listOf(
        g("Documents/Taxes/2025/invoice-0142.pdf", 212_000, 3), g("Documents/Taxes/2025/invoice-0143.pdf", 198_000, 4),
        g("Download/invoice-amazon-oct.pdf", 88_000, 18), g("Documents/Work/Clients/Acme/invoice-template.docx", 41_000, 11),
        g("Documents/Taxes/2026/invoice-0201.pdf", 205_000, 19), g("Telegram/Telegram Documents/invoice_final(2).pdf", 133_000, 13),
    )

    @Test fun searchResultsLight() = shot("search-results-light.png") {
        val sui = ui.copy(
            crumbs = listOf(Crumb("Internal storage", root)), storage = null, entries = hits, searchQuery = "invoice",
            locations = loc(hits), search = SearchUi(recursive = true, running = true, found = hits.size, limitHit = false, limit = 1000),
            highlight = null,
        )
        Phone(false, "invoice", Tab.INTERNAL, back = true, actions = false) { BrowserContent(sui, noop) }
    }

    @Test fun globalSearchDark() = shot("search-global-dark.png") {
        HomePhone(true, "invoice", back = true) {
            FileListContent(
                FileListUi(ListKind.SEARCH, hits, loc(hits), query = "invoice",
                    search = SearchUi(recursive = true, running = false, found = hits.size, limitHit = false, limit = 1000, supportsRecursive = false)),
                object : FileListActions {},
            )
        }
    }

    @Test fun searchLimitThisFolderDark() = shot("search-limit-dark.png") {
        val many = (1..9).map { g("Pictures/IMG_${2026_1000 + it}.jpg", 1_000_000L + it * 37_000, it) }
        val sui = ui.copy(
            crumbs = listOf(Crumb("Internal storage", root), Crumb("Pictures", "$root/Pictures")), storage = null, entries = many, searchQuery = "img",
            locations = loc(many), search = SearchUi(recursive = true, running = false, found = 1000, limitHit = true, limit = 1000),
            highlight = many[2].path,
        )
        Phone(true, "img", Tab.INTERNAL, back = true, actions = false) { BrowserContent(sui, noop) }
    }


    // ------------------------------------------------------------------ 0.2.1: Open with File Manager

    /** Stand-in for the app that shared the APK (the install activity is translucent over it). */
    @Composable
    private fun SenderBehind(dark: Boolean, body: @Composable () -> Unit) {
        FileManagerTheme(ThemeMode.SYSTEM, darkOverride = dark) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        TopAppBar(title = { Text("Downloads") })
                        BrowserContent(ui.copy(crumbs = emptyList(), storage = null, entries = internal.drop(5)), noop)
                    }
                    body()
                }
            }
        }
    }

    private val sharedApk = ApkInfo(
        fileName = "Signal-7.24.2.apk", filePath = "/data/user/0/local.files.explorer/cache/incoming/x/Signal-7.24.2.apk",
        label = "Signal", packageName = "org.thoughtcrime.securesms", versionName = "7.24.2", versionCode = 151_402,
        minSdk = 26, targetSdk = 34, sizeBytes = 98_400_000, permissionCount = 68,
    )

    @Test fun externalInstallLight() = shot("external-install-light.png") {
        SenderBehind(false) {
            ExternalInstallContent(ExternalInstallUi("Signal-7.24.2.apk", sharedApk, loading = false, canInstallUnknown = true), object : ExternalInstallActions {})
        }
    }

    @Test fun externalInstallDark() = shot("external-install-dark.png") {
        SenderBehind(true) {
            ExternalInstallContent(
                ExternalInstallUi("FileManager.apks", sharedApk.copy(label = "File Manager", packageName = "local.files.explorer", versionName = "0.2.1", versionCode = 3,
                    splitCount = 3, sizeBytes = 6_400_000, permissionCount = 9, icon = iconBitmap()), loading = false, canInstallUnknown = true),
                object : ExternalInstallActions {},
            )
        }
    }

    @Test fun externalInstallExplainerLight() = shot("external-install-explainer-light.png") {
        SenderBehind(false) {
            ExternalInstallContent(ExternalInstallUi("Signal-7.24.2.apk", sharedApk, loading = false, explainer = true), object : ExternalInstallActions {})
        }
    }

    @Test fun externalInstallNeedsPermissionDark() = shot("external-install-permission-dark.png") {
        SenderBehind(true) {
            ExternalInstallContent(ExternalInstallUi("Signal-7.24.2.apk", sharedApk, loading = false, canInstallUnknown = false), object : ExternalInstallActions {})
        }
    }

    @Test fun launcherIcon() = shot("launcher-icon.png") {
        Box(Modifier.size(240.dp).background(Color(0xFFEFEFF4)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).clip(RoundedCornerShape(48.dp))) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
        }
    }

    // ---- 0.3.0 archives --------------------------------------------------------

    private val zipRoot = "$root/Download/holiday-photos.zip!"
    private fun ae(name: String, dir: Boolean, size: Long = 0, packed: Long? = null, n: Int? = null, day: Int = 18) =
        FileEntry(name, "$zipRoot/$name", Backend.ARCHIVE, dir, size = size, lastModified = t(day, 11, 5), childCount = n, compressedSize = packed)

    private val archiveEntries = listOf(
        ae("Day 1 – Lisbon", true, n = 48), ae("Day 2 – Sintra", true, n = 31), ae("Videos", true, n = 4),
        ae("IMG_20261012_101544.jpg", false, 4_210_000, 4_160_000), ae("IMG_20261012_114020.jpg", false, 3_870_000, 3_820_000),
        ae("itinerary.pdf", false, 412_000, 301_000), ae("Café Ñandú – receipts.txt", false, 18_400, 5_100),
        ae("route.gpx", false, 2_340_000, 410_000), ae("sample-0.3.0-release.apk", false, 1_520_000, 1_440_000),
    )
    private val archiveUi = BrowserUi(
        crumbs = listOf(Crumb("Internal storage", root), Crumb("Download", "$root/Download"), Crumb("holiday-photos.zip", zipRoot)),
        entries = archiveEntries, backend = Backend.ARCHIVE, readOnly = true,
        archive = local.files.explorer.ui.archive.ArchiveUi(
            "holiday-photos.zip", local.files.explorer.archive.ArchiveFormat.ZIP, files = 92, totalBytes = 1_904_000_000,
            archiveBytes = 1_712_000_000, encrypted = false, unlocked = false,
        ),
    )

    @Test fun archiveBrowserLight() = shot("archive-browser-light.png") {
        Phone(false, "holiday-photos.zip", Tab.INTERNAL, back = true) { BrowserContent(archiveUi, noop) }
    }

    @Test fun archiveBrowserDark() = shot("archive-browser-dark.png") {
        Phone(true, "holiday-photos.zip", Tab.INTERNAL, back = true) {
            BrowserContent(
                archiveUi.copy(
                    crumbs = archiveUi.crumbs + Crumb("Day 1 – Lisbon", "$zipRoot/Day 1 – Lisbon"),
                    entries = (1..9).map { ae("IMG_2026101${it % 3}_0${it}1544.jpg", false, 3_000_000L + it * 211_000, 2_950_000L + it * 200_000) },
                    archive = archiveUi.archive!!.copy(name = "holiday-photos.7z", format = local.files.explorer.archive.ArchiveFormat.SEVEN_Z, encrypted = true, unlocked = true, skippedUnsafe = 1),
                ),
                noop,
            )
        }
    }

    @Test fun archiveItemMenuLight() = shot("archive-item-menu-light.png") {
        val e = archiveEntries.last()
        Phone(false, "holiday-photos.zip", Tab.INTERNAL, back = true) {
            Box {
                BrowserContent(archiveUi, noop)
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, true)) {} }
                    }
                }
            }
        }
    }

    @Test fun archiveFileMenuDark() = shot("archive-file-menu-dark.png") {
        val e = internal.first { it.name == "backup.zip" }
        Phone(true, "Internal storage", Tab.INTERNAL) {
            Box {
                BrowserContent(ui, noop)
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    /** Dialog drawn inline over a scrim, exactly as FileManagerDialog lays it out inside its window. */
    private fun dialogShot(name: String, dark: Boolean, under: BrowserUi, title: String, dialog: @Composable () -> Unit) = shot(name) {
        Phone(dark, title, Tab.INTERNAL, back = true) {
            Box {
                BrowserContent(under, noop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(local.files.explorer.ui.archive.LocalInlineDialogs provides true) { dialog() }
                }
            }
        }
    }

    @Test fun extractDialogLight() = dialogShot("extract-dialog-light.png", false, archiveUi, "holiday-photos.zip") {
        local.files.explorer.ui.archive.ExtractDialog("Extract \u201choliday-photos.zip\u201d", "Download", "holiday-photos", null, {}, {})
    }

    @Test fun compressDialogDark() = dialogShot("compress-dialog-dark.png", true, ui.copy(storage = null, selection = setOf("$root/DCIM", "$root/Documents", "$root/notes.md")), "3 selected") {
        local.files.explorer.ui.archive.CompressDialog("Internal storage", 3, "Internal storage", { false }, {}, {})
    }

    @Test fun passwordPromptLight() = dialogShot("archive-password-light.png", false, archiveUi.copy(entries = emptyList(), error = "This archive is password protected."), "secrets.7z") {
        local.files.explorer.ui.archive.PasswordDialog("secrets.7z", wrong = true, busy = false, onSubmit = {}, onDismiss = {})
    }

    private val extractReq = OpRequest(OpKind.EXTRACT, archiveEntries, destLabel = "holiday-photos", id = 2)
    private val compressReq = OpRequest(
        OpKind.COMPRESS, internal.take(3), destLabel = "Internal storage", id = 3,
        compress = local.files.explorer.archive.CompressSpec("Internal storage.7z", local.files.explorer.archive.ArchiveFormat.SEVEN_Z),
    )

    @Test fun extractProgressLight() = progress(
        "extract-progress-light.png", false,
        OpState(extractReq, OpProgress(612_000_000, 1_904_000_000, 31, 92, "IMG_20261012_114020.jpg"), OpStatus.RUNNING),
    )
    @Test fun compressProgressDark() = progress(
        "compress-progress-dark.png", true,
        OpState(compressReq, OpProgress(212_000_000, 845_000_000, 120, 410, "IMG_0042.jpg"), OpStatus.RUNNING),
    )

    // ---- 0.4.0 APK contents & info ------------------------------------------------

    private val folioApk: File get() = File(javaClass.getResource("/apk/folio-0.3.0.apk")!!.toURI())

    private fun apkInfoUi(): local.files.explorer.ui.apk.ApkInfoUi {
        val d = local.files.explorer.apk.ApkParser.parse(folioApk, "sample-0.3.0-release.apk")
        // The real APK's icon is an adaptive vector; on a phone the system renders it. Here: the launcher artwork.
        val icon = d.iconBytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } ?: iconBitmap()
        return local.files.explorer.ui.apk.ApkInfoUi(d.fileName, loading = false, details = d, icon = icon?.asImageBitmap())
    }

    @Test fun apkInfoLight() = shot("apk-info-light.png") {
        Phone(false, "App info", null, back = true, actions = false) { local.files.explorer.ui.apk.ApkInfoContent(apkInfoUi(), object : local.files.explorer.ui.apk.ApkInfoActions {}) }
    }

    @Test fun apkInfoDark() = shot("apk-info-dark.png") {
        Phone(true, "App info", null, back = true, actions = false) { local.files.explorer.ui.apk.ApkInfoContent(apkInfoUi(), object : local.files.explorer.ui.apk.ApkInfoActions {}) }
    }

    private fun apkBrowserUi(dir: String): BrowserUi {
        val archive = local.files.explorer.fs.ArchiveBackend()
        val r = archive.register(folioApk, local.files.explorer.archive.ArchiveFormat.ZIP, "sample-0.3.0-release.apk")
        val s = archive.session(r)!!
        val path = if (dir.isEmpty()) r else "$r/$dir"
        val entries = archive.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val tree = s.tree()
        val crumbs = listOf(Crumb("Internal storage", root), Crumb("Download", "$root/Download"), Crumb("sample-0.3.0-release.apk", r)) +
            (if (dir.isEmpty()) emptyList() else listOf(Crumb(dir, path)))
        return BrowserUi(
            crumbs = crumbs, entries = entries, backend = Backend.ARCHIVE, readOnly = true,
            archive = local.files.explorer.ui.archive.ArchiveUi(
                "sample-0.3.0-release.apk", local.files.explorer.archive.ArchiveFormat.ZIP, files = tree.fileCount, totalBytes = tree.totalSize,
                archiveBytes = folioApk.length(), encrypted = false, unlocked = false, apk = local.files.explorer.ui.archive.ArchiveUi.ApkKind.APK,
            ),
        )
    }

    @Test fun apkBrowserLight() = shot("apk-browser-light.png") {
        Phone(false, "sample-0.3.0-release.apk", Tab.INTERNAL, back = true) { BrowserContent(apkBrowserUi(""), noop) }
    }

    @Test fun apkBrowserDark() = shot("apk-browser-dark.png") {
        Phone(true, "sample-0.3.0-release.apk", Tab.INTERNAL, back = true) { BrowserContent(apkBrowserUi(""), noop) }
    }

    @Test fun apkExtractDialogDark() = dialogShot("apk-extract-dialog-dark.png", true, apkBrowserUi(""), "sample-0.3.0-release.apk") {
        local.files.explorer.ui.archive.ExtractDialog("Extract \u201csample-0.3.0-release.apk\u201d", "Download", "sample-0.3.0-release", null, {}, {}, decodeXml = true)
    }

    @Test fun apkFileMenuLight() = shot("apk-file-menu-light.png") {
        val e = FileEntry("sample-0.3.0-release.apk", "$root/Download/sample-0.3.0-release.apk", Backend.LOCAL, false, size = 1_700_850, lastModified = t(26, 21, 40))
        Phone(false, "Download", Tab.INTERNAL) {
            Box {
                BrowserContent(ui, noop)
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, false)) {} }
                    }
                }
            }
        }
    }


    // ---- 0.4.3 toolbar .nomedia --------------------------------------------------------

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun browserToolbarNomediaDark() {
        val telegram = listOf(
            d("Video", 4, 15),
            f("IMG_20261015_142201.jpg", 2_400_000, 15),
            f("video_2026-10-15.mp4", 84_000_000, 15),
        )
        val folderUi = ui.copy(
            crumbs = listOf(Crumb("Internal storage", root), Crumb("Download", "$root/Download"), Crumb("Telegram", "$root/Download/Telegram")),
            entries = telegram,
            nomediaBadges = emptySet(),
            storage = null,
        )
        val overflow = browserOverflowItems(
            browsing = true, hasLocation = true, backend = Backend.LOCAL, readOnly = false,
            showHidden = false, hasEntries = true, hasNomedia = false, tab = Tab.INTERNAL, rootWrites = false,
        )
        compose.setContent {
            Phone(true, "Telegram", Tab.INTERNAL, back = true, overflow = overflow) { BrowserContent(folderUi, noop) }
        }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "browser-toolbar-nomedia-dark.png").absolutePath)
    }

    // ---- 0.4.2 folder-size files, go-to, home storage --------------------------------

    @Test fun folderSizesFilesLight() = shot("folder-sizes-files-light.png") {
        // Fewer folders so the loose files section is visible without scrolling.
        val compact = folders.copy(
            folders = folders.folders.take(3),
            scan = ScanUi(complete = true),
        )
        HomePhone(false, "Folder sizes", back = true) {
            FolderSizesContent(compact, object : FolderSizesActions {})
        }
    }

    @Test fun folderSizesFilesDark() = shot("folder-sizes-files-dark.png") {
        val compact = folders.copy(
            folders = folders.folders.take(2),
            scan = ScanUi(complete = true),
        )
        HomePhone(true, "Folder sizes", back = true) {
            FolderSizesContent(compact, object : FolderSizesActions {})
        }
    }

    @Test fun searchGoToMenuDark() = shot("search-go-to-menu-dark.png") {
        val e = hits[0]
        HomePhone(true, "invoice", back = true) {
            Box {
                FileListContent(
                    FileListUi(ListKind.SEARCH, hits, loc(hits), query = "invoice",
                        search = SearchUi(recursive = true, running = false, found = hits.size, limitHit = false, limit = 1000, supportsRecursive = false)),
                    object : FileListActions {},
                )
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, contextActionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    // ---- 0.4.1 folder actions, .nomedia, duplicates --------------------------------

    @Test fun folderSizesSelectionDark() = shot("folder-sizes-selection-dark.png") {
        val sel = setOf("$root/Movies", "$root/DCIM")
        HomePhone(true, "2 selected", back = true) {
            FolderSizesContent(folders.copy(scan = ScanUi(complete = true), selection = sel), object : FolderSizesActions {})
        }
    }

    @Test fun folderSizesMenuLight() = shot("folder-sizes-menu-light.png") {
        val e = FileEntry("Movies", "$root/Movies", Backend.LOCAL, true, size = 18_400_000_000, hasNomedia = false, childCount = 64)
        HomePhone(false, "Folder sizes", back = true) {
            Box {
                FolderSizesContent(folders.copy(scan = ScanUi(complete = true)), object : FolderSizesActions {})
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    @Test fun folderNomediaMenuDark() = shot("folder-nomedia-menu-dark.png") {
        val e = FileEntry("Telegram", "$root/Telegram", Backend.LOCAL, true, size = 1_150_000_000, hasNomedia = true, childCount = 880)
        HomePhone(true, "Folder sizes", back = true) {
            Box {
                FolderSizesContent(
                    folders.copy(
                        scan = ScanUi(complete = true),
                        folders = folders.folders.map { if (it.name == "Telegram") it.copy(hasNomedia = true) else it },
                    ),
                    object : FolderSizesActions {},
                )
                Scrim {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(top = 20.dp)) { ItemActionsContent(e, actionsFor(e, false)) {} }
                    }
                }
            }
        }
    }

    @Test fun largestSelectionLight() = shot("largest-selection-light.png") {
        val sel = setOf(largest[0].path, largest[1].path)
        HomePhone(false, "2 selected", back = true) {
            FileListContent(
                FileListUi(ListKind.LARGEST, largest, loc(largest), sizeFilter = SizeFilter.OVER_10MB,
                    totalBytes = largest.sumOf { it.size }, scan = ScanUi(complete = true), selection = sel),
                object : FileListActions {},
            )
        }
    }

    private val dupGroups = listOf(
        DuplicateGroupUi(
            hash = "aa", size = 4_210_000_000,
            files = listOf(
                g("Movies/Interstellar.mkv", 4_210_000_000, 2),
                g("Backups/Interstellar.mkv", 4_210_000_000, 2),
            ),
            locations = mapOf(
                "$root/Movies/Interstellar.mkv" to "Internal storage/Movies",
                "$root/Backups/Interstellar.mkv" to "Internal storage/Backups",
            ),
        ),
        DuplicateGroupUi(
            hash = "bb", size = 3_400_000,
            files = listOf(
                g("DCIM/Camera/IMG_20261019_183012.jpg", 3_400_000, 19),
                g("Pictures/IMG_20261019_183012.jpg", 3_400_000, 19),
                g("Telegram/Telegram Images/IMG_20261019_183012.jpg", 3_400_000, 20),
            ),
            locations = mapOf(
                "$root/DCIM/Camera/IMG_20261019_183012.jpg" to "Internal storage/DCIM/Camera",
                "$root/Pictures/IMG_20261019_183012.jpg" to "Internal storage/Pictures",
                "$root/Telegram/Telegram Images/IMG_20261019_183012.jpg" to "Internal storage/Telegram/Telegram Images",
            ),
        ),
    )

    @Test fun duplicatesLight() = shot("duplicates-light.png") {
        HomePhone(false, "Duplicates", back = true) {
            DuplicatesContent(DuplicatesUi(groups = dupGroups, ready = true, scan = ScanUi(complete = true)), object : DuplicatesActions {})
        }
    }

    @Test fun duplicatesDark() = shot("duplicates-dark.png") {
        val sel = setOf(dupGroups[0].files[1].path, dupGroups[1].files[1].path, dupGroups[1].files[2].path)
        HomePhone(true, "3 selected", back = true) {
            DuplicatesContent(
                DuplicatesUi(groups = dupGroups, ready = true, scan = ScanUi(complete = true), selection = sel),
                object : DuplicatesActions {},
            )
        }
    }

    @Test fun duplicatesHashingDark() = shot("duplicates-hashing-dark.png") {
        HomePhone(true, "Duplicates", back = true) {
            DuplicatesContent(DuplicatesUi(hashing = true, progress = 42, total = 118, scan = ScanUi(complete = true)), object : DuplicatesActions {})
        }
    }
}
