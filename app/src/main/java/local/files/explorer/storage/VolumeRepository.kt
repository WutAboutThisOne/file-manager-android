package local.files.explorer.storage

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import local.files.explorer.core.SmParser
import local.files.explorer.core.SmVolume
import local.files.explorer.core.VolumeInfo
import local.files.explorer.core.VolumeKind
import local.files.explorer.core.VolumeState
import local.files.explorer.root.RootShell
import java.io.File

class VolumeRepository(private val context: Context) {
    private val sm = context.getSystemService(StorageManager::class.java)

    private fun dirOf(v: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory
        else runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as? File }.getOrNull()

    private fun kindOf(v: StorageVolume, label: String): VolumeKind = when {
        v.isPrimary && !v.isRemovable -> VolumeKind.INTERNAL
        label.contains("usb", true) || label.contains("otg", true) || label.contains("drive", true) -> VolumeKind.USB
        else -> VolumeKind.SD_CARD
    }

    /** StorageManager's view (works unrooted), optionally enriched with `sm list-volumes` when rooted. */
    suspend fun volumes(safTrees: Set<String>): List<VolumeInfo> {
        val rootVols: List<SmVolume> =
            if (RootShell.isGranted) SmParser.parseVolumes(RootShell.run("sm list-volumes all").out) else emptyList()
        val list = sm.storageVolumes.map { v ->
            val label = v.getDescription(context) ?: "Storage"
            val dir = dirOf(v)
            val state = VolumeState.fromEnvironment(v.state)
            val stat = dir?.takeIf { state.isReadable }?.let { runCatching { StatFs(it.path) }.getOrNull() }
            val uuid = v.uuid
            VolumeInfo(
                id = uuid ?: if (v.isPrimary) "primary" else label,
                label = if (v.isPrimary && !v.isRemovable) "Internal storage" else label,
                kind = kindOf(v, label),
                path = dir?.absolutePath,
                state = state,
                totalBytes = stat?.totalBytes ?: 0L,
                freeBytes = stat?.availableBytes ?: 0L,
                uuid = uuid,
                directAccess = dir != null && state.isReadable && dir.canRead() && dir.list() != null,
                safTree = safTrees.firstOrNull { it.substringBefore('|') == (uuid ?: "\u0000") }?.substringAfter('|'),
                rootVolumeId = rootVols.firstOrNull { uuid != null && it.uuid.equals(uuid, true) }?.id,
            )
        }.toMutableList()
        // Unmounted public volumes only show up in `sm` (StorageManager hides them).
        rootVols.filter { it.isPublic && list.none { v -> v.rootVolumeId == it.id } }.forEach {
            list += VolumeInfo(
                id = it.id, label = "Removable volume (${it.id.removePrefix("public:")})",
                kind = VolumeKind.USB, path = null, state = VolumeState.fromEnvironment(it.state),
                uuid = it.uuid, rootVolumeId = it.id,
            )
        }
        return list
    }

    fun storageVolumeFor(uuid: String?): StorageVolume? =
        sm.storageVolumes.firstOrNull { it.uuid == uuid && uuid != null }

    /** SAF grant prompt pre-pointed at the volume (API 29+), otherwise the generic tree picker. */
    fun openTreeIntent(uuid: String?): Intent {
        val v = storageVolumeFor(uuid)
        return if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.createOpenDocumentTreeIntent()
        else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    /** Unrooted "safely eject": Android only allows the system Storage settings to unmount. */
    fun ejectSettingsIntent(): Intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)

    suspend fun rootMount(volumeId: String, mount: Boolean): RootShell.Result {
        val cmd = SmParser.mountCommand(SmVolume(volumeId, "", null), mount)
            ?: return RootShell.Result(false, emptyList(), listOf("Not a removable volume"))
        return RootShell.run(cmd)
    }
}
