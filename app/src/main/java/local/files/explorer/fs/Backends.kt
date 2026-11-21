package local.files.explorer.fs

import android.content.Context
import local.files.explorer.core.Backend

/** Process-wide backend registry. */
object Backends {
    @Volatile var rootWrites: Boolean = false
    private val local = LocalBackend()
    private val root = RootBackend { rootWrites }
    @Volatile private var saf: SafBackend? = null
    val archive = ArchiveBackend()

    fun init(context: Context) {
        if (saf == null) saf = SafBackend(context)
    }

    fun get(kind: Backend): FsBackend = when (kind) {
        Backend.LOCAL -> local
        Backend.ROOT -> root
        Backend.SAF -> saf ?: error("Backends.init not called")
        Backend.ARCHIVE -> archive
    }
}
