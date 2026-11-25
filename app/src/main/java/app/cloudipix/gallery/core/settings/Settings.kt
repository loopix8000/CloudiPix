package app.cloudipix.gallery.core.settings

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.FolderTab
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.model.Sorting
import app.cloudipix.gallery.core.cache.CACHE_LIMIT_RANGE
import app.cloudipix.gallery.core.security.LockSettings
import app.cloudipix.gallery.core.sync.FolderSyncSettingsExport
import app.cloudipix.gallery.core.sync.MIN_SYNC_INTERVAL
import androidx.compose.ui.graphics.toArgb
import app.cloudipix.gallery.ui.theme.Accent
import app.cloudipix.gallery.ui.theme.Contrast
import app.cloudipix.gallery.ui.theme.CornerStyle
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.ThumbnailSpacing
import app.cloudipix.gallery.ui.theme.GalleryFont
import app.cloudipix.gallery.ui.theme.TextScale
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.ScreenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GallerySettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val settingsTheme: ScreenTheme = ScreenTheme.System,
    val accentArgb: Int = Accent.Indigo.color.toArgb(),
    val font: GalleryFont = GalleryFont.System,
    val textScale: TextScale = TextScale.Default,
    val folderColumns: Int = 3,
    val mediaColumns: Int = 3,
    val sorting: Sorting = Sorting(),
    val manualFolderSort: Boolean = false,
    val folderOrder: List<Long> = emptyList(),
    val folderTabs: List<FolderTab> = emptyList(),
    val homeTabName: String? = null,
    val homeTabPosition: Int = 0,
    val grouping: Grouping = Grouping.Day,
    val mediaFilter: MediaFilter = MediaFilter.All,
    val scrollDirection: ScrollDirection = ScrollDirection.Down,
    val showFolderNames: Boolean = true,
    val showFolderCount: Boolean = true,
    val showFolderSize: Boolean = false,
    val showCloudFolderIcons: Boolean = true,
    val showCloudFolderIconsOnlyWhileSyncing: Boolean = false,
    val showRecent: Boolean = false,
    val detailsPlacement: DetailsPlacement = DetailsPlacement.Overlay,
    val showVideoDuration: Boolean = true,
    val autoplayNextVideo: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val confirmTrash: Boolean = true,
    val volumeButtonPaging: Boolean = false,
    val panoramaForAll: Boolean = false,
    val selectionBar: BarLayout = BarLayout.Selection,
    val viewerBar: BarLayout = BarLayout.Viewer,
    val photoEditorPackage: String? = null,
    val language: Language = Language.System,
    val folderMediaSettings: Map<Long, FolderMediaSettings> = emptyMap(),
) {
    val contrast get() = Contrast.Normal
    val corners get() = CornerStyle.Default
    val overlayUi get() = false
    val cropThumbnails get() = true
    val thumbnailSpacing get() = ThumbnailSpacing.Small
    val viewerScrim get() = 0f
}

@Serializable
data class FolderMediaSettings(
    val sorting: Sorting? = null,
    val grouping: Grouping? = null,
    val sortingFolderOnly: Boolean = true,
    val groupingFolderOnly: Boolean = true,
)

internal fun GallerySettings.sortingFor(bucketId: Long): Sorting =
    folderMediaSettings[bucketId]?.takeIf { it.sortingFolderOnly }?.sorting ?: sorting

internal fun GallerySettings.groupingFor(bucketId: Long): Grouping =
    if (sortingFor(bucketId).key == SortKey.FavoriteAdded) Grouping.None
    else folderMediaSettings[bucketId]?.takeIf { it.groupingFolderOnly }?.grouping ?: grouping

private const val BOOTSTRAP_PREFS = "bootstrap"
private const val BOOTSTRAP_LANGUAGE = "language"
private const val DEFAULT_REMOTE_CACHE_MIB = 640L
private const val DEFAULT_THUMBNAIL_CACHE_MIB = 256L

@Serializable
internal data class SettingsExport(
    @Required val schemaVersion: Int = 1,
    val settings: GallerySettings,
    val remoteCacheLimitMiB: Long,
    val thumbnailCacheLimitMiB: Long? = null,
    val appLock: Boolean,
    val hideRecentsPreview: Boolean,
    val sync: FolderSyncSettingsExport? = null,
)

