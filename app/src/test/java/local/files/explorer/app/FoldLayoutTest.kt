package local.files.explorer.app

import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import local.files.explorer.MainActivity
import local.files.explorer.ops.OpStatus
import local.files.explorer.ops.OperationManager
import local.files.explorer.ui.Dialog
import local.files.explorer.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The real MainActivity at the open Fold's inner screen (841x701dp landscape, 2208x1840 px): navigation rail,
 * folder tree beside the contents, dual pane with copy/move by tap and by dragging a row across.
 * API 29 so Robolectric can grant legacy storage access.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w841dp-h701dp-land-420dpi")
class FoldLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    @Suppress("DEPRECATION")
    private val root: File get() = Environment.getExternalStorageDirectory()

    private fun vm(): MainViewModel {
        var v: MainViewModel? = null
        compose.activityRule.scenario.onActivity { v = ViewModelProvider(it)[MainViewModel::class.java] }
        return v!!
    }

    private fun seed() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        File(root, "Camera").mkdirs(); File(root, "Documents").mkdirs(); File(root, "Download").mkdirs(); File(root, "Music").mkdirs()
        File(root, "Documents/Taxes").mkdirs()
        File(root, "readme.txt").writeText("hello")
        File(root, "notes.md").writeText("# notes")
        File(root, "Camera/IMG_0001.jpg").writeBytes(ByteArray(2048))
        File(root, "Documents/report.pdf").writeBytes(ByteArray(4096))
        compose.activityRule.scenario.onActivity { it.recreate() }
        // The first-launch "Allow access to all files?" explainer was raised before access was granted.
        compose.runOnUiThread { vm().dialog = null; OperationManager.dismiss() }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun shot(path: String) { compose.waitForIdle(); captureScreenRoboImage(path) }

    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))

    private fun waitFor(what: String, cond: () -> Boolean) {
        try { compose.waitUntil(10_000) { idle(); cond() } } catch (e: Throwable) {
            runCatching { captureScreenRoboImage(File(outDir, "fold-failure.png").absolutePath) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }
    private fun waitText(t: String) = waitFor(t) { compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }
    private fun inPane(pane: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(pane))
    private fun waitInPane(pane: String, text: String) = waitFor("$text in $pane") {
        compose.onAllNodes(inPane(pane, text)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Runs queued copies/moves the way FileOperationService does (Robolectric doesn't start the service). */
    private fun drainOps() {
        var req: local.files.explorer.ops.OpRequest? = null
        waitFor("an operation to be queued") { req = OperationManager.next(); req != null }
        while (req != null) {
            val st = OperationManager.run(app, req!!) {}
            assertEquals(st.toString(), OpStatus.DONE, st.status)
            req = OperationManager.next()
        }
        compose.waitForIdle()
    }

    private fun openInternal() {
        compose.onNodeWithTag("navRail").assertIsDisplayed()
        compose.onAllNodesWithText("Internal")[0].performClick()
        waitInPane("paneMain", "readme.txt")
    }

    @Test fun railAndFolderTreeBesideContents() {
        seed()
        waitText("Categories")
        compose.onNodeWithTag("navRail").assertIsDisplayed()
        openInternal()
        compose.onNodeWithTag("folderTree").assertIsDisplayed()
        // The tree lists the subfolders; tapping one opens it on the right and it becomes the current node.
        compose.onNode(inPane("folderTree", "Documents")).performClick()
        waitInPane("paneMain", "report.pdf")
        waitInPane("folderTree", "Taxes")
        compose.onNode(inPane("folderTree", "Internal storage")).assertIsDisplayed()
        shot(File(outDir, "40-fold-browser-tree.png").absolutePath)
        // Back up via the tree's root node.
        compose.onNode(inPane("folderTree", "Internal storage")).performClick()
        waitInPane("paneMain", "readme.txt")
    }

    @Test fun dualPaneCopyByTap() {
        seed()
        waitText("Categories")
        openInternal()
        compose.onNodeWithTag("dualToggle").performClick()
        waitInPane("paneOther", "Documents")
        compose.onNode(inPane("paneOther", "Documents")).performClick()
        waitInPane("paneOther", "report.pdf")
        compose.onNode(inPane("paneMain", "readme.txt")).performTouchInput { longClick() }
        compose.onNode(inPane("paneMain", "notes.md")).performClick() // selection mode: tap adds
        waitFor("transfer bar") { compose.onAllNodesWithTag("transferBar").fetchSemanticsNodes().isNotEmpty() }
        shot(File(outDir, "41-fold-dual-pane-selection.png").absolutePath)
        compose.onNodeWithText("Copy \u2192").performClick()
        drainOps()
        assertTrue(File(root, "Documents/readme.txt").exists())
        assertTrue(File(root, "Documents/notes.md").exists())
        assertTrue("copy keeps the original", File(root, "readme.txt").exists())
        waitInPane("paneOther", "notes.md")
        assertTrue(vm().browser.selection.isEmpty())
    }

    @Test fun dualPaneMoveByDraggingARowAcross() {
        seed()
        waitText("Categories")
        openInternal()
        compose.onNodeWithTag("dualToggle").performClick()
        waitInPane("paneOther", "Music")
        compose.onNode(inPane("paneOther", "Music")).performClick()
        waitFor("Music opened") { vm().other.location?.path?.endsWith("/Music") == true }
        // Drag readme.txt sideways from the left pane onto the right one.
        compose.onNode(inPane("paneMain", "readme.txt")).performTouchInput {
            down(center); moveBy(Offset(40f, 0f)); repeat(10) { moveBy(Offset(110f, 0f)) }; up()
        }
        waitFor("drop dialog") { vm().dialog is Dialog.Transfer }
        val d = vm().dialog as Dialog.Transfer
        assertTrue(d.toOther); assertEquals(listOf("readme.txt"), d.items.map { it.name }); assertEquals("Music", d.destLabel)
        shot(File(outDir, "42-fold-drop-copy-or-move.png").absolutePath)
        compose.onNodeWithText("Move").performClick()
        drainOps()
        assertTrue(File(root, "Music/readme.txt").exists())
        assertFalse(File(root, "readme.txt").exists())
    }

    @Test fun otherPaneToMainAndStateSurvivesFoldUnfold() {
        seed()
        waitText("Categories")
        openInternal()
        compose.onNode(inPane("folderTree", "Camera")).performClick()
        waitInPane("paneMain", "IMG_0001.jpg")
        compose.onNodeWithTag("dualToggle").performClick()
        waitInPane("paneOther", "IMG_0001.jpg") // starts on the same folder
        compose.onNode(hasText("Internal storage") and hasAnyAncestor(hasTestTag("paneOther"))).performClick()
        waitInPane("paneOther", "Documents")

        // Fold: the phone layout comes back (bottom bar, no second pane), keeping the folder.
        RuntimeEnvironment.setQualifiers("w392dp-h816dp-port-420dpi")
        compose.activityRule.scenario.recreate()
        waitText("IMG_0001.jpg")
        assertTrue(compose.onAllNodesWithTag("navRail").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("paneOther").fetchSemanticsNodes().isEmpty())
        assertTrue(vm().dualPane)

        // Unfold: both panes are back where they were.
        RuntimeEnvironment.setQualifiers("w841dp-h701dp-land-420dpi")
        compose.activityRule.scenario.recreate()
        waitInPane("paneMain", "IMG_0001.jpg")
        waitInPane("paneOther", "Documents")
        compose.onNodeWithTag("navRail").assertIsDisplayed()

        // Right-to-left: select in the other pane, Move ← into Camera.
        compose.onNode(inPane("paneOther", "notes.md")).performTouchInput { longClick() }
        waitFor("transfer bar") { compose.onAllNodesWithTag("transferBar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Move \u2190").performClick()
        drainOps()
        assertTrue(File(root, "Camera/notes.md").exists())
        assertFalse(File(root, "notes.md").exists())
        waitInPane("paneMain", "notes.md")
    }
}
