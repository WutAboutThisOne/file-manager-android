package local.files.explorer.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.archive.ArchiveFormat
import local.files.explorer.archive.CompressLevel
import local.files.explorer.archive.CompressSpec
import local.files.explorer.core.Format
import local.files.explorer.core.Names

/** Screenshot tests render dialogs inline (Robolectric never idles a dialog window holding a text field). */
val LocalInlineDialogs = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Same layout as Material 3's AlertDialog, usable inside or outside a dialog window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerDialog(
    onDismissRequest: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val frame = @Composable {
        androidx.compose.material3.Surface(
            shape = androidx.compose.material3.AlertDialogDefaults.shape,
            color = androidx.compose.material3.AlertDialogDefaults.containerColor,
            tonalElevation = androidx.compose.material3.AlertDialogDefaults.TonalElevation,
            modifier = Modifier.androidx_widthIn(),
        ) {
            Column(Modifier.padding(24.dp)) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
                    modifier = if (icon != null) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton(); confirmButton() }
            }
        }
    }
    if (LocalInlineDialogs.current) frame()
    else androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismissRequest) { frame() }
}

private fun Modifier.androidx_widthIn() = this.widthIn(min = 280.dp, max = 560.dp)

/** Summary strip at the top of an archive being browsed. */
data class ArchiveUi(
    val name: String,
    val format: ArchiveFormat,
    val files: Int,
    val totalBytes: Long,
    val archiveBytes: Long,
    val encrypted: Boolean,
    val unlocked: Boolean,
    val skippedUnsafe: Int = 0,
    val suspicious: Boolean = false,
    /** Set when the archive is an Android package (APK or split bundle). */
    val apk: ApkKind? = null,
) {
    enum class ApkKind { APK, BUNDLE }
}

