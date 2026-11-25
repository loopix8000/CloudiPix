package app.cloudipix.gallery.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.media.MediaPaths
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.GridItem
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.model.Sorting
import app.cloudipix.gallery.core.model.grouped
import app.cloudipix.gallery.core.model.isVirtualFolder
import app.cloudipix.gallery.core.model.shellQuoted
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.groupingFor
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.navigation.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.navigation.toRoute
import javax.inject.Inject

data class MediaUiState(
    val title: String = "",
    val items: List<GridItem> = emptyList(),
    val totalCount: Int = 0,
    val totalSize: Long = 0,
    val selected: Set<Long> = emptySet(),
    val settings: GallerySettings = GallerySettings(),
    val virtual: Boolean = false,
    val favoritesSelected: Boolean = false,
    val refreshing: Boolean = false,
    val choosingCover: Boolean = false,
    val loaded: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    // 뷰어에서 돌아오는 첫 프레임에는 이전 그리드가 캐시되어 있을 수 있다.
    fun withoutPending(ids: Set<Long>): MediaUiState {
        if (ids.isEmpty()) return this
        val removedPerGroup = mutableMapOf<GridItem.Header, Int>()
        var group: GridItem.Header? = null
        var removed = 0
        var bytes = 0L
        for (item in items) {
            when (item) {
                is GridItem.Header -> group = item
                is GridItem.Item -> if (item.medium.id in ids) {
                    removed++
                    bytes += item.medium.size
                    group?.let { removedPerGroup[it] = removedPerGroup.getOrDefault(it, 0) + 1 }
                }
            }
        }
        if (removed == 0) return this
        val visible = items.mapNotNull { item ->
            when (item) {
                is GridItem.Item -> item.takeUnless { it.medium.id in ids }
                is GridItem.Header -> {
                    val count = item.count - removedPerGroup.getOrDefault(item, 0)
                    if (count > 0) item.copy(count = count) else null
                }
            }
        }
        return copy(items = visible, totalCount = totalCount - removed, totalSize = totalSize - bytes, selected = selected - ids)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
    private val paths: MediaPaths,
    private val folderSync: FolderSync,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Media>()
    internal val bucketId = route.bucketId
    private val virtual = isVirtualFolder(route.bucketId)
    val isFavorites = route.bucketId == FAVORITES_BUCKET_ID
    val showSyncMenu = folderSync.foldersForAlbum(route.bucketId).map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pendingRemovals = repository.pendingRemovals
    private val selected = MutableStateFlow(emptySet<Long>())
    private val refreshing = MutableStateFlow(false)
    private var loaded = emptyList<Medium>()
    private var positions = emptyMap<Long, Int>()
    private var lastRangeEnd: Long? = null
    private var rangeAnchor: Long? = null
    private var rangeBase = emptySet<Long>()
    private var savingCover = false

    private val arranged = settings.settings.map(::albumQuery).distinctUntilChanged()
        .flatMapLatest { query ->
            repository.mediaIn(route.bucketId, query.filter)
                .distinctUntilChanged()
                .map { media -> media.arranged(query) }
        }.flowOn(Dispatchers.Default)

    val state: StateFlow<MediaUiState> =
        combine(arranged, settings.settings, selected, refreshing) { library, config, picked, busy ->
            if (library.query != albumQuery(config)) return@combine null
            loaded = library.sorted
            positions = library.positions
            MediaUiState(
                title = route.title,
                items = library.items,
                totalCount = library.sorted.size,
                totalSize = library.totalSize,
                selected = picked,
                settings = config,
                virtual = virtual,
                favoritesSelected = picked.isNotEmpty() && picked.all { id ->
                    library.positions[id]?.let { library.sorted[it].isFavorite } == true
                },
                refreshing = busy,
                choosingCover = route.chooseCover,
                loaded = true,
            )
        }.filterNotNull().stateIn(
            viewModelScope, SharingStarted.Lazily,
            MediaUiState(title = route.title, virtual = virtual, choosingCover = route.chooseCover),
        )

    private fun albumQuery(config: GallerySettings) = AlbumQuery(
        filter = if (route.chooseCover) MediaFilter.Images else config.mediaFilter,
        sorting = config.sortingFor(bucketId),
        grouping = config.groupingFor(bucketId),
        scrollDirection = config.scrollDirection,
    )

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                repository.refresh(route.bucketId.takeUnless(::isVirtualFolder)?.let(::setOf))
            } finally {
                refreshing.value = false
            }
        }
    }

    fun toggle(id: Long) {
        if (route.chooseCover) return
        selected.value = selected.value.let { if (id in it) it - id else it + id }
    }

    fun beginRange(id: Long) {
        if (route.chooseCover) return
        rangeBase = selected.value
        rangeAnchor = lastRangeEnd?.takeIf { it in rangeBase && it in positions } ?: id
        selectRange(id)
    }

    fun selectRange(to: Long) {
        val start = rangeAnchor?.let(positions::get) ?: return
        val end = positions[to] ?: return
        selected.value = rangeBase + loaded.subList(minOf(start, end), maxOf(start, end) + 1).map(Medium::id)
        lastRangeEnd = to
    }

    fun selectAll() { selected.value = loaded.map(Medium::id).toSet() }

    fun medium(id: Long): Medium? = positions[id]?.let(loaded::getOrNull)

    fun clearSelection() {
        lastRangeEnd = null
        rangeAnchor = null
        rangeBase = emptySet()
        selected.value = emptySet()
    }

    fun setGrouping(grouping: Grouping) = viewModelScope.launch {
        settings.setMediaGrouping(bucketId, grouping)
    }

    fun setGroupingFolderOnly(enabled: Boolean) = viewModelScope.launch {
        settings.setMediaGroupingFolderOnly(bucketId, enabled)
    }

    fun setSortKey(key: SortKey) = viewModelScope.launch { settings.setMediaSortKey(bucketId, key) }

    fun setSortDescending(descending: Boolean) = viewModelScope.launch { settings.setMediaSortDescending(bucketId, descending) }

    fun setSortingFolderOnly(enabled: Boolean) = viewModelScope.launch {
        settings.setMediaSortingFolderOnly(bucketId, enabled)
    }

    fun zoomGrid(step: Int) = viewModelScope.launch { settings.zoomMediaGrid(step) }

    fun pickCover(medium: Medium, onSaved: () -> Unit) {
        if (!route.chooseCover || savingCover || medium.type == MediaType.Video || medium.bucketId != route.bucketId) return
        savingCover = true
        viewModelScope.launch {
            try {
                repository.setFolderCover(route.bucketId, medium.id)
                onSaved()
            } finally {
                savingCover = false
            }
        }
    }

    fun selectedMedia(): List<Medium> = loaded.filter { it.id in selected.value }

    fun copySelectedPaths(copy: (String) -> Unit) = viewModelScope.launch {
        val items = selectedMedia()
        val found = paths.absolute(items.filterNot(Medium::isRemote)) + items.filter(Medium::isRemote).mapNotNull(repository::remotePath)
        if (found.isNotEmpty()) copy(shellQuoted(found))
        clearSelection()
    }

    internal val hideTarget get() = mapOf(route.bucketId to route.title)

    internal suspend fun folderHideOptions(bucketIds: Set<Long>) = repository.folderHideOptions(bucketIds)

    internal suspend fun hideFolders(folders: Map<Long, String>) = repository.ignoreFolders(folders)

    fun toggleFavorite() = viewModelScope.launch {
        val picked = loaded.filter { it.id in selected.value }
        val makeFavorite = picked.any { !it.isFavorite }
        picked.forEach { repository.setFavorite(it, makeFavorite) }
        clearSelection()
    }
}

private data class AlbumQuery(
    val filter: MediaFilter,
    val sorting: Sorting,
    val grouping: Grouping,
    val scrollDirection: ScrollDirection,
)

private class ArrangedMedia(
    val sorted: List<Medium>,
    val items: List<GridItem>,
    val positions: Map<Long, Int>,
    val totalSize: Long,
    val query: AlbumQuery,
)

private fun List<Medium>.arranged(query: AlbumQuery): ArrangedMedia {
    val order = query.sorting.copy(
        descending = query.sorting.descending != (query.scrollDirection == ScrollDirection.Up)
    )
    val sorted = sortedBy(order)
    val items = sorted.grouped(query.grouping, order)
    val shown = if (query.grouping == Grouping.None) {
        sorted
    } else {
        items.mapNotNullTo(ArrayList(size)) { (it as? GridItem.Item)?.medium }
    }
    val positions = HashMap<Long, Int>(shown.size * 2)
    var totalSize = 0L
    shown.forEachIndexed { index, medium ->
        positions[medium.id] = index
        totalSize += medium.size
    }
    return ArrangedMedia(
        sorted = shown,
        items = items,
        positions = positions,
        totalSize = totalSize,
        query = query,
    )
}
