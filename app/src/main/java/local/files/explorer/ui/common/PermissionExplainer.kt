package local.files.explorer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Permissions the app asks for just in time, each with its own explanation. */
enum class PermissionKind(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val points: List<String>,
    val settingsHint: String,
) {
    ALL_FILES(
        Icons.Rounded.FolderOpen,
        "Allow access to all files?",
        "Android asks you to switch on \u201cAllow access to manage all files\u201d before this app can show your folders.",
        listOf(
            "Browse, copy, move and delete anything on your storage",
            "Show Images, Videos, Downloads and Largest files on Home",
            "File Manager has no internet permission. Your files stay on this phone",
        ),
        "Next, Android opens a settings page. Turn on the switch for File Manager, then come back.",
    ),
    INSTALL_APPS(
        Icons.Rounded.Android,
        "Allow File Manager to install apps?",
        "To install this APK, Android needs you to allow File Manager as a source of apps. You only have to do this once.",
        listOf(
            "Android always shows its own Install or Update screen first",
            "Nothing is installed without your confirmation",
            "You can turn it off again whenever you like",
        ),
        "Next, Android opens \u201cInstall unknown apps\u201d. Turn on \u201cAllow from this source\u201d, then come back.",
    ),
    NOTIFICATIONS(
        Icons.Rounded.Notifications,
        "Show progress notifications?",
        "Big copies and moves keep going when you leave the app. A notification shows how far along they are and lets you cancel them.",
        listOf(
            "Only used for file operations you start",
            "No ads, no reminders, no tracking",
            "If you say no, operations still finish normally",
        ),
        "Next, Android asks whether File Manager can send notifications.",
    ),
}

@Composable
fun PermissionExplainerCard(
    kind: PermissionKind,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    settingsHint: String = kind.settingsHint,
) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(kind.icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(16.dp))
            Text(kind.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(kind.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                kind.points.forEach { p ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                        Spacer(Modifier.width(10.dp))
                        Text(p, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(settingsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNotNow) { Text("Not now") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onContinue) { Text("Continue") }
            }
        }
    }
}

@Composable
fun PermissionExplainerDialog(kind: PermissionKind, onContinue: () -> Unit, onNotNow: () -> Unit, settingsHint: String = kind.settingsHint) {
    Dialog(onDismissRequest = onNotNow, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PermissionExplainerCard(kind, onContinue, onNotNow, Modifier.padding(horizontal = 24.dp).fillMaxWidth(), settingsHint)
    }
}