@Composable
fun ArchiveBar(a: ArchiveUi, onExtract: () -> Unit, onUnlock: () -> Unit, onAppInfo: () -> Unit = {}) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (a.encrypted) Icons.Rounded.Lock else if (a.apk != null) Icons.Rounded.Android else Icons.Rounded.Archive, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${when (a.apk) { ArchiveUi.ApkKind.APK -> "APK"; ArchiveUi.ApkKind.BUNDLE -> "App bundle"; null -> "${a.format.label} archive" }} · ${Format.items(a.files).replace("item", "file")}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    buildString {
                        if (a.totalBytes > 0) append("${Format.size(a.totalBytes)} unpacked, ")
                        append("${Format.size(a.archiveBytes)} packed")
                        if (a.encrypted) append(if (a.unlocked) " · unlocked" else " · password protected")
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (a.encrypted && !a.unlocked) TextButton(onClick = onUnlock) { Text("Unlock") }
            if (a.apk != null) TextButton(onClick = onAppInfo) { Text("App info") }
            FilledTonalButton(onClick = onExtract) {
                Icon(Icons.Rounded.Unarchive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Extract")
            }
        }
        if (a.skippedUnsafe > 0 || a.suspicious) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        if (a.skippedUnsafe > 0) append("${a.skippedUnsafe} unsafe ${if (a.skippedUnsafe == 1) "entry" else "entries"} (paths outside the archive) hidden and never extracted. ")
                        if (a.suspicious) append("Declared size is far larger than the file; extraction is capped.")
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun PasswordDialog(archiveName: String, wrong: Boolean, busy: Boolean, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FileManagerDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Lock,
        title = "Password needed",
        confirmButton = { TextButton(onClick = { onSubmit(pw.toCharArray()) }, enabled = pw.isNotEmpty() && !busy) { Text(if (busy) "Checking…" else "Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                Text("\u201c$archiveName\u201d is encrypted. Enter its password to open or extract it. File Manager keeps it in memory only until you close the app.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    isError = wrong,
                    supportingText = { if (wrong) Text("Wrong password. Try again.") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) onSubmit(pw.toCharArray()) }),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide password" else "Show password") }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
    )
}

enum class ExtractTarget { HERE, FOLDER, CHOOSE }

/**
 * [hereLabel] is the folder the archive lives in (null when it can't be written, e.g. an archive
 * opened from another app or a read-only root folder).
 */
@Composable
fun ExtractDialog(
    title: String, hereLabel: String?, folderName: String, count: Int?,
    onConfirm: (ExtractTarget) -> Unit, onDismiss: () -> Unit,
    /** Non-null for APKs: whether compiled XML is written decoded (readable) or as-is. */
    decodeXml: Boolean? = null, onDecodeXml: (Boolean) -> Unit = {},
) {
    var choice by remember { mutableStateOf(if (hereLabel != null) ExtractTarget.FOLDER else ExtractTarget.CHOOSE) }
    FileManagerDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Unarchive,
        title = title,
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text(if (choice == ExtractTarget.CHOOSE) "Choose" else "Extract") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                if (count != null) Text("${Format.items(count)} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val opts = buildList {
                    if (hereLabel != null) {
                        add(Triple(ExtractTarget.FOLDER, "Into a new folder", "$hereLabel \u203A $folderName"))
                        add(Triple(ExtractTarget.HERE, "Here", "Straight into $hereLabel"))
                    }
                    add(Triple(ExtractTarget.CHOOSE, "Choose folder\u2026", "Browse to a folder, then tap Extract here"))
                }
                opts.forEach { (t, label, sub) ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { choice = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == t, { choice = t })
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (decodeXml != null) {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onDecodeXml(!decodeXml) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Decode binary XML", style = MaterialTheme.typography.bodyLarge)
                            Text("Write the manifest and res/ XML as readable text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(decodeXml, onDecodeXml)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Existing files with the same name: you'll be asked whether to keep both, replace or skip.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressDialog(
    defaultName: String, count: Int, destLabel: String,
    nameExists: (String) -> Boolean,
    onConfirm: (CompressSpec) -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var level by remember { mutableStateOf(CompressLevel.NORMAL) }
    var protect by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val ext = if (format == ArchiveFormat.ZIP) ".zip" else ".7z"
    val fileName = name.trim().removeSuffix(".zip").removeSuffix(".7z") + ext
    val nameError = Names.validate(name.trim())
    val pwError = when {
        !protect -> null
        pw.isEmpty() -> "Enter a password"
        pw != pw2 -> "Passwords don't match"
        else -> null
    }
    FileManagerDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Archive,
        title = "Compress ${Format.items(count)}",
        confirmButton = {
            TextButton(
                onClick = { onConfirm(CompressSpec(fileName, format, level, if (protect) pw.toCharArray() else null)) },
                enabled = nameError == null && pwError == null,
            ) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Archive name") },
                    suffix = { Text(ext) }, isError = nameError != null,
                    supportingText = {
                        Text(nameError ?: if (nameExists(fileName)) "$fileName exists; File Manager will add \u201c(1)\u201d" else "Saved in $destLabel")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Format", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z).forEachIndexed { i, f ->
                        SegmentedButton(format == f, { format = f }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(f.label) }
                    }
                }
                Text("Compression", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CompressLevel.entries.forEachIndexed { i, l ->
                        SegmentedButton(level == l, { level = l }, SegmentedButtonDefaults.itemShape(i, CompressLevel.entries.size)) { Text(l.label, maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { protect = !protect }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Password protect", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "AES-256 encryption of the contents. File names stay visible.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(protect, { protect = it })
                }
                if (protect) {
                    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, label = { Text("Password") }, visualTransformation = vt,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide password" else "Show password") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        pw2, { pw2 = it }, singleLine = true, label = { Text("Confirm password") }, visualTransformation = vt,
                        isError = pwError != null && pw2.isNotEmpty(),
                        supportingText = { Text(pwError ?: "File Manager can't recover a forgotten password.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
