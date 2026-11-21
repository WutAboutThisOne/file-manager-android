package local.files.explorer.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import local.files.explorer.BuildConfig

/** Thin libsu wrapper. Everything here is a no-op unless root was granted. */
object RootShell {
    private val _state = MutableStateFlow(RootState.CHECKING)
    val state: StateFlow<RootState> = _state

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
    }

    private val probe = object : RootProbe {
        override fun alreadyGranted(): Boolean? = Shell.isAppGrantedRoot()
        override fun suBinaryPresent(): Boolean = RootDetector.findSu()
        override fun requestRoot(): Boolean = Shell.getShell().isRoot
    }

    suspend fun detect(force: Boolean = false): RootState = withContext(Dispatchers.IO) {
        val s = RootDetector(probe).detect(force)
        _state.value = s
        s
    }

    val isGranted: Boolean get() = _state.value.isGranted

    data class Result(val ok: Boolean, val out: List<String>, val err: List<String>)

    suspend fun run(vararg cmds: String): Result = withContext(Dispatchers.IO) {
        if (!isGranted) return@withContext Result(false, emptyList(), listOf("Root not granted"))
        val r = Shell.cmd(*cmds).exec()
        Result(r.isSuccess, r.out, r.err)
    }
}
