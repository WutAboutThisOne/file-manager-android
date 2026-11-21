package local.files.explorer.ops

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import local.files.explorer.MainActivity
import local.files.explorer.R
import local.files.explorer.archive.CompressSpec
import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.FileEntry
import local.files.explorer.core.Format
import local.files.explorer.fs.Backends
import local.files.explorer.fs.FileOperationEngine
import local.files.explorer.fs.OpOutcome
import local.files.explorer.fs.OpProgress
import local.files.explorer.storage.MediaScan
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class OpKind(val verb: String, val past: String) {
    COPY("Copying", "Copied"), MOVE("Moving", "Moved"), DELETE("Deleting", "Deleted"),
    EXTRACT("Extracting", "Extracted"), COMPRESS("Compressing", "Compressed"),
}

data class OpRequest(
    val kind: OpKind,
    val sources: List<FileEntry>,
    val destBackend: Backend = Backend.LOCAL,
    val destDir: String = "",
    val destLabel: String = "",
    val policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    /** Only for [OpKind.COMPRESS]. */
    val compress: CompressSpec? = null,
    val id: Long = ids.incrementAndGet(),
) {
    companion object { private val ids = AtomicLong() }
}

enum class OpStatus { QUEUED, RUNNING, DONE, CANCELLED, FAILED }

data class OpState(
    val request: OpRequest,
    val progress: OpProgress = OpProgress(),
    val status: OpStatus = OpStatus.QUEUED,
    val summary: String? = null,
) {
    val title: String
        get() = "${if (status == OpStatus.DONE) request.kind.past else request.kind.verb} ${Format.items(request.sources.size)}" +
            when {
                request.kind == OpKind.COMPRESS && request.compress != null -> " into ${Compressor.fileName(request.compress)}"
                request.kind != OpKind.DELETE && request.destLabel.isNotEmpty() -> " to ${request.destLabel}"
                else -> ""
            }
}

/** In-process queue shared by the UI (progress card) and [FileOperationService]. */
object OperationManager {
    private val queue = ConcurrentLinkedQueue<OpRequest>()
    private val cancelFlag = AtomicBoolean(false)
    private val _state = MutableStateFlow<OpState?>(null)
    val state: StateFlow<OpState?> = _state
    private val _finished = MutableStateFlow(0L)
    /** Bumps whenever an operation finishes so screens can refresh. */
    val finished: StateFlow<Long> = _finished

    private val engine by lazy { FileOperationEngine(Backends::get) }

    fun enqueue(context: Context, request: OpRequest) {
        queue += request
        if (_state.value == null || _state.value!!.status != OpStatus.RUNNING) _state.value = OpState(request)
        ContextCompat.startForegroundService(context, Intent(context, FileOperationService::class.java))
    }

    /** Free space where an operation writes, when the app can measure it (normal or readable root paths). */
    private fun destinationFreeSpace(backend: Backend, dir: String): Long? = when (backend) {
        Backend.LOCAL -> local.files.explorer.storage.DirectAccess.freeSpace(java.io.File(dir))
        Backend.ROOT -> java.io.File(dir).takeIf { it.canRead() }?.let { local.files.explorer.storage.DirectAccess.freeSpace(it) }
        else -> null // SAF providers don't report free space reliably
    }

    fun cancel() = cancelFlag.set(true)
    fun dismiss() { if (_state.value?.status != OpStatus.RUNNING) _state.value = null }

    internal fun next(): OpRequest? = queue.poll()

