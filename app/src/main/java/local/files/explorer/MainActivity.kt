package local.files.explorer

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import local.files.explorer.core.FileType
import local.files.explorer.settings.AppSettings
import local.files.explorer.settings.SettingsRepository
import local.files.explorer.settings.ThemeMode
import local.files.explorer.storage.StorageAccess
import local.files.explorer.storage.Thumbnails
import local.files.explorer.ui.FileManagerApp
import local.files.explorer.ui.adaptive.LocalAdaptive
import local.files.explorer.ui.adaptive.rememberAdaptive
import local.files.explorer.ui.MainViewModel
import local.files.explorer.ui.UiEvent
import local.files.explorer.ui.common.LocalThumbnails
import local.files.explorer.ui.common.ThumbnailSource
import local.files.explorer.ui.theme.FileManagerTheme
import local.files.explorer.ui.theme.isDark
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ARCHIVE_PATH = "local.files.explorer.extra.ARCHIVE_PATH"
        const val EXTRA_ARCHIVE_NAME = "local.files.explorer.extra.ARCHIVE_NAME"

        fun archiveIntent(context: android.content.Context, file: File, name: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ARCHIVE_PATH, file.absolutePath)
                .putExtra(EXTRA_ARCHIVE_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /** Archives handed over by InstallActivity; only our own incoming cache copies are accepted. */
    internal fun handleArchiveIntent(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_ARCHIVE_PATH) ?: return
        intent.removeExtra(EXTRA_ARCHIVE_PATH)
        if (!local.files.explorer.install.IncomingInstall.isIncomingCopy(this, path) && !local.files.explorer.install.IncomingInstall.isOpenedInPlace(path)) return
        vm.openIncomingArchive(path, intent.getStringExtra(EXTRA_ARCHIVE_NAME) ?: File(path).name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleArchiveIntent(intent)
    }

    private lateinit var vm: MainViewModel
    private var pendingTreeUuid: String? = null

    private val treePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onTreePicked(pendingTreeUuid, r.data?.data)
    }
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onLegacyResult() }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onNotificationResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Read the saved theme synchronously so the very first frame is already right.
        val initial: AppSettings = runBlocking { SettingsRepository(applicationContext).settings.first() }
        applyNightMode(initial.theme)
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application, initial) as T
        })[MainViewModel::class.java]

        if (savedInstanceState == null) handleArchiveIntent(intent)

        val thumbs = ThumbnailSource { e ->
            withContext(Dispatchers.IO) {
                if (e.type == FileType.APK) apkIcon(e.path) else Thumbnails.load(e, 160)?.asImageBitmap()
            }
        }

        setContent {
            val mode = vm.settings.theme
            val dark = mode.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            LaunchedEffect(mode) { applyNightMode(mode) }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                vm.events.collect { ev ->
                    when (ev) {
                        is UiEvent.Message -> snackbar.showSnackbar(ev.text)
                        is UiEvent.Launch -> launch(ev.intent, ev.fallback)
                        is UiEvent.PickTree -> { pendingTreeUuid = ev.uuid; runCatching { treePicker.launch(ev.intent) } }
                        UiEvent.RequestLegacyStorage -> legacyStorage.launch(StorageAccess.legacyPermissions)
                        UiEvent.RequestNotifications -> if (Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else vm.onNotificationResult(true)
                    }
                }
            }
            FileManagerTheme(mode) {
                CompositionLocalProvider(LocalThumbnails provides thumbs, LocalAdaptive provides rememberAdaptive(this@MainActivity)) {
                    FileManagerApp(vm, snackbar, onExit = ::finish)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    private fun launch(intent: Intent, fallback: Intent?) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            try { fallback?.let(::startActivity) } catch (_: ActivityNotFoundException) {}
        } catch (_: SecurityException) {}
    }

    /** Keeps the system's view of our night mode in sync so splash/window match the chosen theme. */
    private fun applyNightMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT >= 31) {
            val um = getSystemService(UiModeManager::class.java) ?: return
            val target = when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            }
            runCatching { um.setApplicationNightMode(target) }
        }
    }

    private fun apkIcon(path: String) = runCatching {
        val pm = packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(path, 0) ?: return@runCatching null
        val ai = info.applicationInfo ?: return@runCatching null
        ai.sourceDir = path; ai.publicSourceDir = path
        val d = ai.loadIcon(pm)
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, 96, 96); d.draw(c)
        b.asImageBitmap()
    }.getOrNull()
}
