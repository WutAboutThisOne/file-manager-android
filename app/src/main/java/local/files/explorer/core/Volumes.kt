package local.files.explorer.core

enum class VolumeState(val label: String) {
    MOUNTED("Mounted"),
    MOUNTED_READ_ONLY("Mounted (read-only)"),
    UNMOUNTED("Unmounted"),
    EJECTING("Ejecting"),
    CHECKING("Checking"),
    REMOVED("Removed"),
    BAD_REMOVAL("Removed unsafely"),
    UNMOUNTABLE("Unreadable"),
    NOFS("Unsupported format"),
    UNKNOWN("Unknown");

    val isReadable: Boolean get() = this == MOUNTED || this == MOUNTED_READ_ONLY

    companion object {
        /** Maps android.os.Environment.MEDIA_* strings (and `sm` states). */
        fun fromEnvironment(s: String?): VolumeState = when (s) {
            "mounted" -> MOUNTED
            "mounted_ro" -> MOUNTED_READ_ONLY
            "unmounted" -> UNMOUNTED
            "ejecting" -> EJECTING
            "checking" -> CHECKING
            "removed" -> REMOVED
            "bad_removal" -> BAD_REMOVAL
            "unmountable" -> UNMOUNTABLE
            "nofs" -> NOFS
            else -> UNKNOWN
        }
    }
}

enum class VolumeKind { INTERNAL, SD_CARD, USB }

data class VolumeInfo(
    val id: String,
    val label: String,
    val kind: VolumeKind,
    val path: String?,
    val state: VolumeState,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val uuid: String? = null,
    /** True if java.io can list [path] (All files access). */
    val directAccess: Boolean = false,
    /** Persisted SAF tree Uri for this volume, if the user granted one. */
    val safTree: String? = null,
    /** `sm` volume id when running with root, e.g. "public:179,65". */
    val rootVolumeId: String? = null,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
