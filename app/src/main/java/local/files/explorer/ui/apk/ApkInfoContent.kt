package local.files.explorer.ui.apk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.apk.ApkComponent
import local.files.explorer.apk.ApkDetails
import local.files.explorer.core.Format
import local.files.explorer.ui.theme.MonoStyle

data class ApkInfoUi(
    val fileName: String,
    val loading: Boolean = true,
    val details: ApkDetails? = null,
    val icon: ImageBitmap? = null,
    val error: String? = null,
    /** False when the screen was opened from inside the APK's own file list. */
    val canBrowse: Boolean = true,
)

interface ApkInfoActions {
    fun install() {}
    fun browse() {}
    fun extractAll() {}
}

/** Offline APK info: identity, SDK levels, permissions and components, decoded from the file itself. */
@Composable
fun ApkInfoContent(ui: ApkInfoUi, actions: ApkInfoActions, modifier: Modifier = Modifier) {
    val d = ui.details
    when {
        d == null && ui.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        d == null -> Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Android, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(ui.fileName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(ui.error ?: "Couldn't read this package.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        else -> ApkDetailsList(d, ui, actions, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApkDetailsList(d: ApkDetails, ui: ApkInfoUi, actions: ApkInfoActions, modifier: Modifier) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(ui.icon)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(d.packageName, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("Version ${d.versionName ?: "?"} (${d.versionCode})")
                            if (d.debuggable) append(" · debuggable")
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact("Min SDK", sdkText(d.minSdk))
                Fact("Target SDK", sdkText(d.targetSdk))
                Fact("Size", Format.size(d.sizeBytes))
                Fact("Files", d.entryCount.toString())
                if (d.dexCount > 0) Fact("Dex files", d.dexCount.toString())
                Fact("Native code", d.nativeAbis.joinToString(", ").ifEmpty { "None" })
                if (d.bundleBase != null) Fact("Bundle", "${d.splitCount} APKs")
            }
        }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::install) { Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Install") }
                if (ui.canBrowse) FilledTonalButton(onClick = actions::browse) { Icon(Icons.Rounded.FolderZip, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browse files") }
                OutlinedButton(onClick = actions::extractAll) { Icon(Icons.Rounded.Unarchive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Extract all") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        section("Permissions", d.permissions.map { Line(it.substringAfterLast('.'), it, null) }, open, "No permissions requested")
        section("Activities", d.activities.map { comp(it, d.packageName) }, open, "None")
        section("Services", d.services.map { comp(it, d.packageName) }, open, "None")
        section("Receivers", d.receivers.map { comp(it, d.packageName) }, open, "None")
        section("Providers", d.providers.map { comp(it, d.packageName) }, open, "None")
        if (d.features.isNotEmpty()) section("Hardware & software features", d.features.map { Line(it.substringAfterLast('.'), it, null) }, open, "None")
        item {
            Text(
                "Read offline from the file itself. Nothing is installed or sent anywhere.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

private data class Line(val title: String, val detail: String, val badge: String?)

private fun comp(c: ApkComponent, pkg: String): Line {
    val short = if (c.name.startsWith("$pkg.")) c.name.removePrefix(pkg) else c.name
    val badges = buildList {
        if (c.isLauncher) add("launcher")
        if (c.exported == true) add("exported")
        c.permission?.let { add("needs ${it.substringAfterLast('.')}") }
    }
    val detail = c.authorities?.let { "${c.name}\nauthority: $it" } ?: c.name
    return Line(short.substringAfterLast('.').ifEmpty { short }, detail, badges.joinToString(" · ").ifEmpty { null })
}

private const val COLLAPSED = 6

private fun LazyListScope.section(title: String, lines: List<Line>, open: MutableMap<String, Boolean>, empty: String) {
    item(key = "h-$title") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Text(lines.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (lines.isEmpty()) {
        item(key = "e-$title") { Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        return
    }
    val expanded = open[title] == true
    val shown = if (expanded || lines.size <= COLLAPSED + 1) lines else lines.take(COLLAPSED)
    items(shown.size, key = { "$title-$it" }) { i ->
        val l = shown[i]
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Text(l.detail, style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (lines.size > COLLAPSED + 1) item(key = "m-$title") {
        Row(
            Modifier.fillMaxWidth().clickable { open[title] = !expanded }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "Show fewer" else "Show all ${lines.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) Image(icon, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
    else Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Android, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** "24 · Android 7.0" */
fun sdkText(api: Int?): String = if (api == null) "?" else androidVersion(api)?.let { "$api · Android $it" } ?: api.toString()

fun androidVersion(api: Int): String? = when (api) {
    1 -> "1.0"; 2 -> "1.1"; 3 -> "1.5"; 4 -> "1.6"; 5 -> "2.0"; 6 -> "2.0.1"; 7 -> "2.1"; 8 -> "2.2"; 9 -> "2.3"; 10 -> "2.3.3"
    11 -> "3.0"; 12 -> "3.1"; 13 -> "3.2"; 14 -> "4.0"; 15 -> "4.0.3"; 16 -> "4.1"; 17 -> "4.2"; 18 -> "4.3"; 19 -> "4.4"; 20 -> "4.4W"
    21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
    31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
    else -> null
}
