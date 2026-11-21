package local.files.explorer.install

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import local.files.explorer.InstallActivity
import local.files.explorer.core.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import local.files.explorer.storage.DirectAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Serves files like another app's FileProvider would, incl. a display name and a denied URI. */
class FakeSenderProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.example.sender.files"
        val files = mutableMapOf<String, Pair<String, File>>() // id -> (display name, file)
    }
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val (name, f) = files[uri.lastPathSegment] ?: return null
        val cols = p?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else name }.toTypedArray()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment == "denied") throw SecurityException("no grant")
        val f = files[uri.lastPathSegment]?.second ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingInstallTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val apkMime = "application/vnd.android.package-archive"

    private val realFreeSpace = DirectAccess.freeSpace

    @Before fun provider() {
        Robolectric.buildContentProvider(FakeSenderProvider::class.java).create(ProviderInfo().apply { authority = FakeSenderProvider.AUTH })
        FakeSenderProvider.files.clear()
        // Most tests here cover the copy fallback; the in-place path has its own tests below.
        DirectAccess.enabled = false
    }

    @After fun restore() {
        DirectAccess.enabled = true
        DirectAccess.freeSpace = realFreeSpace
    }

    // ---- 0.4.0: open in place, free-space checks --------------------------------

    @Test fun contentUriOpensInPlaceWithoutCopy() = runBlocking {
        DirectAccess.enabled = true
        val src = zip("a.jpg", "b.jpg")
        val p = IncomingInstall.prepare(ctx, serve("ip", "photos.zip", src))
        assertEquals(PackageKind.ARCHIVE, p.kind)
        assertTrue("should open in place", p.inPlace)
        assertTrue(IncomingInstall.isOpenedInPlace(p.file.path))
        assertTrue(p.file.canRead())
        assertEquals(src.length(), p.file.length())
        assertTrue("nothing copied to the cache", File(ctx.cacheDir, IncomingInstall.DIR).listFiles().orEmpty().none { it.walk().any { f -> f.isFile } })
        IncomingInstall.cleanup(ctx, p.file)
        assertTrue("the sender's file is never deleted", src.exists())
        assertFalse(IncomingInstall.isOpenedInPlace(p.file.path))
    }

    @Test fun fileUriOpensInPlace() = runBlocking {
        DirectAccess.enabled = true
        val src = zip("base.apk", "split_config.arm64_v8a.apk").let { f -> File(f.parentFile, "game-${System.nanoTime()}.apks").also { f.renameTo(it) } }
        val p = IncomingInstall.prepare(ctx, Uri.fromFile(src))
        assertEquals(PackageKind.BUNDLE, p.kind)
        assertTrue(p.inPlace)
        assertEquals(src.canonicalPath, p.file.canonicalPath)
        IncomingInstall.cleanup(ctx, p.file)
        assertTrue(src.exists())
    }

    @Test fun descriptorWithoutPathIsHeldAndSeekable() {
        val src = zip("x.txt")
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val o = DirectAccess.fromDescriptor(pfd)
        assertTrue(o != null)
        o!!.file.inputStream().use { assertEquals(0x50, it.read()) }
        java.util.zip.ZipFile(o.file).use { assertEquals("x.txt", it.entries().nextElement().name) } // random access works
        o.release()
    }

    @Test fun notEnoughSpaceForCopySaysHowMuch() {
        DirectAccess.freeSpace = { 900L }
        val big = File.createTempFile("big", ".zip").apply { writeBytes(ByteArray(64 * 1024).also { it[0] = 0x50; it[1] = 0x4B; it[2] = 3; it[3] = 4 }) }
        runBlocking {
            try { IncomingInstall.prepare(ctx, serve("big", "movie-backup.zip", big)); fail("expected an error") } catch (e: IncomingException) {
                val m = e.message!!
                assertTrue(m, m.startsWith("Not enough space to open \u201cmovie-backup.zip\u201d"))
                assertTrue(m, m.contains("needs 65.5 kB") && m.contains("900 B free"))
            }
        }
        assertTrue(File(ctx.cacheDir, IncomingInstall.DIR).listFiles().orEmpty().none { it.walk().any { f -> f.isFile } })
    }

    @Test fun spaceCheckWording() {
        assertNull(local.files.explorer.core.SpaceCheck.check("extract", 100, 1000))
        assertNull(local.files.explorer.core.SpaceCheck.check("extract", 100, null))
        assertEquals("Not enough space to extract: needs 2 kB, 1 kB free.", local.files.explorer.core.SpaceCheck.check("extract", 2048, 1000))
    }

    private fun zip(vararg entries: String): File = File.createTempFile("pkg", ".bin").apply {
        ZipOutputStream(outputStream()).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it)); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() } }
    }
    private fun serve(id: String, name: String, f: File): Uri {
        FakeSenderProvider.files[id] = name to f
        return Uri.parse("content://${FakeSenderProvider.AUTH}/$id")
    }

    // ---- intent parsing --------------------------------------------------------

    @Test fun viewAndInstallUseData() {
        val u = Uri.parse("content://x/1")
        assertEquals(u, IncomingInstall.uriFrom(Intent(Intent.ACTION_VIEW).setDataAndType(u, apkMime)))
        @Suppress("DEPRECATION")
        assertEquals(u, IncomingInstall.uriFrom(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(u)))
        assertEquals(Uri.parse("file:///sdcard/a.apk"), IncomingInstall.uriFrom(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.apk"))))
    }

    @Test fun sendUsesStreamThenClipData() {
        val u = Uri.parse("content://x/2")
        assertEquals(u, IncomingInstall.uriFrom(Intent(Intent.ACTION_SEND).setType(apkMime).putExtra(Intent.EXTRA_STREAM, u)))
        val clip = Intent(Intent.ACTION_SEND).setType(apkMime).apply { clipData = ClipData.newRawUri(null, u) }
        assertEquals(u, IncomingInstall.uriFrom(clip))
    }

    @Test fun rejectsOtherActionsAndSchemes() {
        assertNull(IncomingInstall.uriFrom(null))
        assertNull(IncomingInstall.uriFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingInstall.uriFrom(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/a.apk"))))
        assertNull(IncomingInstall.uriFrom(Intent(Intent.ACTION_SEND).setType(apkMime)))
    }

    // ---- copying & checking ----------------------------------------------------

    @Test fun copiesAnApkFromAContentUriUsingItsDisplayName() = runBlocking {
        val uri = serve("1", "Signal-7.24.2.apk", zip("AndroidManifest.xml", "classes.dex"))
        assertEquals("Signal-7.24.2.apk", IncomingInstall.displayName(ctx, uri))
        val p = IncomingInstall.prepare(ctx, uri)
        assertEquals(PackageKind.APK, p.kind)
        assertEquals("Signal-7.24.2.apk", p.file.name)
        assertTrue(p.file.exists() && p.file.path.startsWith(File(ctx.cacheDir, IncomingInstall.DIR).path))
        IncomingInstall.cleanup(ctx, p.file)
        assertFalse(p.file.exists()); assertFalse(p.file.parentFile!!.exists())
    }

    @Test fun octetStreamBundleBecomesApks() = runBlocking {
        val uri = serve("2", "game.bin", zip("base.apk", "split_config.arm64_v8a.apk"))
        val p = IncomingInstall.prepare(ctx, uri)
        assertEquals(PackageKind.BUNDLE, p.kind)
        assertEquals("game.apks", p.file.name)
        IncomingInstall.cleanup(ctx, p.file)
    }

    private fun expectError(uri: Uri, contains: String) = runBlocking {
        try { IncomingInstall.prepare(ctx, uri); fail("expected an error") } catch (e: IncomingException) {
            assertTrue("'${e.message}' should mention '$contains'", e.message!!.contains(contains, ignoreCase = true))
        }
        // nothing left behind in cache
        assertTrue(File(ctx.cacheDir, IncomingInstall.DIR).listFiles().orEmpty().isEmpty())
    }

    @Test fun notAZip() = expectError(serve("3", "notes.pdf", File.createTempFile("xxx", ".pdf").apply { writeText("%PDF-1.7 hello") }), "isn't an Android app package")
    // 0.3.0: zips that aren't app packages are archives for the archive browser (no longer refused).
    @Test fun zipWithoutApkIsAnArchive() = runBlocking {
        val p = IncomingInstall.prepare(ctx, serve("4", "photos.zip", zip("a.jpg", "b.jpg")))
        assertEquals(PackageKind.ARCHIVE, p.kind)
        assertEquals("photos.zip", p.file.name)
        assertTrue(IncomingInstall.isIncomingCopy(ctx, p.file.path))
        IncomingInstall.cleanup(ctx, p.file)
    }

    @Test fun zipThatOnlyContainsAnApkIsAnArchive() = runBlocking {
        val p = IncomingInstall.prepare(ctx, serve("4b", "release.zip", zip("MyApp.apk", "README.txt")))
        assertEquals(PackageKind.ARCHIVE, p.kind)
        IncomingInstall.cleanup(ctx, p.file)
    }

    @Test fun namelessArchivesGetAnExtensionFromTheirBytes() = runBlocking {
        val seven = File.createTempFile("sev", ".bin").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4) + ByteArray(40)) }
        val p = IncomingInstall.prepare(ctx, serve("4c", "download", seven))
        assertEquals(PackageKind.ARCHIVE, p.kind)
        assertEquals("download.7z", p.file.name)
        IncomingInstall.cleanup(ctx, p.file)
        val z = IncomingInstall.prepare(ctx, serve("4d", "blob", zip("a.txt")))
        assertEquals("blob.zip", z.file.name)
        IncomingInstall.cleanup(ctx, z.file)
    }

    @Test fun onlyOurOwnCacheCopiesAreAccepted() {
        assertFalse(IncomingInstall.isIncomingCopy(ctx, "/data/data/local.files.explorer/files/datastore/settings.preferences_pb"))
        assertFalse(IncomingInstall.isIncomingCopy(ctx, File(ctx.cacheDir, "${IncomingInstall.DIR}/../x").path))
        assertFalse(IncomingInstall.isIncomingCopy(ctx, File(ctx.cacheDir, "${IncomingInstall.DIR}/missing.zip").path))
    }
    @Test fun corruptZip() = expectError(serve("5", "broken.apk", File.createTempFile("xxx", ".apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 1)) }), "damaged")
    @Test fun emptyFile() = expectError(serve("6", "empty.apk", File.createTempFile("xxx", ".apk")), "empty")
    @Test fun missingFile() = expectError(Uri.parse("content://${FakeSenderProvider.AUTH}/gone"), "couldn't be opened")
    @Test fun deniedGrant() = expectError(Uri.parse("content://${FakeSenderProvider.AUTH}/denied"), "permission")

    @Test fun staleCopiesAreRemoved() {
        val old = File(File(ctx.cacheDir, IncomingInstall.DIR), "old").apply { mkdirs(); File(this, "a.apk").writeText("x"); setLastModified(1000) }
        val fresh = File(File(ctx.cacheDir, IncomingInstall.DIR), "fresh").apply { mkdirs() }
        IncomingInstall.cleanupStale(ctx)
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    // ---- manifest: the app shows up for APKs but not for every zip ---------------

    private fun resolves(i: Intent): Boolean =
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == InstallActivity::class.java.name }

    @Test fun intentFiltersMatchApksAndBundles() {
        val c = Uri.parse("content://com.other/files/1")
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, apkMime)))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/a.apk"), apkMime)))
        @Suppress("DEPRECATION")
        assertTrue(resolves(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(c, apkMime)))
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType(apkMime).putExtra(Intent.EXTRA_STREAM, c)))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, "application/xapk-package-archive")))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, "application/octet-stream")))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Download/game.xapk"), "application/octet-stream")))
    }

    @Test fun archivesOpenWithTheApp() {
        val c = Uri.parse("content://com.other/files/1")
        for (m in listOf("application/zip", "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-gzip",
            "application/x-compressed-tar", "application/x-xz", "application/x-bzip2")) {
            assertTrue(m, resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, m)))
        }
        assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/a.zip"), "application/zip")))
    }

    @Test fun doesNotHijackSharesOrOtherFiles() {
        val c = Uri.parse("content://com.other/files/1")
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/zip")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/x-7z-compressed")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/octet-stream")))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, "image/jpeg")))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(c, "text/plain")))
    }
}
