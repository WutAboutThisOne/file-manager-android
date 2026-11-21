package local.files.explorer.ops

import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.archive.ArchiveWriter
import local.files.explorer.archive.CancelledException
import local.files.explorer.archive.CompressSpec
import local.files.explorer.archive.SourceItem
import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictResolver
import local.files.explorer.core.FileEntry
import local.files.explorer.fs.FsBackend
import local.files.explorer.fs.OpOutcome
import local.files.explorer.fs.OpProgress
import java.io.File
import java.util.UUID

/** Runs an [OpKind.COMPRESS] request: walks the sources on their backends and streams them into one archive. */
class Compressor(private val backendFor: (Backend) -> FsBackend) {

    /** Flattens [sources] into archive entries (folders first, then their contents), relative to their parent. */
    fun collect(sources: List<FileEntry>, isCancelled: () -> Boolean = { false }): List<Pair<SourceItem, FileEntry>> {
        val out = ArrayList<Pair<SourceItem, FileEntry>>()
        fun walk(e: FileEntry, rel: String, depth: Int) {
            if (isCancelled()) throw CancelledException()
            val be = backendFor(e.backend)
            if (e.isDirectory) {
                if (e.isSymlink || depth > 64) return
                out += SourceItem(rel, true, 0, e.lastModified) { throw IllegalStateException() } to e
                for (c in be.list(e.path).sortedBy { it.name }) walk(c, "$rel/${c.name}", depth + 1)
            } else {
                out += SourceItem(rel, false, e.size, e.lastModified) { be.openInput(e.path) } to e
            }
        }
        for (s in sources) walk(s, s.name, 0)
        return out
    }

    fun run(cacheDir: File, req: OpRequest, onProgress: (OpProgress) -> Unit, isCancelled: () -> Boolean): OpOutcome {
        val spec = req.compress ?: return OpOutcome(failed = listOf("Nothing to compress"))
        val dest = backendFor(req.destBackend)
        val items = try { collect(req.sources, isCancelled) } catch (_: CancelledException) { return OpOutcome(cancelled = true) }
        val total = items.sumOf { it.first.size }
        val count = items.size
        var last = 0L
        var p = OpProgress(0, total, 0, count)
        onProgress(p)
        val name = ConflictResolver.uniqueName(fileName(spec)) { dest.exists(req.destDir, it) }
        val tmp = if (req.destBackend == Backend.LOCAL) File(req.destDir, ".$name.${UUID.randomUUID().toString().take(8)}.part")
        else File(File(cacheDir, "compress").apply { mkdirs() }, "${UUID.randomUUID()}.${if (spec.format == ArchiveFormat.ZIP) "zip" else "7z"}")
        var itemsDone = 0
        var lastPath = ""
        return try {
            ArchiveWriter.write(tmp, spec, items.asSequence().map { it.first }, onBytes = { bytes, path ->
                if (path != lastPath) { lastPath = path; itemsDone++ }
                val now = System.nanoTime()
                if (now - last > 100_000_000L) {
                    last = now
                    p = OpProgress(bytes, total, itemsDone, count, path.substringAfterLast('/'))
                    onProgress(p)
                }
            }, isCancelled = isCancelled)
            val finalPath: String = if (req.destBackend == Backend.LOCAL) {
                val target = File(req.destDir, name)
                if (!tmp.renameTo(target)) throw java.io.IOException("Couldn't save $name")
                target.path
            } else {
                try {
                    val (path, out) = dest.createFile(req.destDir, name, if (spec.format == ArchiveFormat.ZIP) "application/zip" else "application/x-7z-compressed")
                    var copied = 0L
                    out.use { o ->
                        tmp.inputStream().use { i ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (isCancelled()) { runCatching { dest.delete(path) }; throw CancelledException() }
                                val n = i.read(buf); if (n < 0) break
                                o.write(buf, 0, n); copied += n
                            }
                        }
                    }
                    path
                } finally { tmp.delete() }
            }
            onProgress(OpProgress(total, total, count, count, name))
            OpOutcome(done = req.sources.size, createdPaths = listOf(finalPath))
        } catch (_: CancelledException) {
            tmp.delete()
            OpOutcome(cancelled = true)
        } catch (e: Exception) {
            tmp.delete()
            OpOutcome(failed = listOf("$name: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    companion object {
        fun fileName(spec: CompressSpec): String {
            val ext = if (spec.format == ArchiveFormat.ZIP) ".zip" else ".7z"
            val n = spec.name.trim()
            return if (n.lowercase().endsWith(ext)) n else n + ext
        }
    }
}