private val settingsExportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

private object Keys {
    val themeMode = stringPreferencesKey("theme_mode")
    val settingsTheme = stringPreferencesKey("settings_theme")
    val accentArgb = intPreferencesKey("accent_argb")
    val font = stringPreferencesKey("font")
    val textScale = stringPreferencesKey("text_scale")
    val folderColumns = intPreferencesKey("folder_columns")
    val mediaColumns = intPreferencesKey("media_columns")
    val sortKey = stringPreferencesKey("sort_key")
    val sortDesc = booleanPreferencesKey("sort_desc")
    val manualFolderSort = booleanPreferencesKey("manual_folder_sort")
    val folderOrder = stringPreferencesKey("folder_order")
    val folderTabs = stringPreferencesKey("folder_tabs")
    val homeTabName = stringPreferencesKey("home_tab_name")
    val homeTabPosition = intPreferencesKey("home_tab_position")
    val grouping = stringPreferencesKey("grouping")
    val folderMediaSettings = stringPreferencesKey("folder_media_settings")
    val mediaFilter = stringPreferencesKey("media_filter")
    val scrollDirection = stringPreferencesKey("scroll_direction")
    val showFolderNames = booleanPreferencesKey("show_folder_names")
    val legacyShowFolderDetails = booleanPreferencesKey("show_folder_details")
    val showFolderCount = booleanPreferencesKey("show_folder_count")
    val showFolderSize = booleanPreferencesKey("show_folder_size")
    val showCloudFolderIcons = booleanPreferencesKey("show_cloud_folder_icons")
    val showCloudFolderIconsOnlyWhileSyncing = booleanPreferencesKey("show_cloud_folder_icons_only_while_syncing")
    val showRecent = booleanPreferencesKey("show_recent")
    val detailsPlacement = stringPreferencesKey("details_placement")
    val showVideoDuration = booleanPreferencesKey("show_video_duration")
    val autoplayNextVideo = booleanPreferencesKey("autoplay_next_video")
    val backgroundPlayback = booleanPreferencesKey("background_playback")
    val confirmTrash = booleanPreferencesKey("confirm_trash")
    val copyPathAction = booleanPreferencesKey("copy_path_action")
    val volumeButtonPaging = booleanPreferencesKey("volume_button_paging")
    val panoramaForAll = booleanPreferencesKey("panorama_for_all")
    val selectionBar = stringPreferencesKey("selection_bar")
    val viewerBar = stringPreferencesKey("viewer_bar")
    val photoEditorPackage = stringPreferencesKey("photo_editor_package")
    val language = stringPreferencesKey("language")
    val remoteCacheLimitMiB = longPreferencesKey("remote_cache_limit_mib")
    val thumbnailCacheLimitMiB = longPreferencesKey("thumbnail_cache_limit_mib")
}

