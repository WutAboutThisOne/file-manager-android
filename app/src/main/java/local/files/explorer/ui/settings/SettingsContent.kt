package local.files.explorer.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import local.files.explorer.R
import local.files.explorer.core.ChangelogEntry
import local.files.explorer.core.SortField
import local.files.explorer.core.SortOrder
import local.files.explorer.root.RootState
import local.files.explorer.settings.AppSettings
import local.files.explorer.settings.ThemeMode
import local.files.explorer.settings.ViewMode

interface SettingsActions {
    fun theme(m: ThemeMode) {}
    fun respectNomedia(v: Boolean) {}
    fun showHidden(v: Boolean) {}
    fun viewMode(v: ViewMode) {}
    fun sort(v: SortOrder) {}
    fun recursiveSearch(v: Boolean) {}
    fun rootWrites(v: Boolean) {}
    fun recheckRoot() {}
    fun about() {}
}

/** Settings screen: theme, browsing options, root status and opt-ins, and the link to About. */
@Composable
fun SettingsContent(s: AppSettings, root: RootState, version: String, actions: SettingsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Appearance")
        Card {
            RowItem(Icons.Rounded.DarkMode, "Theme", "System default follows your phone's dark mode")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.theme == m, onClick = { actions.theme(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(if (m == ThemeMode.SYSTEM) "System" else m.label) }
                }
            }
        }
        Section("Browsing")
        Card {
            SwitchItem(Icons.Rounded.Visibility, "Show hidden files", "Files and folders starting with a dot", s.showHidden, actions::showHidden)
            SwitchItem(
                Icons.Rounded.VisibilityOff, "Respect .nomedia",
                "Folders with .nomedia get a badge, no thumbnails, and are left out of Recent. You can still browse them.",
                s.respectNomedia, actions::respectNomedia,
            )
            RowItem(Icons.Rounded.GridView, "Default view", null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                ViewMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = s.viewMode == m, onClick = { actions.viewMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size)) { Text(m.label) }
                }
            }
            RowItem(Icons.AutoMirrored.Rounded.Sort, "Default sort", null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SortField.entries.forEachIndexed { i, f ->
                    SegmentedButton(selected = s.sort.field == f, onClick = { actions.sort(s.sort.copy(field = f)) }, shape = SegmentedButtonDefaults.itemShape(i, SortField.entries.size)) {
                        Text(f.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 4.dp)) {
                SegmentedButton(selected = s.sort.ascending, onClick = { actions.sort(s.sort.copy(ascending = true)) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ascending") }
                SegmentedButton(selected = !s.sort.ascending, onClick = { actions.sort(s.sort.copy(ascending = false)) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Descending") }
            }
            SwitchItem(Icons.Rounded.Search, "Search subfolders", "Same as the This folder / Subfolders switch in the search bar", s.recursiveSearch, actions::recursiveSearch)
        }
        Section("Root")
        Card {
            when (root) {
                RootState.GRANTED -> {
                    RowItem(Icons.Rounded.Key, "Root access granted", "Root tab, real mount/unmount and silent installs are on")
                    SwitchItem(
                        Icons.Rounded.EditNote, "Allow changes in root locations",
                        "Off by default. Changing system files can stop your phone from booting.",
                        s.rootWrites, actions::rootWrites,
                    )
                }
                RootState.DENIED -> {
                    RowItem(Icons.Rounded.Block, "Root was denied", "Allow File Manager in your root manager, then check again")
                    TextButton(onClick = actions::recheckRoot, modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) { Text("Check again") }
                }
                RootState.CHECKING -> RowItem(Icons.Rounded.Key, "Checking for root…", null)
                RootState.NOT_AVAILABLE -> {
                    RowItem(Icons.Rounded.Key, "Root not available", "Everything else works normally without it")
                    TextButton(onClick = actions::recheckRoot, modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) { Text("Check again") }
                }
            }
        }
        Section("About")
        Card { RowItem(Icons.Rounded.Info, "File Manager $version", "No ads, no tracking, no internet permission", onClick = actions::about) }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun RowItem(icon: ImageVector, title: String, sub: String?, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.let { Spacer(Modifier.width(12.dp)); it() }
    }
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowItem(icon, title, sub, onClick = { onChange(!checked) }) { Switch(checked, onChange) }
}

@Composable
fun AboutContent(
    version: String,
    changelog: List<ChangelogEntry> = emptyList(),
    versionCode: Int? = null,
    modifier: Modifier = Modifier,
    onLicences: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("File Manager", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (versionCode != null) "Version $version (build $versionCode)" else "Version $version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("aboutVersion"),
        )
        Spacer(Modifier.height(24.dp))
        listOf(
            Icons.Rounded.Block to "No ads. Ever.",
            Icons.Rounded.Shield to "No analytics, trackers or crash reporters.",
            Icons.Rounded.WifiOff to "No internet permission: nothing can leave your phone.",
            Icons.Rounded.Key to "Root features switch on only when root is granted.",
        ).forEach { (i, t) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(i, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(t, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (changelog.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "What's new",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp),
            ) { ChangelogList(changelog, installed = version) }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(onClick = onLicences).padding(16.dp).testTag("openLicences"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Gavel, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Open-source licences", style = MaterialTheme.typography.bodyLarge)
                Text("libsu, zip4j, Apache Commons, XZ for Java, AndroidX and Kotlin", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
