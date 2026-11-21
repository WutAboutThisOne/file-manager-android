package local.files.explorer.core

/** File-name rules for Android packages. */
object ApkNames {
    private val bundleExt = setOf("apks", "xapk", "apkm")
    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isApk(name: String) = ext(name) == "apk"
    fun isBundle(name: String) = ext(name) in bundleExt
    fun isPackage(name: String) = isApk(name) || isBundle(name)
}
