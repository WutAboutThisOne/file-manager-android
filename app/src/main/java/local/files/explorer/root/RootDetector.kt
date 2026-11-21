package local.files.explorer.root

import java.io.File

enum class RootState {
    /** Detection hasn't finished yet. */
    CHECKING,
    /** No su binary / root manager found. Root UI stays hidden. */
    NOT_AVAILABLE,
    /** su exists but the root manager refused (or timed out). */
    DENIED,
    /** We hold a root shell. */
    GRANTED;

    val isGranted: Boolean get() = this == GRANTED
}

/** The side-effecting bits of root detection, swappable in tests. */
interface RootProbe {
    /** Non-prompting: has the root manager already granted us? null = unknown. */
    fun alreadyGranted(): Boolean?

    /** Cheap file-system check for an su binary. Must never prompt. */
    fun suBinaryPresent(): Boolean

    /** Opens a root shell; this shows the Magisk/KernelSU prompt. true=granted, false=denied. */
    fun requestRoot(): Boolean
}

/**
 * Decides the root state with layered fallbacks so a broken or hostile
 * environment can never crash the app or show root UI on unrooted phones:
 * 1. already granted (no prompt) -> GRANTED
 * 2. no su binary and not [force]d -> NOT_AVAILABLE (never prompts)
 * 3. ask the root manager; any exception -> NOT_AVAILABLE
 */
class RootDetector(private val probe: RootProbe) {
    fun detect(force: Boolean = false): RootState {
        val cached = runCatching { probe.alreadyGranted() }.getOrNull()
        if (cached == true) return RootState.GRANTED
        val hasSu = runCatching { probe.suBinaryPresent() }.getOrDefault(false)
        if (!hasSu && !force) return RootState.NOT_AVAILABLE
        val granted = runCatching { probe.requestRoot() }
        return when {
            granted.isFailure -> if (hasSu) RootState.DENIED else RootState.NOT_AVAILABLE
            granted.getOrDefault(false) -> RootState.GRANTED
            hasSu -> RootState.DENIED
            else -> RootState.NOT_AVAILABLE
        }
    }

    companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/magisk/su", "/system/bin/.ext/su",
        )

        /** Looks for su in the well-known locations and on PATH. */
        fun findSu(path: String? = System.getenv("PATH"), exists: (String) -> Boolean = { File(it).exists() }): Boolean {
            val fromPath = path.orEmpty().split(':').filter { it.isNotBlank() }.map { "${it.trimEnd('/')}/su" }
            return (SU_PATHS + fromPath).any { runCatching { exists(it) }.getOrDefault(false) }
        }
    }
}
