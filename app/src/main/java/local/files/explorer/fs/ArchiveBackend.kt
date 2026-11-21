package local.files.explorer.fs

import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.archive.ArchiveItem
import local.files.explorer.archive.ArchiveReader
import local.files.explorer.archive.ArchiveReaders
import local.files.explorer.archive.ArchiveTree
import local.files.explorer.archive.BombCheck
import local.files.explorer.archive.BombLimits
import local.files.explorer.archive.GuardedInputStream
import local.files.explorer.archive.PasswordRequiredException
import local.files.explorer.archive.WrongPasswordException
import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import local.files.explorer.apk.BinaryXml
import local.files.explorer.apk.ResourceTable
import local.files.explorer.core.ApkNames
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only view of archives. Paths look like "<archive file>!" for the root and
 * "<archive file>!/dir/file.txt" inside. The archive file is a readable path: the real file,
 * a /proc/<pid>/fd/<n> path for a held descriptor, or (last resort) a cache copy.
 * APKs and split bundles are browsed as zips; compiled XML in APKs is decoded on the fly.
 */
class ArchiveBackend(private val limits: () -> BombLimits = { BombLimits() }) : FsBackend {
    override val kind = Backend.ARCHIVE

    /** Where an archive really lives, so "Extract here" can target its folder even when we browse a cache copy. */
    data class Origin(val backend: Backend, val parentDir: String, val parentLabel: String)

