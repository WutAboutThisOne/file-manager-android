package local.files.explorer.ui.install

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Format
import local.files.explorer.install.ApkInfo
import local.files.explorer.install.InstallStatus
import local.files.explorer.ui.theme.MonoStyle

private fun sdkName(api: Int?): String = when (api) {
    null -> "?"
    26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
    else -> "API $api"
}

/** Install sheet for an APK or split bundle: app details, update/downgrade status, and the install flow. */
@Composable
fun ApkInstallContent(
    info: ApkInfo?,
    loading: Boolean,
    error: String?,
    status: InstallStatus,
    canInstallUnknown: Boolean,
    rootAvailable: Boolean,
    onInstall: () -> Unit,
    onInstallRoot: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        when {
            loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null || info == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(error ?: "Couldn't read this package", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (info.icon != null) Image(info.icon.asImageBitmap(), null, Modifier.size(56.dp))
                        else Icon(Icons.Rounded.Android, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(info.packageName, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Fact("Version", "${info.versionName ?: "?"} (${info.versionCode})")
                    Fact("Requires", "Android ${sdkName(info.minSdk)}+ · targets ${sdkName(info.targetSdk)}")
                    Fact("Size", Format.size(info.sizeBytes) + if (info.isBundle) " · ${info.splitCount} split APKs" else "")
                    if (info.obbCount > 0) Fact("OBB", "${info.obbCount} expansion file(s) not installed automatically")
                    Fact("Permissions", "${info.permissionCount} requested")
                }
                Spacer(Modifier.height(16.dp))
                when (status) {
                    is InstallStatus.Installing -> {
                        Text("Installing…", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    InstallStatus.WaitingForConfirmation -> Text("Confirm the install in the system dialog…", style = MaterialTheme.typography.bodyMedium)
                    is InstallStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp)); Text(status.message, style = MaterialTheme.typography.titleSmall)
                    }
                    else -> {
                        if (status is InstallStatus.Failure) {
                            Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (!canInstallUnknown) {
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Android needs you to allow File Manager to install apps. It's a one-time switch in Settings. Nothing is installed without your confirmation.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenUnknownSources, modifier = Modifier.fillMaxWidth()) { Text("Allow installs") }
                        } else {
                            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Android's installer will show whether this is a new install or an update before anything changes.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (rootAvailable) {
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(onClick = onInstallRoot, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install silently with root")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(k: String, v: String, highlight: Boolean = false) {
    Row {
        Text(k, Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
