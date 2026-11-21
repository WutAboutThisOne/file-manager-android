package local.files.explorer.storage

import local.files.explorer.core.Categories
import local.files.explorer.core.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seed(): File {
        val r = tmp.newFolder("storage")
        File(r, "DCIM/Camera").mkdirs(); File(r, "DCIM/Camera/a.jpg").writeBytes(ByteArray(300))
        File(r, "Telegram/img").mkdirs(); File(r, "Telegram/.nomedia").createNewFile(); File(r, "Telegram/img/b.jpg").writeBytes(ByteArray(200))
        File(r, ".thumbnails").mkdirs(); File(r, ".thumbnails/c.jpg").writeBytes(ByteArray(50))
        File(r, "Download").mkdirs(); File(r, "Download/app.apk").writeBytes(ByteArray(70))
        runCatching { Files.createSymbolicLink(File(r, "loop").toPath(), r.toPath()) }
        return r
    }

    @Test fun indexesFilesAndFoldersWithNomediaAndHiddenFlags() {
        val r = seed()
        val res = FileScanner().scan(listOf(r.path))
        assertFalse(res.cancelled)
        val byName = res.files.associateBy { it.entry.name }
        assertFalse(byName.getValue("a.jpg").inNomediaTree)
        assertTrue(byName.getValue("b.jpg").inNomediaTree)
        assertFalse(byName.getValue("b.jpg").inHiddenPath)
        assertTrue(byName.getValue("c.jpg").inHiddenPath)
        assertTrue(byName.getValue("DCIM").entry.isDirectory)
        // symlink "loop" back to the root is not followed
        assertEquals(1, res.files.count { it.entry.name == "a.jpg" })
        assertEquals(300L + 200 + 50 + 70, res.progress.bytes)
        val imgs = Categories.files(res.files, Category.IMAGES, File(r, "Download").path, respectNomedia = true, showHidden = false)
        assertEquals(listOf("a.jpg"), imgs.map { it.name })
    }

    @Test fun cancellationReturnsPartialResults() {
        val r = seed()
        var n = 0
        val res = FileScanner().scan(listOf(r.path), isCancelled = { n++ > 1 })
        assertTrue(res.cancelled)
        assertTrue(res.files.size < FileScanner().scan(listOf(r.path)).files.size)
    }
}
