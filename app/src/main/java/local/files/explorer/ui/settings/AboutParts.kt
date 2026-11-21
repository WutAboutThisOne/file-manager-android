package local.files.explorer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.files.explorer.BuildConfig
import local.files.explorer.core.ChangelogEntry

/** Newest release open; every older one collapsed under its header. Tap a header to expand or collapse it. */
@Composable
fun ChangelogList(changelog: List<ChangelogEntry>, installed: String) {
    val latest = changelog.firstOrNull()?.version
    var open by rememberSaveable { mutableStateOf(listOfNotNull(latest)) }
    changelog.forEachIndexed { i, e ->
        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val expanded = e.version in open
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse" else "Expand") {
                open = if (expanded) open - e.version else open + e.version
            }.padding(vertical = 4.dp).testTag("changelog_${e.version}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(e.version, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            e.date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (e.version == installed) Text("installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (!expanded) Text(
                "${e.items.size} change${if (e.items.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column {
                e.items.forEach { item ->
                    Row(Modifier.padding(start = 4.dp, top = 2.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * One bundled component and the licence it ships under.
 *
 * @param version shown after the name in the list (blank when it doesn't apply)
 * @param licenceFile full licence text under assets/licenses/
 * @param noticeFile optional NOTICE text (under assets/licenses/) the licence asks to be reproduced
 */
data class LicenceInfo(
    val name: String,
    val version: String,
    val author: String,
    val licence: String,
    val licenceFile: String,
    val noticeFile: String? = null,
) {
    /** "name version", used as the list title and the detail page's title. */
    val title: String get() = if (version.isBlank()) name else "$name $version"
}

object Licences {
    val all = listOf(
        LicenceInfo("File Manager", BuildConfig.VERSION_NAME, "Copyright 2026 The project authors", "Apache License 2.0", "apache-2.0.txt"),
        LicenceInfo("libsu", "6.0.0", "John Wu (topjohnwu)", "Apache License 2.0", "apache-2.0.txt"),
        LicenceInfo("zip4j", "2.11.5", "Srikanth Reddy Lingala", "Apache License 2.0", "apache-2.0.txt"),
        LicenceInfo("Apache Commons Compress, IO, Lang and Codec", "1.27.1 / 2.16.1 / 3.16.0 / 1.17.1", "The Apache Software Foundation",
            "Apache License 2.0 + NOTICE", "apache-2.0.txt", noticeFile = "apache-commons-NOTICE.txt"),
        LicenceInfo("XZ for Java", "1.10", "Lasse Collin and contributors", "BSD Zero Clause (0BSD)", "0bsd.txt"),
        LicenceInfo("AndroidX, Jetpack Compose, WindowManager and Material icons", "", "The Android Open Source Project", "Apache License 2.0", "apache-2.0.txt"),
        LicenceInfo("Kotlin standard library and kotlinx.coroutines", "1.9.24 / 1.8.1", "JetBrains s.r.o. and contributors", "Apache License 2.0", "apache-2.0.txt"),
    )

    /** Looks a component up by [LicenceInfo.name]. */
    fun byName(name: String?): LicenceInfo? = all.firstOrNull { it.name == name }
}

/** The Licences page: a compact list of components and their licence types. Tapping one calls [onOpen]. */
@Composable
fun LicencesContent(modifier: Modifier = Modifier, onOpen: (LicenceInfo) -> Unit = {}) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("licencesPage"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "File Manager is open source and built with these open-source components. Tap one to read its licence.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Not affiliated with or endorsed by Google. Android is a trademark of Google LLC.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Licences.all.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Read licence") { onOpen(l) }
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("licence_${l.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(l.licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One component's own page: who made it, its NOTICE text (if any) and the full licence text. */
@Composable
fun LicenceDetailContent(info: LicenceInfo, licenceText: (String) -> String, modifier: Modifier = Modifier) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("licenceDetail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(info.licence, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(info.author, style = MaterialTheme.typography.bodyMedium)
        info.noticeFile?.let { Text(licenceText(it), style = mono, modifier = Modifier.testTag("licenceNotice")) }
        Text(licenceText(info.licenceFile), style = mono, modifier = Modifier.testTag("licenceText"))
    }
}
