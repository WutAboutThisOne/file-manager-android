package local.files.explorer.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import local.files.explorer.core.FileType
import local.files.explorer.core.NOMEDIA
import local.files.explorer.core.NomediaPolicy
import local.files.explorer.core.NomediaProbe
import java.io.File
import java.io.IOException

object Mime {
    fun of(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.files.explorer.core.Names.extension(name).lowercase())
            ?: when (FileType.of(name)) {
                FileType.APK -> "application/vnd.android.package-archive"
                FileType.TEXT, FileType.CODE -> "text/plain"
                else -> "*/*"
            }
}

object LocalNomediaProbe : NomediaProbe {
    override fun hasNomedia(dir: String): Boolean = File(dir, NOMEDIA).exists()
}

object MediaScan {
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
    }

    /** Adds or removes `.nomedia` in [dir] and asks MediaStore to rescan that folder. */
    fun setNomedia(context: Context, dir: String, present: Boolean) {
        val f = File(dir, NOMEDIA)
        if (present) {
            if (!f.exists() && !f.createNewFile()) throw IOException("Couldn't create .nomedia")
        } else if (f.exists() && !f.delete()) throw IOException("Couldn't remove .nomedia")
        val children = File(dir).listFiles()?.filter { it.isFile && it.name != NOMEDIA }?.map { it.absolutePath }.orEmpty()
        scan(context, listOf(dir, f.absolutePath) + children.take(500))
    }
}

class RecentRepository(private val context: Context) {
    /** Recently modified files from MediaStore (last 30 days), filtered by the .nomedia policy. */
    fun recent(policy: NomediaPolicy, showHidden: Boolean, limit: Int = 200): List<FileEntry> {
        val resolver = context.contentResolver
        val uri = MediaStore.Files.getContentUri("external")
        val since = System.currentTimeMillis() / 1000 - 30L * 24 * 3600
        val proj = arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val sel = "${MediaStore.Files.FileColumns.DATE_MODIFIED} > ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        val args = arrayOf(since.toString())
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(uri, proj, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit * 2)
            }, null)
        } else resolver.query(uri, proj, sel, args, "$sort LIMIT ${limit * 2}")
        val out = mutableListOf<FileEntry>()
        cursor?.use { c ->
            while (c.moveToNext() && out.size < limit * 2) {
                val path = c.getString(0) ?: continue
                val name = c.getString(1) ?: File(path).name
                if (!showHidden && path.split('/').any { it.startsWith(".") }) continue
                out += FileEntry(
                    name = name, path = path, backend = Backend.LOCAL, isDirectory = false,
                    size = c.getLong(2), lastModified = c.getLong(3) * 1000, mimeType = c.getString(4),
                )
            }
        }
        return policy.filterMedia(out.filter { File(it.path).exists() }).take(limit)
    }
}

/** Cheap, cached thumbnails for local images/videos (no third-party image loader). */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(path: String): Bitmap? = cache.get(path)

    fun load(entry: FileEntry, px: Int): Bitmap? {
        if (entry.backend != Backend.LOCAL) return null
        cache.get(entry.path)?.let { return it }
        val f = File(entry.path)
        val bmp = runCatching {
            when (entry.type) {
                FileType.IMAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createImageThumbnail(f, Size(px, px), null) else decodeSampled(f, px)
                FileType.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
                    else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
                else -> null
            }
        }.getOrNull() ?: return null
        cache.put(entry.path, bmp)
        return bmp
    }

    private fun decodeSampled(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
