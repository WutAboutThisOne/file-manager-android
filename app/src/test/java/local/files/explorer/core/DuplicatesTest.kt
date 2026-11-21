package local.files.explorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(rel: String, bytes: ByteArray): File {
        val f = tmp.newFile(rel.replace('/', '_'))
        // Prefer real relative paths under tmp for path-based hashing.
        return f
    }

    private fun indexed(path: String, size: Long, hidden: Boolean = false) =
        IndexedFile(FileEntry(File(path).name, path, Backend.LOCAL, false, size = size), inHiddenPath = hidden)

    @Test fun sizeGroupsNeedAtLeastTwo() {
        val a = tmp.newFile("a.bin"); a.writeBytes(ByteArray(100) { 1 })
        val b = tmp.newFile("b.bin"); b.writeBytes(ByteArray(100) { 2 })
        val c = tmp.newFile("c.bin"); c.writeBytes(ByteArray(50) { 3 })
        val idx = listOf(
            indexed(a.path, a.length()),
            indexed(b.path, b.length()),
            indexed(c.path, c.length()),
        )
        val groups = DuplicateFinder.sizeGroups(idx, showHidden = false)
        assertEquals(setOf(100L), groups.keys)
        assertEquals(2, groups[100L]!!.size)
    }

    @Test fun findGroupsIdenticalContents() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val a = File(tmp.root, "folder1/copy.bin").also { it.parentFile.mkdirs(); it.writeBytes(payload) }
        val b = File(tmp.root, "folder2/copy.bin").also { it.parentFile.mkdirs(); it.writeBytes(payload) }
        val c = File(tmp.root, "folder2/other.bin").also { it.writeBytes(payload.copyOf().also { arr -> arr[0] = (arr[0] + 1).toByte() }) }
        val idx = listOf(indexed(a.path, a.length()), indexed(b.path, b.length()), indexed(c.path, c.length()))
        val groups = DuplicateFinder.find(idx, showHidden = false)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].files.size)
        assertEquals(payload.size.toLong(), groups[0].size)
        assertEquals(payload.size.toLong(), groups[0].wastedBytes)
        assertTrue(groups[0].files.map { it.path }.containsAll(listOf(a.path, b.path)))
    }

    @Test fun hiddenSkippedUnlessEnabled() {
        val payload = byteArrayOf(9, 9, 9, 9)
        val a = File(tmp.root, "vis.bin").also { it.writeBytes(payload) }
        val b = File(tmp.root, ".hid/vis.bin").also { it.parentFile.mkdirs(); it.writeBytes(payload) }
        val idx = listOf(
            indexed(a.path, a.length()),
            indexed(b.path, b.length(), hidden = true),
        )
        assertTrue(DuplicateFinder.find(idx, showHidden = false).isEmpty())
        assertEquals(1, DuplicateFinder.find(idx, showHidden = true).size)
    }

    @Test fun sha256Stable() {
        val f = tmp.newFile("x.dat"); f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(DuplicateFinder.sha256(f.path), DuplicateFinder.sha256(f.path))
    }
}