private inline fun <reified T : Enum<T>> Preferences.enum(key: Preferences.Key<String>, fallback: T): T =
    this[key]?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    internal val remoteCacheLimitMiB: Flow<Long> = context.dataStore.data.map {
        it[Keys.remoteCacheLimitMiB] ?: DEFAULT_REMOTE_CACHE_MIB
    }

    internal suspend fun setRemoteCacheLimitMiB(value: Long) = put(Keys.remoteCacheLimitMiB, value)

    internal val thumbnailCacheLimitMiB: Flow<Long> = context.dataStore.data.map {
        it[Keys.thumbnailCacheLimitMiB] ?: DEFAULT_THUMBNAIL_CACHE_MIB
    }

    internal suspend fun setThumbnailCacheLimitMiB(value: Long) = put(Keys.thumbnailCacheLimitMiB, value)

    val settings: Flow<GallerySettings> = context.dataStore.data.map(::readSettings)

    internal suspend fun exportSettings(
        destination: Uri,
        locks: LockSettings,
        sync: FolderSyncSettingsExport,
    ) = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        val export = SettingsExport(
            settings = readSettings(preferences),
            remoteCacheLimitMiB = preferences[Keys.remoteCacheLimitMiB] ?: DEFAULT_REMOTE_CACHE_MIB,
            thumbnailCacheLimitMiB = preferences[Keys.thumbnailCacheLimitMiB] ?: DEFAULT_THUMBNAIL_CACHE_MIB,
            appLock = locks.app,
            hideRecentsPreview = locks.hideRecentsPreview,
            sync = sync,
        )
        val json = settingsExportJson.encodeToString(export)
        val output = context.contentResolver.openOutputStream(destination)
            ?: throw IOException("설정 파일을 열 수 없습니다.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal suspend fun readSettingsFile(source: Uri): SettingsExport = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("설정 파일을 열 수 없습니다.")
        val backup = input.use { settingsExportJson.decodeFromStream<SettingsExport>(it) }
        val settings = backup.settings
        require(backup.schemaVersion == 1 && backup.remoteCacheLimitMiB in CACHE_LIMIT_RANGE)
        require(backup.thumbnailCacheLimitMiB?.let { it in CACHE_LIMIT_RANGE } ?: true)
        require(settings.folderColumns in 2..6 && settings.mediaColumns in 2..6)
        require(settings.sorting.key != SortKey.FavoriteAdded)
        require(settings.folderMediaSettings.none { (id, prefs) ->
            id != FAVORITES_BUCKET_ID && prefs.sorting?.key == SortKey.FavoriteAdded
        })
        require(settings.folderTabs.map(FolderTab::id).distinct().size == settings.folderTabs.size)
        require(settings.folderTabs.none { it.id.isBlank() || it.id == FolderTab.Home.id })
        backup.sync?.let { sync ->
            require(sync.intervalMinutes >= MIN_SYNC_INTERVAL)
            require(sync.folders.distinctBy { it.serverId to it.remotePath }.size == sync.folders.size)
            sync.cloudFolders?.let { cloud ->
                require(cloud.distinctBy { it.serverId }.size == cloud.size)
                require(cloud.all { it.folders.distinctBy { entry -> entry.path }.size == it.folders.size })
            }
        }
        backup
    }

    internal suspend fun restoreSettings(value: GallerySettings): Boolean {
        val languageChanged = context.storedLanguage() != value.language
        context.dataStore.edit { p ->
            p[Keys.themeMode] = value.themeMode.name
            p[Keys.settingsTheme] = value.settingsTheme.name
            p[Keys.accentArgb] = value.accentArgb
            p[Keys.font] = value.font.name
            p[Keys.textScale] = value.textScale.name
            p[Keys.folderColumns] = value.folderColumns
            p[Keys.mediaColumns] = value.mediaColumns
            p.writeSorting(value.sorting)
            p[Keys.manualFolderSort] = value.manualFolderSort
            p[Keys.folderOrder] = value.folderOrder.distinct().joinToString(",")
            p[Keys.folderTabs] = Json.encodeToString(value.folderTabs)
            if (value.homeTabName == null) p.remove(Keys.homeTabName) else p[Keys.homeTabName] = value.homeTabName
            p[Keys.homeTabPosition] = value.homeTabPosition.coerceIn(0, value.folderTabs.size)
            p[Keys.grouping] = value.grouping.name
            p.setFolderMediaSettings(value.folderMediaSettings)
            p[Keys.mediaFilter] = value.mediaFilter.name
            p[Keys.scrollDirection] = value.scrollDirection.name
            p[Keys.showFolderNames] = value.showFolderNames
            p[Keys.showFolderCount] = value.showFolderCount
            p[Keys.showFolderSize] = value.showFolderSize
            p[Keys.showCloudFolderIcons] = value.showCloudFolderIcons
            p[Keys.showCloudFolderIconsOnlyWhileSyncing] = value.showCloudFolderIconsOnlyWhileSyncing
            p[Keys.showRecent] = value.showRecent
            p[Keys.detailsPlacement] = value.detailsPlacement.name
            p[Keys.showVideoDuration] = value.showVideoDuration
            p[Keys.autoplayNextVideo] = value.autoplayNextVideo
            p[Keys.backgroundPlayback] = value.backgroundPlayback
            p[Keys.confirmTrash] = value.confirmTrash
            p[Keys.volumeButtonPaging] = value.volumeButtonPaging
            p[Keys.panoramaForAll] = value.panoramaForAll
            p[Keys.selectionBar] = value.selectionBar.encode()
            p[Keys.viewerBar] = value.viewerBar.encode()
            val photoEditorPackage = value.photoEditorPackage
            if (photoEditorPackage == null) p.remove(Keys.photoEditorPackage) else p[Keys.photoEditorPackage] = photoEditorPackage
            p[Keys.language] = value.language.name
        }
        writeBootstrapLanguage(value.language)
        return languageChanged
    }

    private fun readSettings(p: Preferences): GallerySettings {
        val defaults = GallerySettings()
        val legacyFolderDetails = p[Keys.legacyShowFolderDetails]
        return GallerySettings(
            themeMode = p.enum(Keys.themeMode, defaults.themeMode),
            settingsTheme = p.enum(Keys.settingsTheme, defaults.settingsTheme),
            accentArgb = p[Keys.accentArgb] ?: defaults.accentArgb,
            font = p.enum(Keys.font, defaults.font),
            textScale = p.enum(Keys.textScale, defaults.textScale),
            folderColumns = p[Keys.folderColumns] ?: defaults.folderColumns,
            mediaColumns = p[Keys.mediaColumns] ?: defaults.mediaColumns,
            sorting = p.sorting(),
            manualFolderSort = p[Keys.manualFolderSort] ?: false,
            folderOrder = p.folderOrder(),
            folderTabs = p.folderTabs(),
            homeTabName = p[Keys.homeTabName],
            homeTabPosition = p[Keys.homeTabPosition] ?: defaults.homeTabPosition,
            grouping = p.grouping(),
            mediaFilter = p.enum(Keys.mediaFilter, defaults.mediaFilter),
            scrollDirection = p.enum(Keys.scrollDirection, defaults.scrollDirection),
            showFolderNames = p[Keys.showFolderNames] ?: defaults.showFolderNames,
            showFolderCount = p[Keys.showFolderCount] ?: legacyFolderDetails ?: defaults.showFolderCount,
            showFolderSize = p[Keys.showFolderSize] ?: legacyFolderDetails ?: defaults.showFolderSize,
            showCloudFolderIcons = p[Keys.showCloudFolderIcons] ?: defaults.showCloudFolderIcons,
            showCloudFolderIconsOnlyWhileSyncing =
                p[Keys.showCloudFolderIconsOnlyWhileSyncing] ?: defaults.showCloudFolderIconsOnlyWhileSyncing,
            showRecent = p[Keys.showRecent] ?: defaults.showRecent,
            detailsPlacement = p.enum(Keys.detailsPlacement, defaults.detailsPlacement),
            showVideoDuration = p[Keys.showVideoDuration] ?: defaults.showVideoDuration,
            autoplayNextVideo = p[Keys.autoplayNextVideo] ?: defaults.autoplayNextVideo,
            backgroundPlayback = p[Keys.backgroundPlayback] ?: defaults.backgroundPlayback,
            confirmTrash = p[Keys.confirmTrash] ?: defaults.confirmTrash,
            volumeButtonPaging = p[Keys.volumeButtonPaging] ?: defaults.volumeButtonPaging,
            panoramaForAll = p[Keys.panoramaForAll] ?: defaults.panoramaForAll,
            selectionBar = BarLayout.decode(
                p[Keys.selectionBar],
                defaults.selectionBar.shown(BarAction.CopyPath, p[Keys.copyPathAction] == true),
            ),
            viewerBar = BarLayout.decode(p[Keys.viewerBar], defaults.viewerBar, newBarActions = setOf(BarAction.Favorite)),
            photoEditorPackage = p[Keys.photoEditorPackage],
            language = p.enum(Keys.language, defaults.language),
            folderMediaSettings = p.folderMediaSettings(),
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.themeMode, value.name)
    suspend fun setSettingsTheme(value: ScreenTheme) = put(Keys.settingsTheme, value.name)
    suspend fun setAccent(argb: Int) = put(Keys.accentArgb, argb)
    suspend fun setFont(value: GalleryFont) = put(Keys.font, value.name)
    suspend fun setTextScale(value: TextScale) = put(Keys.textScale, value.name)
    suspend fun setFolderColumns(value: Int) = put(Keys.folderColumns, value)
    suspend fun setMediaColumns(value: Int) = put(Keys.mediaColumns, value)
    suspend fun setGrouping(value: Grouping) = put(Keys.grouping, value.name)
    suspend fun setMediaFilter(value: MediaFilter) = put(Keys.mediaFilter, value.name)
    suspend fun setScrollDirection(value: ScrollDirection) = put(Keys.scrollDirection, value.name)
    suspend fun setShowFolderNames(value: Boolean) = put(Keys.showFolderNames, value)
    suspend fun setShowFolderCount(value: Boolean) = put(Keys.showFolderCount, value)
    suspend fun setShowFolderSize(value: Boolean) = put(Keys.showFolderSize, value)
    suspend fun setShowCloudFolderIcons(value: Boolean) = put(Keys.showCloudFolderIcons, value)
    suspend fun setShowCloudFolderIconsOnlyWhileSyncing(value: Boolean) =
        put(Keys.showCloudFolderIconsOnlyWhileSyncing, value)

    suspend fun setShowRecent(value: Boolean) = put(Keys.showRecent, value)
    suspend fun setDetailsPlacement(value: DetailsPlacement) = put(Keys.detailsPlacement, value.name)
    suspend fun setShowVideoDuration(value: Boolean) = put(Keys.showVideoDuration, value)
    suspend fun setAutoplayNextVideo(value: Boolean) = put(Keys.autoplayNextVideo, value)
    suspend fun setBackgroundPlayback(value: Boolean) = put(Keys.backgroundPlayback, value)
    suspend fun setConfirmTrash(value: Boolean) = put(Keys.confirmTrash, value)
    suspend fun setVolumeButtonPaging(value: Boolean) = put(Keys.volumeButtonPaging, value)
    suspend fun setPanoramaForAll(value: Boolean) = put(Keys.panoramaForAll, value)
    suspend fun setSelectionBar(value: BarLayout) = put(Keys.selectionBar, value.encode())
    suspend fun setViewerBar(value: BarLayout) = put(Keys.viewerBar, value.encode())
    suspend fun setPhotoEditorPackage(value: String?) {
        context.dataStore.edit { preferences ->
            if (value == null) preferences.remove(Keys.photoEditorPackage) else preferences[Keys.photoEditorPackage] = value
        }
    }
    suspend fun setLanguage(value: Language) {
        writeBootstrapLanguage(value)
        put(Keys.language, value.name)
    }

    private fun writeBootstrapLanguage(value: Language) {
        context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BOOTSTRAP_LANGUAGE, value.name)
            .apply()
    }

    suspend fun setSorting(value: Sorting) {
        context.dataStore.edit { it.writeSorting(value) }
    }

    suspend fun setDefaultSortKey(key: SortKey) = put(Keys.sortKey, key.name)

    suspend fun setFolderSortKey(key: SortKey) {
        context.dataStore.edit {
            it[Keys.sortKey] = key.name
            it[Keys.manualFolderSort] = false
        }
    }

    suspend fun setSortDescending(descending: Boolean) = put(Keys.sortDesc, descending)

    internal suspend fun setMediaSortKey(bucketId: Long, key: SortKey) =
        if (key == SortKey.FavoriteAdded) {
            require(bucketId == FAVORITES_BUCKET_ID)
            editFolderMediaSettings(bucketId) { folder ->
                folder.copy(sorting = (folder.sorting ?: sorting()).copy(key = key), sortingFolderOnly = true)
            }
        } else {
            updateMediaSorting(bucketId) { it.copy(key = key) }
        }

    internal suspend fun setMediaSortDescending(bucketId: Long, descending: Boolean) =
        updateMediaSorting(bucketId) { it.copy(descending = descending) }

    internal suspend fun setMediaSortingFolderOnly(bucketId: Long, enabled: Boolean) =
        editFolderMediaSettings(bucketId) { folder ->
            folder.copy(
                sorting = if (enabled) folder.sorting ?: sorting() else null,
                sortingFolderOnly = enabled,
            )
        }

    internal suspend fun setMediaGrouping(bucketId: Long, grouping: Grouping) = editFolderMediaSettings(bucketId) { folder ->
        if (folder.groupingFolderOnly) {
            folder.copy(grouping = grouping)
        } else {
            this[Keys.grouping] = grouping.name
            folder
        }
    }

    internal suspend fun setMediaGroupingFolderOnly(bucketId: Long, enabled: Boolean) =
        editFolderMediaSettings(bucketId) { folder ->
            folder.copy(
                grouping = if (enabled) folder.grouping ?: grouping() else null,
                groupingFolderOnly = enabled,
            )
        }

    private suspend fun updateMediaSorting(bucketId: Long, update: (Sorting) -> Sorting) =
        editFolderMediaSettings(bucketId) { folder ->
            if (folder.sortingFolderOnly) {
                folder.copy(sorting = update(folder.sorting ?: sorting()))
            } else {
                writeSorting(update(sorting()))
                folder
            }
        }

    private suspend fun editFolderMediaSettings(
        bucketId: Long,
        update: MutablePreferences.(FolderMediaSettings) -> FolderMediaSettings,
    ) {
        context.dataStore.edit { prefs ->
            val folders = prefs.folderMediaSettings()
            val defaults = FolderMediaSettings()
            val current = folders[bucketId] ?: defaults
            val updated = prefs.update(current)
            if (updated != current) {
                prefs.setFolderMediaSettings(if (updated == defaults) folders - bucketId else folders + (bucketId to updated))
            }
        }
    }

    suspend fun useManualFolderSort() = put(Keys.manualFolderSort, true)

    suspend fun saveFolderOrder(order: List<Long>, localByCloud: Map<Long, Long> = emptyMap()) {
        context.dataStore.edit { it.setFolderOrder(order, localByCloud) }
    }

    suspend fun renameHomeTab(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) prefs.remove(Keys.homeTabName) else prefs[Keys.homeTabName] = name
        }
    }

    suspend fun moveFoldersToTab(
        ids: Set<Long>, from: String, to: String, order: List<Long>? = null,
        localByCloud: Map<Long, Long> = emptyMap(),
    ): Boolean {
        if (ids.isEmpty() || from == to) return false
        val home = FolderTab.Home.id
        val matchingIds = ids + ids.mapNotNull(localByCloud::get)
        var moved = false
        context.dataStore.edit { prefs ->
            val tabs = prefs.folderTabs()
            if (to != home && tabs.none { it.id == to }) return@edit
            val movedIds = if (from == home) {
                if (tabs.any { tab -> matchingIds.any { it in tab.folderIds } }) return@edit
                ids
            } else {
                val source = tabs.find { it.id == from }?.folderIds ?: return@edit
                if (ids.any { it !in source && localByCloud[it]?.let(source::contains) != true }) return@edit
                source.intersect(matchingIds)
            }
            val updated = tabs.map { tab ->
                when {
                    tab.id == to -> tab.copy(folderIds = tab.folderIds + movedIds)
                    tab.id == from || to == home -> tab.copy(folderIds = tab.folderIds - matchingIds)
                    else -> tab
                }
            }
            prefs[Keys.folderTabs] = Json.encodeToString(updated)
            if (order != null) prefs.setFolderOrder(order, localByCloud)
            moved = true
        }
        return moved
    }

    suspend fun saveFolderTab(tab: FolderTab) {
        context.dataStore.edit { prefs ->
            val tabs = prefs.folderTabs().toMutableList()
            val index = tabs.indexOfFirst { it.id == tab.id }
            if (index < 0) tabs.add(tab) else tabs[index] = tab
            prefs[Keys.folderTabs] = Json.encodeToString(tabs)
        }
    }

    suspend fun moveFolderTab(id: String, offset: Int) {
        context.dataStore.edit { prefs ->
            val tabs = prefs.folderTabs().toMutableList()
            val homePosition = (prefs[Keys.homeTabPosition] ?: 0).coerceIn(0, tabs.size)
            tabs.add(homePosition, FolderTab.Home)
            val index = tabs.indexOfFirst { it.id == id }
            val target = index + offset
            if (index < 0 || target !in tabs.indices || target == index) return@edit
            tabs.add(target, tabs.removeAt(index))
            val newHomePosition = tabs.indexOfFirst { it.id == FolderTab.Home.id }
            tabs.removeAt(newHomePosition)
            prefs[Keys.homeTabPosition] = newHomePosition
            prefs[Keys.folderTabs] = Json.encodeToString(tabs)
        }
    }

    suspend fun deleteFolderTab(id: String) {
        context.dataStore.edit { prefs ->
            val tabs = prefs.folderTabs().toMutableList()
            val index = tabs.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            val homePosition = (prefs[Keys.homeTabPosition] ?: 0).coerceIn(0, tabs.size)
            if (index < homePosition) prefs[Keys.homeTabPosition] = homePosition - 1
            tabs.removeAt(index)
            prefs[Keys.folderTabs] = Json.encodeToString(tabs)
        }
    }

    internal suspend fun remapFolders(ids: Map<Long, Long>) {
        context.dataStore.edit { prefs ->
            val current = prefs.folderOrder()
            val updated = current.map { ids[it] ?: it }.distinct()
            if (updated != current) prefs[Keys.folderOrder] = updated.joinToString(",")
            val tabs = prefs.folderTabs()
            val remapped = tabs.map { tab ->
                tab.copy(folderIds = tab.folderIds.mapTo(mutableSetOf()) { ids[it] ?: it })
            }
            if (remapped != tabs) prefs[Keys.folderTabs] = Json.encodeToString(remapped)

            val relocated = ids.filter { (oldId, newId) -> oldId != newId }
            if (relocated.isNotEmpty()) {
                val folders = prefs.folderMediaSettings()
                val moved = relocated.mapNotNull { (oldId, newId) -> folders[oldId]?.let { newId to it } }.toMap()
                val updatedFolders = folders - relocated.keys - relocated.values + moved
                if (updatedFolders != folders) prefs.setFolderMediaSettings(updatedFolders)
            }
        }
    }

    suspend fun zoomFolderGrid(step: Int) = zoomGrid(Keys.folderColumns, step)

    suspend fun zoomMediaGrid(step: Int) = zoomGrid(Keys.mediaColumns, step)

    private suspend fun zoomGrid(key: Preferences.Key<Int>, step: Int) {
        context.dataStore.edit { it[key] = ((it[key] ?: 3) + step).coerceIn(2, 6) }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}

