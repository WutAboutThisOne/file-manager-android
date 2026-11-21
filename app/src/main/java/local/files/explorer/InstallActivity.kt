package local.files.explorer

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.files.explorer.install.ApkInstaller
import local.files.explorer.install.IncomingInstall
import local.files.explorer.install.IncomingPackage
import local.files.explorer.install.InstallStatus
import local.files.explorer.root.RootShell
import local.files.explorer.settings.SettingsRepository
import local.files.explorer.settings.ThemeMode
import local.files.explorer.ui.install.ExternalInstallActions
import local.files.explorer.ui.install.ExternalInstallContent
import local.files.explorer.ui.install.ExternalInstallUi
import local.files.explorer.ui.theme.FileManagerTheme

/**
 * Lightweight entry point for "Open with File Manager" / "Share to File Manager" on APKs and split bundles.
 * It only uses the URI grant that came with the intent: no All files access, no storage scan,
 * no main UI. Runs over the sending app (translucent) and finishes when the sheet closes.
 */
class InstallActivity : ComponentActivity() {
    private lateinit var vm: InstallViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 draws edge-to-edge regardless; opt in explicitly so the sheet pads itself above the nav bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val theme = runCatching { runBlocking { SettingsRepository(applicationContext).settings.first().theme } }.getOrDefault(ThemeMode.SYSTEM)
        vm = ViewModelProvider(this)[InstallViewModel::class.java]
        vm.launchIntent = { i -> try { startActivity(i) } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {} }
        if (savedInstanceState == null) vm.handle(intent)
        setContent {
            val status by ApkInstaller.status.collectAsState()
            BackHandler { vm.dismiss() }
            FileManagerTheme(theme) {
                ExternalInstallContent(vm.ui.copy(status = status), vm)
            }
            androidx.compose.runtime.LaunchedEffect(vm.finished) { if (vm.finished) finish() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNewIntent(intent)
    }

    /** Another APK arrived while the sheet is open (singleTop): replace the current one. */
    internal fun handleNewIntent(intent: Intent) {
        setIntent(intent)
        vm.handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) vm.release()
    }
}

class InstallViewModel(app: Application) : AndroidViewModel(app), ExternalInstallActions {
    private val ctx get() = getApplication<Application>()
    var ui by mutableStateOf(ExternalInstallUi()); private set
    var finished by mutableStateOf(false); private set
    var launchIntent: (Intent) -> Unit = {}
    private var current: IncomingPackage? = null
    private var job: Job? = null
    private var rootGranted = false
    private var explained = false
    /** For tests: the last incoming file was an archive and went to MainActivity. */
    var archiveHandedOff = false; private set

    init {
        IncomingInstall.cleanupStale(app)
        viewModelScope.launch { ApkInstaller.status.collect { if (it is InstallStatus.Success) release() } }
    }

    fun handle(intent: Intent?) {
        job?.cancel()
        release()
        ApkInstaller.reset()
        explained = false
        val uri = IncomingInstall.uriFrom(intent)
        ui = ExternalInstallUi(loading = true, canInstallUnknown = ApkInstaller.canInstall(ctx))
        if (uri == null) {
            ui = ui.copy(loading = false, error = "File Manager didn't receive a file to install.")
            return
        }
        ui = ui.copy(fileName = IncomingInstall.displayName(ctx, uri))
        job = viewModelScope.launch {
            val r = runCatching {
                val pkg = IncomingInstall.prepare(ctx, uri)
                if (pkg.kind == local.files.explorer.core.PackageKind.ARCHIVE) {
                    // Not an app: hand it to the main window's archive browser. A cache copy stays until it's stale; an in-place file is never deleted.
                    launchIntent(MainActivity.archiveIntent(ctx, pkg.file, pkg.displayName))
                    archiveHandedOff = true
                    finished = true
                    return@launch
                }
                current = pkg
                val info = try { ApkInstaller.inspect(ctx, pkg.file, pkg.isBundle) } catch (e: Exception) {
                    throw local.files.explorer.install.IncomingException(
                        if (pkg.kind == local.files.explorer.core.PackageKind.BUNDLE) "This split bundle is damaged or isn't supported."
                        else "This APK is damaged or can't be read.",
                    )
                }
                pkg to info.copy(fileName = pkg.displayName)
            }
            r.onSuccess { (pkg, info) ->
                ui = ui.copy(fileName = pkg.displayName, info = info, loading = false, error = null)
                // Root only matters if it's already known to be granted; don't trigger a su prompt just for this.
                rootGranted = RootShell.state.value.isGranted
                ui = ui.copy(rootAvailable = rootGranted)
                maybeExplain()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                IncomingInstall.cleanup(ctx, current?.file); current = null
                ui = ui.copy(loading = false, error = e.message ?: "Couldn't read this package.")
            }
        }
    }

    private fun maybeExplain() {
        val can = ApkInstaller.canInstall(ctx)
        ui = ui.copy(canInstallUnknown = can, explainer = !can && !rootGranted && !explained && ui.info != null)
    }

    fun onResume() {
        val can = ApkInstaller.canInstall(ctx)
        ui = ui.copy(canInstallUnknown = can)
        if (can) ui = ui.copy(explainer = false)
    }

    override fun explainerContinue() {
        explained = true
        ui = ui.copy(explainer = false)
        viewModelScope.launch { SettingsRepository(ctx).setExplained(install = true) }
        launchIntent(ApkInstaller.unknownSourcesIntent(ctx))
    }

    override fun explainerNotNow() { explained = true; ui = ui.copy(explainer = false) }

    override fun openUnknownSources() = launchIntent(ApkInstaller.unknownSourcesIntent(ctx))

    override fun install() {
        val p = current ?: return
        viewModelScope.launch { ApkInstaller.install(ctx, p.file, p.isBundle) }
    }

    override fun installRoot() {
        val p = current ?: return
        viewModelScope.launch { ApkInstaller.installWithRoot(ctx, p.file, p.isBundle) }
    }

    override fun dismiss() {
        val s = ApkInstaller.status.value
        if (s is InstallStatus.Installing) return // the session is still being written
        finished = true
    }

    /** Deletes the cache copy. Safe after commit: the session already holds the bytes. */
    fun release() {
        IncomingInstall.cleanup(ctx, current?.file)
        current = null
    }

    override fun onCleared() { release() }
}
