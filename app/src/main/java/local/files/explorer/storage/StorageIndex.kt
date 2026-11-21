package local.files.explorer.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import local.files.explorer.core.Backend
import local.files.explorer.core.FileEntry
import local.files.explorer.core.IndexedFile
import local.files.explorer.core.NOMEDIA
import local.files.explorer.core.PathRoot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

data class ScanProgress(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0, val current: String = "")

data class ScanResult(val files: List<IndexedFile>, val progress: ScanProgress, val cancelled: Boolean)

/** Walks storage with java.io (needs All files access). Never follows symlinks. */
class FileScanner {
    fun scan(roots: List<String>, onProgress: (ScanProgress) -> Unit = {}, isCancelled: () -> Boolean = { false }): ScanResult {
        val out = ArrayList<IndexedFile>(4096)
        var p = ScanProgress()
        var last = 0L
        data class D(val dir: File, val nomedia: Boolean, val hidden: Boolean)
        val stack = ArrayDeque<D>()
        roots.distinct().forEach { stack += D(File(it), false, false) }
        while (stack.isNotEmpty()) {
            if (isCancelled()) { onProgress(p); return ScanResult(out, p, true) }
            val d = stack.removeLast()
            val children = d.dir.listFiles() ?: continue
            val hides = d.nomedia || children.any { it.name == NOMEDIA }
            p = p.copy(folders = p.folders + 1, current = d.dir.path)
            for (c in children) {
                if (Files.isSymbolicLink(c.toPath())) continue
                val hiddenName = c.name.startsWith(".")
                if (c.isDirectory) {
                    out += IndexedFile(
                        FileEntry(c.name, c.path, Backend.LOCAL, true, lastModified = c.lastModified()),
                        inNomediaTree = hides, inHiddenPath = d.hidden || hiddenName,
                    )
                    stack += D(c, hides || hiddenName, d.hidden || hiddenName)
                } else {
                    val size = c.length()
                    out += IndexedFile(
                        FileEntry(c.name, c.path, Backend.LOCAL, false, size = size, lastModified = c.lastModified()),
                        inNomediaTree = hides, inHiddenPath = d.hidden || hiddenName,
                    )
                    p = p.copy(files = p.files + 1, bytes = p.bytes + size)
                }
            }
            val now = System.nanoTime()
            if (now - last > 120_000_000L) { last = now; onProgress(p) }
        }
        onProgress(p)
        return ScanResult(out, p, false)
    }
}

data class IndexState(
    val files: List<IndexedFile> = emptyList(),
    val roots: List<PathRoot> = emptyList(),
    val scanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val complete: Boolean = false,
    val cancelled: Boolean = false,
    val version: Int = 0,
)

/** In-memory index of every file on directly readable storage, rebuilt in the background. */
class StorageIndex(private val scope: CoroutineScope, private val scanner: FileScanner = FileScanner()) {
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state
    private var job: Job? = null
    private val cancel = AtomicBoolean(false)

    fun rescan(roots: List<PathRoot>) {
        job?.let { cancel.set(true) }
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            cancel.set(false)
            _state.value = _state.value.copy(scanning = true, cancelled = false, roots = roots, progress = ScanProgress())
            val r = scanner.scan(roots.map { it.path }, { pr -> _state.value = _state.value.copy(progress = pr) }, cancel::get)
            _state.value = _state.value.copy(
                files = r.files, scanning = false, progress = r.progress,
                complete = !r.cancelled, cancelled = r.cancelled, version = _state.value.version + 1,
            )
        }
    }

    fun cancel() = cancel.set(true)
}
