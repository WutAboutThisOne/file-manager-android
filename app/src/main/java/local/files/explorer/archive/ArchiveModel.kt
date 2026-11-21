package local.files.explorer.archive

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Archive containers the app can browse. Detection is by name for files, by magic bytes for incoming streams. */
enum class ArchiveFormat(val label: String, val canWrite: Boolean = false, val randomAccess: Boolean = false) {
    ZIP("ZIP", canWrite = true, randomAccess = true),
    SEVEN_Z("7z", canWrite = true, randomAccess = true),
    TAR("TAR"), TAR_GZ("TAR.GZ"), TAR_XZ("TAR.XZ"), TAR_BZ2("TAR.BZ2"),
    GZ("GZ"), XZ("XZ"), BZ2("BZ2");

    val isSingleFile: Boolean get() = this == GZ || this == XZ || this == BZ2

    companion object {
        fun of(name: String): ArchiveFormat? {
            val n = name.lowercase()
            return when {
                n.endsWith(".zip") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".tar") -> TAR
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> TAR_BZ2
                n.endsWith(".gz") -> GZ
                n.endsWith(".xz") -> XZ
                n.endsWith(".bz2") -> BZ2
                else -> null
            }
        }

        /** Magic-byte sniffing (first 512+ bytes) for files that arrive without a trustworthy name. */
        fun sniff(header: ByteArray): ArchiveFormat? {
            fun at(i: Int, vararg b: Int) = header.size >= i + b.size && b.indices.all { header[i + it] == b[it].toByte() }
            return when {
                at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> ZIP
                at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SEVEN_Z
                at(257, 'u'.code, 's'.code, 't'.code, 'a'.code, 'r'.code) -> TAR
                at(0, 0x1F, 0x8B) -> GZ
                at(0, 0xFD, '7'.code, 'z'.code, 'X'.code, 'Z'.code, 0x00) -> XZ
                at(0, 'B'.code, 'Z'.code, 'h'.code) -> BZ2
                else -> null
            }
        }

        fun isArchiveName(name: String) = of(name) != null
    }
}

/** One entry as stored in the archive, with its path already made safe. */
data class ArchiveItem(
    /** Safe relative path, '/'-separated, no leading slash, no "." or ".." segments. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long = -1,
    val lastModified: Long = 0,
    val encrypted: Boolean = false,
    /** Reader-specific key (raw entry name) used to open the entry. */
    val key: String = path,
) {
    val name: String get() = path.substringAfterLast('/')
}

open class ArchiveException(message: String) : IOException(message)
class PasswordRequiredException(message: String = "This archive is password protected.") : ArchiveException(message)
class WrongPasswordException(message: String = "Wrong password.") : ArchiveException(message)
class ArchiveBombException(message: String) : ArchiveException(message)
class CorruptArchiveException(message: String) : ArchiveException(message)

object EntryNames {
    /**
     * Normalises a raw entry name into a safe relative path, or null if it must be skipped
     * (absolute paths, drive letters, "..", NUL, empty). Backslashes count as separators.
     */
    fun safe(raw: String): String? {
        if (raw.isEmpty() || raw.contains('\u0000')) return null
        var s = raw.replace('\\', '/')
        if (s.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(s)) return null
        val out = ArrayList<String>()
        for (seg in s.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> out += seg
            }
        }
        s = out.joinToString("/")
        return s.ifEmpty { null }
    }
}

/** Directory view over a flat entry list, synthesising folders that the archive only implies. */
class ArchiveTree(raw: List<ArchiveItem>) {
    private val byPath = LinkedHashMap<String, ArchiveItem>()
    private val children = HashMap<String, LinkedHashMap<String, ArchiveItem>>()
    var skippedUnsafe = 0; private set
    val items: Collection<ArchiveItem> get() = byPath.values
    val totalSize: Long
    val totalCompressed: Long
    val anyEncrypted: Boolean

