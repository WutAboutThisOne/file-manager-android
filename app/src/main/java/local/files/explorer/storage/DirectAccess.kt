package local.files.explorer.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import local.files.explorer.core.SpaceCheck
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NotEnoughSpaceException(message: String) : IOException(message)

/**
 * Opens files handed to the app (content:// URIs, SAF documents) in place instead of copying them:
 * 1. the real file path behind the descriptor, when the app can read it directly;
 * 2. otherwise a seekable descriptor kept open and addressed as /proc/<pid>/fd/<n>, which
 *    java.io.File / RandomAccessFile (zip4j, 7z, java.util.zip) can use for random access.
 * Only when neither works does the caller copy to cache, after [ensureSpace].
 */
object DirectAccess {
    /** Tests switch this off to exercise the copy fallback. */
    @Volatile internal var enabled = true

    /** Free bytes on the volume holding [dir]. Replaceable in tests. */
    @Volatile internal var freeSpace: (File) -> Long = { dir ->
        runCatching {
            var d: File? = dir
            while (d != null && !d.exists()) d = d.parentFile
            val st = StatFs((d ?: dir).path)
            // A volume reporting zero total size can't be measured (treated as unknown, not full).
            if (st.totalBytes <= 0) Long.MAX_VALUE else st.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    /** Descriptors kept open for /proc fd paths, by path. */
    private val held = ConcurrentHashMap<String, ParcelFileDescriptor>()

    class Opened(val file: File, val viaDescriptor: Boolean, private val pfd: ParcelFileDescriptor? = null) {
        /** Closes the held descriptor (only if it is still the one registered for this path). */
        fun release() { val p = pfd ?: return; held.remove(file.path, p); runCatching { p.close() } }
    }

    fun isHeld(path: String) = held.containsKey(path)

    /** Closes the descriptor behind a /proc fd path (no-op for real paths). */
    fun release(path: String) { held.remove(path)?.let { runCatching { it.close() } } }

    fun spaceMessage(what: String, needed: Long, free: Long) = SpaceCheck.message(what, needed, free)

    /** Throws [NotEnoughSpaceException] unless [needed] bytes (plus a small margin) fit in [dir]. */
    fun ensureSpace(dir: File, needed: Long, what: String) {
        SpaceCheck.check(what, needed, freeSpace(dir), MARGIN)?.let { throw NotEnoughSpaceException(it) }
    }

    /** Keep a little room so filling the cache doesn't wedge the phone. */
    const val MARGIN = 32L * 1024 * 1024

    /** Size the provider reports for [uri], or -1. */
    fun sizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).length() } ?: -1
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { if (it.statSize >= 0) return it.statSize } }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }

    /**
     * Opens [uri] for in-place reading, or returns null if a copy is needed.
     * SecurityException / FileNotFoundException from the provider propagate.
     */
    fun open(context: Context, uri: Uri): Opened? {
        if (!enabled) return null
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }?.let { Opened(it, false) }
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fromDescriptor(pfd)
    }

    /** Takes ownership of [pfd]: closes it unless it ends up held for a /proc path. */
    fun fromDescriptor(pfd: ParcelFileDescriptor): Opened? {
        try {
            realPath(pfd)?.let { pfd.close(); return Opened(it, false) }
            if (seekableRegularFile(pfd)) {
                val path = "/proc/${Process.myPid()}/fd/${pfd.fd}"
                val f = File(path)
                if (f.canRead()) { held[path] = pfd; return Opened(f, true, pfd) }
            }
        } catch (_: Throwable) { }
        runCatching { pfd.close() }
        return null
    }

    /** The real file behind [pfd] if the app may read it by path. */
    fun realPath(pfd: ParcelFileDescriptor): File? = runCatching {
        val link = java.nio.file.Files.readSymbolicLink(java.nio.file.Paths.get("/proc/self/fd/${pfd.fd}")).toString()
        File(link).takeIf { link.startsWith("/") && it.isFile && it.canRead() }
    }.getOrNull()

    /** Regular file + lseek works = safe for random access (pipes and sockets are not). */
    fun seekableRegularFile(pfd: ParcelFileDescriptor): Boolean = runCatching {
        val st = Os.fstat(pfd.fileDescriptor)
        OsConstants.S_ISREG(st.st_mode) && Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR) >= 0
    }.getOrDefault(false)
}
