package local.files.explorer.fs

import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val local = LocalBackend()
    private val engine = FileOperationEngine({ local }, bufferSize = 8)

    private fun file(dir: File, name: String, text: String) = File(dir, name).apply { parentFile?.mkdirs(); writeText(text) }
    private fun entry(f: File) = local.stat(f.path)!!

    private fun setup(): Pair<File, File> {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        return src to dst
    }

    @Test fun copiesFilesAndFoldersWithProgress() {
        val (src, dst) = setup()
        val a = file(src, "a.txt", "hello world")
        val dir = File(src, "album").apply { mkdirs() }
        file(dir, "1.jpg", "1111"); file(dir, "sub/2.jpg", "22")
        val progress = mutableListOf<OpProgress>()
        val out = engine.transfer(listOf(entry(a), entry(dir)), Backend.LOCAL, dst.path, move = false, ConflictPolicy.KEEP_BOTH, { progress += it })
        assertEquals(2, out.done)
        assertEquals("hello world", File(dst, "a.txt").readText())
        assertEquals("22", File(dst, "album/sub/2.jpg").readText())
        assertTrue(a.exists())
        val last = progress.last()
        assertEquals(17L, last.bytesTotal)
        assertEquals(last.bytesTotal, last.bytesDone)
        assertEquals(1f, last.fraction)
    }

    @Test fun moveRemovesSource() {
        val (src, dst) = setup()
        val a = file(src, "a.txt", "x")
        val out = engine.transfer(listOf(entry(a)), Backend.LOCAL, dst.path, move = true, ConflictPolicy.SKIP)
        assertEquals(1, out.done)
        assertFalse(a.exists())
        assertTrue(File(dst, "a.txt").exists())
    }

    @Test fun skipLeavesExistingUntouched() {
        val (src, dst) = setup()
        val a = file(src, "a.txt", "new"); file(dst, "a.txt", "old")
        val out = engine.transfer(listOf(entry(a)), Backend.LOCAL, dst.path, move = false, ConflictPolicy.SKIP)
        assertEquals(1, out.skipped)
        assertEquals("old", File(dst, "a.txt").readText())
    }

    @Test fun replaceOverwrites() {
        val (src, dst) = setup()
        val a = file(src, "a.txt", "new"); file(dst, "a.txt", "older content")
        engine.transfer(listOf(entry(a)), Backend.LOCAL, dst.path, move = false, ConflictPolicy.REPLACE)
        assertEquals("new", File(dst, "a.txt").readText())
    }

    @Test fun keepBothRenames() {
        val (src, dst) = setup()
        val a = file(src, "a.txt", "new"); file(dst, "a.txt", "old")
        val out = engine.transfer(listOf(entry(a)), Backend.LOCAL, dst.path, move = false, ConflictPolicy.KEEP_BOTH)
        assertEquals(listOf(File(dst, "a (1).txt").path), out.createdPaths)
        assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("new", File(dst, "a (1).txt").readText())
    }

    @Test fun replacingAFolderMergesIt() {
        val (src, dst) = setup()
        val dir = File(src, "docs").apply { mkdirs() }
        file(dir, "keep.txt", "src"); file(dir, "same.txt", "from src")
        file(dst, "docs/other.txt", "dst only"); file(dst, "docs/same.txt", "from dst")
        engine.transfer(listOf(entry(dir)), Backend.LOCAL, dst.path, move = true, ConflictPolicy.REPLACE)
        assertEquals("dst only", File(dst, "docs/other.txt").readText())
        assertEquals("from src", File(dst, "docs/same.txt").readText())
        assertEquals("src", File(dst, "docs/keep.txt").readText())
        assertFalse(dir.exists())
    }

    @Test fun pastingCopyIntoSameFolderKeepsBothEvenWithReplace() {
        val (src, _) = setup()
        val a = file(src, "a.txt", "x")
        engine.transfer(listOf(entry(a)), Backend.LOCAL, src.path, move = false, ConflictPolicy.REPLACE)
        assertTrue(File(src, "a.txt").exists())
        assertTrue(File(src, "a (1).txt").exists())
    }

    @Test fun movingIntoSameFolderIsANoOp() {
        val (src, _) = setup()
        val a = file(src, "a.txt", "x")
        val out = engine.transfer(listOf(entry(a)), Backend.LOCAL, src.path, move = true, ConflictPolicy.REPLACE)
        assertEquals(1, out.skipped)
        assertEquals("x", a.readText())
    }

    @Test fun refusesToCopyFolderIntoItself() {
        val (src, _) = setup()
        val dir = File(src, "loop").apply { mkdirs() }
        val inner = File(dir, "inner").apply { mkdirs() }
        val out = engine.transfer(listOf(entry(dir)), Backend.LOCAL, inner.path, move = false, ConflictPolicy.KEEP_BOTH)
        assertEquals(0, out.done)
        assertEquals(1, out.failed.size)
        assertFalse(File(inner, "loop").exists())
    }

    @Test fun cancelStopsAndRemovesPartialFile() {
        val (src, dst) = setup()
        val big = file(src, "big.bin", "x".repeat(10_000))
        var reads = 0
        val out = engine.transfer(listOf(entry(big)), Backend.LOCAL, dst.path, move = false, ConflictPolicy.KEEP_BOTH,
            isCancelled = { reads++ > 20 })
        assertTrue(out.cancelled)
        assertFalse(File(dst, "big.bin").exists())
        assertTrue(big.exists())
    }

    @Test fun deleteReportsProgress() {
        val (src, _) = setup()
        val a = file(src, "a.txt", "x"); val d = File(src, "d").apply { mkdirs() }; file(d, "z", "z")
        val seen = mutableListOf<OpProgress>()
        val out = engine.delete(listOf(entry(a), entry(d)), { seen += it })
        assertEquals(2, out.done)
        assertFalse(a.exists() || d.exists())
        assertEquals(2, seen.last().itemsDone)
    }

    @Test fun localBackendFlagsNomediaFolders() {
        val (src, _) = setup()
        val d = File(src, "private").apply { mkdirs() }
        File(d, ".nomedia").createNewFile()
        val e = local.list(src.path).single { it.name == "private" }
        assertTrue(e.hasNomedia)
        assertEquals(1, e.childCount)
    }
}