    inner class Session(
        val file: File, val format: ArchiveFormat, val displayName: String, val origin: Origin?,
        /** Runs once when the session closes (e.g. releases a held file descriptor). */
        private val onClose: (() -> Unit)? = null,
    ) {
        @Volatile var password: CharArray? = null
        /** APK sessions: hand out compiled XML (manifest, res/...xml) as readable text. */
        @Volatile var decodeXml: Boolean = true
        private var tableCache: ResourceTable? = null
        private var tableTried = false

        /** True for an APK (has a root AndroidManifest.xml) or an .apk-named file. */
        val isApk: Boolean by lazy {
            ApkNames.isApk(displayName) || (format == ArchiveFormat.ZIP && runCatching {
                val t = tree()
                t.get("AndroidManifest.xml")?.isDirectory == false && (t.get("classes.dex") != null || t.get("resources.arsc") != null)
            }.getOrDefault(false))
        }

        /** True for a split bundle (.apks/.xapk/.apkm). */
        val isApkBundle: Boolean get() = ApkNames.isBundle(displayName)

        /** resources.arsc of an APK, parsed once (null if missing or unreadable). */
        fun resources(): ResourceTable? = synchronized(this) {
            if (tableTried) return tableCache
            tableTried = true
            tableCache = runCatching {
                val item = tree().get("resources.arsc") ?: return null
                GuardedInputStream(open(item), item.name, item.size, file.length(), limits()).use { ResourceTable.parse(it.readBytes()) }
            }.getOrNull()
            tableCache
        }
        private var reader: ArchiveReader? = null
        private var treeCache: ArchiveTree? = null
        val root: String get() = file.absolutePath + SEP

        @Synchronized fun tree(): ArchiveTree {
            treeCache?.let { return it }
            val r = reader ?: ArchiveReaders.open(file, format, password).also { reader = it }
            return ArchiveTree(r.items()).also { treeCache = it }
        }

        @Synchronized fun open(item: ArchiveItem): InputStream {
            tree()
            if (item.encrypted && password == null) throw PasswordRequiredException()
            return reader!!.open(item)
        }

        /** Tries [pw]; on success it's kept (in memory only) for this session. */
        @Synchronized fun unlock(pw: CharArray) {
            val r = try { ArchiveReaders.open(file, format, pw) } catch (e: PasswordRequiredException) { throw WrongPasswordException() }
            try { r.verifyPassword() } catch (t: Throwable) { r.close(); throw t }
            reader?.close()
            reader = r; treeCache = null; password = pw
        }

        val suspicious: Boolean get() = BombCheck.suspicious(tree(), file.length(), limits())

        @Synchronized fun close() {
            runCatching { reader?.close() }; reader = null; treeCache = null; tableCache = null; tableTried = false
        }

        /** Final close: also runs [onClose]. */
        fun dispose() { close(); runCatching { onClose?.invoke() } }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Registers [file] and returns its root path. Re-registering the same file keeps its password. */
    fun register(file: File, format: ArchiveFormat, displayName: String = file.name, origin: Origin? = null, onClose: (() -> Unit)? = null): String {
        val key = file.absolutePath
        val old = sessions[key]
        if (old != null && old.format == format && old.file.length() == file.length() && old.displayName == displayName) {
            onClose?.invoke() // already open through the existing session
            return old.root
        }
        old?.dispose()
        sessions[key] = Session(file, format, displayName, origin, onClose)
        return key + SEP
    }

    fun session(path: String): Session? = split(path)?.first

    fun isEmpty() = sessions.isEmpty()

    fun closeAll() { sessions.values.forEach { it.dispose() }; sessions.clear() }

    /** (session, inner path without slashes at ends). */
    fun split(path: String): Pair<Session, String>? {
        val idx = path.indexOf(SEP)
        if (idx < 0) return null
        // Longest registered archive prefix wins (archive names can contain '!').
        var best: Session? = null
        for ((k, s) in sessions) {
            if (path.startsWith(k + SEP) && (path.length == k.length + 1 || path[k.length + 1] == '/') && (best == null || k.length > best.file.absolutePath.length)) best = s
        }
        val s = best ?: return null
        return s to path.substring(s.file.absolutePath.length + 1).trim('/')
    }

    private fun entryFor(s: Session, item: ArchiveItem) = FileEntry(
        name = item.name,
        path = s.root + "/" + item.path,
        backend = Backend.ARCHIVE,
        isDirectory = item.isDirectory,
        size = if (item.isDirectory) 0 else item.size,
        lastModified = item.lastModified,
        mimeType = null,
        childCount = if (item.isDirectory) s.tree().list(item.path).size else null,
        compressedSize = item.compressedSize.takeIf { it >= 0 && !item.isDirectory },
    )

    private fun require(path: String) = split(path) ?: throw IOException("Archive is no longer open")

    override fun list(dir: String): List<FileEntry> {
        val (s, inner) = require(dir)
        return s.tree().list(inner).map { entryFor(s, it) }
    }

    override fun stat(path: String): FileEntry? {
        val (s, inner) = split(path) ?: return null
        if (inner.isEmpty()) return FileEntry(s.displayName, s.root, Backend.ARCHIVE, isDirectory = true, size = s.file.length(), lastModified = s.file.lastModified())
        return s.tree().get(inner)?.let { entryFor(s, it) }
    }

    override fun findChild(dir: String, name: String): String? {
        val (s, inner) = split(dir) ?: return null
        val p = if (inner.isEmpty()) name else "$inner/$name"
        return s.tree().get(p)?.let { s.root + "/" + it.path }
    }

    override fun openInput(path: String): InputStream {
        val (s, inner) = require(path)
        val item = s.tree().get(inner) ?: throw IOException("Not found in archive: $inner")
        if (item.isDirectory) throw IOException("${item.name} is a folder")
        val raw = s.open(item)
        // Single-file streams (.gz/.xz/.bz2) don't record a trustworthy size (gzip's is mod 4 GB).
        val declared = if (s.format.isSingleFile) -1L else item.size
        val guarded = GuardedInputStream(raw, item.name, declared, s.file.length(), limits())
        val stream: InputStream = if (s.format == ArchiveFormat.SEVEN_Z) Locked(guarded, s) else guarded
        if (s.isApk && s.decodeXml && isCompiledXmlPath(inner) && item.size in 8..MAX_DECODE) {
            val bytes = stream.use { it.readBytes() }
            if (BinaryXml.isBinaryXml(bytes)) {
                val text = runCatching { BinaryXml.decodeToText(bytes, s.resources()) }.getOrNull()
                if (text != null) return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
            }
            return ByteArrayInputStream(bytes)
        }
        return stream
    }

    /** Readable text for an APK's resources.arsc (or null when [path] isn't one). */
    fun resourcesDump(path: String): String? {
        val (s, inner) = split(path) ?: return null
        if (!s.isApk || inner != "resources.arsc") return null
        return s.resources()?.dump()
    }

    /** SevenZFile isn't thread-safe; serialise reads through the session. */
    private class Locked(input: InputStream, private val lock: Any) : FilterInputStream(input) {
        override fun read(): Int = synchronized(lock) { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) { super.read(b, off, len) }
    }

    private fun readOnly(): Nothing = throw IOException("Archives are read-only. Extract first, then edit.")
    override fun createFile(dir: String, name: String, mime: String?): Pair<String, OutputStream> = readOnly()
    override fun mkdir(dir: String, name: String): String = readOnly()
    override fun delete(path: String): Boolean = readOnly()
    override fun rename(path: String, newName: String): String? = readOnly()

    companion object {
        const val SEP = "!"
        private const val MAX_DECODE = 16L * 1024 * 1024

        /** Compiled XML inside an APK: the manifest and XML under res/. */
        fun isCompiledXmlPath(inner: String) = inner == "AndroidManifest.xml" || (inner.startsWith("res/") && inner.endsWith(".xml", true))
        fun isArchivePath(path: String) = path.contains("$SEP/") || path.endsWith(SEP)
    }
}
