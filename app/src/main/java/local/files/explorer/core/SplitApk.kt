package local.files.explorer.core

import java.io.File
import java.util.zip.ZipFile

/** What's inside an .apks / .xapk / .apkm split bundle. */
data class SplitBundle(
    val apkEntries: List<String>,
    val baseEntry: String?,
    val obbEntries: List<String>,
) {
    val splitCount: Int get() = apkEntries.size
    val isValid: Boolean get() = baseEntry != null
}

object SplitApk {
    fun isBundleName(name: String): Boolean = Names.extension(name).lowercase() in setOf("apks", "xapk", "apkm")

    fun inspect(file: File): SplitBundle = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        analyze(names)
    }

    fun analyze(entryNames: List<String>): SplitBundle {
        val apks = entryNames.filter { it.endsWith(".apk", ignoreCase = true) && !it.contains("../") }
        val obbs = entryNames.filter { it.endsWith(".obb", ignoreCase = true) }
        val base = apks.firstOrNull { it.substringAfterLast('/').equals("base.apk", true) }
            ?: apks.firstOrNull {
                val n = it.substringAfterLast('/').lowercase()
                !n.startsWith("split_") && !n.startsWith("config.") && !n.contains("config.")
            }
            ?: apks.singleOrNull()
        val ordered = listOfNotNull(base) + apks.filter { it != base }.sorted()
        return SplitBundle(ordered, base, obbs)
    }
}
