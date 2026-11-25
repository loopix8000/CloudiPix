package app.cloudipix.gallery.feature.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderTab
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.model.Sorting
import app.cloudipix.gallery.core.model.firstInSortOrder
import app.cloudipix.gallery.core.model.isVirtualFolder
import app.cloudipix.gallery.core.model.manualSortPosition
import app.cloudipix.gallery.core.model.recent
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.sync.FolderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.IOException

data class FoldersUiState(
    val folders: List<Folder> = emptyList(),
    val settings: GallerySettings = GallerySettings(),
    val selected: Set<Long> = emptySet(),
    val refreshing: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    val selectionHasFavorites: Boolean get() = FAVORITES_BUCKET_ID in selected
    val selectionPinned: Boolean get() = folders.filter { it.bucketId in selected }.all(Folder::isPinned)
    val selectionHasLocked: Boolean get() = folders.any { it.bucketId in selected && it.isLocked }
    val selectedFolder: Folder? get() = selected.singleOrNull()?.let { id ->
        folders.firstOrNull { it.bucketId == id && !it.isVirtual }
    }
}

private data class FolderOrderDraft(val original: List<Long>, val order: List<Long> = original)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
    private val folderSync: FolderSync,
) : ViewModel() {

    private val selected = MutableStateFlow(emptySet<Long>())
    private val refreshing = MutableStateFlow(false)
    private val showHiddenFolders = MutableStateFlow(false)
    internal val showingHiddenFolders = showHiddenFolders.asStateFlow()
    private val dragOrder = MutableStateFlow<FolderOrderDraft?>(null)
    private var loaded = emptyList<Folder>()
    private var selectionAnchor: Long? = null

    val tabFolders = combine(repository.folderEntries, repository.ignoredFolders) { folders, ignored ->
        val hidden = ignored.mapTo(mutableSetOf()) { it.bucketId }
        folders.filter { it.itemCount > 0 && it.bucketId !in hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val coverSettings = settings.settings.distinctUntilChangedBy {
        listOf(it.mediaFilter, it.sorting, it.folderMediaSettings, it.showRecent)
    }

    private val albums = combine(coverSettings, repository.pendingRemovals.map { it.isEmpty() }.distinctUntilChanged()) {
        config, complete -> config to complete
    }.flatMapLatest { (config, complete) ->
        val filter = config.mediaFilter
        val latest = Sorting()
        val fromSummaries = complete && !config.showRecent && config.sorting == latest &&
            config.folderMediaSettings.keys.none { !isVirtualFolder(it) && config.sortingFor(it) != latest }
        combine(
            showHiddenFolders.flatMapLatest { includeHidden ->
                repository.folders(filter, latestCovers = fromSummaries, includeHidden = includeHidden)
            },
            repository.mediaIn(FAVORITES_BUCKET_ID, filter).map<List<Medium>, List<Medium>?> { it }
                .onStart { emit(null) },
            if (fromSummaries) flowOf(emptyList()) else repository.mediaIn(RECENT_BUCKET_ID, filter)
                .onStart { emit(emptyList()) },
        ) { folders, favorites, media ->
            val covered = if (fromSummaries) folders else {
                val byFolder = media.groupBy(Medium::bucketId)
                folders.map { it.withAutomaticCover(byFolder[it.bucketId].orEmpty(), config.sortingFor(it.bucketId)) }
            }
            val recent = if (config.showRecent) {
                folders.filterNot { it.isLocked || it.isHidden }.recent()
                    ?.withAutomaticCover(media, config.sortingFor(RECENT_BUCKET_ID))
            } else null
            FolderAlbums(covered, favorites?.asFavoritesFolder(config.sortingFor(FAVORITES_BUCKET_ID)), recent)
        }
    }.flowOn(Dispatchers.Default)

    private val orderedAlbums = combine(albums, settings.settings, dragOrder) {
        (folders, favorites, recent), config, draft ->
        val autoSorted = listOfNotNull(favorites) + folders.sortedBy(config.sorting)
        val manualSort = draft != null || config.manualFolderSort
        val ordered = if (manualSort) {
            val positions = (draft?.order ?: config.folderOrder).withIndex().associate { it.value to it.index }
            autoSorted.sortedBy { folder ->
                val position = if (draft == null) folder.manualSortPosition(positions) else positions[folder.bucketId]
                position ?: if (folder.isFavorites) Int.MIN_VALUE else Int.MAX_VALUE
            }
        } else autoSorted
        val sorted = ordered.sortedByDescending(Folder::isPinned)
        val shown = listOfNotNull(recent) + sorted
        val shownSettings = if (config.manualFolderSort == manualSort) config else config.copy(manualFolderSort = manualSort)
        sorted to FoldersUiState(shown, shownSettings)
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<FoldersUiState?> =
        combine(orderedAlbums, selected, refreshing) { (sorted, ui), picked, busy ->
            loaded = sorted
            val validSelection = if (picked.isEmpty()) emptySet() else sorted.mapNotNullTo(mutableSetOf()) {
                it.bucketId.takeIf(picked::contains)
            }
            ui.copy(selected = validSelection, refreshing = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal val albumSyncStates = folderSync.albumSyncStates(state.map { ui ->
        ui?.folders.orEmpty().mapNotNullTo(hashSetOf()) { folder ->
            folder.bucketId.takeUnless { folder.isVirtual || isRemoteId(it) }
        }
    }.distinctUntilChanged()).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { repository.changes.collect { ids -> repository.sync(ids) } }
    }

    internal fun toggleHiddenFolders() {
        if (showHiddenFolders.value) stopShowingHiddenFolders()
        else showHiddenFolders.value = true
    }

    internal fun stopShowingHiddenFolders() {
        if (!showHiddenFolders.value) return
        showHiddenFolders.value = false
        if (selected.value.isEmpty()) return
        val hidden = loaded.filter(Folder::isHidden).mapTo(hashSetOf(), Folder::bucketId)
        selected.value = selected.value - hidden
        selectionAnchor = selectionAnchor?.takeUnless { it in hidden }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try { repository.refresh(null) } finally { refreshing.value = false }
        }
    }

    fun toggle(bucketId: Long) {
        if (bucketId == RECENT_BUCKET_ID) return
        val picked = selected.value
        if (bucketId in picked) {
            selected.value = picked - bucketId
            if (selectionAnchor == bucketId) selectionAnchor = null
        } else {
            selectionAnchor = bucketId
            selected.value = picked + bucketId
        }
    }

    fun selectRange(folders: List<Folder>, bucketId: Long) {
        if (bucketId == RECENT_BUCKET_ID) return
        val end = folders.indexOfFirst { it.bucketId == bucketId }
        if (end < 0) return
        val picked = selected.value
        val anchor = selectionAnchor?.takeIf { it in picked } ?: picked.lastOrNull()
        val start = folders.indexOfFirst { it.bucketId == anchor }.takeIf { it >= 0 } ?: end
        val range = folders.subList(minOf(start, end), maxOf(start, end) + 1)
        selectionAnchor = bucketId
        selected.value = picked + range.mapNotNull { folder -> folder.bucketId.takeUnless { it == RECENT_BUCKET_ID } }
    }

    fun selectAll(folders: List<Folder>) {
        selected.value = folders.filterNot(Folder::isVirtual).mapTo(mutableSetOf(), Folder::bucketId)
    }

    suspend fun saveTab(tab: FolderTab) = settings.saveFolderTab(tab)

    suspend fun moveTab(id: String, offset: Int) = settings.moveFolderTab(id, offset)

    suspend fun deleteTab(id: String) = settings.deleteFolderTab(id)

    suspend fun renameHomeTab(name: String?) = settings.renameHomeTab(name)

    suspend fun moveSelectedToTab(from: String, to: String): Boolean {
        val ids = state.value?.selected.orEmpty()
        val moved = settings.moveFoldersToTab(ids, from, to, localByCloud = localIdsByCloud())
        if (moved) clearSelection()
        return moved
    }

    fun clearSelection() {
        selectionAnchor = null
        selected.value = emptySet()
    }

    internal suspend fun folderHideOptions(bucketIds: Set<Long>) = repository.folderHideOptions(bucketIds)

    internal suspend fun hideFolders(folders: Map<Long, String>) = repository.ignoreFolders(folders)

    internal suspend fun unhideFolders(bucketIds: Set<Long>) {
        repository.unignoreFolders(bucketIds)
        selected.value = selected.value - bucketIds
        selectionAnchor = selectionAnchor?.takeUnless { it in bucketIds }
    }

    fun pinSelected() = viewModelScope.launch {
        val ids = selected.value.filterNot(::isVirtualFolder)
        val pin = state.value?.selectionPinned == false
        val localIds = if (pin) emptyList() else ids.mapNotNull(localIdsByCloud()::get)
        repository.setPinned(ids + localIds, pin)
        clearSelection()
    }

    suspend fun selectedMedia() = repository.mediaForFolders(selected.value.filterNot(::isVirtualFolder))

    fun zoomGrid(step: Int) = viewModelScope.launch { settings.zoomFolderGrid(step) }

    fun setSortKey(key: SortKey) = viewModelScope.launch { settings.setFolderSortKey(key) }

    fun setSortDescending(descending: Boolean) = viewModelScope.launch { settings.setSortDescending(descending) }

    fun useManualSort() = viewModelScope.launch { settings.useManualFolderSort() }

    fun resetCover(folder: Folder) = viewModelScope.launch {
        repository.setFolderCover(folder.bucketId, null)
        clearSelection()
    }

    suspend fun localFolderPath(folder: Folder) = repository.localFolderPath(folder)

    suspend fun renameLocalFolder(folder: Folder, name: String) = repository.renameLocalFolder(folder, name)

    suspend fun moveLocalFolder(folder: Folder, destination: String) = repository.moveLocalFolder(folder, destination)

    fun beginFolderDrag(bucketId: Long): Boolean {
        if (dragOrder.value != null || state.value?.selecting != false || loaded.none { it.bucketId == bucketId }) return false
        dragOrder.value = FolderOrderDraft(loaded.map(Folder::bucketId))
        return true
    }

    fun moveFolder(from: Long, to: Long) {
        val draft = dragOrder.value ?: return
        val source = loaded.firstOrNull { it.bucketId == from } ?: return
        val target = loaded.firstOrNull { it.bucketId == to } ?: return
        if (source.isPinned != target.isPinned) return
        val fromIndex = draft.order.indexOf(from)
        val toIndex = draft.order.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        dragOrder.value = draft.copy(order = draft.order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
    }

    fun finishFolderDrag(bucketId: Long, from: String, to: String?, onFailure: () -> Unit) {
        val draft = dragOrder.value ?: return
        val order = draft.order.takeIf { it != draft.original }
        if (to == null) {
            dragOrder.value = null
            return
        }
        val localByCloud = localIdsByCloud()
        viewModelScope.launch {
            try {
                if (from == to) {
                    if (order != null) settings.saveFolderOrder(order, localByCloud)
                } else if (!settings.moveFoldersToTab(setOf(bucketId), from, to, order, localByCloud)) {
                    onFailure()
                }
            } catch (_: IOException) {
                onFailure()
            } finally {
                dragOrder.value = null
            }
        }
    }

    private fun localIdsByCloud(): Map<Long, Long> = buildMap {
        for (folder in loaded) folder.localBucketId?.let { put(folder.bucketId, it) }
    }

    fun cycleFilter() = viewModelScope.launch {
        val current = settings.settings.first().mediaFilter
        settings.setMediaFilter(MediaFilter.entries[(current.ordinal + 1) % MediaFilter.entries.size])
    }
}

private data class FolderAlbums(val folders: List<Folder>, val favorites: Folder?, val recent: Folder?)

private fun Folder.withAutomaticCover(media: List<Medium>, sorting: Sorting): Folder =
    if (manualCover != null || media.isEmpty()) this
    else copy(automaticCover = media.firstInSortOrder(sorting)?.displayUri)

private fun List<Medium>.asFavoritesFolder(sorting: Sorting): Folder {
    val cover = firstInSortOrder(sorting)
    return Folder(
        bucketId = FAVORITES_BUCKET_ID,
        name = "",
        itemCount = size,
        size = sumOf(Medium::size),
        dateTaken = maxOfOrNull(Medium::dateTaken) ?: 0L,
        dateModified = maxOfOrNull(Medium::dateModified) ?: 0L,
        coverId = cover?.id ?: 0L,
        coverType = cover?.type?.ordinal ?: 0,
        remoteCover = cover?.localCopyUri ?: cover?.remoteUri,
    )
}
