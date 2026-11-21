package local.files.explorer.fs

import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.ConflictResolver
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Paths
import local.files.explorer.core.Resolution
import java.io.IOException

data class OpProgress(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val current: String = "",
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            itemsTotal > 0 -> (itemsDone.toFloat() / itemsTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class OpOutcome(
    val done: Int = 0,
    val skipped: Int = 0,
    val failed: List<String> = emptyList(),
    val cancelled: Boolean = false,
    /** Top-level results, for media scanning / selection after paste. */
    val createdPaths: List<String> = emptyList(),
)

class OperationCancelled : Exception()

/**
 * Copy / move / delete across any pair of [FsBackend]s with progress,
 * cancellation and conflict handling. Folder + REPLACE merges into the
 * existing folder (children conflicts are replaced too).
 */
class FileOperationEngine(
    private val backendFor: (Backend) -> FsBackend,
    private val bufferSize: Int = 256 * 1024,
) {
    private class Ctx(
        val onProgress: (OpProgress) -> Unit,
        val isCancelled: () -> Boolean,
        var p: OpProgress,
        var lastEmit: Long = 0,
    ) {
        fun emit(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastEmit > 100_000_000L) { lastEmit = now; onProgress(p) }
        }
        fun check() { if (isCancelled()) throw OperationCancelled() }
    }

    /** Total bytes and file count under [entries]. */
    fun measure(entries: List<FileEntry>, isCancelled: () -> Boolean = { false }): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        fun walk(e: FileEntry) {
            if (isCancelled()) throw OperationCancelled()
            if (e.isDirectory && !e.isSymlink) {
                count++
                runCatching { backendFor(e.backend).list(e.path) }.getOrDefault(emptyList()).forEach(::walk)
            } else { bytes += e.size; count++ }
        }
        entries.forEach(::walk)
        return bytes to count
    }

    fun transfer(
        sources: List<FileEntry>,
        destBackend: Backend,
        destDir: String,
        move: Boolean,
        policy: ConflictPolicy,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        /** Free bytes at the destination (null = unknown); checked against the total before writing. */
        freeSpace: ((Backend, String) -> Long?)? = null,
        spaceVerb: String = "copy",
    ): OpOutcome {
        val dest = backendFor(destBackend)
        if (destBackend != Backend.SAF) {
            ConflictResolver.validatePaste(
                sources.filter { it.backend == destBackend }.map { it.path }, destDir,
            ) { p -> sources.first { it.path == p }.isDirectory }?.let { return OpOutcome(failed = listOf(it)) }
        }
        val ctx = Ctx(onProgress, isCancelled, OpProgress())
        return try {
            val (bytes, count) = measure(sources, isCancelled)
            if (freeSpace != null && !(move && sources.all { it.backend == destBackend })) {
                val free = runCatching { freeSpace(destBackend, destDir) }.getOrNull()
                local.files.explorer.core.SpaceCheck.check(spaceVerb, bytes, free)?.let { return OpOutcome(failed = listOf(it)) }
            }
            ctx.p = OpProgress(bytesTotal = bytes, itemsTotal = count)
            ctx.emit(force = true)
            var done = 0; var skipped = 0
            val failed = mutableListOf<String>()
            val created = mutableListOf<String>()
            for (src in sources) {
                ctx.check()
                val srcBe = backendFor(src.backend)
                val existing = dest.findChild(destDir, src.name)
                val sameLocation = src.backend == destBackend && existing == src.path
                if (move && sameLocation) {
                    val (b, c) = measure(listOf(src))
                    ctx.p = ctx.p.copy(bytesDone = ctx.p.bytesDone + b, itemsDone = ctx.p.itemsDone + c)
                    skipped++
                    continue
                }
                val res = ConflictResolver.resolve(src.name, { n -> if (n == src.name) existing != null else dest.exists(destDir, n) }, policy, sameLocation)
                when (res) {
                    Resolution.Skip -> {
                        val (b, c) = measure(listOf(src))
                        ctx.p = ctx.p.copy(bytesDone = ctx.p.bytesDone + b, itemsDone = ctx.p.itemsDone + c)
                        skipped++
                    }
                    is Resolution.Write -> {
                        val errors = mutableListOf<String>()
                        val target = transferOne(src, srcBe, dest, destDir, res, move, policy, ctx, errors)
                        if (errors.isEmpty()) { done++; target?.let(created::add) } else failed += errors
                    }
                }
                ctx.emit()
            }
            ctx.emit(force = true)
            OpOutcome(done, skipped, failed, cancelled = false, createdPaths = created)
        } catch (_: OperationCancelled) {
            ctx.emit(force = true)
            OpOutcome(cancelled = true)
        }
    }

    private fun transferOne(
        src: FileEntry, srcBe: FsBackend, dest: FsBackend, destDir: String,
        res: Resolution.Write, move: Boolean, policy: ConflictPolicy, ctx: Ctx, errors: MutableList<String>,
    ): String? {
        ctx.check()
        ctx.p = ctx.p.copy(current = src.name)
        ctx.emit()
        val existingPath = if (res.overwrite) dest.findChild(destDir, res.name) else null
        val existing = existingPath?.let { dest.stat(it) }
        // Fast path: rename within the same backend.
        if (move && src.backend == dest.kind && src.backend != Backend.SAF) {
            val canFast = existing == null || !(existing.isDirectory && src.isDirectory)
            if (canFast) {
                if (existing != null && !dest.delete(existing.path)) { errors += "Couldn't replace ${res.name}"; return null }
                val moved = runCatching { dest.moveFast(src.path, destDir, res.name) }.getOrNull()
                if (moved != null) {
                    val (b, c) = measure(listOf(src.copy(path = moved)))
                    ctx.p = ctx.p.copy(bytesDone = ctx.p.bytesDone + b, itemsDone = ctx.p.itemsDone + c)
                    return moved
                }
            }
        }
        return try {
            val target: String
            if (src.isDirectory && !src.isSymlink) {
                target = if (existing != null && existing.isDirectory) existing.path else {
                    if (existing != null && !dest.delete(existing.path)) throw IOException("Couldn't replace ${res.name}")
                    dest.mkdir(destDir, res.name)
                }
                ctx.p = ctx.p.copy(itemsDone = ctx.p.itemsDone + 1)
                val children = srcBe.list(src.path)
                val before = errors.size
                for (child in children) {
                    ctx.check()
                    val childExisting = dest.findChild(target, child.name)
                    val r = if (childExisting == null) Resolution.Write(child.name, false)
                    else ConflictResolver.resolve(child.name, { n -> dest.exists(target, n) }, policy)
                    when (r) {
                        Resolution.Skip -> {
                            val (b, c) = measure(listOf(child))
                            ctx.p = ctx.p.copy(bytesDone = ctx.p.bytesDone + b, itemsDone = ctx.p.itemsDone + c)
                        }
                        is Resolution.Write -> transferOne(child, srcBe, dest, target, r, move, policy, ctx, errors)
                    }
                }
                if (move && errors.size == before) srcBe.delete(src.path)
            } else {
                if (existing != null) {
                    if (existing.isDirectory != src.isDirectory || existing.path != src.path) {
                        if (!dest.delete(existing.path)) throw IOException("Couldn't replace ${res.name}")
                    }
                }
                target = copyFile(src, srcBe, dest, destDir, res.name, ctx)
                if (move) srcBe.delete(src.path)
            }
            target
        } catch (e: OperationCancelled) {
            throw e
        } catch (e: Exception) {
            errors += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    private fun copyFile(src: FileEntry, srcBe: FsBackend, dest: FsBackend, destDir: String, name: String, ctx: Ctx): String {
        val (path, out) = dest.createFile(destDir, name, src.mimeType)
        var ok = false
        try {
            srcBe.openInput(src.path).use { input ->
                out.use { o ->
                    val buf = ByteArray(bufferSize)
                    while (true) {
                        if (ctx.isCancelled()) throw OperationCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        ctx.p = ctx.p.copy(bytesDone = ctx.p.bytesDone + n)
                        ctx.emit()
                    }
                }
            }
            ok = true
        } finally {
            if (!ok) runCatching { dest.delete(path) } // never leave a truncated file behind
        }
        if (src.lastModified > 0) dest.setLastModified(path, src.lastModified)
        ctx.p = ctx.p.copy(itemsDone = ctx.p.itemsDone + 1)
        return path
    }

    fun delete(
        entries: List<FileEntry>,
        onProgress: (OpProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): OpOutcome {
        var p = OpProgress(itemsTotal = entries.size)
        onProgress(p)
        var done = 0
        val failed = mutableListOf<String>()
        for (e in entries) {
            if (isCancelled()) return OpOutcome(done = done, failed = failed, cancelled = true)
            p = p.copy(current = e.name)
            onProgress(p)
            val ok = runCatching { backendFor(e.backend).delete(e.path) }.getOrElse { false }
            if (ok) done++ else failed += e.name
            p = p.copy(itemsDone = p.itemsDone + 1)
            onProgress(p)
        }
        return OpOutcome(done = done, failed = failed)
    }

    companion object {
        fun parentOf(entry: FileEntry): String? =
            if (entry.backend == Backend.SAF) null else Paths.parent(entry.path)
    }
}
