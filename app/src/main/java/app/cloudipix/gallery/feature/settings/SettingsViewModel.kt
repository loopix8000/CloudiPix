package app.cloudipix.gallery.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.cache.CacheUsage
import app.cloudipix.gallery.core.database.FavoriteDao
import app.cloudipix.gallery.core.media.ThumbnailStorage
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.RemoteImageCache
import app.cloudipix.gallery.core.remote.findServer
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.remote.remoteFavoriteKey
import app.cloudipix.gallery.core.remote.remoteId
import app.cloudipix.gallery.core.security.GalleryLocks
import app.cloudipix.gallery.core.settings.FavoriteBackup
import app.cloudipix.gallery.core.settings.FolderTabBackup
import app.cloudipix.gallery.core.settings.Language
import app.cloudipix.gallery.core.settings.RemoteFavoriteLocation
import app.cloudipix.gallery.core.settings.SettingsExport
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.TabFolderRestore
import app.cloudipix.gallery.core.settings.tabFolderIds
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.SyncRestoreAccess
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryFont
import app.cloudipix.gallery.ui.theme.TextScale
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.ScreenTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class SettingsImportResult(
    val languageChanged: Boolean,
    val syncRestored: Boolean,
    val cacheRestored: Boolean,
    val thumbnailCacheRestored: Boolean,
    val appLockMatches: Boolean,
    val favoritesRestored: Boolean,
    val tabFoldersRestored: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject internal constructor(
    private val repository: SettingsRepository,
    private val locks: GalleryLocks,
    private val cache: RemoteImageCache,
    private val thumbnails: ThumbnailStorage,
    private val folderSync: FolderSync,
    private val favorites: FavoriteDao,
    private val remote: RemoteGallery,
    private val tabFolders: FolderTabBackup,
) : ViewModel() {

    val settings: StateFlow<GallerySettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GallerySettings())

    private val transferringSettings = MutableStateFlow(false)
    internal val settingsFileBusy = transferringSettings.asStateFlow()
    private val pendingSyncAccess = MutableStateFlow<List<SyncRestoreAccess>>(emptyList())
    internal val syncRestoreAccess = pendingSyncAccess.asStateFlow()
    private val thumbnailUsage = MutableStateFlow<CacheUsage?>(null)
    internal val thumbnailCacheUsage = thumbnailUsage.asStateFlow()
    private val changingThumbnailCache = MutableStateFlow(false)
    internal val thumbnailCacheBusy = changingThumbnailCache.asStateFlow()

    internal fun refreshThumbnailCacheUsage() = thumbnailCacheAction(onResult = {}) { thumbnails.usage() }

    internal fun setThumbnailCacheLimit(limitMiB: Long, onResult: (Boolean) -> Unit) =
        thumbnailCacheAction(onResult) { thumbnails.resize(limitMiB) }

    internal fun clearThumbnailCache(onResult: (Boolean) -> Unit) = thumbnailCacheAction(onResult) { thumbnails.clear() }

    private fun thumbnailCacheAction(onResult: (Boolean) -> Unit, action: suspend () -> CacheUsage) {
        if (!changingThumbnailCache.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val usage = attemptSettingsFileAction { action() }
                if (usage != null) thumbnailUsage.value = usage
                onResult(usage != null)
            } finally {
                changingThumbnailCache.value = false
            }
        }
    }

    internal fun exportSettings(destination: Uri, onResult: (Boolean) -> Unit) = settingsFileAction {
        val saved = attemptSettingsFileAction {
            val sync = folderSync.settingsForExport()
            repository.exportSettings(destination, locks.settings.value, sync, favoritesForExport(), tabFolders::locations)
        } != null
        onResult(saved)
    }

    internal fun importSettings(source: Uri, onResult: (SettingsImportResult?) -> Unit) = settingsFileAction {
        val result = attemptSettingsFileAction {
            val backup = repository.readSettingsFile(source)
            val serverIds = backupServerIds(backup)
            val tabs = attemptSettingsFileAction { tabFolders.restore(backup, serverIds) }
                ?: TabFolderRestore(backup.settings, complete = false)
            val languageChanged = repository.restoreSettings(tabs.settings)
            val favoritesRestored = attemptSettingsFileAction { restoreFavorites(backup, serverIds) } == true
            locks.setHideRecentsPreview(backup.hideRecentsPreview)
            val cacheRestored = attemptSettingsFileAction { cache.resize(backup.remoteCacheLimitMiB) } != null
            // 썸네일 캐시 한도가 없는 이전 백업은 지금 한도를 그대로 둔다.
            val thumbnailCacheRestored = backup.thumbnailCacheLimitMiB?.let { limit ->
                attemptSettingsFileAction { thumbnails.resize(limit) }?.also { thumbnailUsage.value = it } != null
            } ?: true
            val sync = backup.sync?.let { attemptSettingsFileAction { folderSync.restoreSettings(it) } }
            pendingSyncAccess.value = sync?.pending.orEmpty()
            val syncRestored = backup.sync == null || sync?.complete == true
            SettingsImportResult(
                languageChanged = languageChanged,
                syncRestored = syncRestored,
                cacheRestored = cacheRestored,
                thumbnailCacheRestored = thumbnailCacheRestored,
                appLockMatches = locks.settings.value.app == backup.appLock,
                favoritesRestored = favoritesRestored,
                tabFoldersRestored = tabs.complete,
            )
        }
        onResult(result)
    }

    private suspend fun favoritesForExport(): List<FavoriteBackup> = withContext(Dispatchers.Default) {
        val keys = favorites.pathsForBackup()
        val remotePrefix = "$REMOTE_MEDIA_SCHEME:"
        val idsByKey = buildMap {
            for (key in keys) {
                if (!key.startsWith(remotePrefix)) continue
                key.removePrefix(remotePrefix).toLongOrNull()?.let { put(key, it) }
            }
        }
        // 서버 ID가 달라지는 기기에서 해시 키를 다시 만들 수 있도록 이미 읽은 경로를 함께 저장한다.
        val photos = remote.indexedFavoritePhotos(idsByKey.values.toSet())
        keys.map { key ->
            val photo = idsByKey[key]?.let { photos[it] }
            FavoriteBackup(key, photo?.let { RemoteFavoriteLocation(it.serverId, it.path) })
        }
    }

    private suspend fun backupServerIds(backup: SettingsExport): Map<String, String> = withContext(Dispatchers.Default) {
        val serverBackups = backup.sync?.cloudFolders.orEmpty()
        val needsServerIds = backup.favorites.any { it.remote != null } || backup.settings.tabFolderIds().any(::isRemoteId)
        if (serverBackups.isEmpty() || !needsServerIds) return@withContext emptyMap()
        val servers = attemptSettingsFileAction { remote.connectionsForSync() }.orEmpty()
        serverBackups.mapNotNull { saved ->
            saved.findServer(servers, allowLegacySingleMatch = false)?.let { saved.serverId to it.id }
        }.toMap()
    }

    private suspend fun restoreFavorites(backup: SettingsExport, serverIds: Map<String, String>): Boolean = withContext(Dispatchers.Default) {
        if (backup.favorites.isEmpty()) return@withContext true
        var complete = true
        val paths = backup.favorites.map { favorite ->
            val location = favorite.remote ?: return@map favorite.key
            val serverId = serverIds[location.serverId]
            if (serverId == null) {
                complete = false
                favorite.key
            } else if (serverId == location.serverId) {
                favorite.key
            } else {
                remoteFavoriteKey(remoteId(serverId, location.path))
            }
        }
        favorites.restoreBackup(paths)
        complete
    }

    internal fun allowSyncRestore(uri: Uri, onResult: (Boolean?) -> Unit) = settingsFileAction {
        val access = pendingSyncAccess.value.firstOrNull() ?: return@settingsFileAction
        val restored = attemptSettingsFileAction { folderSync.restoreAccess(access, uri) }
        if (restored != null) pendingSyncAccess.value = pendingSyncAccess.value.drop(1)
        onResult(restored)
    }

    internal fun closeSyncRestore() {
        if (!transferringSettings.value) pendingSyncAccess.value = emptyList()
    }

    private fun settingsFileAction(action: suspend () -> Unit) {
        if (!transferringSettings.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                action()
            } finally {
                transferringSettings.value = false
            }
        }
    }

    private suspend fun <T> attemptSettingsFileAction(action: suspend () -> T): T? = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun setThemeMode(value: ThemeMode) = update { repository.setThemeMode(value) }
    fun setSettingsTheme(value: ScreenTheme) = update { repository.setSettingsTheme(value) }
    fun setAccent(argb: Int) = update { repository.setAccent(argb) }
    fun setFont(value: GalleryFont) = update { repository.setFont(value) }
    fun setTextScale(value: TextScale) = update { repository.setTextScale(value) }


    fun setGrouping(value: Grouping) = update { repository.setGrouping(value) }
    fun setSortKey(value: SortKey) = update { repository.setDefaultSortKey(value) }
    fun setSortDescending(descending: Boolean) = update { repository.setSortDescending(descending) }
    fun setFolderColumns(value: Int) = update { repository.setFolderColumns(value) }
    fun setMediaColumns(value: Int) = update { repository.setMediaColumns(value) }
    fun setScrollDirection(value: ScrollDirection) = update { repository.setScrollDirection(value) }
    fun setShowFolderNames(value: Boolean) = update { repository.setShowFolderNames(value) }
    fun setShowFolderCount(value: Boolean) = update { repository.setShowFolderCount(value) }
    fun setShowFolderSize(value: Boolean) = update { repository.setShowFolderSize(value) }
    fun setShowCloudFolderIcons(value: Boolean) = update { repository.setShowCloudFolderIcons(value) }
    fun setShowCloudFolderIconsOnlyWhileSyncing(value: Boolean) =
        update { repository.setShowCloudFolderIconsOnlyWhileSyncing(value) }

    fun setShowRecent(value: Boolean) = update { repository.setShowRecent(value) }
    fun setDetailsPlacement(value: DetailsPlacement) = update { repository.setDetailsPlacement(value) }
    fun setShowVideoDuration(value: Boolean) = update { repository.setShowVideoDuration(value) }
    fun setAutoplayNextVideo(value: Boolean) = update { repository.setAutoplayNextVideo(value) }
    fun setBackgroundPlayback(value: Boolean) = update { repository.setBackgroundPlayback(value) }
    fun setConfirmTrash(value: Boolean) = update { repository.setConfirmTrash(value) }
    fun setVolumeButtonPaging(value: Boolean) = update { repository.setVolumeButtonPaging(value) }
    fun setPanoramaForAll(value: Boolean) = update { repository.setPanoramaForAll(value) }
    fun setSelectionBar(value: BarLayout) = update { repository.setSelectionBar(value) }
    fun setViewerBar(value: BarLayout) = update { repository.setViewerBar(value) }
    fun setPhotoEditorPackage(value: String?) = update { repository.setPhotoEditorPackage(value) }
    fun setLanguage(value: Language, onApplied: () -> Unit) = viewModelScope.launch {
        repository.setLanguage(value)
        onApplied()
    }

    private fun update(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
