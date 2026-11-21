package local.files.explorer.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import local.files.explorer.core.Paths
import local.files.explorer.core.SplitApk
import local.files.explorer.root.RootShell
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object ApkInstaller {
    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val status: StateFlow<InstallStatus> = _status
    fun reset() { _status.value = InstallStatus.Idle }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val flags = PackageManager.GET_PERMISSIONS
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(path, flags)
    }

    private fun Drawable.toBitmap(px: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, px, px, true)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        setBounds(0, 0, px, px); draw(c)
        return b
    }

    private fun bundleCache(context: Context, file: File) =
        File(context.cacheDir, "bundles/${file.path.hashCode()}_${file.length()}").apply { mkdirs() }

    fun clearBundleCache(context: Context, file: File) {
        File(context.cacheDir, "bundles/${file.path.hashCode()}_${file.length()}").deleteRecursively()
    }

    /** Reads label / package / version / icon. Bundles: extracts base.apk to cache first. */
    suspend fun inspect(context: Context, file: File, bundle: Boolean = SplitApk.isBundleName(file.name)): ApkInfo = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        var splitCount = 1
        var obbCount = 0
        val apkPath: String = if (bundle) {
            val b = SplitApk.inspect(file)
            if (!b.isValid) throw IOException("No base APK found in this bundle")
            splitCount = b.splitCount
            obbCount = b.obbEntries.size
            val out = File(bundleCache(context, file), "base.apk")
            if (!out.exists()) ZipFile(file).use { z -> z.getInputStream(z.getEntry(b.baseEntry)).use { i -> out.outputStream().use { i.copyTo(it) } } }
            out.path
        } else file.path
        val info = archiveInfo(pm, apkPath) ?: throw IOException("This file isn't a valid APK")
        val app = info.applicationInfo?.apply { sourceDir = apkPath; publicSourceDir = apkPath }
        ApkInfo(
            fileName = file.name,
            filePath = file.path,
            label = app?.loadLabel(pm)?.toString() ?: info.packageName,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            minSdk = if (Build.VERSION.SDK_INT >= 24) app?.minSdkVersion else null,
            targetSdk = app?.targetSdkVersion,
            sizeBytes = file.length(),
            icon = runCatching { app?.loadIcon(pm)?.toBitmap(192) }.getOrNull(),
            splitCount = splitCount,
            obbCount = obbCount,
            permissionCount = info.requestedPermissions?.size ?: 0,
        )
    }

    /** Standard PackageInstaller session (asks the user to confirm). Handles single APKs and split bundles. */
    suspend fun install(context: Context, file: File, bundle: Boolean = SplitApk.isBundleName(file.name)) = withContext(Dispatchers.IO) {
        _status.value = InstallStatus.Installing(0f)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 34) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                if (bundle) {
                    val b = SplitApk.inspect(file)
                    ZipFile(file).use { z ->
                        b.apkEntries.forEachIndexed { i, name ->
                            val e = z.getEntry(name)
                            z.getInputStream(e).use { input ->
                                session.openWrite("${i}_" + name.substringAfterLast('/'), 0, e.size).use { out ->
                                    input.copyTo(out); session.fsync(out)
                                }
                            }
                            _status.value = InstallStatus.Installing((i + 1f) / b.apkEntries.size)
                        }
                    }
                } else {
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                _status.value = InstallStatus.Installing(done.toFloat() / file.length().coerceAtLeast(1))
                            }
                            session.fsync(out)
                        }
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            _status.value = InstallStatus.Failure(e.message ?: "Install failed")
        }
    }

    /** Optional silent install through `pm` when root is granted. Streams the file through stdin. */
    suspend fun installWithRoot(context: Context, file: File, bundle: Boolean = SplitApk.isBundleName(file.name)) = withContext(Dispatchers.IO) {
        _status.value = InstallStatus.Installing(0f)
        val q = Paths.shellQuote(file.path)
        val result = if (!bundle) {
            RootShell.run("cat $q | pm install -r -S ${file.length()}")
        } else {
            val b = SplitApk.inspect(file)
            val dir = bundleCache(context, file)
            ZipFile(file).use { z ->
                b.apkEntries.forEach { n -> File(dir, n.substringAfterLast('/')).let { f -> if (!f.exists()) z.getInputStream(z.getEntry(n)).use { i -> f.outputStream().use { i.copyTo(it) } } } }
            }
            val create = RootShell.run("pm install-create -r")
            val id = Regex("\\[(\\d+)]").find(create.out.joinToString())?.groupValues?.get(1)
            if (id == null) create else {
                val writes = b.apkEntries.mapIndexed { i, n ->
                    val f = File(dir, n.substringAfterLast('/'))
                    "cat ${Paths.shellQuote(f.path)} | pm install-write -S ${f.length()} $id ${i}_${f.name} -"
                }
                RootShell.run(*(writes + "pm install-commit $id").toTypedArray())
            }
        }
        _status.value = if (result.ok && result.out.any { it.contains("Success") }) InstallStatus.Success("Installed with root")
        else InstallStatus.Failure((result.err + result.out).lastOrNull() ?: "pm install failed")
    }

    internal fun onResult(context: Context, intent: Intent) {
        when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                _status.value = InstallStatus.WaitingForConfirmation
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> _status.value = InstallStatus.Success("App installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _status.value = if (s == PackageInstaller.STATUS_FAILURE_ABORTED) InstallStatus.Failure("Install cancelled")
                else InstallStatus.Failure(friendly(s, msg))
            }
        }
    }

    private fun friendly(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app (different signature?)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The package is invalid or corrupt"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked"
        else -> msg ?: "Install failed"
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstaller.onResult(context, intent)
}
