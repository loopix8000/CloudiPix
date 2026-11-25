package app.cloudipix.gallery.core.settings

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cloudipix.gallery.core.cache.CACHE_LIMIT_RANGE
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.FolderTab
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.model.Sorting
import app.cloudipix.gallery.core.security.LockSettings
import app.cloudipix.gallery.core.sync.FolderSyncSettingsExport
import app.cloudipix.gallery.core.sync.MIN_SYNC_INTERVAL
import app.cloudipix.gallery.ui.theme.Accent
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryFont
import app.cloudipix.gallery.ui.theme.ScreenTheme
import app.cloudipix.gallery.ui.theme.TextScale
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.ThumbnailSpacing
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/*
 * 앱 설정입니다. 값은 DataStore("settings")에 키별로 저장하고, 백업 파일에는 GallerySettings를 JSON으로 씁니다.
 *
 * 호환성 주의
 * - DataStore 키 문자열([Keys])과 enum 이름은 저장된 값이므로 바꾸지 않습니다.
 * - GallerySettings·FolderMediaSettings·SettingsExport의 속성 이름은 백업 JSON 필드 이름입니다.
 *   이름을 바꾸면 이전에 만든 백업을 읽지 못합니다. 새 속성에는 기본값을 두어 예전 백업도 읽히게 합니다.
 */

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
    // 예전에 설정으로 고를 수 있던 값입니다. 지금은 한 가지 값으로 고정했고 화면 코드가 아직 이 이름으로 읽습니다.
    val cropThumbnails get() = true
    val thumbnailSpacing get() = ThumbnailSpacing.Small
    val viewerScrim get() = 0f
}

/**
 * 폴더별 미디어 정렬·묶기입니다. `...FolderOnly`가 참이면 이 폴더에만 따로 적용하고,
 * 거짓이면 전체 설정을 따릅니다. 모든 값이 기본값이면 저장하지 않습니다.
 */
@Serializable
data class FolderMediaSettings(
    val sorting: Sorting? = null,
    val grouping: Grouping? = null,
    val sortingFolderOnly: Boolean = true,
    val groupingFolderOnly: Boolean = true,
)

/** 폴더에 실제로 적용할 정렬입니다. */
internal fun GallerySettings.sortingFor(bucketId: Long): Sorting {
    val own = folderMediaSettings[bucketId]?.takeIf { it.sortingFolderOnly }?.sorting
    return own ?: sorting
}

/** 폴더에 실제로 적용할 묶기입니다. 즐겨찾기 추가순으로 볼 때는 날짜로 묶지 않습니다. */
internal fun GallerySettings.groupingFor(bucketId: Long): Grouping {
    if (sortingFor(bucketId).key == SortKey.FavoriteAdded) return Grouping.None
    val own = folderMediaSettings[bucketId]?.takeIf { it.groupingFolderOnly }?.grouping
    return own ?: grouping
}

/** 액티비티를 만들기 전에 언어를 정해야 해서 DataStore와 별도로 SharedPreferences에도 언어를 둡니다. */
private const val BOOTSTRAP_PREFS = "bootstrap"
private const val BOOTSTRAP_LANGUAGE = "language"

private const val DEFAULT_REMOTE_CACHE_MIB = 640L
private const val DEFAULT_THUMBNAIL_CACHE_MIB = 256L
private const val DEFAULT_GRID_COLUMNS = 3
private val GRID_COLUMN_RANGE = 2..6

/** 설정 백업 파일 형식입니다. */
@Serializable
internal data class SettingsExport(
    @Required val schemaVersion: Int = 1,
    val settings: GallerySettings,
    val remoteCacheLimitMiB: Long,
    val thumbnailCacheLimitMiB: Long? = null,
    val appLock: Boolean,
    val hideRecentsPreview: Boolean,
    val sync: FolderSyncSettingsExport? = null,
    val favorites: List<FavoriteBackup> = emptyList(),
    val tabFolderLocations: Map<Long, TabFolderLocation> = emptyMap(),
)

/** 백업한 즐겨찾기 하나입니다. 원격 항목이면 서버와 경로를 함께 둡니다. */
@Serializable
internal data class FavoriteBackup(
    val key: String,
    val remote: RemoteFavoriteLocation? = null,
)

@Serializable
internal data class RemoteFavoriteLocation(
    val serverId: String,
    val path: String,
)

