package local.files.explorer.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import local.files.explorer.InstallActivity
import local.files.explorer.MainActivity
import local.files.explorer.install.FakeSenderProvider
import local.files.explorer.install.IncomingInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Cold start and onNewIntent of the "Open with File Manager" entry point, without All files access. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h816dp-xhdpi")
class InstallActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val outDir = File(System.getProperty("screens.outDir") ?: "build/screens")

    @Before fun provider() {
        Robolectric.buildContentProvider(FakeSenderProvider::class.java).create(ProviderInfo().apply { authority = FakeSenderProvider.AUTH })
        FakeSenderProvider.files.clear()
    }

    private fun serve(id: String, name: String, body: ByteArray): Uri {
        FakeSenderProvider.files[id] = name to File.createTempFile("incoming", ".bin").apply { writeBytes(body) }
        return Uri.parse("content://${FakeSenderProvider.AUTH}/$id")
    }

    private fun view(uri: Uri, type: String = "application/octet-stream") =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setComponent(ComponentName(ctx, InstallActivity::class.java))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * One activity per Robolectric sandbox (Compose + buildActivity leak state between tests in the same
     * sandbox), so the cold-start and onNewIntent paths are exercised in one test.
     */
    @Test fun coldStartErrorThenNewIntentThenCloseCleansUp() {
        val c = Robolectric.buildActivity(InstallActivity::class.java, view(serve("a", "report.pdf", "%PDF-1.7".toByteArray()))).setup()
        waitFor("isn't an Android app package")
        compose.onNodeWithText("Opened from another app", substring = true).assertExists()
        compose.onNodeWithText("report.pdf", substring = true).assertExists()
        compose.onNodeWithText("Allow access to all files?").assertDoesNotExist() // no all-files onboarding on this path
        compose.onRoot().captureRoboImage(File(outDir, "external-install-error-light.png").absolutePath)

        // Already running: a second file replaces the first (singleTop -> onNewIntent).
        c.get().handleNewIntent(view(Uri.parse("content://${FakeSenderProvider.AUTH}/missing")))
        waitFor("couldn't be opened")

        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); c.get().isFinishing }
        c.pause().stop().destroy()
        assertTrue(File(ctx.cacheDir, IncomingInstall.DIR).listFiles().orEmpty().isEmpty())
    }

    /** Also covers 0.3.0 routing: a plain zip goes to the main window's archive browser instead of being refused. */
    @Config(sdk = [29])
    @Test fun intentWithoutAFileIsHandledThenAZipIsHandedToTheArchiveBrowser() {
        val c = Robolectric.buildActivity(InstallActivity::class.java, Intent(Intent.ACTION_VIEW).setComponent(ComponentName(ctx, InstallActivity::class.java))).setup()
        waitFor("didn't receive a file")
        c.get().handleNewIntent(view(serve("b", "photos.zip", zipOf("a.jpg", "b.jpg")), "application/zip"))
        compose.waitUntil(10_000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); c.get().isFinishing }
        val started = Shadows.shadowOf(c.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        val path = started.getStringExtra(MainActivity.EXTRA_ARCHIVE_PATH)!!
        assertEquals("photos.zip", started.getStringExtra(MainActivity.EXTRA_ARCHIVE_NAME))
        // 0.4.0: opened in place (no cache copy); MainActivity accepts in-place paths it was handed.
        assertTrue(File(path).isFile && IncomingInstall.isOpenedInPlace(path))
        assertTrue(File(ctx.cacheDir, IncomingInstall.DIR).walk().none { it.isFile })
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        c.pause().stop().destroy()
        assertTrue("archive survives the hand-off", File(path).isFile)
    }

    private fun zipOf(vararg names: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z -> names.forEach { z.putNextEntry(java.util.zip.ZipEntry(it)); z.write(1); z.closeEntry() } }
        return bos.toByteArray()
    }
}
