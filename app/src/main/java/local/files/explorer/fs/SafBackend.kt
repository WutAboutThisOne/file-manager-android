package local.files.explorer.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Storage Access Framework fallback for volumes java.io can't reach (some USB drives). */
class SafBackend(context: Context) : FsBackend {
    private val resolver = context.applicationContext.contentResolver
    override val kind = Backend.SAF

    private val cols = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    private fun treeOf(doc: Uri): Uri =
        DocumentsContract.buildTreeDocumentUri(doc.authority, DocumentsContract.getTreeDocumentId(doc))

    override fun list(dir: String): List<FileEntry> {
        val dirUri = Uri.parse(dir)
        val tree = treeOf(dirUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dirUri))
        val out = mutableListOf<FileEntry>()
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(2)
                val isDir = mime == Document.MIME_TYPE_DIR
                out += FileEntry(
                    name = c.getString(1) ?: id,
                    path = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                    backend = Backend.SAF,
                    isDirectory = isDir,
                    size = if (isDir || c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    mimeType = if (isDir) null else mime,
                )
            }
        } ?: throw IOException("Access to this folder was revoked")
        return out
    }

    override fun stat(path: String): FileEntry? {
        val uri = Uri.parse(path)
        return runCatching {
            resolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val mime = c.getString(2)
                val isDir = mime == Document.MIME_TYPE_DIR
                FileEntry(
                    name = c.getString(1) ?: "",
                    path = path,
                    backend = Backend.SAF,
                    isDirectory = isDir,
                    size = if (isDir || c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    mimeType = if (isDir) null else mime,
                )
            }
        }.getOrNull()
    }

    override fun findChild(dir: String, name: String): String? =
        runCatching { list(dir).firstOrNull { it.name == name }?.path }.getOrNull()

    override fun openInput(path: String): InputStream =
        resolver.openInputStream(Uri.parse(path)) ?: throw FileNotFoundException(path)

    override fun createFile(dir: String, name: String, mime: String?): Pair<String, OutputStream> {
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(dir), "application/octet-stream", name)
            ?: throw IOException("Couldn't create $name")
        val os = resolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write $name")
        return uri.toString() to os
    }

    override fun mkdir(dir: String, name: String): String =
        DocumentsContract.createDocument(resolver, Uri.parse(dir), Document.MIME_TYPE_DIR, name)?.toString()
            ?: throw IOException("Couldn't create $name")

    override fun delete(path: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(path)) }.getOrDefault(false)

    override fun rename(path: String, newName: String): String? =
        runCatching { DocumentsContract.renameDocument(resolver, Uri.parse(path), newName)?.toString() }.getOrNull()
}
