package local.files.explorer.ui.install

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.files.explorer.install.ApkInfo
import local.files.explorer.install.InstallStatus
import local.files.explorer.ui.common.PermissionExplainerCard
import local.files.explorer.ui.common.PermissionKind

/** State of the "Open with File Manager" install screen. */
data class ExternalInstallUi(
    val fileName: String? = null,
    val info: ApkInfo? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val status: InstallStatus = InstallStatus.Idle,
    val canInstallUnknown: Boolean = false,
    val rootAvailable: Boolean = false,
    val explainer: Boolean = false,
)

interface ExternalInstallActions {
    fun install() {}
    fun installRoot() {}
    fun openUnknownSources() {}
    fun explainerContinue() {}
    fun explainerNotNow() {}
    fun dismiss() {}
}

/** The APK sheet on its own, over whatever app sent the file (the activity is translucent). */
@Composable
fun ExternalInstallContent(ui: ExternalInstallUi, actions: ExternalInstallActions, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!ui.explainer) actions.dismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (ui.explainer) {
            Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
                PermissionExplainerCard(
                    PermissionKind.INSTALL_APPS, actions::explainerContinue, actions::explainerNotNow,
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(top = 12.dp).navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inbox, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Opened from another app" + (ui.fileName?.let { " \u00b7 $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    ApkInstallContent(
                        ui.info, ui.loading, ui.error, ui.status, ui.canInstallUnknown, ui.rootAvailable,
                        actions::install, actions::installRoot, actions::openUnknownSources, actions::dismiss,
                    )
                }
            }
        }
    }
}
