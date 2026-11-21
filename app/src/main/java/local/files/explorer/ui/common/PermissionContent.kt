package local.files.explorer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shown until All files access is granted; offers the system switch or Android's folder picker instead. */
@Composable
fun PermissionContent(denied: Boolean, onGrant: () -> Unit, onPicker: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(24.dp))
        Text("Let File Manager see your files", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "To browse, copy and organise everything on your phone, Android needs you to turn on " +
                "\u201cAllow access to manage all files\u201d for File Manager. File Manager has no internet permission, so your files never leave the device.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Access is still off. You can keep using folders you pick yourself, SD cards you grant, and Root (if available).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow access") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onPicker, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Pick a folder instead") }
    }
}
