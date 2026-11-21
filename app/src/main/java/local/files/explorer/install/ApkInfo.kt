package local.files.explorer.install

import android.graphics.Bitmap

/** Everything the install sheet shows. Plain data so it can be screenshot-tested. */
data class ApkInfo(
    val fileName: String,
    val filePath: String,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sizeBytes: Long,
    val icon: Bitmap? = null,
    /** >1 for split bundles (.apks/.xapk/.apkm). */
    val splitCount: Int = 1,
    val obbCount: Int = 0,
    val permissionCount: Int = 0,
) {
    val isBundle: Boolean get() = splitCount > 1
}

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data object NeedsUnknownSourcesPermission : InstallStatus
    data class Installing(val progress: Float) : InstallStatus
    data object WaitingForConfirmation : InstallStatus
    data class Success(val message: String) : InstallStatus
    data class Failure(val message: String) : InstallStatus
}
