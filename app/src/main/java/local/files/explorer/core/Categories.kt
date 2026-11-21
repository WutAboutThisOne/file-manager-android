package local.files.explorer.core

/** A file found by the storage scan, with the flags needed for .nomedia / hidden filtering. */
data class IndexedFile(
    val entry: FileEntry,
    /** Some folder above it holds a .nomedia or is a dot-folder (MediaProvider rules). */
    val inNomediaTree: Boolean = false,
    /** The file or one of its folders starts with a dot. */
    val inHiddenPath: Boolean = false,
)

enum class Category(val label: String, val isMedia: Boolean) {
    IMAGES("Images", true),
    VIDEOS("Videos", true),
    AUDIO("Music", true),
    DOCUMENTS("Documents", false),
    APKS("APKs", false),
    ARCHIVES("Archives", false),
    DOWNLOADS("Downloads", false),
}

data class CategoryStats(val count: Int = 0, val bytes: Long = 0)

object Categories {
    private val docTypes = setOf(FileType.PDF, FileType.DOCUMENT, FileType.SPREADSHEET, FileType.PRESENTATION)
    private val docText = setOf("txt", "md")

    fun matches(e: FileEntry, c: Category, downloadsDir: String): Boolean = when (c) {
        Category.IMAGES -> e.type == FileType.IMAGE
        Category.VIDEOS -> e.type == FileType.VIDEO
        Category.AUDIO -> e.type == FileType.AUDIO
        Category.DOCUMENTS -> e.type in docTypes || e.extension.lowercase() in docText
        Category.APKS -> e.type == FileType.APK || e.type == FileType.APK_BUNDLE
        Category.ARCHIVES -> e.type == FileType.ARCHIVE
        Category.DOWNLOADS -> Paths.isAncestorOrSelf(downloadsDir, e.path) && e.path != Paths.normalize(downloadsDir)
    }

    /**
     * Hidden files only show when "Show hidden files" is on. With "Respect .nomedia"
     * on, Images/Videos/Music leave out anything inside a .nomedia (or dot) folder —
     * the same files a gallery or music player would skip.
     */
    fun visible(f: IndexedFile, c: Category?, respectNomedia: Boolean, showHidden: Boolean): Boolean {
        if (!showHidden && f.inHiddenPath) return false
        if (respectNomedia && c?.isMedia == true && f.inNomediaTree) return false
        return true
    }

    fun files(index: List<IndexedFile>, c: Category, downloadsDir: String, respectNomedia: Boolean, showHidden: Boolean): List<FileEntry> =
        index.asSequence()
            .filter { !it.entry.isDirectory && matches(it.entry, c, downloadsDir) && visible(it, c, respectNomedia, showHidden) }
            .map { it.entry }.toList()

    fun stats(index: List<IndexedFile>, downloadsDir: String, respectNomedia: Boolean, showHidden: Boolean): Map<Category, CategoryStats> {
        val counts = IntArray(Category.entries.size)
        val bytes = LongArray(Category.entries.size)
        for (f in index) {
            if (f.entry.isDirectory) continue
            for (c in Category.entries) {
                if (matches(f.entry, c, downloadsDir) && visible(f, c, respectNomedia, showHidden)) {
                    counts[c.ordinal]++; bytes[c.ordinal] += f.entry.size
                }
            }
        }
        return Category.entries.associateWith { CategoryStats(counts[it.ordinal], bytes[it.ordinal]) }
    }
}

enum class SizeFilter(val label: String, val minBytes: Long) {
    OVER_10MB("> 10 MB", 10_000_000),
    OVER_100MB("> 100 MB", 100_000_000),
    OVER_1GB("> 1 GB", 1_000_000_000),
}

object LargestFiles {
    fun select(index: List<IndexedFile>, filter: SizeFilter, showHidden: Boolean, limit: Int = 1000): List<FileEntry> =
        index.asSequence()
            .filter { !it.entry.isDirectory && it.entry.size >= filter.minBytes && (showHidden || !it.inHiddenPath) }
            .map { it.entry }
            .sortedWith(compareByDescending<FileEntry> { it.size }.thenBy { it.path })
            .take(limit).toList()
}

/** Aggregated folder sizes, built from the scan so drilling down is instant. */
class FolderNode(val path: String, val name: String) {
    var bytes: Long = 0; internal set
    var files: Int = 0; internal set
    var directBytes: Long = 0; internal set
    var directFiles: Int = 0; internal set
    internal val childMap = HashMap<String, FolderNode>()
    val children: List<FolderNode> get() = childMap.values.sortedWith(compareByDescending<FolderNode> { it.bytes }.thenBy { it.name.lowercase() })
}

object FolderSizes {
    fun build(rootPath: String, rootName: String, files: Iterable<FileEntry>): FolderNode {
        val root = FolderNode(Paths.normalize(rootPath), rootName)
        val prefix = if (root.path == "/") "/" else root.path + "/"
        for (f in files) {
            if (f.isDirectory) continue
            val p = Paths.normalize(f.path)
            if (!p.startsWith(prefix)) continue
            val segs = p.substring(prefix.length).split('/')
            var node = root
            node.bytes += f.size; node.files++
            for (i in 0 until segs.size - 1) {
                val s = segs[i]
                node = node.childMap.getOrPut(s) { FolderNode(Paths.child(node.path, s), s) }
                node.bytes += f.size; node.files++
            }
            node.directBytes += f.size; node.directFiles++
        }
        return root
    }

    fun find(root: FolderNode, path: String): FolderNode? {
        val p = Paths.normalize(path)
        if (p == root.path) return root
        if (!Paths.isAncestorOrSelf(root.path, p)) return null
        val rel = if (root.path == "/") p.removePrefix("/") else p.removePrefix(root.path + "/")
        var node = root
        for (s in rel.split('/').filter { it.isNotEmpty() }) node = node.childMap[s] ?: return null
        return node
    }

    /** Files that sit directly in [folderPath] (not in subfolders), biggest first. */
    fun directEntries(files: Iterable<FileEntry>, folderPath: String, showHidden: Boolean = true): List<FileEntry> {
        val folder = Paths.normalize(folderPath)
        return files.asSequence()
            .filter { !it.isDirectory && Paths.parent(it.path) == folder }
            .filter { showHidden || !it.isHidden }
            .sortedWith(compareByDescending<FileEntry> { it.size }.thenBy { it.name.lowercase() })
            .toList()
    }
}

/** Copies/moves big enough to be worth a progress notification. */
object LargeOp {
    const val BYTES = 50_000_000L
    const val ITEMS = 20
    fun isLarge(sources: List<FileEntry>): Boolean =
        sources.any { it.isDirectory } || sources.size >= ITEMS || sources.sumOf { it.size } >= BYTES
}
