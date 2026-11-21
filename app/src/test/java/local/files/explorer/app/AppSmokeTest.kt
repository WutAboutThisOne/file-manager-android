package local.files.explorer.app

import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.github.takahirom.roborazzi.captureRoboImage
import local.files.explorer.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import androidx.test.core.app.ApplicationProvider
import java.io.File

/**
 * Boots the real MainActivity (DataStore, ViewModel, libsu detection, LocalBackend)
 * against Robolectric's fake external storage and drives a few taps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h816dp-xhdpi")
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens")

    /** Android 14 without All files access: the onboarding card, not a crash or empty list. */
    @Test fun withoutAccessShowsOnboarding() {
        // First launch: the in-app explainer comes before Android's settings screen.
        waitFor("Allow access to all files?")
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        waitFor("Let File Manager see your files")
        compose.onNodeWithText("Allow access to all files?").assertDoesNotExist()
        compose.onNodeWithText("Pick a folder instead").assertIsDisplayed()
        compose.onNodeWithText("Root").assertDoesNotExist() // unrooted: no Root tab
        // Asking again re-explains first.
        compose.onNodeWithText("Allow access").performClick()
        waitFor("Allow access to all files?")
    }

    /** Legacy storage permission path (API 29) so Robolectric can grant access, then browse. */
    @Config(sdk = [29])
    @Test fun bootsBrowsesAndOpensSettings() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        // Robolectric's activity is created before we can seed files, so seed then refresh.
        File(root, "Camera").mkdirs()
        File(root, "Private").mkdirs(); File(root, "Private/.nomedia").createNewFile()
        File(root, "readme.txt").writeText("hello")
        File(root, "Camera/IMG_0001.jpg").writeBytes(ByteArray(2048))
        File(root, "Private/secret.jpg").writeBytes(ByteArray(4096))
        compose.activityRule.scenario.onActivity { it.recreate() }

        // Home first: categories from the background scan (secret.jpg is under .nomedia, so Images = 1).
        waitFor("Categories")
        waitFor("1 \u00b7 2 kB")
        compose.onNodeWithText("Images").performClick()
        waitFor("IMG_0001.jpg")
        compose.onNodeWithText("secret.jpg").assertDoesNotExist()
        compose.onNodeWithText("Internal storage/Camera").assertIsDisplayed()

        // ⋮ menu -> Go to file location opens the browser in Camera.
        // (Long-press now multi-selects, matching the normal file list.)
        compose.onNodeWithContentDescription("More options for IMG_0001.jpg").performClick()
        waitFor("Go to file location")
        compose.onNodeWithText("Details").assertIsDisplayed()
        compose.onNodeWithText("Go to file location").performClick()
        waitFor("Internal storage")
        assert(compose.onAllNodesWithText("Camera").fetchSemanticsNodes().isNotEmpty()) // title + breadcrumb
        compose.onNodeWithText("IMG_0001.jpg").assertIsDisplayed()
        compose.onRoot().captureRoboImage(File(outDir, "smoke-go-to-location.png").absolutePath)

        // Global search on Home covers all storage and shows where each hit lives.
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Home").performClick()
        waitFor("Search all storage")
        compose.onAllNodesWithText("Search all storage")[0].performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("readme")
        waitFor("readme.txt")
        waitFor("1 result")

        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithText("Internal").performClick() // remembers Camera from "Go to file location"
        compose.onNodeWithText("Internal").performClick() // re-tap jumps to the root
        waitFor("readme.txt")
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("readme.txt").assertIsDisplayed()
        compose.onNodeWithText("nomedia").assertIsDisplayed() // badge on Private (respect is on by default)
        compose.onRoot().captureRoboImage(File(outDir, "smoke-app-internal.png").absolutePath)

        compose.onNodeWithText("Private").performClick()
        waitFor("secret.jpg")

        compose.onNodeWithContentDescription("More").performClick()
        waitFor("Remove .nomedia")
        compose.onNodeWithText("Remove .nomedia").assertIsDisplayed()
        compose.onNodeWithText("Add .nomedia").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Respect .nomedia").assertIsDisplayed()
    }

    private fun waitFor(text: String) {
        try {
            compose.waitUntil(10_000) {
                // Robolectric's paused looper doesn't advance time by itself; let debounce delays elapse.
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            runCatching { compose.onRoot().captureRoboImage(File(outDir, "smoke-failure.png").absolutePath) }
            throw e
        }
    }

}
