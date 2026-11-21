package local.files.explorer.fs

import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import java.io.InputStream
import java.io.OutputStream

/** Minimal file-system surface shared by java.io, the root shell, and SAF. */
interface FsBackend {
    val kind: Backend

    fun list(dir: String): List<FileEntry>
    fun stat(path: String): FileEntry?

    /** Path/uri of an existing child, or null. */
    fun findChild(dir: String, name: String): String?
    fun exists(dir: String, name: String): Boolean = findChild(dir, name) != null

    fun openInput(path: String): InputStream
    /** Creates (or truncates) [name] in [dir]; returns its path and a stream. */
    fun createFile(dir: String, name: String, mime: String? = null): Pair<String, OutputStream>
    fun mkdir(dir: String, name: String): String
    /** Recursive delete. */
    fun delete(path: String): Boolean
    /** Returns the new path, or null on failure. */
    fun rename(path: String, newName: String): String?
    /** Fast same-volume move; return null if unsupported so the engine falls back to copy+delete. */
    fun moveFast(path: String, destDir: String, newName: String): String? = null
    fun setLastModified(path: String, time: Long) {}
}
