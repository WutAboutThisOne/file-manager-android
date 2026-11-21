package local.files.explorer.install

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import local.files.explorer.core.PackageKind
import local.files.explorer.core.PackageSniffer
import java.io.File
import local.files.explorer.storage.DirectAccess
import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.core.SplitApk
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** A package another app handed to the app (Open with / Share / Install). */
/** [inPlace] = [file] is the sender's own file (or a /proc fd path), never deleted by the app. */
data class IncomingPackage(val file: File, val displayName: String, val kind: PackageKind, val inPlace: Boolean = false) {
    val isBundle get() = kind == PackageKind.BUNDLE
}

class IncomingException(message: String) : IOException(message)

/**
 * Turns VIEW / INSTALL_PACKAGE / SEND intents into a private cache copy the app can inspect and
 * install. Only the URI grant that came with the intent is used: no storage permission needed.
 */
object IncomingInstall {
    const val DIR = "incoming"

    val ACTIONS = setOf(Intent.ACTION_VIEW, @Suppress("DEPRECATION") Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_SEND)

    /** The package URI an intent carries, or null if the intent isn't something the app installs. */
    fun uriFrom(intent: Intent?): Uri? {
        intent ?: return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW, @Suppress("DEPRECATION") Intent.ACTION_INSTALL_PACKAGE -> intent.data
            Intent.ACTION_SEND -> stream(intent) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        } ?: return null
        return uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.lastPathSegment
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Opens [uri] in place when possible (readable real path, or a seekable descriptor addressed as
     * /proc/<pid>/fd/<n>); otherwise copies it into cache/incoming/<random>/<name> after checking
     * there is room. Then checks it really is an APK, split bundle or archive.
     */
    suspend fun prepare(context: Context, uri: Uri): IncomingPackage = withContext(Dispatchers.IO) {
        // Keep access if the sender offered a persistable grant; the temporary grant is enough otherwise.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(context, uri)
        val direct = try {
            DirectAccess.open(context, uri)
        } catch (e: SecurityException) {
            throw IncomingException("File Manager wasn't given permission to read this file. Try opening it again from the other app.")
        } catch (e: FileNotFoundException) {
            throw IncomingException("The file couldn't be opened. It may have been moved or deleted.")
        } catch (e: IOException) { null } catch (e: IllegalArgumentException) { null }
        if (direct != null) {
            try {
                val (kind, _) = classify(direct.file, name)
                inPlace += direct.file.path
                return@withContext IncomingPackage(direct.file, name ?: direct.file.name, kind, inPlace = true)
            } catch (e: Throwable) {
                DirectAccess.release(direct.file.path)
                throw e
            }
        }
        val dir = File(File(context.cacheDir, DIR), UUID.randomUUID().toString()).apply { mkdirs() }
        val tmp = File(dir, "download.tmp")
        try {
            val size = DirectAccess.sizeOf(context, uri)
            val what = "open “${name ?: "this file"}”"
            if (size > 0) DirectAccess.ensureSpace(dir, size, what)
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                throw IncomingException("File Manager wasn't given permission to read this file. Try opening it again from the other app.")
            } catch (e: FileNotFoundException) {
                throw IncomingException("The file couldn't be opened. It may have been moved or deleted.")
            } ?: throw IncomingException("The other app didn't provide the file.")
            input.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    var nextCheck = CHECK_EVERY
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); total += n
                        if (total >= nextCheck) {
                            // Size unknown (or wrong): stop before the cache fills the disk.
                            nextCheck += CHECK_EVERY
                            val free = DirectAccess.freeSpace(dir)
                            if (free < DirectAccess.MARGIN) throw IncomingException(DirectAccess.spaceMessage(what, maxOf(size, total + CHECK_EVERY), free))
                        }
                    }
                }
            }
            if (tmp.length() == 0L) throw IncomingException("The file is empty.")
            val (kind, sniffed) = classify(tmp, name)
            var cacheName = PackageSniffer.cacheName(name, kind)
            if (kind == PackageKind.ARCHIVE && ArchiveFormat.of(cacheName) == null) cacheName += extensionFor(sniffed ?: ArchiveFormat.ZIP)
            val target = File(dir, cacheName)
            if (!tmp.renameTo(target)) throw IncomingException("Couldn't prepare the file.")
            IncomingPackage(target, name ?: target.name, kind)
        } catch (e: local.files.explorer.storage.NotEnoughSpaceException) {
            dir.deleteRecursively()
            throw IncomingException(e.message ?: "Not enough space")
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private const val CHECK_EVERY = 64L * 1024 * 1024

    /** Paths opened in place this process (MainActivity accepts these alongside cache copies). */
    private val inPlace = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Works out what [file] is from its bytes (and [name] for bundles). */
    private fun classify(file: File, name: String?): Pair<PackageKind, ArchiveFormat?> {
        if (file.length() == 0L) throw IncomingException("The file is empty.")
        val header = file.inputStream().use { i -> val b = ByteArray(600); var o = 0; while (o < b.size) { val r = i.read(b, o, b.size - o); if (r < 0) break; o += r }; b.copyOf(o) }
        if (header.isEmpty()) throw IncomingException("The file is empty.")
        val sniffed = ArchiveFormat.sniff(header)
        val kind = if (PackageSniffer.looksLikeZip(header)) {
            val entries = try {
                ZipFile(file).use { z -> z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList() }
            } catch (e: IOException) {
                // java.util.zip is stricter than zip4j (e.g. some encrypted or oddly-encoded zips); let the archive browser try.
                if (name != null && SplitApk.isBundleName(name) || name?.endsWith(".apk", true) == true) throw IncomingException("This package is damaged and can't be read.")
                null
            }
            if (entries == null) PackageKind.ARCHIVE else PackageSniffer.classifyIncoming(entries, name)
        } else if (sniffed != null) PackageKind.ARCHIVE
        else throw IncomingException("This file isn't an Android app package (APK) or an archive File Manager can open.")
        return kind to sniffed
    }

    private fun extensionFor(f: ArchiveFormat) = when (f) {
        ArchiveFormat.ZIP -> ".zip"; ArchiveFormat.SEVEN_Z -> ".7z"; ArchiveFormat.TAR -> ".tar"
        ArchiveFormat.GZ, ArchiveFormat.TAR_GZ -> ".gz"; ArchiveFormat.XZ, ArchiveFormat.TAR_XZ -> ".xz"
        ArchiveFormat.BZ2, ArchiveFormat.TAR_BZ2 -> ".bz2"
    }

    /** True if [path] was opened in place by [prepare] in this process. */
    fun isOpenedInPlace(path: String): Boolean = path in inPlace

    /** True if [path] is one of our incoming cache copies (MainActivity only accepts these from intents). */
    fun isIncomingCopy(context: Context, path: String): Boolean {
        val base = File(context.cacheDir, DIR).canonicalFile
        val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return f.isFile && f.path.startsWith(base.path + File.separator)
    }

    /**
     * Deletes a cache copy and anything extracted from it. Files opened in place are never
     * deleted: their descriptor (if any) is just closed.
     */
    fun cleanup(context: Context, file: File?) {
        file ?: return
        ApkInstaller.clearBundleCache(context, file)
        if (inPlace.remove(file.path) || !isIncomingCopy(context, file.path)) { DirectAccess.release(file.path); return }
        val dir = file.parentFile
        if (dir != null && dir.parentFile?.name == DIR) dir.deleteRecursively() else file.delete()
    }

    /** Removes leftovers from earlier sessions (e.g. the process was killed mid-install). */
    fun cleanupStale(context: Context, olderThanMs: Long = 6 * 60 * 60 * 1000L, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, DIR).listFiles()?.forEach { if (now - it.lastModified() > olderThanMs) it.deleteRecursively() }
    }
}
