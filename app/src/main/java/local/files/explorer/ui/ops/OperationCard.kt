package local.files.explorer.ui.ops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Format
import local.files.explorer.ops.OpKind
import local.files.explorer.ops.OpState
import local.files.explorer.ops.OpStatus

/** Progress card for a running copy/move/delete/compress job, with Cancel and a final result. */
@Composable
fun OperationCard(st: OpState, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val running = st.status == OpStatus.RUNNING || st.status == OpStatus.QUEUED
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    st.status == OpStatus.DONE -> Icons.Rounded.CheckCircle
                    st.status == OpStatus.FAILED || st.status == OpStatus.CANCELLED -> Icons.Rounded.ErrorOutline
                    st.request.kind == OpKind.MOVE -> Icons.AutoMirrored.Rounded.DriveFileMove
                    st.request.kind == OpKind.DELETE -> Icons.Rounded.Delete
                    else -> Icons.Rounded.ContentCopy
                }
                Icon(
                    icon, null, Modifier.size(22.dp),
                    tint = if (st.status == OpStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val p = st.progress
                    val line = when {
                        !running -> st.summary ?: ""
                        p.bytesTotal > 0 -> "${Format.size(p.bytesDone)} of ${Format.size(p.bytesTotal)} · ${p.itemsDone}/${p.itemsTotal} items"
                        p.itemsTotal > 0 -> "${p.itemsDone} of ${p.itemsTotal} items"
                        else -> "Preparing…"
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (running) TextButton(onClick = onCancel) { Text("Cancel") }
                else IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss") }
            }
            if (running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { st.progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
                if (st.progress.current.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        st.progress.current, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
