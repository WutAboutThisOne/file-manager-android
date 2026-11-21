package local.files.explorer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import local.files.explorer.core.Backend

/** Items for the normal folder browser's top-right toolbar ⋮ menu. */
enum class BrowserOverflowItem(val label: String, val icon: ImageVector) {
    NEW_FOLDER("New folder", Icons.Rounded.CreateNewFolder),
    SORT("Sort by…", Icons.AutoMirrored.Rounded.Sort),
    HIDE_HIDDEN("Hide hidden files", Icons.Rounded.VisibilityOff),
    SHOW_HIDDEN("Show hidden files", Icons.Rounded.Visibility),
    SELECT_ALL("Select all", Icons.Rounded.SelectAll),
    ADD_NOMEDIA("Add .nomedia", Icons.Rounded.VisibilityOff),
    REMOVE_NOMEDIA("Remove .nomedia", Icons.Rounded.Visibility),
    REMOUNT_SYSTEM("Remount /system read-write…", Icons.Rounded.Warning),
    REFRESH("Refresh", Icons.Rounded.Refresh),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/**
 * Builds the toolbar ⋮ menu for the current folder.
 * [hasNomedia] is whether that folder already contains a `.nomedia` file.
 * Add / Remove .nomedia is only offered for a writable local folder.
 */
fun browserOverflowItems(
    browsing: Boolean,
    hasLocation: Boolean,
    backend: Backend?,
    readOnly: Boolean,
    showHidden: Boolean,
    hasEntries: Boolean,
    hasNomedia: Boolean,
    tab: Tab,
    rootWrites: Boolean,
): List<BrowserOverflowItem> = buildList {
    if (browsing) {
        if (hasLocation && !readOnly) add(BrowserOverflowItem.NEW_FOLDER)
        add(BrowserOverflowItem.SORT)
        add(if (showHidden) BrowserOverflowItem.HIDE_HIDDEN else BrowserOverflowItem.SHOW_HIDDEN)
        if (hasEntries) add(BrowserOverflowItem.SELECT_ALL)
        if (hasLocation && !readOnly && backend == Backend.LOCAL) {
            add(if (hasNomedia) BrowserOverflowItem.REMOVE_NOMEDIA else BrowserOverflowItem.ADD_NOMEDIA)
        }
    }
    if (tab == Tab.ROOT && rootWrites) add(BrowserOverflowItem.REMOUNT_SYSTEM)
    add(BrowserOverflowItem.REFRESH)
    add(BrowserOverflowItem.SETTINGS)
}

@Composable
fun BrowserOverflowMenuItems(items: List<BrowserOverflowItem>, onItem: (BrowserOverflowItem) -> Unit) {
    items.forEach { item ->
        if (item == BrowserOverflowItem.SETTINGS) HorizontalDivider()
        DropdownMenuItem(
            { Text(item.label) },
            { onItem(item) },
            leadingIcon = { Icon(item.icon, null) },
        )
    }
}
