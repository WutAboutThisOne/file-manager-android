package local.files.explorer.core

const val NOMEDIA = ".nomedia"

/** True if a folder listing (unfiltered, including hidden) contains a `.nomedia` file. */
fun listingHasNomedia(entries: List<FileEntry>): Boolean =
    entries.any { !it.isDirectory && it.name == NOMEDIA }


/** Abstracts "does this directory contain a .nomedia file" so the rules are unit-testable. */
fun interface NomediaProbe {
    fun hasNomedia(dir: String): Boolean
}

/**
 * What "Respect .nomedia" means in File Manager (mirrors Android's MediaProvider rules):
 *
 *  * A file is *media-hidden* if any directory between it and its storage root
 *    contains a `.nomedia` file, or is itself a dot-directory (MediaProvider
 *    treats hidden directories the same way).
 *  * When respect is ON: media-hidden files never get image/video thumbnails in
 *    the browser, are excluded from the Recent view, and folders that carry a
 *    `.nomedia` show a small badge. The folders stay browsable: a file manager
 *    must still let you reach your own files.
 *  * When respect is OFF: everything is thumbnailed and shown in Recent; no badges.
 */
class NomediaPolicy(
    private val probe: NomediaProbe,
    val respect: Boolean,
    private val stopAt: List<String> = emptyList(),
) {
    private val cache = HashMap<String, Boolean>()

    private fun dirHides(dir: String): Boolean = cache.getOrPut(dir) {
        Paths.name(dir).let { it.startsWith(".") && it != "/" } || probe.hasNomedia(dir)
    }

    /** True if [path] (a file or directory) lives in a .nomedia/hidden tree, ignoring the respect toggle. */
    fun isInNomediaTree(path: String, isDirectory: Boolean = false): Boolean {
        val roots = stopAt.map(Paths::normalize)
        var dir: String? = if (isDirectory) Paths.normalize(path) else Paths.parent(path)
        while (dir != null) {
            if (dirHides(dir)) return true
            if (dir in roots) return false
            dir = Paths.parent(dir)
        }
        return false
    }

    fun hiddenFromMedia(path: String, isDirectory: Boolean = false): Boolean =
        respect && isInNomediaTree(path, isDirectory)

    fun allowThumbnail(entry: FileEntry): Boolean =
        entry.backend != Backend.LOCAL || !hiddenFromMedia(entry.path)

    fun showBadge(entry: FileEntry): Boolean = respect && entry.isDirectory && entry.hasNomedia

    /** Filters a media-style listing (e.g. Recent). */
    fun filterMedia(entries: List<FileEntry>): List<FileEntry> =
        if (!respect) entries else entries.filterNot { hiddenFromMedia(it.path, it.isDirectory) }
}
