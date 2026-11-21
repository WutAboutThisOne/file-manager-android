package local.files.explorer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import local.files.explorer.core.SortField
import local.files.explorer.core.SortOrder

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }
enum class ViewMode(val label: String) { LIST("List"), GRID("Grid") }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val respectNomedia: Boolean = true,
    val showHidden: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val sort: SortOrder = SortOrder(),
    val recursiveSearch: Boolean = true,
    val rootWrites: Boolean = false,
    /** Persisted SAF trees, stored as "uuid|uri" (uuid may be empty). */
    val safTrees: Set<String> = emptySet(),
    val onboardingDone: Boolean = false,
    /** Just-in-time permission explainers already shown once. */
    val allFilesExplained: Boolean = false,
    val installExplained: Boolean = false,
    val notificationsExplained: Boolean = false,
    /** Times the user declined the notification permission (2+ = Android won't ask again). */
    val notificationDenials: Int = 0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("folio_settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = intPreferencesKey("theme")
        val nomedia = booleanPreferencesKey("respect_nomedia")
        val hidden = booleanPreferencesKey("show_hidden")
        val view = intPreferencesKey("view_mode")
        val sortField = intPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val recursive = booleanPreferencesKey("recursive_search")
        val rootWrites = booleanPreferencesKey("root_writes")
        val saf = stringSetPreferencesKey("saf_trees")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val exAllFiles = booleanPreferencesKey("explained_all_files")
        val exInstall = booleanPreferencesKey("explained_install")
        val exNotif = booleanPreferencesKey("explained_notifications")
        val notifDenials = intPreferencesKey("notification_denials")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = ThemeMode.entries.getOrElse(p[K.theme] ?: 0) { ThemeMode.SYSTEM },
            respectNomedia = p[K.nomedia] ?: true,
            showHidden = p[K.hidden] ?: false,
            viewMode = ViewMode.entries.getOrElse(p[K.view] ?: 0) { ViewMode.LIST },
            sort = SortOrder(
                field = SortField.entries.getOrElse(p[K.sortField] ?: 0) { SortField.NAME },
                ascending = p[K.sortAsc] ?: true,
                foldersFirst = p[K.foldersFirst] ?: true,
            ),
            recursiveSearch = p[K.recursive] ?: true,
            rootWrites = p[K.rootWrites] ?: false,
            safTrees = p[K.saf] ?: emptySet(),
            onboardingDone = p[K.onboarding] ?: false,
            allFilesExplained = p[K.exAllFiles] ?: false,
            installExplained = p[K.exInstall] ?: false,
            notificationsExplained = p[K.exNotif] ?: false,
            notificationDenials = p[K.notifDenials] ?: 0,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.ordinal }
    suspend fun setRespectNomedia(v: Boolean) = context.dataStore.edit { it[K.nomedia] = v }
    suspend fun setShowHidden(v: Boolean) = context.dataStore.edit { it[K.hidden] = v }
    suspend fun setViewMode(v: ViewMode) = context.dataStore.edit { it[K.view] = v.ordinal }
    suspend fun setSort(v: SortOrder) = context.dataStore.edit {
        it[K.sortField] = v.field.ordinal; it[K.sortAsc] = v.ascending; it[K.foldersFirst] = v.foldersFirst
    }
    suspend fun setRecursiveSearch(v: Boolean) = context.dataStore.edit { it[K.recursive] = v }
    suspend fun setRootWrites(v: Boolean) = context.dataStore.edit { it[K.rootWrites] = v }
    suspend fun setExplained(allFiles: Boolean? = null, install: Boolean? = null, notifications: Boolean? = null) = context.dataStore.edit {
        allFiles?.let { v -> it[K.exAllFiles] = v }
        install?.let { v -> it[K.exInstall] = v }
        notifications?.let { v -> it[K.exNotif] = v }
    }
    suspend fun addNotificationDenial() = context.dataStore.edit { it[K.notifDenials] = (it[K.notifDenials] ?: 0) + 1 }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun addSafTree(uuid: String?, uri: String) = context.dataStore.edit {
        val cur = (it[K.saf] ?: emptySet()).filterNot { e -> e.substringBefore('|') == (uuid ?: "") && uuid != null }
        it[K.saf] = (cur + "${uuid ?: ""}|$uri").toSet()
    }
    suspend fun removeSafTree(uri: String) = context.dataStore.edit {
        it[K.saf] = (it[K.saf] ?: emptySet()).filterNot { e -> e.substringAfter('|') == uri }.toSet()
    }
}