    internal fun run(context: Context, req: OpRequest, onUpdate: (OpState) -> Unit): OpState {
        cancelFlag.set(false)
        var st = OpState(req, status = OpStatus.RUNNING)
        _state.value = st; onUpdate(st)
        val progress: (OpProgress) -> Unit = { p -> st = st.copy(progress = p); _state.value = st; onUpdate(st) }
        val outcome: OpOutcome = runCatching {
            when (req.kind) {
                OpKind.DELETE -> engine.delete(req.sources, progress, cancelFlag::get)
                OpKind.COMPRESS -> Compressor(Backends::get).run(context.cacheDir, req, progress, cancelFlag::get)
                else -> engine.transfer(
                    req.sources, req.destBackend, req.destDir, req.kind == OpKind.MOVE, req.policy, progress, cancelFlag::get,
                    freeSpace = ::destinationFreeSpace,
                    spaceVerb = if (req.kind == OpKind.EXTRACT) "extract" else req.kind.name.lowercase(),
                )
            }
        }.getOrElse { OpOutcome(failed = listOf(it.message ?: "Failed")) }
        val status = when {
            outcome.cancelled -> OpStatus.CANCELLED
            outcome.failed.isNotEmpty() && outcome.done == 0 -> OpStatus.FAILED
            else -> OpStatus.DONE
        }
        val summary = buildString {
            when (status) {
                OpStatus.CANCELLED -> append("Cancelled")
                else -> {
                    append("${req.kind.past} ${Format.items(outcome.done)}")
                    if (outcome.skipped > 0) append(", skipped ${outcome.skipped}")
                    if (outcome.failed.isNotEmpty()) append(", ${outcome.failed.size} failed: ${outcome.failed.first()}")
                }
            }
        }
        // Keep MediaStore in sync for normal storage.
        if (req.destBackend == Backend.LOCAL && outcome.createdPaths.isNotEmpty()) MediaScan.scan(context, outcome.createdPaths)
        if (req.kind == OpKind.MOVE || req.kind == OpKind.DELETE) MediaScan.scan(context, req.sources.filter { it.backend == Backend.LOCAL }.map { it.path })
        st = st.copy(status = status, summary = summary)
        _state.value = st; onUpdate(st)
        _finished.value = _finished.value + 1
        return st
    }
}

class FileOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        if (intent?.action == ACTION_CANCEL) {
            OperationManager.cancel()
            return START_NOT_STICKY
        }
        val first = OperationManager.state.value
        val n = progressNotification(first)
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (worker?.isActive != true) {
            worker = scope.launch {
                var last: OpState? = null
                while (true) {
                    // short grace period so a paste queued right as we finish isn't dropped
                    val req = OperationManager.next() ?: run { delay(300); OperationManager.next() } ?: break
                    last = OperationManager.run(applicationContext, req) { st ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 500) { lastNotify = now; notify(NOTIF_ID, progressNotification(st)) }
                    }
                }
                ServiceCompat.stopForeground(this@FileOperationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                last?.let { notify(DONE_ID, doneNotification(it)) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 (targetSdk 35) caps dataSync foreground services at 6 hours a day and calls this when the
     * limit is hit; the service must stop within a few seconds or the app crashes. Cancel the running
     * copy/move cleanly (partial files are cleaned up by the engine) and leave the foreground state.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        OperationManager.cancel()
        worker?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(st: OpState?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, FileOperationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val p = st?.progress ?: OpProgress()
        val pct = (p.fraction * 100).toInt()
        val detail = if (p.bytesTotal > 0) "${Format.size(p.bytesDone)} of ${Format.size(p.bytesTotal)}" else "${p.itemsDone} of ${p.itemsTotal}"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_files)
            .setContentTitle(st?.title ?: "Preparing…")
            .setContentText(if (p.current.isNotEmpty()) "${p.current} · $detail" else detail)
            .setProgress(100, pct, p.itemsTotal == 0 && p.bytesTotal == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun doneNotification(st: OpState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_files)
        .setContentTitle(st.title)
        .setContentText(st.summary)
        .setAutoCancel(true)
        .setContentIntent(contentIntent())
        .build()

    companion object {
        const val CHANNEL = "file_ops"
        const val NOTIF_ID = 42
        const val DONE_ID = 43
        const val ACTION_CANCEL = "local.files.explorer.CANCEL_OP"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress for copy, move, delete, extract and compress"
                    },
                )
            }
        }
    }
}
