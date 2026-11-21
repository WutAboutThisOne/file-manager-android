package local.files.explorer.core

/** What an incoming "Open with File Manager" file turned out to be, judged by its contents (not its MIME type). */
/** [ARCHIVE] = a zip/7z/tar/… that isn't an app package; it opens in the archive browser. */
enum class PackageKind { APK, BUNDLE, ARCHIVE, NOT_PACKAGE }

object PackageSniffer {
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun looksLikeZip(header: ByteArray): Boolean =
        header.size >= 4 && (0 until 4).all { header[it] == ZIP_MAGIC[it] }

    /** An APK has AndroidManifest.xml at its root; a split bundle is a zip holding a base APK. */
    fun classify(entryNames: List<String>): PackageKind = when {
        "AndroidManifest.xml" in entryNames -> PackageKind.APK
        SplitApk.analyze(entryNames).isValid -> PackageKind.BUNDLE
        else -> PackageKind.NOT_PACKAGE
    }

    /** Files that are split bundles by their own markers (SAI / APKMirror / XAPK) or a base.apk + splits layout. */
    private val bundleMarkers = setOf("toc.pb", "manifest.json", "info.json", "meta.sai_v1.json", "meta.sai_v2.json", "icon.png")

    /**
     * 0.3.0 routing for incoming zips: an APK stays an APK, a split bundle stays a bundle, and any other
     * zip (even one that merely contains some .apk files) is an [PackageKind.ARCHIVE] for the archive browser.
     */
    fun classifyIncoming(entryNames: List<String>, displayName: String?): PackageKind {
        if ("AndroidManifest.xml" in entryNames) return PackageKind.APK
        val bundle = SplitApk.analyze(entryNames)
        if (bundle.isValid) {
            val byName = displayName != null && SplitApk.isBundleName(displayName)
            val topLevel = entryNames.filter { !it.contains('/') }
            val markers = topLevel.any { it.lowercase() in bundleMarkers && it.lowercase() != "icon.png" }
            val layout = bundle.apkEntries.any { it.substringAfterLast('/').equals("base.apk", true) } &&
                bundle.apkEntries.size > 1 && bundle.apkEntries.all { !it.contains('/') }
            if (byName || markers || layout) return PackageKind.BUNDLE
        }
        return PackageKind.ARCHIVE
    }

    /** Safe cache file name that carries the right extension for the installer code paths. */
    fun cacheName(displayName: String?, kind: PackageKind): String {
        val raw = displayName?.substringAfterLast('/')?.trim().orEmpty()
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trimStart('.').take(120).ifEmpty { "package" }
        return when (kind) {
            PackageKind.APK -> if (Names.extension(clean).equals("apk", true)) clean else "$clean.apk"
            PackageKind.BUNDLE -> if (SplitApk.isBundleName(clean)) clean else "${Names.splitExtension(clean).first}.apks"
            PackageKind.ARCHIVE, PackageKind.NOT_PACKAGE -> clean
        }
    }
}
