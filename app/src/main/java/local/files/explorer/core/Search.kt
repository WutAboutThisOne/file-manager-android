package local.files.explorer.core

/** One search match plus where it lives, for display ("in Documents/Taxes") and "Go to file location". */
data class SearchHit(
    val entry: FileEntry,
    /** Folder path relative to the search root, prefixed with the root's label. */
    val folderLabel: String,
    /** Crumbs from the search root down to the folder holding [entry] (needed to re-open SAF folders). */
    val parentCrumbs: List<Crumb> = emptyList(),
)

data class SearchProgress(
    val found: Int = 0,
    val foldersScanned: Int = 0,
    val done: Boolean = false,
    val limitHit: Boolean = false,
    val cancelled: Boolean = false,
)

object SearchMatch {
    /** Every whitespace-separated token must appear in the name (case-insensitive). */
    fun matches(name: String, query: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { name.contains(it, ignoreCase = true) }
    }
}

/**
 * Breadth-first search over any backend (java.io, SAF, …). Shallow matches come
 * first, results are delivered in batches while the walk is still running, and
 * the walk stops at [limit] matches (reported via [SearchProgress.limitHit]).
 */
class RecursiveSearcher(
    private val listDir: (FileEntry) -> List<FileEntry>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val dir: FileEntry, val label: String, val crumbs: List<Crumb>)

    fun search(
        root: FileEntry,
        rootLabel: String,
        query: String,
        recursive: Boolean,
        showHidden: Boolean,
        limit: Int = DEFAULT_LIMIT,
        rootCrumbs: List<Crumb> = listOf(Crumb(rootLabel, root.path)),
        batchMs: Long = 150,
        isCancelled: () -> Boolean = { false },
        onHits: (List<SearchHit>, SearchProgress) -> Unit,
    ): SearchProgress {
        val queue = ArrayDeque<Pending>()
        queue += Pending(root, rootLabel, rootCrumbs)
        val batch = mutableListOf<SearchHit>()
        var found = 0
        var folders = 0
        var lastEmit = clock()
        fun progress(done: Boolean, limitHit: Boolean = false, cancelled: Boolean = false) =
            SearchProgress(found, folders, done, limitHit, cancelled)

        while (queue.isNotEmpty()) {
            if (isCancelled()) {
                onHits(batch.toList(), progress(done = true, cancelled = true)); return progress(true, cancelled = true)
            }
            val p = queue.removeFirst()
            val children = runCatching { listDir(p.dir) }.getOrDefault(emptyList())
            folders++
            for (c in children) {
                if (!showHidden && c.isHidden) continue
                if (SearchMatch.matches(c.name, query)) {
                    batch += SearchHit(c, p.label, p.crumbs)
                    found++
                    if (found >= limit) {
                        onHits(batch.toList(), progress(done = true, limitHit = true))
                        return progress(true, limitHit = true)
                    }
                }
                if (recursive && c.isDirectory && !c.isSymlink) {
                    queue += Pending(c, "${p.label}/${c.name}", p.crumbs + Crumb(c.name, c.path))
                }
            }
            val now = clock()
            if (batch.isNotEmpty() && now - lastEmit >= batchMs) {
                onHits(batch.toList(), progress(done = false)); batch.clear(); lastEmit = now
            }
        }
        onHits(batch.toList(), progress(done = true))
        return progress(true)
    }

    companion object { const val DEFAULT_LIMIT = 1000 }
}

object FolderLabels {
    /** "Internal storage/DCIM/Camera" for the folder that holds [path]; falls back to the raw parent path. */
    fun parentLabel(path: String, roots: List<PathRoot>): String {
        val parent = Paths.parent(path) ?: return "/"
        return label(parent, roots)
    }

    fun label(folder: String, roots: List<PathRoot>): String {
        val f = Paths.normalize(folder)
        val root = roots.filter { Paths.isAncestorOrSelf(it.path, f) }.maxByOrNull { Paths.normalize(it.path).length } ?: return f
        val rp = Paths.normalize(root.path)
        val rel = if (f == rp) "" else f.removePrefix(if (rp == "/") "/" else "$rp/")
        return if (rel.isEmpty()) root.label else "${root.label}/$rel"
    }
}