private fun Preferences.sorting(): Sorting {
    val defaults = Sorting()
    return Sorting(enum(Keys.sortKey, defaults.key), this[Keys.sortDesc] ?: defaults.descending)
}

private fun MutablePreferences.writeSorting(value: Sorting) {
    this[Keys.sortKey] = value.key.name
    this[Keys.sortDesc] = value.descending
}

private fun Preferences.grouping(): Grouping = enum(Keys.grouping, Grouping.Day)

private fun Preferences.folderMediaSettings(): Map<Long, FolderMediaSettings> =
    this[Keys.folderMediaSettings]?.let { Json.decodeFromString<Map<Long, FolderMediaSettings>>(it) }.orEmpty()

private fun MutablePreferences.setFolderMediaSettings(folders: Map<Long, FolderMediaSettings>) {
    if (folders.isEmpty()) remove(Keys.folderMediaSettings)
    else this[Keys.folderMediaSettings] = Json.encodeToString(folders)
}

private fun Preferences.folderOrder(): List<Long> =
    this[Keys.folderOrder]?.split(',')?.mapNotNull(String::toLongOrNull)?.distinct().orEmpty()

private fun MutablePreferences.setFolderOrder(order: List<Long>, localByCloud: Map<Long, Long>) {
    val current = folderOrder()
    val saved = current.toHashSet()
    val updated = order.map { id -> localByCloud[id]?.takeIf(saved::contains) ?: id }
    val visible = updated.toHashSet()
    val updates = updated.iterator()
    // 필터 때문에 감춰진 폴더의 상대 위치는 그대로 둔다.
    val merged = current.map { if (it in visible) updates.next() else it } + updates.asSequence().toList()
    this[Keys.folderOrder] = merged.joinToString(",")
    this[Keys.manualFolderSort] = true
}

private fun Preferences.folderTabs(): List<FolderTab> =
    this[Keys.folderTabs]?.let { Json.decodeFromString<List<FolderTab>>(it) }.orEmpty()

fun Context.storedLanguage(): Language =
    getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
        .getString(BOOTSTRAP_LANGUAGE, null)
        ?.let { stored -> Language.entries.firstOrNull { it.name == stored } }
        ?: Language.System

fun Context.withLanguage(language: Language): Context {
    if (language == Language.System) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
    }
    return createConfigurationContext(configuration)
}
