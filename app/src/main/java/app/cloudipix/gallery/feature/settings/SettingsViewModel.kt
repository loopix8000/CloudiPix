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
import app.cloudipix.gallery.core.media.ThumbnailStorage
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.remote.RemoteImageCache
import app.cloudipix.gallery.core.security.GalleryLocks
import app.cloudipix.gallery.core.settings.Language
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.SyncRestoreAccess
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryFont
import app.cloudipix.gallery.ui.theme.TextScale
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.ScreenTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class SettingsImportResult(
    val languageChanged: Boolean,
    val syncRestored: Boolean,
    val cacheRestored: Boolean,
    val thumbnailCacheRestored: Boolean,
    val appLockMatches: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val locks: GalleryLocks,
    private val cache: RemoteImageCache,
    private val thumbnails: ThumbnailStorage,
    private val folderSync: FolderSync,
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
            repository.exportSettings(destination, locks.settings.value, folderSync.settingsForExport())
        } != null
        onResult(saved)
    }

    internal fun importSettings(source: Uri, onResult: (SettingsImportResult?) -> Unit) = settingsFileAction {
        val result = attemptSettingsFileAction {
            val backup = repository.readSettingsFile(source)
            val languageChanged = repository.restoreSettings(backup.settings)
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
                languageChanged, syncRestored, cacheRestored, thumbnailCacheRestored,
                locks.settings.value.app == backup.appLock,
            )
        }
        onResult(result)
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
