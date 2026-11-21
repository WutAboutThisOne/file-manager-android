package local.files.explorer.core

/**
 * Parses the output of [RootListingParser.script]: `stat -c` lines separated by
 * tabs, then a marker, then the names of symlinks that point at directories.
 */
object RootListingParser {
    const val MARKER = "--FOLIO-LINKDIRS--"

    /** Shell script that lists [dir] (including dot-files) in a parseable form. */
    fun script(dir: String): String {
        val q = Paths.shellQuote(dir)
        val fmt = "%A\t%U\t%G\t%s\t%Y\t%n"
        return "cd $q && { stat -c '$fmt' -- .[!.]* ..?* * 2>/dev/null; echo '$MARKER'; " +
            "for f in .[!.]* ..?* *; do [ -L \"\$f\" ] && [ -d \"\$f\" ] && echo \"\$f\"; done; }"
    }

    fun parse(dir: String, lines: List<String>): List<FileEntry> {
        val markerAt = lines.indexOf(MARKER)
        val statLines = if (markerAt >= 0) lines.subList(0, markerAt) else lines
        val linkDirs = if (markerAt >= 0) lines.subList(markerAt + 1, lines.size).filter { it.isNotEmpty() }.toSet() else emptySet()
        return statLines.mapNotNull { parseLine(dir, it, linkDirs) }
    }

    private fun parseLine(dir: String, line: String, linkDirs: Set<String>): FileEntry? {
        val parts = line.split('\t', limit = 6)
        if (parts.size < 6) return null
        val (mode, owner, group, size, mtime, name) = parts
        if (name.isEmpty() || name == "." || name == ".." || mode.length < 10) return null
        val type = mode[0]
        val isLink = type == 'l'
        val isDir = type == 'd' || (isLink && name in linkDirs)
        return FileEntry(
            name = name,
            path = Paths.child(dir, name),
            backend = Backend.ROOT,
            isDirectory = isDir,
            size = if (isDir) 0L else size.toLongOrNull() ?: 0L,
            lastModified = (mtime.toLongOrNull() ?: 0L) * 1000L,
            permissions = mode,
            owner = owner,
            group = group,
            isSymlink = isLink,
        )
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}

/** One line of `sm list-volumes all`, e.g. "public:179,65 mounted 1234-ABCD". */
data class SmVolume(val id: String, val state: String, val uuid: String?) {
    val isPublic: Boolean get() = id.startsWith("public:")
    val isMounted: Boolean get() = state == "mounted" || state == "mounted_ro"
}

object SmParser {
    fun parseVolumes(lines: List<String>): List<SmVolume> = lines.mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 2 || p[0].isEmpty()) return@mapNotNull null
        if (!p[0].contains(':') && !p[0].contains(';') && p[0] != "private") return@mapNotNull null
        SmVolume(p[0], p[1], p.getOrNull(2)?.takeUnless { it == "null" })
    }

    private val volId = Regex("^[a-z]+:[0-9]+,[0-9]+$")

    /** Only public (removable) volume ids are ever passed to `sm mount/unmount`. */
    fun mountCommand(v: SmVolume, mount: Boolean): String? {
        if (!v.isPublic || !volId.matches(v.id)) return null
        return (if (mount) "sm mount " else "sm unmount ") + v.id
    }
}

/** Parses `find … -exec stat -c '<fmt>' {} +` output where %n is an absolute path. */
object RootFindParser {
    const val DEFAULT_LIMIT = 500

    /** Uses the longest token for find's glob; callers filter on every token afterwards. */
    fun script(dir: String, query: String, limit: Int = DEFAULT_LIMIT): String {
        val token = query.trim().split(Regex("\\s+")).maxByOrNull { it.length } ?: ""
        val glob = Paths.shellQuote("*" + token.replace("*", "").replace("?", "").replace("[", "") + "*")
        return "find ${Paths.shellQuote(dir)} -mindepth 1 -iname $glob -exec stat -c '%A\t%U\t%G\t%s\t%Y\t%n' {} + 2>/dev/null | head -n $limit"
    }

    fun parse(lines: List<String>): List<FileEntry> = lines.mapNotNull { line ->
        val p = line.split('\t', limit = 6)
        if (p.size < 6 || p[0].length < 10) return@mapNotNull null
        val path = p[5]
        FileEntry(
            name = Paths.name(path), path = Paths.normalize(path), backend = Backend.ROOT,
            isDirectory = p[0][0] == 'd', size = p[3].toLongOrNull() ?: 0L,
            lastModified = (p[4].toLongOrNull() ?: 0L) * 1000L,
            permissions = p[0], owner = p[1], group = p[2], isSymlink = p[0][0] == 'l',
        )
    }
}
