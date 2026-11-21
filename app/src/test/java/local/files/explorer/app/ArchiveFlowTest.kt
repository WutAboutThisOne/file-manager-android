package local.files.explorer.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import local.files.explorer.MainActivity
import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.archive.ArchiveWriter
import local.files.explorer.archive.CompressSpec
import local.files.explorer.archive.SourceItem
import local.files.explorer.install.IncomingInstall
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** An archive handed over from another app opens in the real MainActivity; password prompt + wrong password + unlock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w392dp-h816dp-xhdpi")
class ArchiveFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun waitFor(text: String) = compose.waitUntil(15_000) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun incomingEncryptedZipBrowsesAndUnlocks() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val f = File(File(app.cacheDir, "${IncomingInstall.DIR}/t1").apply { mkdirs() }, "secret notes.zip")
        val body = "top secret".toByteArray()
        ArchiveWriter.write(
            f, CompressSpec("secret notes", ArchiveFormat.ZIP, password = "hunter2".toCharArray()),
            sequenceOf(
                SourceItem("journal", true, 0, 0) { error("dir") },
                SourceItem("journal/2026-10-21 – día.txt", false, body.size.toLong(), 0) { body.inputStream() },
            ),
        )
        val intent = MainActivity.archiveIntent(app, f, "secret notes.zip")
        val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()

        waitFor("ZIP archive")
        waitFor("password protected")
        compose.onNodeWithText("journal").performClick()
        waitFor("2026-10-21 – día.txt")

        // The password field's blinking cursor runs on the real Choreographer, which never lets Compose's
        // test idling settle, so the dialog round-trips are driven through the ViewModel (same calls as the buttons).
        val vm = androidx.lifecycle.ViewModelProvider(c.get())[local.files.explorer.ui.MainViewModel::class.java]
        fun until(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 15_000
            while (!cond()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
        }
        vm.unlockCurrentArchive()
        until("password dialog") { vm.dialog is local.files.explorer.ui.Dialog.Password }
        vm.submitPassword("wrong".toCharArray())
        until("wrong-password state") { (vm.dialog as? local.files.explorer.ui.Dialog.Password)?.wrong == true }
        vm.submitPassword("hunter2".toCharArray())
        until("dialog closed") { vm.dialog == null }
        waitFor("unlocked")
        compose.onNodeWithText("Password needed").assertDoesNotExist()

        // Paths given by other apps are refused unless they're our own incoming copies.
        val outside = File(app.filesDir, "x.zip").apply { writeBytes(f.readBytes()) }
        c.get().handleArchiveIntent(MainActivity.archiveIntent(app, outside, "x.zip"))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithText("x.zip").assertDoesNotExist()
        c.pause().stop().destroy()
    }

    /** 0.4.0: an APK opens like a zip with its manifest decoded, and the App info screen reads it offline. */
    @Test fun apkBrowsesWithDecodedXmlAndShowsAppInfo() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val apk = File(File(app.filesDir, "Download").apply { mkdirs() }, "fixture.apk")
        javaClass.getResourceAsStream("/apk/fixture.apk")!!.use { i -> apk.outputStream().use { i.copyTo(it) } }
        val e = local.files.explorer.core.FileEntry(apk.name, apk.path, local.files.explorer.core.Backend.LOCAL, false, size = apk.length(), lastModified = apk.lastModified())
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = androidx.lifecycle.ViewModelProvider(c.get())[local.files.explorer.ui.MainViewModel::class.java]
        fun until(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 15_000
            while (!cond()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
        }

        // The home screen's scan animation keeps Compose from idling, so this test drives the ViewModel directly.
        vm.browseApk(e)
        until("apk listing") { vm.browser.entries.any { it.name == "resources.arsc" } && vm.browser.entries.any { it.name == "AndroidManifest.xml" } }
        org.junit.Assert.assertEquals(local.files.explorer.ui.archive.ArchiveUi.ApkKind.APK, vm.browser.archive?.apk)
        val root = vm.location!!.rootPath
        val archive = local.files.explorer.fs.Backends.archive
        val manifest = archive.openInput("$root/AndroidManifest.xml").use { String(it.readBytes()) }
        org.junit.Assert.assertTrue(manifest, manifest.contains("package=\"com.example.fixture\"") && manifest.contains("android:name=\"android.permission.CAMERA\""))
        val dump = archive.resourcesDump("$root/resources.arsc")!!
        org.junit.Assert.assertTrue(dump, dump.contains("@string/app_name = \"Fixture Viewer\""))

        // App info from the archive bar.
        vm.showCurrentApkInfo()
        until("info loaded") { vm.apkInfo?.details != null }
        org.junit.Assert.assertEquals(local.files.explorer.ui.Screen.APK_INFO, vm.screen)
        val d = vm.apkInfo!!.details!!
        org.junit.Assert.assertEquals("Fixture Viewer", d.label)
        org.junit.Assert.assertEquals("com.example.fixture", d.packageName)
        org.junit.Assert.assertEquals(24, d.minSdk)
        org.junit.Assert.assertTrue(d.permissions.toString(), "android.permission.CAMERA" in d.permissions)
        org.junit.Assert.assertEquals(5, vm.apkInfo!!.details!!.componentCount)
        org.junit.Assert.assertNotNull("PNG icon decoded from the APK", vm.apkInfo!!.icon)
        vm.back()
        until("back to browser") { vm.screen == local.files.explorer.ui.Screen.MAIN && vm.apkInfo == null }

        // App info straight from the file's menu, then Extract all with decoded XML.
        vm.showApkInfo(e)
        until("info loaded") { vm.apkInfo?.details?.packageName == "com.example.fixture" }
        vm.apkInfoExtractAll()
        until("extract dialog") { vm.dialog is local.files.explorer.ui.Dialog.Extract }
        org.junit.Assert.assertEquals(true, (vm.dialog as local.files.explorer.ui.Dialog.Extract).decodeXml)
        vm.confirmExtract(local.files.explorer.ui.archive.ExtractTarget.FOLDER)
        val out = File(apk.parentFile, "fixture")
        // Robolectric doesn't start the foreground service, so run the queued operation the way the service does.
        val ops = local.files.explorer.ops.OperationManager
        var req: local.files.explorer.ops.OpRequest? = null
        until("extract queued") { req = ops.next(); req != null }
        org.junit.Assert.assertEquals(local.files.explorer.ops.OpKind.EXTRACT, req!!.kind)
        val st = ops.run(app, req!!) {}
        org.junit.Assert.assertEquals(st.toString(), local.files.explorer.ops.OpStatus.DONE, st.status)
        org.junit.Assert.assertTrue(File(out, "AndroidManifest.xml").readText().startsWith("<?xml"))
        org.junit.Assert.assertTrue(File(out, "res").isDirectory)
        c.pause().stop().destroy()
    }
}
