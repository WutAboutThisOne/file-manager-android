package local.files.explorer.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.files.explorer.core.FileEntry
import local.files.explorer.core.FileType
import local.files.explorer.ui.theme.LocalTypePalette

data class TypeVisual(val icon: ImageVector, val color: Color)

@Composable
fun visualFor(type: FileType): TypeVisual {
    val dark = LocalTypePalette.current.dark
    fun c(light: Long, darkC: Long) = Color(if (dark) darkC else light)
    return when (type) {
        FileType.FOLDER -> TypeVisual(Icons.Rounded.Folder, MaterialTheme.colorScheme.primary)
        FileType.IMAGE -> TypeVisual(Icons.Rounded.Image, c(0xFFD9480F, 0xFFFFA77A))
        FileType.VIDEO -> TypeVisual(Icons.Rounded.VideoFile, c(0xFFC2255C, 0xFFFF8FB3))
        FileType.AUDIO -> TypeVisual(Icons.Rounded.AudioFile, c(0xFF7048E8, 0xFFC5B3FF))
        FileType.PDF -> TypeVisual(Icons.Rounded.PictureAsPdf, c(0xFFC92A2A, 0xFFFF9C9C))
        FileType.DOCUMENT -> TypeVisual(Icons.Rounded.Description, c(0xFF1C7ED6, 0xFF8CC8FF))
        FileType.SPREADSHEET -> TypeVisual(Icons.Rounded.TableChart, c(0xFF2B8A3E, 0xFF8CE99A))
        FileType.PRESENTATION -> TypeVisual(Icons.Rounded.Slideshow, c(0xFFE67700, 0xFFFFC078))
        FileType.ARCHIVE -> TypeVisual(Icons.Rounded.Inventory2, c(0xFF8D6E00, 0xFFF0CF6B))
        FileType.APK -> TypeVisual(Icons.Rounded.Android, c(0xFF2F9E44, 0xFF8CE99A))
        FileType.APK_BUNDLE -> TypeVisual(Icons.Rounded.Widgets, c(0xFF2F9E44, 0xFF8CE99A))
        FileType.CODE -> TypeVisual(Icons.Rounded.Code, c(0xFF0B7285, 0xFF84DCEB))
        FileType.TEXT -> TypeVisual(Icons.AutoMirrored.Rounded.TextSnippet, c(0xFF495057, 0xFFCED4DA))
        FileType.FONT -> TypeVisual(Icons.Rounded.FontDownload, c(0xFF5F3DC4, 0xFFB197FC))
        FileType.OTHER -> TypeVisual(Icons.AutoMirrored.Rounded.InsertDriveFile, c(0xFF6C6F7D, 0xFFB5B7C4))
    }
}

/** Supplies thumbnails; the default (tests/previews) returns none. */
fun interface ThumbnailSource {
    suspend fun load(entry: FileEntry): ImageBitmap?
}

val LocalThumbnails = staticCompositionLocalOf<ThumbnailSource?> { null }

@Composable
fun FileIcon(
    entry: FileEntry,
    size: Dp = 42.dp,
    allowThumbnail: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val v = visualFor(entry.type)
    val source = LocalThumbnails.current
    var thumb by remember(entry.path) { mutableStateOf<ImageBitmap?>(null) }
    val wantsThumb = allowThumbnail && source != null && (entry.type == FileType.IMAGE || entry.type == FileType.VIDEO || entry.type == FileType.APK)
    if (wantsThumb) {
        LaunchedEffect(entry.path, entry.lastModified) { thumb = runCatching { source!!.load(entry) }.getOrNull() }
    }
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier.size(size).clip(shape).background(v.color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(t, null, Modifier.size(size), contentScale = if (entry.type == FileType.APK) ContentScale.Fit else ContentScale.Crop)
        } else {
            Icon(v.icon, null, tint = v.color, modifier = Modifier.size(size * 0.56f))
        }
    }
}
