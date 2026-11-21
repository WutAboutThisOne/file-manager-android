package local.files.explorer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import local.files.explorer.core.Crumb
import local.files.explorer.core.FileEntry
import local.files.explorer.settings.ThemeMode
import local.files.explorer.ui.WideBrowser
import local.files.explorer.ui.WideBrowserActions
import local.files.explorer.ui.adaptive.Adaptive
import local.files.explorer.ui.adaptive.Hinge
import local.files.explorer.ui.adaptive.LocalAdaptive
import local.files.explorer.ui.browser.BrowserActions
import local.files.explorer.ui.browser.BrowserUi
import local.files.explorer.ui.settings.AboutContent
import local.files.explorer.ui.settings.LicencesContent
import local.files.explorer.core.ChangelogEntry
import local.files.explorer.ui.theme.FileManagerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Half-open Fold postures at the inner screen size (2208x1840 px): panes split at the hinge, nothing on the crease. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w841dp-h701dp-land-420dpi")
class FoldPaneScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }
    private val t = 1_763_500_000_000L

    private fun d(name: String, n: Int) = FileEntry(name, "/storage/emulated/0/$name", isDirectory = true, lastModified = t).copy(childCount = n)
    private fun f(name: String, size: Long, dir: String = "") = FileEntry(name, "/storage/emulated/0/$dir$name", isDirectory = false, size = size, lastModified = t)

    private val root = Crumb("Internal storage", "/storage/emulated/0")
    private val main = BrowserUi(
        crumbs = listOf(root, Crumb("Camera", "/storage/emulated/0/Camera")),
        entries = listOf(f("IMG_2041.jpg", 3_400_000, "Camera/"), f("IMG_2042.jpg", 3_100_000, "Camera/"), f("VID_0007.mp4", 48_000_000, "Camera/")),
        selection = setOf("/storage/emulated/0/Camera/IMG_2041.jpg", "/storage/emulated/0/Camera/IMG_2042.jpg"),
    )
    private val other = BrowserUi(
        crumbs = listOf(root, Crumb("Pictures", "/storage/emulated/0/Pictures")),
        entries = listOf(d("Screenshots", 12), d("Trips", 40), f("wallpaper.png", 2_200_000, "Pictures/")),
    )

    private fun actions() = WideBrowserActions(
        main = object : BrowserActions {}, other = object : BrowserActions {}, tree = {}, treeOpen = {}, setDual = {}, otherHere = {},
        transfer = { _, _ -> }, drop = { _, _ -> }, mainSelection = { emptyList() }, otherSelection = { emptyList() },
    )

    @Composable
    private fun Frame(adaptive: Adaptive, dark: Boolean, content: @Composable () -> Unit) {
        FileManagerTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, darkOverride = dark) {
            CompositionLocalProvider(LocalAdaptive provides adaptive) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
    }

    @Test fun tabletopDualPaneSplitsAboveAndBelowTheHinge() {
        // Hinge across the middle of the 1840 px screen.
        val a = Adaptive.of(841f, Hinge(0, 920, 2208, 920, horizontal = true, separating = true, halfOpened = true))
        compose.setContent { Frame(a, dark = true) { WideBrowser(main, other, dual = true, otherTitle = "Pictures", mainTitle = "Camera", actions = actions()) } }
        val top = compose.onNodeWithTag("paneTop").getBoundsInRoot()
        val bottom = compose.onNodeWithTag("paneBottom").getBoundsInRoot()
        assertTrue("panes leave a gap at the hinge", bottom.top > top.bottom)
        compose.onNodeWithText("Copy \u2193").assertIsDisplayed()
        compose.onRoot().captureRoboImage(File(outDir, "43-fold-tabletop-dual-dark.png").absolutePath)
    }

    @Test fun bookPostureSplitsAtTheVerticalHinge() {
        val a = Adaptive.of(841f, Hinge(1104, 0, 1104, 1840, horizontal = false, separating = true, halfOpened = true))
        compose.setContent { Frame(a, dark = false) { WideBrowser(main, other, dual = false, otherTitle = "Pictures", mainTitle = "Camera", actions = actions()) } }
        compose.onNodeWithTag("folderTree").assertIsDisplayed()
        val first = compose.onNodeWithTag("paneFirst").getBoundsInRoot()
        val second = compose.onNodeWithTag("paneSecond").getBoundsInRoot()
        assertTrue("a margin is kept around the crease", second.left - first.right >= 20.dp)
        compose.onRoot().captureRoboImage(File(outDir, "44-fold-book-tree-light.png").absolutePath)
    }

    @Test fun aboutAndLicencesAtFoldSize() {
        val log = listOf(
            ChangelogEntry("0.4.5", "2026-11-19", listOf("Open Fold layout: navigation rail, folder tree, dual pane", "Targets Android 15 (API 35)", "Licences page")),
            ChangelogEntry("0.4.4", "2026-11-17", listOf("About page with this changelog")),
            ChangelogEntry("0.4.3", "2026-11-17", listOf("Add / Remove .nomedia from the toolbar")),
        )
        var licences by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            Frame(Adaptive.of(841f), dark = false) {
                if (licences) LicencesContent()
                else AboutContent("0.4.5", log, 10, onLicences = { licences = true })
            }
        }
        compose.onRoot().captureRoboImage(File(outDir, "45-about-whats-new-collapsed.png").absolutePath)
        licences = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "46-licences-page.png").absolutePath)
    }
}
