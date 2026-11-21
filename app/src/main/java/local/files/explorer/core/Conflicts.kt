package local.files.explorer.core

enum class ConflictPolicy { REPLACE, SKIP, KEEP_BOTH }

sealed interface Resolution {
    /** Write to [name]. [overwrite] means an existing item with that name must be replaced (or merged, for folders). */
    data class Write(val name: String, val overwrite: Boolean) : Resolution
    data object Skip : Resolution
}

object ConflictResolver {
    private val numbered = Regex("^(.*) \\((\\d+)\\)$")

    /** "photo.jpg" -> "photo (1).jpg", "photo (1).jpg" -> "photo (2).jpg", skipping names that exist. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, ext) = Names.splitExtension(name)
        val m = numbered.matchEntire(base)
        val stem = m?.groupValues?.get(1) ?: base
        var n = (m?.groupValues?.get(2)?.toIntOrNull() ?: 0) + 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /**
     * Decides where an item called [name] lands in a destination folder.
     * [sameLocation] is true when the source already lives in the destination
     * (pasting a copy into its own folder): REPLACE would destroy the source, so
     * that case always keeps both.
     */
    fun resolve(
        name: String,
        exists: (String) -> Boolean,
        policy: ConflictPolicy,
        sameLocation: Boolean = false,
    ): Resolution {
        if (!exists(name)) return Resolution.Write(name, overwrite = false)
        if (sameLocation) return Resolution.Write(uniqueName(name, exists), overwrite = false)
        return when (policy) {
            ConflictPolicy.REPLACE -> Resolution.Write(name, overwrite = true)
            ConflictPolicy.SKIP -> Resolution.Skip
            ConflictPolicy.KEEP_BOTH -> Resolution.Write(uniqueName(name, exists), overwrite = false)
        }
    }

    /** Names among [sourceNames] that already exist in the destination. */
    fun conflicts(sourceNames: List<String>, exists: (String) -> Boolean): List<String> =
        sourceNames.filter(exists)

    /**
     * Returns a human-readable reason the paste can't run, or null if it can.
     * Catches copying/moving a folder into itself or one of its descendants.
     */
    fun validatePaste(sourcePaths: List<String>, destDir: String, sourceIsDir: (String) -> Boolean): String? {
        val dest = Paths.normalize(destDir)
        for (src in sourcePaths) {
            val s = Paths.normalize(src)
            if (sourceIsDir(s) && Paths.isAncestorOrSelf(s, dest)) {
                return "Can't put \"${Paths.name(s)}\" inside itself"
            }
        }
        return null
    }
}
