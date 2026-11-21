package local.files.explorer.fs

import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import local.files.explorer.core.NOMEDIA
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Plain java.io access (works under All files access and in tests). */
open class LocalBackend : FsBackend {
    override val kind = Backend.LOCAL

    protected open fun entry(f: File): FileEntry {
        val dir = f.isDirectory
        val children = if (dir) f.list() else null
        return FileEntry(
            name = f.name,
            path = f.absolutePath,
            backend = kind,
            isDirectory = dir,
            size = if (dir) 0L else f.length(),
            lastModified = f.lastModified(),
            hasNomedia = dir && children?.contains(NOMEDIA) == true,
            childCount = children?.size,
        )
    }

    override fun list(dir: String): List<FileEntry> {
        val files = File(dir).listFiles() ?: throw IOException("Can't read $dir")
        return files.map(::entry)
    }

    override fun stat(path: String): FileEntry? = File(path).takeIf { it.exists() }?.let(::entry)

    override fun findChild(dir: String, name: String): String? =
        File(dir, name).takeIf { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }?.absolutePath

    override fun openInput(path: String): InputStream = FileInputStream(path)

    override fun createFile(dir: String, name: String, mime: String?): Pair<String, OutputStream> {
        val f = File(dir, name)
        return f.absolutePath to FileOutputStream(f)
    }

    override fun mkdir(dir: String, name: String): String {
        val f = File(dir, name)
        if (!f.isDirectory && !f.mkdirs()) throw IOException("Couldn't create ${f.name}")
        return f.absolutePath
    }

    override fun delete(path: String): Boolean {
        val f = File(path)
        // don't follow symlinked directories when deleting
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return f.delete()
        return f.deleteRecursively()
    }

    override fun rename(path: String, newName: String): String? {
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (dst.exists() && !dst.equals(src) && !dst.name.equals(src.name, ignoreCase = true)) return null
        return if (src.renameTo(dst)) dst.absolutePath else null
    }

    override fun moveFast(path: String, destDir: String, newName: String): String? {
        val dst = File(destDir, newName)
        if (dst.exists()) return null
        return if (File(path).renameTo(dst)) dst.absolutePath else null
    }

    override fun setLastModified(path: String, time: Long) {
        runCatching { File(path).setLastModified(time) }
    }
}
