package local.files.explorer.core

/** Which file-system implementation a path belongs to. */
enum class Backend { LOCAL, ROOT, SAF, ARCHIVE }

/**
 * One row in the browser. [path] is an absolute POSIX path for [Backend.LOCAL]
 * and [Backend.ROOT], and a document Uri string for [Backend.SAF].
 */
data class FileEntry(
    val name: String,
    val path: String,
    val backend: Backend = Backend.LOCAL,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val hasNomedia: Boolean = false,
    val permissions: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val isSymlink: Boolean = false,
    val mimeType: String? = null,
    val childCount: Int? = null,
    /** Bytes stored inside an archive (entries of [Backend.ARCHIVE] only). */
    val compressedSize: Long? = null,
) {
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = Names.extension(name)
    val type: FileType get() = if (isDirectory) FileType.FOLDER else FileType.of(name, mimeType)
}

enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION,
    ARCHIVE, APK, APK_BUNDLE, CODE, TEXT, FONT, OTHER;

    companion object {
        private val byExt: Map<String, FileType> = buildMap {
            listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "dng", "raw").forEach { put(it, IMAGE) }
            listOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv").forEach { put(it, VIDEO) }
            listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid", "midi", "wma").forEach { put(it, AUDIO) }
            put("pdf", PDF)
            listOf("doc", "docx", "odt", "rtf", "epub").forEach { put(it, DOCUMENT) }
            listOf("xls", "xlsx", "ods", "csv", "tsv").forEach { put(it, SPREADSHEET) }
            listOf("ppt", "pptx", "odp", "key").forEach { put(it, PRESENTATION) }
            listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "img", "iso").forEach { put(it, ARCHIVE) }
            put("apk", APK)
            listOf("apks", "xapk", "apkm").forEach { put(it, APK_BUNDLE) }
            listOf("kt", "java", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "sh", "json", "xml", "html", "css", "yml", "yaml", "toml", "gradle", "kts", "prop", "rc").forEach { put(it, CODE) }
            listOf("txt", "md", "log", "ini", "conf", "cfg", "nfo", "srt", "vtt").forEach { put(it, TEXT) }
            listOf("ttf", "otf", "woff", "woff2").forEach { put(it, FONT) }
        }

        fun of(name: String, mime: String? = null): FileType {
            byExt[Names.extension(name).lowercase()]?.let { return it }
            return when {
                mime == null -> OTHER
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime.startsWith("text/") -> TEXT
                mime == "application/pdf" -> PDF
                mime == "application/vnd.android.package-archive" -> APK
                else -> OTHER
            }
        }
    }
}

object Names {
    /** Extension without the dot; dot-files like ".bashrc" have none. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    /** Splits "photo.jpg" -> ("photo", ".jpg"); ".nomedia" -> (".nomedia", ""). */
    fun splitExtension(name: String): Pair<String, String> {
        val ext = extension(name)
        return if (ext.isEmpty()) name to "" else name.substring(0, name.length - ext.length - 1) to ".$ext"
    }

    private val illegal = Regex("[/\\u0000]")

    /** Returns an error message for an invalid file name or null if valid. */
    fun validate(name: String): String? = when {
        name.isBlank() -> "Name can't be empty"
        name == "." || name == ".." -> "That name is reserved"
        illegal.containsMatchIn(name) -> "Names can't contain /"
        name.toByteArray().size > 255 -> "Name is too long"
        else -> null
    }
}