    init {
        for (it0 in raw) {
            val safe = EntryNames.safe(it0.path)
            if (safe == null) { skippedUnsafe++; continue }
            val it = it0.copy(path = safe)
            // Implied parent folders.
            var parent = ""
            val segs = safe.split('/')
            for (i in 0 until segs.size - 1) {
                val p = if (parent.isEmpty()) segs[i] else "$parent/${segs[i]}"
                if (byPath[p]?.isDirectory != true) {
                    if (byPath[p] == null) put(ArchiveItem(p, true, 0, lastModified = it.lastModified))
                }
                parent = p
            }
            val existing = byPath[safe]
            if (existing == null || (existing.isDirectory && !it.isDirectory && existing.size == 0L && !hasChildren(safe))) put(it)
            else if (existing.isDirectory && it.isDirectory) byPath[safe] = it.copy() // explicit dir entry wins (mtime)
        }
        totalSize = byPath.values.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        totalCompressed = byPath.values.filter { !it.isDirectory }.sumOf { it.compressedSize.coerceAtLeast(0) }
        anyEncrypted = byPath.values.any { it.encrypted }
    }

    private fun hasChildren(p: String) = children[p]?.isNotEmpty() == true

    private fun put(item: ArchiveItem) {
        byPath[item.path] = item
        val parent = item.path.substringBeforeLast('/', "")
        children.getOrPut(parent) { LinkedHashMap() }[item.name] = item
    }

    fun list(dir: String): List<ArchiveItem> = children[dir.trim('/')]?.values?.toList() ?: emptyList()
    fun get(path: String): ArchiveItem? = byPath[path.trim('/')]
    fun isDirectory(path: String): Boolean = path.trim('/').isEmpty() || byPath[path.trim('/')]?.isDirectory == true
    val fileCount: Int get() = byPath.values.count { !it.isDirectory }
}

/**
 * Limits for extraction, so a hostile archive can't fill the phone.
 * An entry may not grow past its declared size (lying headers), and once more than
 * [ratioFloorBytes] came out of an archive, output may not exceed [maxRatio] x the archive size.
 */
/**
 * Zip-bomb guards. There is no fixed size cap any more: an entry may be as large as the archive
 * honestly declares, but it must produce exactly the bytes it declares (over by more than
 * [declaredSlack] stops at once, any mismatch at the end is an error), it may not expand beyond
 * [maxRatio]x the archive once past [ratioFloorBytes], and extraction is refused up front when
 * the total doesn't fit the destination's free space (see FileOperationEngine.transfer).
 */
data class BombLimits(
    val maxRatio: Long = 1000,
    val ratioFloorBytes: Long = 256L * 1024 * 1024,
    val declaredSlack: Long = 1024 * 1024,
)

class GuardedInputStream(
    input: InputStream,
    private val entryName: String,
    /** -1 when the format doesn't record the size (e.g. plain .gz streams). */
    private val declaredSize: Long,
    private val archiveBytes: Long,
    private val limits: BombLimits = BombLimits(),
) : FilterInputStream(input) {
    var count = 0L; private set
    private var ended = false

    private fun add(n: Int) {
        if (n <= 0) return
        count += n
        if (declaredSize >= 0 && count > declaredSize + limits.declaredSlack)
            throw ArchiveBombException("\u201c$entryName\u201d is larger than the archive says. Stopped to protect your storage.")
        if (count > limits.ratioFloorBytes && count > limits.maxRatio * archiveBytes.coerceAtLeast(1))
            throw ArchiveBombException("\u201c$entryName\u201d expands far beyond the archive's size (possible zip bomb). Stopped.")
    }

    /** End of data: the byte count must match what the archive declared. */
    private fun end() {
        if (ended) return
        ended = true
        if (declaredSize >= 0 && count != declaredSize) {
            if (count > declaredSize) throw ArchiveBombException("\u201c$entryName\u201d is larger than the archive says (${count} bytes, declared $declaredSize). Stopped.")
            throw CorruptArchiveException("\u201c$entryName\u201d is shorter than the archive says (${count} of $declaredSize bytes). The archive may be damaged or truncated.")
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) else end() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it) else if (it < 0) end() }
    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it.toInt().coerceAtMost(Int.MAX_VALUE)) }
}

/** Quick pre-check when an archive is opened: declared sizes that dwarf the file are refused for extraction. */
object BombCheck {
    fun suspicious(tree: ArchiveTree, archiveBytes: Long, limits: BombLimits = BombLimits()): Boolean =
        tree.totalSize > limits.ratioFloorBytes && tree.totalSize > limits.maxRatio * archiveBytes.coerceAtLeast(1)
}