private val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** DataStore 키입니다. 문자열은 저장된 이름이라 바꾸지 않습니다. */
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
    val volumeButtonPaging = booleanPreferencesKey("volume_button_paging")
    val panoramaForAll = booleanPreferencesKey("panorama_for_all")
    val selectionBar = stringPreferencesKey("selection_bar")
    val viewerBar = stringPreferencesKey("viewer_bar")
    val photoEditorPackage = stringPreferencesKey("photo_editor_package")
    val language = stringPreferencesKey("language")
    val remoteCacheLimitMiB = longPreferencesKey("remote_cache_limit_mib")
    val thumbnailCacheLimitMiB = longPreferencesKey("thumbnail_cache_limit_mib")

    // 예전 버전에서만 쓰던 키입니다. 새 키가 없을 때 이전 선택을 이어받는 데만 읽습니다.
    /** 폴더 개수와 크기를 함께 켜고 끄던 설정입니다. */
    val legacyShowFolderDetails = booleanPreferencesKey("show_folder_details")

    /** 선택 바에 '경로 복사'를 보이던 설정입니다. */
    val legacyCopyPathAction = booleanPreferencesKey("copy_path_action")
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val store get() = context.dataStore

    /** 설정 전체입니다. 설정과 무관한 키(캐시 한도 등)만 바뀐 경우에는 다시 내보내지 않습니다. */
    val settings: Flow<GallerySettings> = store.data.map { it.toGallerySettings() }.distinctUntilChanged()

    internal val remoteCacheLimitMiB: Flow<Long> = store.data.map { it.remoteCacheLimit() }
    internal val thumbnailCacheLimitMiB: Flow<Long> = store.data.map { it.thumbnailCacheLimit() }

    internal suspend fun setRemoteCacheLimitMiB(value: Long) = put(Keys.remoteCacheLimitMiB, value)
    internal suspend fun setThumbnailCacheLimitMiB(value: Long) = put(Keys.thumbnailCacheLimitMiB, value)

    // ---- 백업 ----

    /** 설정과 함께 받은 잠금·동기화·즐겨찾기 정보를 JSON 백업 파일로 씁니다. */
    internal suspend fun exportSettings(
        destination: Uri,
        locks: LockSettings,
        sync: FolderSyncSettingsExport,
        favorites: List<FavoriteBackup>,
        tabFolderLocations: suspend (GallerySettings) -> Map<Long, TabFolderLocation>,
    ) = withContext(Dispatchers.IO) {
        val stored = store.data.first()
        val current = stored.toGallerySettings()
        val backup = SettingsExport(
            settings = current,
            remoteCacheLimitMiB = stored.remoteCacheLimit(),
            thumbnailCacheLimitMiB = stored.thumbnailCacheLimit(),
            appLock = locks.app,
            hideRecentsPreview = locks.hideRecentsPreview,
            sync = sync,
            favorites = favorites,
            tabFolderLocations = tabFolderLocations(current),
        )
        val text = backupJson.encodeToString(backup)
        val output = context.contentResolver.openOutputStream(destination) ?: throw IOException("설정 파일을 열 수 없습니다.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    /** 백업 파일을 읽고 값이 허용 범위인지 확인합니다. 맞지 않으면 IllegalArgumentException을 던집니다. */
    @OptIn(ExperimentalSerializationApi::class)
    internal suspend fun readSettingsFile(source: Uri): SettingsExport = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("설정 파일을 열 수 없습니다.")
        val backup = input.use { backupJson.decodeFromStream<SettingsExport>(it) }
        backup.validate()
        backup
    }

    /** 백업한 설정을 모두 덮어씁니다. 언어가 바뀌었으면 화면을 다시 만들어야 하므로 참을 돌려줍니다. */
    internal suspend fun restoreSettings(value: GallerySettings): Boolean {
        val languageChanged = context.storedLanguage() != value.language
        store.edit { it.writeAll(value) }
        writeBootstrapLanguage(value.language)
        return languageChanged
    }

    // ---- 화면·표시 ----

    suspend fun setThemeMode(value: ThemeMode) = putName(Keys.themeMode, value)
    suspend fun setSettingsTheme(value: ScreenTheme) = putName(Keys.settingsTheme, value)
    suspend fun setAccent(argb: Int) = put(Keys.accentArgb, argb)
    suspend fun setFont(value: GalleryFont) = putName(Keys.font, value)
    suspend fun setTextScale(value: TextScale) = putName(Keys.textScale, value)
    suspend fun setFolderColumns(value: Int) = put(Keys.folderColumns, value)
    suspend fun setMediaColumns(value: Int) = put(Keys.mediaColumns, value)
    suspend fun setGrouping(value: Grouping) = putName(Keys.grouping, value)
    suspend fun setMediaFilter(value: MediaFilter) = putName(Keys.mediaFilter, value)
    suspend fun setScrollDirection(value: ScrollDirection) = putName(Keys.scrollDirection, value)
    suspend fun setShowFolderNames(value: Boolean) = put(Keys.showFolderNames, value)
    suspend fun setShowFolderCount(value: Boolean) = put(Keys.showFolderCount, value)
    suspend fun setShowFolderSize(value: Boolean) = put(Keys.showFolderSize, value)
    suspend fun setShowCloudFolderIcons(value: Boolean) = put(Keys.showCloudFolderIcons, value)
    suspend fun setShowCloudFolderIconsOnlyWhileSyncing(value: Boolean) = put(Keys.showCloudFolderIconsOnlyWhileSyncing, value)
    suspend fun setShowRecent(value: Boolean) = put(Keys.showRecent, value)
    suspend fun setDetailsPlacement(value: DetailsPlacement) = putName(Keys.detailsPlacement, value)
    suspend fun setShowVideoDuration(value: Boolean) = put(Keys.showVideoDuration, value)
    suspend fun setAutoplayNextVideo(value: Boolean) = put(Keys.autoplayNextVideo, value)
    suspend fun setBackgroundPlayback(value: Boolean) = put(Keys.backgroundPlayback, value)
    suspend fun setConfirmTrash(value: Boolean) = put(Keys.confirmTrash, value)
    suspend fun setVolumeButtonPaging(value: Boolean) = put(Keys.volumeButtonPaging, value)
    suspend fun setPanoramaForAll(value: Boolean) = put(Keys.panoramaForAll, value)
    suspend fun setSelectionBar(value: BarLayout) = put(Keys.selectionBar, value.encode())
    suspend fun setViewerBar(value: BarLayout) = put(Keys.viewerBar, value.encode())

    /** 사진 편집에 쓸 앱입니다. `null`이면 기본 선택으로 돌아갑니다. */
    suspend fun setPhotoEditorPackage(value: String?) {
        store.edit { it.putOrRemove(Keys.photoEditorPackage, value) }
    }

    suspend fun setLanguage(value: Language) {
        writeBootstrapLanguage(value)
        putName(Keys.language, value)
    }

    private fun writeBootstrapLanguage(value: Language) {
        context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BOOTSTRAP_LANGUAGE, value.name)
            .apply()
    }

    suspend fun zoomFolderGrid(step: Int) = zoomGrid(Keys.folderColumns, step)
    suspend fun zoomMediaGrid(step: Int) = zoomGrid(Keys.mediaColumns, step)

    private suspend fun zoomGrid(key: Preferences.Key<Int>, step: Int) {
        store.edit { prefs ->
            val columns = (prefs[key] ?: DEFAULT_GRID_COLUMNS) + step
            prefs[key] = columns.coerceIn(GRID_COLUMN_RANGE)
        }
    }

    // ---- 정렬·묶기 ----

    suspend fun setSorting(value: Sorting) {
        store.edit { it.putSorting(value) }
    }

    suspend fun setDefaultSortKey(key: SortKey) = putName(Keys.sortKey, key)

    /** 폴더 목록 정렬 기준을 바꾸면 직접 정한 순서는 그만 씁니다. */
    suspend fun setFolderSortKey(key: SortKey) {
        store.edit { prefs ->
            prefs[Keys.sortKey] = key.name
            prefs[Keys.manualFolderSort] = false
        }
    }

    suspend fun setSortDescending(descending: Boolean) = put(Keys.sortDesc, descending)

    /** '즐겨찾기 추가순'은 즐겨찾기 폴더에만, 그 폴더 전용 정렬로만 쓸 수 있습니다. */
    internal suspend fun setMediaSortKey(bucketId: Long, key: SortKey) {
        if (key != SortKey.FavoriteAdded) return updateMediaSorting(bucketId) { it.copy(key = key) }
        require(bucketId == FAVORITES_BUCKET_ID)
        editFolderMedia(bucketId) { folder ->
            val base = folder.sorting ?: storedSorting()
            folder.copy(sorting = base.copy(key = key), sortingFolderOnly = true)
        }
    }

    internal suspend fun setMediaSortDescending(bucketId: Long, descending: Boolean) =
        updateMediaSorting(bucketId) { it.copy(descending = descending) }

    /** 폴더 전용 정렬을 켜면 지금 전체 정렬을 출발점으로 삼고, 끄면 폴더 정렬을 버립니다. */
    internal suspend fun setMediaSortingFolderOnly(bucketId: Long, enabled: Boolean) = editFolderMedia(bucketId) { folder ->
        val sorting = if (enabled) folder.sorting ?: storedSorting() else null
        folder.copy(sorting = sorting, sortingFolderOnly = enabled)
    }

    /** 폴더 전용 묶기가 꺼져 있으면 전체 묶기 설정을 바꿉니다. */
    internal suspend fun setMediaGrouping(bucketId: Long, grouping: Grouping) = editFolderMedia(bucketId) { folder ->
        if (folder.groupingFolderOnly) return@editFolderMedia folder.copy(grouping = grouping)
        this[Keys.grouping] = grouping.name
        folder
    }

    internal suspend fun setMediaGroupingFolderOnly(bucketId: Long, enabled: Boolean) = editFolderMedia(bucketId) { folder ->
        val grouping = if (enabled) folder.grouping ?: storedGrouping() else null
        folder.copy(grouping = grouping, groupingFolderOnly = enabled)
    }

    /** 폴더 전용 정렬이 켜져 있으면 폴더 정렬을, 아니면 전체 정렬을 바꿉니다. */
    private suspend fun updateMediaSorting(bucketId: Long, change: (Sorting) -> Sorting) = editFolderMedia(bucketId) { folder ->
        if (folder.sortingFolderOnly) return@editFolderMedia folder.copy(sorting = change(folder.sorting ?: storedSorting()))
        putSorting(change(storedSorting()))
        folder
    }

    /**
     * 폴더 하나의 미디어 설정을 고칩니다. [change]는 같은 트랜잭션 안에서 다른 키도 고칠 수 있습니다.
     * 결과가 기본값과 같으면 그 폴더 항목을 지웁니다.
     */
    private suspend fun editFolderMedia(
        bucketId: Long,
        change: MutablePreferences.(FolderMediaSettings) -> FolderMediaSettings,
    ) {
        store.edit { prefs ->
            val all = prefs.folderMediaMap()
            val before = all[bucketId] ?: FolderMediaSettings()
            val after = prefs.change(before)
            if (after == before) return@edit
            prefs.putFolderMediaMap(if (after == FolderMediaSettings()) all - bucketId else all + (bucketId to after))
        }
    }

    // ---- 폴더 순서·탭 ----

    suspend fun useManualFolderSort() = put(Keys.manualFolderSort, true)

    suspend fun saveFolderOrder(order: List<Long>, localByCloud: Map<Long, Long> = emptyMap()) {
        store.edit { it.mergeFolderOrder(order, localByCloud) }
    }

    suspend fun renameHomeTab(name: String?) {
        store.edit { it.putOrRemove(Keys.homeTabName, name) }
    }

    /**
     * 폴더들을 탭 사이로 옮깁니다. 옮길 수 없으면(대상 탭이 없거나, 폴더가 원래 탭에 없거나,
     * 기본 탭에서 옮기는 폴더가 이미 다른 탭에 있으면) 아무것도 바꾸지 않고 거짓을 돌려줍니다.
     * [localByCloud]는 서버 폴더 ID에서 같은 폴더의 기기 ID로 가는 대응입니다.
     */
    suspend fun moveFoldersToTab(
        ids: Set<Long>, from: String, to: String, order: List<Long>? = null,
        localByCloud: Map<Long, Long> = emptyMap(),
    ): Boolean {
        if (ids.isEmpty() || from == to) return false
        var moved = false
        store.edit { prefs ->
            val updated = tabsAfterMove(prefs.folderTabList(), ids, from, to, localByCloud) ?: return@edit
            prefs.putFolderTabs(updated)
            if (order != null) prefs.mergeFolderOrder(order, localByCloud)
            moved = true
        }
        return moved
    }

    /** 같은 ID의 탭이 있으면 바꾸고, 없으면 끝에 추가합니다. */
    suspend fun saveFolderTab(tab: FolderTab) {
        store.edit { prefs ->
            val tabs = prefs.folderTabList()
            val at = tabs.indexOfFirst { it.id == tab.id }
            prefs.putFolderTabs(if (at < 0) tabs + tab else tabs.toMutableList().also { it[at] = tab })
        }
    }

    /** 탭을 [offset]칸 옮깁니다. 기본 탭도 다른 탭과 같은 줄에 있는 것으로 보고 위치를 함께 계산합니다. */
    suspend fun moveFolderTab(id: String, offset: Int) {
        store.edit { prefs ->
            val tabs = prefs.folderTabList()
            val (reordered, homeAt) = tabsAfterShift(tabs, prefs.homeTabIndex(tabs.size), id, offset) ?: return@edit
            prefs[Keys.homeTabPosition] = homeAt
            prefs.putFolderTabs(reordered)
        }
    }

    /** 탭을 지웁니다. 기본 탭 앞의 탭을 지우면 기본 탭 위치를 한 칸 당깁니다. */
    suspend fun deleteFolderTab(id: String) {
        store.edit { prefs ->
            val tabs = prefs.folderTabList()
            val at = tabs.indexOfFirst { it.id == id }
            if (at < 0) return@edit
            val homeAt = prefs.homeTabIndex(tabs.size)
            if (at < homeAt) prefs[Keys.homeTabPosition] = homeAt - 1
            prefs.putFolderTabs(tabs.filterIndexed { index, _ -> index != at })
        }
    }

    /**
     * 폴더 ID가 바뀌었을 때(예: 기기 폴더가 서버 폴더와 합쳐짐) 순서·탭·폴더별 설정을 새 ID로 옮깁니다.
     * 폴더별 설정은 새 ID에 이미 있던 값보다 옮겨 온 값을 우선합니다.
     */
    internal suspend fun remapFolders(ids: Map<Long, Long>) {
        fun remap(id: Long) = ids[id] ?: id
        store.edit { prefs ->
            val order = prefs.folderOrderList()
            val newOrder = order.map(::remap).distinct()
            if (newOrder != order) prefs[Keys.folderOrder] = newOrder.joinToString(",")

            val tabs = prefs.folderTabList()
            val newTabs = tabs.map { tab -> tab.copy(folderIds = tab.folderIds.mapTo(LinkedHashSet(), ::remap)) }
            if (newTabs != tabs) prefs.putFolderTabs(newTabs)

            val changed = ids.filter { (old, new) -> old != new }
            if (changed.isEmpty()) return@edit
            val folders = prefs.folderMediaMap()
            val carried = buildMap { for ((old, new) in changed) folders[old]?.let { put(new, it) } }
            val newFolders = folders - changed.keys - changed.values.toSet() + carried
            if (newFolders != folders) prefs.putFolderMediaMap(newFolders)
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private suspend fun putName(key: Preferences.Key<String>, value: Enum<*>) = put(key, value.name)
}

// ---- 읽기 ----

private inline fun <reified T : Enum<T>> Preferences.enumOr(key: Preferences.Key<String>, fallback: T): T {
    val name = this[key] ?: return fallback
    return enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

private fun Preferences.remoteCacheLimit(): Long = this[Keys.remoteCacheLimitMiB] ?: DEFAULT_REMOTE_CACHE_MIB
private fun Preferences.thumbnailCacheLimit(): Long = this[Keys.thumbnailCacheLimitMiB] ?: DEFAULT_THUMBNAIL_CACHE_MIB

private fun Preferences.toGallerySettings(): GallerySettings {
    val d = GallerySettings()
    // 예전의 '폴더 정보 표시' 하나로 개수와 크기를 함께 켜고 끄던 값을 이어받습니다.
    val legacyDetails = this[Keys.legacyShowFolderDetails]
    val copyPathShown = this[Keys.legacyCopyPathAction] == true
    return GallerySettings(
        themeMode = enumOr(Keys.themeMode, d.themeMode),
        settingsTheme = enumOr(Keys.settingsTheme, d.settingsTheme),
        accentArgb = this[Keys.accentArgb] ?: d.accentArgb,
        font = enumOr(Keys.font, d.font),
        textScale = enumOr(Keys.textScale, d.textScale),
        folderColumns = this[Keys.folderColumns] ?: d.folderColumns,
        mediaColumns = this[Keys.mediaColumns] ?: d.mediaColumns,
        sorting = storedSorting(),
        manualFolderSort = this[Keys.manualFolderSort] ?: d.manualFolderSort,
        folderOrder = folderOrderList(),
        folderTabs = folderTabList(),
        homeTabName = this[Keys.homeTabName],
        homeTabPosition = this[Keys.homeTabPosition] ?: d.homeTabPosition,
        grouping = storedGrouping(),
        mediaFilter = enumOr(Keys.mediaFilter, d.mediaFilter),
        scrollDirection = enumOr(Keys.scrollDirection, d.scrollDirection),
        showFolderNames = this[Keys.showFolderNames] ?: d.showFolderNames,
        showFolderCount = this[Keys.showFolderCount] ?: legacyDetails ?: d.showFolderCount,
        showFolderSize = this[Keys.showFolderSize] ?: legacyDetails ?: d.showFolderSize,
        showCloudFolderIcons = this[Keys.showCloudFolderIcons] ?: d.showCloudFolderIcons,
        showCloudFolderIconsOnlyWhileSyncing =
            this[Keys.showCloudFolderIconsOnlyWhileSyncing] ?: d.showCloudFolderIconsOnlyWhileSyncing,
        showRecent = this[Keys.showRecent] ?: d.showRecent,
        detailsPlacement = enumOr(Keys.detailsPlacement, d.detailsPlacement),
        showVideoDuration = this[Keys.showVideoDuration] ?: d.showVideoDuration,
        autoplayNextVideo = this[Keys.autoplayNextVideo] ?: d.autoplayNextVideo,
        backgroundPlayback = this[Keys.backgroundPlayback] ?: d.backgroundPlayback,
        confirmTrash = this[Keys.confirmTrash] ?: d.confirmTrash,
        volumeButtonPaging = this[Keys.volumeButtonPaging] ?: d.volumeButtonPaging,
        panoramaForAll = this[Keys.panoramaForAll] ?: d.panoramaForAll,
        selectionBar = BarLayout.decode(this[Keys.selectionBar], d.selectionBar.shown(BarAction.CopyPath, copyPathShown)),
        // 즐겨찾기 버튼은 나중에 생겨서, 예전 배치에는 바 끝에 붙입니다.
        viewerBar = BarLayout.decode(this[Keys.viewerBar], d.viewerBar, newBarActions = setOf(BarAction.Favorite)),
        photoEditorPackage = this[Keys.photoEditorPackage],
        language = enumOr(Keys.language, d.language),
        folderMediaSettings = folderMediaMap(),
    )
}

private fun Preferences.storedSorting(): Sorting {
    val fallback = Sorting()
    return Sorting(key = enumOr(Keys.sortKey, fallback.key), descending = this[Keys.sortDesc] ?: fallback.descending)
}

private fun Preferences.storedGrouping(): Grouping = enumOr(Keys.grouping, Grouping.Day)

/**
 * JSON으로 저장한 값을 읽습니다. 값이 깨져 있으면 기본값을 씁니다.
 * 깨진 값 하나 때문에 설정 전체를 읽지 못하면 앱이 시작되지 않기 때문입니다.
 */
private inline fun <T> Preferences.decodeJsonOr(key: Preferences.Key<String>, fallback: T, decode: (String) -> T): T {
    val raw = this[key] ?: return fallback
    return try {
        decode(raw)
    } catch (_: SerializationException) {
        fallback
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

private fun Preferences.folderMediaMap(): Map<Long, FolderMediaSettings> =
    decodeJsonOr(Keys.folderMediaSettings, emptyMap()) { Json.decodeFromString<Map<Long, FolderMediaSettings>>(it) }

private fun Preferences.folderTabList(): List<FolderTab> =
    decodeJsonOr(Keys.folderTabs, emptyList()) { Json.decodeFromString<List<FolderTab>>(it) }

/** 쉼표로 이은 폴더 ID입니다. 읽을 수 없는 값과 중복은 버립니다. */
private fun Preferences.folderOrderList(): List<Long> {
    val raw = this[Keys.folderOrder] ?: return emptyList()
    return raw.split(',').mapNotNull { it.toLongOrNull() }.distinct()
}

/** 기본 탭이 다른 탭들 사이 몇 번째에 있는지입니다. */
private fun Preferences.homeTabIndex(tabCount: Int): Int = (this[Keys.homeTabPosition] ?: 0).coerceIn(0, tabCount)

// ---- 쓰기 ----

private fun MutablePreferences.writeAll(value: GallerySettings) {
    this[Keys.themeMode] = value.themeMode.name
    this[Keys.settingsTheme] = value.settingsTheme.name
    this[Keys.accentArgb] = value.accentArgb
    this[Keys.font] = value.font.name
    this[Keys.textScale] = value.textScale.name
    this[Keys.folderColumns] = value.folderColumns
    this[Keys.mediaColumns] = value.mediaColumns
    putSorting(value.sorting)
    this[Keys.manualFolderSort] = value.manualFolderSort
    this[Keys.folderOrder] = value.folderOrder.distinct().joinToString(",")
    putFolderTabs(value.folderTabs)
    putOrRemove(Keys.homeTabName, value.homeTabName)
    this[Keys.homeTabPosition] = value.homeTabPosition.coerceIn(0, value.folderTabs.size)
    this[Keys.grouping] = value.grouping.name
    putFolderMediaMap(value.folderMediaSettings)
    this[Keys.mediaFilter] = value.mediaFilter.name
    this[Keys.scrollDirection] = value.scrollDirection.name
    this[Keys.showFolderNames] = value.showFolderNames
    this[Keys.showFolderCount] = value.showFolderCount
    this[Keys.showFolderSize] = value.showFolderSize
    this[Keys.showCloudFolderIcons] = value.showCloudFolderIcons
    this[Keys.showCloudFolderIconsOnlyWhileSyncing] = value.showCloudFolderIconsOnlyWhileSyncing
    this[Keys.showRecent] = value.showRecent
    this[Keys.detailsPlacement] = value.detailsPlacement.name
    this[Keys.showVideoDuration] = value.showVideoDuration
    this[Keys.autoplayNextVideo] = value.autoplayNextVideo
    this[Keys.backgroundPlayback] = value.backgroundPlayback
    this[Keys.confirmTrash] = value.confirmTrash
    this[Keys.volumeButtonPaging] = value.volumeButtonPaging
    this[Keys.panoramaForAll] = value.panoramaForAll
    this[Keys.selectionBar] = value.selectionBar.encode()
    this[Keys.viewerBar] = value.viewerBar.encode()
    putOrRemove(Keys.photoEditorPackage, value.photoEditorPackage)
    this[Keys.language] = value.language.name
}

private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

private fun MutablePreferences.putSorting(value: Sorting) {
    this[Keys.sortKey] = value.key.name
    this[Keys.sortDesc] = value.descending
}

private fun MutablePreferences.putFolderMediaMap(folders: Map<Long, FolderMediaSettings>) {
    if (folders.isEmpty()) remove(Keys.folderMediaSettings) else this[Keys.folderMediaSettings] = Json.encodeToString(folders)
}

private fun MutablePreferences.putFolderTabs(tabs: List<FolderTab>) {
    this[Keys.folderTabs] = Json.encodeToString(tabs)
}

/**
 * 화면에 보인 폴더들의 새 순서를 저장된 순서에 합칩니다. 필터로 감춰져 화면에 없던 폴더는 저장된
 * 자리를 그대로 지키고, 보인 폴더가 차지하던 자리를 새 순서대로 채웁니다. 저장된 순서에 없던 폴더는 끝에 붙입니다.
 * 서버 폴더 ID로 받은 항목은 저장된 순서에 같은 폴더의 기기 ID가 있으면 그 ID로 바꿔 넣습니다.
 */
private fun MutablePreferences.mergeFolderOrder(order: List<Long>, localByCloud: Map<Long, Long>) {
    val stored = folderOrderList()
    val storedSet = stored.toHashSet()
    val shown = order.map { id -> localByCloud[id]?.takeIf { it in storedSet } ?: id }
    val shownSet = shown.toHashSet()
    val next = shown.iterator()
    val merged = ArrayList<Long>(stored.size + shown.size)
    for (id in stored) merged += if (id in shownSet) next.next() else id
    next.forEachRemaining { merged += it }
    this[Keys.folderOrder] = merged.joinToString(",")
    this[Keys.manualFolderSort] = true
}

// ---- 탭 계산 ----

/** [moveFoldersToTab]의 결과 탭 목록입니다. 옮길 수 없으면 `null`입니다. */
private fun tabsAfterMove(
    tabs: List<FolderTab>,
    ids: Set<Long>,
    from: String,
    to: String,
    localByCloud: Map<Long, Long>,
): List<FolderTab>? {
    val home = FolderTab.Home.id
    if (to != home && tabs.none { it.id == to }) return null
    // 서버 폴더와 합쳐진 기기 폴더는 탭에 기기 ID로 들어 있을 수 있어 두 ID를 모두 봅니다.
    val related = ids + ids.mapNotNull { localByCloud[it] }
    val moving: Set<Long> = if (from == home) {
        // 기본 탭의 폴더는 어느 탭에도 들어 있지 않아야 합니다.
        if (tabs.any { tab -> related.any { it in tab.folderIds } }) return null
        ids
    } else {
        val source = tabs.firstOrNull { it.id == from }?.folderIds ?: return null
        val allInSource = ids.all { id -> id in source || localByCloud[id]?.let { it in source } == true }
        if (!allInSource) return null
        source.intersect(related)
    }
    return tabs.map { tab ->
        when {
            tab.id == to -> tab.copy(folderIds = tab.folderIds + moving)
            tab.id == from || to == home -> tab.copy(folderIds = tab.folderIds - related)
            else -> tab
        }
    }
}

/**
 * 기본 탭을 [homeAt]에 끼운 전체 탭 줄에서 [id] 탭을 [offset]칸 옮깁니다.
 * 기본 탭을 뺀 탭 목록과 기본 탭의 새 위치를 돌려주고, 옮길 수 없으면 `null`입니다.
 */
private fun tabsAfterShift(tabs: List<FolderTab>, homeAt: Int, id: String, offset: Int): Pair<List<FolderTab>, Int>? {
    val row = tabs.toMutableList().apply { add(homeAt, FolderTab.Home) }
    val from = row.indexOfFirst { it.id == id }
    val to = from + offset
    if (from < 0 || to == from || to !in row.indices) return null
    row.add(to, row.removeAt(from))
    val newHomeAt = row.indexOfFirst { it.id == FolderTab.Home.id }
    row.removeAt(newHomeAt)
    return row to newHomeAt
}

// ---- 백업 검증 ----

private fun SettingsExport.validate() {
    require(schemaVersion == 1)
    require(remoteCacheLimitMiB in CACHE_LIMIT_RANGE)
    require(thumbnailCacheLimitMiB == null || thumbnailCacheLimitMiB in CACHE_LIMIT_RANGE)

    with(settings) {
        require(folderColumns in GRID_COLUMN_RANGE && mediaColumns in GRID_COLUMN_RANGE)
        // '즐겨찾기 추가순'은 즐겨찾기 폴더에서만 쓸 수 있습니다.
        require(sorting.key != SortKey.FavoriteAdded)
        require(folderMediaSettings.all { (id, folder) -> id == FAVORITES_BUCKET_ID || folder.sorting?.key != SortKey.FavoriteAdded })
        val tabIds = folderTabs.map { it.id }
        require(tabIds.toSet().size == tabIds.size)
        require(tabIds.none { it.isBlank() || it == FolderTab.Home.id })
    }

    sync?.let { sync ->
        require(sync.intervalMinutes >= MIN_SYNC_INTERVAL)
        require(sync.folders.distinctBy { it.serverId to it.remotePath }.size == sync.folders.size)
        sync.cloudFolders?.let { servers ->
            require(servers.distinctBy { it.serverId }.size == servers.size)
            require(servers.all { server -> server.folders.distinctBy { it.path }.size == server.folders.size })
        }
    }
}

// ---- 언어 ----

/** 액티비티를 만들기 전에 읽는 저장된 언어입니다. */
fun Context.storedLanguage(): Language {
    val stored = getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE).getString(BOOTSTRAP_LANGUAGE, null)
    return Language.entries.firstOrNull { it.name == stored } ?: Language.System
}

/** [language]로 리소스를 읽는 컨텍스트입니다. 기기 언어를 따르면 그대로 돌려줍니다. */
fun Context.withLanguage(language: Language): Context {
    if (language == Language.System) return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
    return createConfigurationContext(configuration)
}
