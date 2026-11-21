package local.files.explorer.ui.volumes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Format
import local.files.explorer.core.VolumeInfo
import local.files.explorer.core.VolumeKind
import local.files.explorer.core.VolumeState

data class SafFolder(val label: String, val uri: String)

interface VolumeActions {
    fun open(v: VolumeInfo) {}
    fun eject(v: VolumeInfo) {}
    fun rootMount(v: VolumeInfo, mount: Boolean) {}
    fun grant(v: VolumeInfo) {}
    fun openSaf(f: SafFolder) {}
    fun forgetSaf(f: SafFolder) {}
    fun addFolder() {}
}

/** Storage tab: every volume with state and free space, plus folders granted through Android's picker. */
@Composable
fun VolumesContent(
    volumes: List<VolumeInfo>,
    rootGranted: Boolean,
    safFolders: List<SafFolder>,
    actions: VolumeActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ModeBanner(rootGranted) }
        items(volumes, key = { it.id }) { VolumeCard(it, rootGranted, actions) }
        if (volumes.none { it.kind != VolumeKind.INTERNAL }) item {
            Text(
                "No SD card or USB drive detected. Plug one in and it'll appear here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Folders you've granted", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = actions::addFolder) {
                    Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add")
                }
            }
        }
        if (safFolders.isEmpty()) item {
            Text(
                "Use \u201cAdd\u201d to open any folder through Android's picker, including drives File Manager can't reach directly.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        items(safFolders, key = { it.uri }) { f ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { actions.openSaf(f) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { actions.forgetSaf(f) }) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun ModeBanner(root: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (root) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(14.dp),
    ) {
        Icon(
            if (root) Icons.Rounded.Key else Icons.Rounded.Info, null,
            tint = if (root) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (root) "Root mode" else "Standard mode",
                style = MaterialTheme.typography.titleSmall,
                color = if (root) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                if (root) "Mount and unmount run directly through the system storage manager (sm)."
                else "Android only lets the system unmount drives. \u201cSafely eject\u201d opens Storage settings to do it.",
                style = MaterialTheme.typography.bodySmall,
                color = if (root) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun stateColor(s: VolumeState): Color = when (s) {
    VolumeState.MOUNTED -> MaterialTheme.colorScheme.tertiary
    VolumeState.MOUNTED_READ_ONLY, VolumeState.CHECKING, VolumeState.EJECTING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VolumeCard(v: VolumeInfo, root: Boolean, actions: VolumeActions) {
    val openable = v.state.isReadable && (v.directAccess || v.safTree != null)
    Surface(
        onClick = { if (openable) actions.open(v) else if (v.state.isReadable) actions.grant(v) },
        enabled = v.state.isReadable,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (v.kind) { VolumeKind.INTERNAL -> Icons.Rounded.PhoneAndroid; VolumeKind.SD_CARD -> Icons.Rounded.SdCard; VolumeKind.USB -> Icons.Rounded.Usb },
                        null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.label, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(v.state)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            v.state.label + (v.path?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
                if (openable) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (v.totalBytes > 0) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { v.usedFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    color = if (v.usedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("${Format.size(v.usedBytes)} used", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("${Format.size(v.freeBytes)} free of ${Format.size(v.totalBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (v.kind != VolumeKind.INTERNAL) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.state.isReadable && !v.directAccess && v.safTree == null) {
                        AssistChip(onClick = { actions.grant(v) }, label = { Text("Grant access") }, leadingIcon = { Icon(Icons.Rounded.Key, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    }
                    if (root && v.rootVolumeId != null) {
                        if (v.state.isReadable) AssistChip(onClick = { actions.rootMount(v, false) }, label = { Text("Unmount") }, leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(AssistChipDefaults.IconSize)) })
                        else AssistChip(onClick = { actions.rootMount(v, true) }, label = { Text("Mount") }, leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    }
                    if (v.state.isReadable) {
                        AssistChip(onClick = { actions.eject(v) }, label = { Text("Safely eject") }, leadingIcon = { Icon(Icons.Rounded.Eject, null, Modifier.size(AssistChipDefaults.IconSize)) })
                    }
                }
            }
        }
    }
}
