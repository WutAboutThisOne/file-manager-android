package local.files.explorer.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** A set of files that share the same size and content hash. */
data class DuplicateGroup(
    val size: Long,
    val hash: String,
    val files: List<FileEntry>,
) {
    /** Bytes you would free by keeping one copy. */
    val wastedBytes: Long get() = size * (files.size - 1L).coerceAtLeast(0)
}

/**
 * Offline duplicate finder: group by size first, then SHA-256 the contents of each
 * same-size cohort. Zero-byte files are skipped. Cancelable and progress-reporting
 * so the UI can show a hashing bar without blocking.
 */
object DuplicateFinder {
    const val DEFAULT_MIN_BYTES = 1L

    /** Files that share a size with at least one other (candidates worth hashing). */
    fun sizeGroups(
        index: List<IndexedFile>,
        showHidden: Boolean,
        minBytes: Long = DEFAULT_MIN_BYTES,
    ): Map<Long, List<FileEntry>> {
        val bySize = HashMap<Long, ArrayList<FileEntry>>()
        for (f in index) {
            val e = f.entry
            if (e.isDirectory || e.size < minBytes) continue
            if (!showHidden && f.inHiddenPath) continue
            bySize.getOrPut(e.size) { ArrayList() }.add(e)
        }
        return bySize.filterValues { it.size >= 2 }
    }

    fun sha256(path: String, bufSize: Int = 64 * 1024): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufSize)
        FileInputStream(File(path)).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Hash every candidate and return groups with 2+ identical files, largest waste first.
     * [onProgress] reports (hashedSoFar, totalToHash).
     */
    fun find(
        index: List<IndexedFile>,
        showHidden: Boolean,
        minBytes: Long = DEFAULT_MIN_BYTES,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DuplicateGroup> {
        val groups = sizeGroups(index, showHidden, minBytes)
        val total = groups.values.sumOf { it.size }
        if (total == 0) { onProgress(0, 0); return emptyList() }
        var done = 0
        onProgress(0, total)
        val out = ArrayList<DuplicateGroup>()
        for ((size, files) in groups) {
            if (isCancelled()) return out.sortedWith(compareByDescending<DuplicateGroup> { it.wastedBytes }.thenByDescending { it.size })
            val byHash = HashMap<String, ArrayList<FileEntry>>()
            for (e in files) {
                if (isCancelled()) break
                val hash = runCatching { sha256(e.path) }.getOrNull() ?: continue
                byHash.getOrPut(hash) { ArrayList() }.add(e)
                done++
                onProgress(done, total)
            }
            for ((hash, same) in byHash) {
                if (same.size >= 2) out += DuplicateGroup(size, hash, same.sortedBy { it.path })
            }
        }
        return out.sortedWith(compareByDescending<DuplicateGroup> { it.wastedBytes }.thenByDescending { it.size }.thenBy { it.hash })
    }
}
