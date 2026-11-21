package local.files.explorer.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import local.files.explorer.core.Backend
import local.files.explorer.core.ConflictPolicy
import local.files.explorer.core.FileEntry
import local.files.explorer.core.FileType
import local.files.explorer.core.Names
import local.files.explorer.ui.common.FileIcon
import local.files.explorer.ui.theme.MonoStyle

@Composable
fun NameDialog(
    title: String, initial: String, confirm: String,
    validate: (String) -> String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    val base = Names.splitExtension(initial).first
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, base.length))) }
    val error = if (value.text == initial && initial.isNotEmpty()) null else validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true, isError = error != null && value.text.isNotEmpty(),
                supportingText = { if (error != null && value.text.isNotEmpty()) Text(error) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (error == null) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = error == null && value.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String, body: String, confirm: String, destructive: Boolean = false,
    icon: ImageVector? = null, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConflictDialog(conflicts: List<String>, onChoose: (ConflictPolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text(if (conflicts.size == 1) "\u201c${conflicts[0]}\u201d already exists" else "${conflicts.size} items already exist") },
        text = {
            Column {
                Text("What should happen to items with the same name here?")
                if (conflicts.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(conflicts.take(4).joinToString("\n") + if (conflicts.size > 4) "\n…" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                listOf(
                    ConflictPolicy.KEEP_BOTH to "Keep both (adds \u201c(1)\u201d)",
                    ConflictPolicy.REPLACE to "Replace (folders are merged)",
                    ConflictPolicy.SKIP to "Skip these",
                ).forEach { (p, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p == ConflictPolicy.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(p) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class PropertiesUi(
    val entry: FileEntry,
    val sizeBytes: Long? = null,
    val files: Int? = null,
    val folders: Int? = null,
    val created: Long? = null,
    val extra: List<Pair<String, String>> = emptyList(),
)

@Composable
fun PropertiesDialog(p: PropertiesUi, onDismiss: () -> Unit) {
    val e = p.entry
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { FileIcon(e, size = 48.dp, allowThumbnail = false) },
        title = { Text(e.name, maxLines = 2) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Prop("Type", if (e.isDirectory) "Folder" else (e.mimeType ?: e.extension.uppercase().ifEmpty { "File" }))
                    Prop("Location", if (e.backend == Backend.SAF) "Storage Access Framework" else e.path, mono = e.backend != Backend.SAF)
                    Prop("Size", p.sizeBytes?.let { "${local.files.explorer.core.Format.size(it)} (${"%,d".format(it)} bytes)" } ?: "Calculating…")
                    if (e.isDirectory) Prop("Contains", if (p.files == null) "Counting…" else "${p.files} files, ${p.folders} folders")
                    Prop("Modified", local.files.explorer.core.Format.date(e.lastModified))
                    p.created?.let { Prop("Created", local.files.explorer.core.Format.date(it)) }
                    e.permissions?.let { Prop("Permissions", it, mono = true) }
                    if (e.owner != null) Prop("Owner", "${e.owner}:${e.group}", mono = true)
                    p.extra.forEach { (k, v) -> Prop(k, v, mono = true) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium)
    }
}

enum class ItemAction(val label: String, val icon: ImageVector) {
    GO_TO_LOCATION("Go to file location", Icons.Rounded.FolderOpen),
    OPEN("Open", Icons.Rounded.FileOpen),
    INSTALL("Install", Icons.Rounded.Android),
    APP_INFO("App info", Icons.Rounded.Info),
    BROWSE_APK("Browse contents", Icons.Rounded.FolderZip),
    OPEN_WITH("Open with…", Icons.AutoMirrored.Rounded.OpenInNew),
    SHARE("Share", Icons.Rounded.Share),
    COPY("Copy", Icons.Rounded.ContentCopy),
    CUT("Cut", Icons.Rounded.ContentCut),
    MOVE("Move to\u2026", Icons.AutoMirrored.Rounded.DriveFileMove),
    RENAME("Rename", Icons.Rounded.DriveFileRenameOutline),
    ADD_NOMEDIA("Add .nomedia", Icons.Rounded.VisibilityOff),
    REMOVE_NOMEDIA("Remove .nomedia", Icons.Rounded.Visibility),
    EXTRACT_HERE("Extract here", Icons.Rounded.Unarchive),
    EXTRACT_TO("Extract to\u2026", Icons.Rounded.Unarchive),
    COMPRESS("Compress\u2026", Icons.Rounded.Archive),
    PROPERTIES("Properties", Icons.Rounded.Info),
    DETAILS("Details", Icons.Rounded.Info),
    DELETE("Delete", Icons.Rounded.Delete),
}

/** Menu for files shown outside their folder (Home lists, Largest files, search results). */
fun contextActionsFor(e: FileEntry, readOnly: Boolean): List<ItemAction> = buildList {
    add(ItemAction.GO_TO_LOCATION)
    add(ItemAction.OPEN)
    if (e.type == FileType.APK || e.type == FileType.APK_BUNDLE) add(ItemAction.INSTALL)
    if (isArchive(e)) { if (!readOnly) add(ItemAction.EXTRACT_HERE); add(ItemAction.EXTRACT_TO) }
    if (!e.isDirectory && e.backend != Backend.ROOT) add(ItemAction.SHARE)
    add(ItemAction.COPY)
    if (!readOnly) {
        add(ItemAction.CUT)
        add(ItemAction.MOVE)
        add(ItemAction.RENAME)
    }
    if (e.isDirectory && e.backend == Backend.LOCAL) {
        add(if (e.hasNomedia) ItemAction.REMOVE_NOMEDIA else ItemAction.ADD_NOMEDIA)
    }
    add(ItemAction.DETAILS)
    if (!readOnly) add(ItemAction.DELETE)
}

fun isArchive(e: FileEntry) = !e.isDirectory && local.files.explorer.archive.ArchiveFormat.isArchiveName(e.name)

fun actionsFor(e: FileEntry, readOnly: Boolean, searchResult: Boolean = false): List<ItemAction> = buildList {
    if (searchResult) add(ItemAction.GO_TO_LOCATION)
    val pkg = !e.isDirectory && (e.type == FileType.APK || e.type == FileType.APK_BUNDLE)
    if (pkg) { add(ItemAction.INSTALL); add(ItemAction.APP_INFO); add(ItemAction.BROWSE_APK) }
    if (e.backend == Backend.ARCHIVE) {
        // Inside an archive: read-only, but entries can be opened, shared, copied out or extracted.
        if (isArchive(e)) add(ItemAction.OPEN)
        if (!e.isDirectory) { add(ItemAction.OPEN_WITH); add(ItemAction.SHARE) }
        add(ItemAction.EXTRACT_TO)
        add(ItemAction.COPY)
        add(ItemAction.PROPERTIES)
        return@buildList
    }
    if (isArchive(e)) { add(ItemAction.OPEN); if (!readOnly) add(ItemAction.EXTRACT_HERE); add(ItemAction.EXTRACT_TO) }
    else if (pkg) { if (!readOnly) add(ItemAction.EXTRACT_HERE); add(ItemAction.EXTRACT_TO) }
    if (!e.isDirectory) { add(ItemAction.OPEN_WITH); if (e.backend != Backend.ROOT) add(ItemAction.SHARE) }
    if (!readOnly) add(ItemAction.COMPRESS)
    add(ItemAction.COPY)
    if (!readOnly) { add(ItemAction.CUT); add(ItemAction.RENAME) }
    if (e.isDirectory && e.backend == Backend.LOCAL) add(if (e.hasNomedia) ItemAction.REMOVE_NOMEDIA else ItemAction.ADD_NOMEDIA)
    add(ItemAction.PROPERTIES)
    if (!readOnly) add(ItemAction.DELETE)
}

@Composable
fun ItemActionsContent(e: FileEntry, actions: List<ItemAction>, onAction: (ItemAction) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(e, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(subtitleFor(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        actions.forEach { a ->
            val danger = a == ItemAction.DELETE
            Row(
                Modifier.fillMaxWidth().clickable { onAction(a) }.padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(a.icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(20.dp))
                Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}
