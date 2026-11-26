package app.cloudipix.gallery.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.model.shellQuoted
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.groupingFor
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.navigation.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 폴더(앨범) 화면 상태입니다. [items]는 날짜 머리글을 섞은 격자 목록이고, [totalCount]·[totalSize]는
 * 화면에 보이는 항목의 합계입니다. [loaded]가 거짓이면 아직 첫 목록을 받지 못한 상태입니다.
 */
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

    /**
     * 삭제를 요청했지만 아직 목록에서 빠지지 않은 항목을 숨긴 상태입니다. 뷰어에서 지우고 돌아온 첫 프레임에
     * 이전 격자가 남아 있어도 지운 항목이 보이지 않게 합니다. 항목이 모두 빠진 날짜 머리글도 숨깁니다.
     */
    fun withoutPending(ids: Set<Long>): MediaUiState {
        if (ids.isEmpty()) return this
        val kept = ArrayList<GridItem>(items.size)
        var droppedCount = 0
        var droppedBytes = 0L
        // 지금 묶음의 머리글이 kept 안 몇 번째에 있는지와, 그 묶음에서 빠진 항목 수입니다.
        var headerSlot = -1
        var droppedInGroup = 0

        fun closeGroup() {
            if (headerSlot < 0 || droppedInGroup == 0) return
            val header = kept[headerSlot] as GridItem.Header
            val left = header.count - droppedInGroup
            if (left > 0) kept[headerSlot] = header.copy(count = left) else kept.removeAt(headerSlot)
        }

        for (item in items) {
            when (item) {
                is GridItem.Header -> {
                    closeGroup()
                    headerSlot = kept.size
                    droppedInGroup = 0
                    kept += item
                }
                is GridItem.Item -> if (item.medium.id in ids) {
                    droppedCount++
                    droppedBytes += item.medium.size
                    droppedInGroup++
                } else {
                    kept += item
                }
            }
        }
        closeGroup()

        if (droppedCount == 0) return this
        return copy(
            items = kept,
            totalCount = totalCount - droppedCount,
            totalSize = totalSize - droppedBytes,
            selected = selected - ids,
        )
    }
}

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
    private val virtual = isVirtualFolder(bucketId)
    private var checkedOnOpen = false
    val isFavorites = bucketId == FAVORITES_BUCKET_ID

    /** 이 폴더가 폴더 동기화 대상이면 메뉴에 동기화 항목을 보입니다. */
    val showSyncMenu = folderSync.foldersForAlbum(bucketId).map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pendingRemovals = repository.pendingRemovals

    private val picked = MutableStateFlow(emptySet<Long>())
    private val refreshing = MutableStateFlow(false)

    /** 화면에 보이는 순서의 항목과 각 ID의 위치입니다. 범위 선택과 항목 찾기에 씁니다. */
    private var visible = emptyList<Medium>()
    private var indexById = emptyMap<Long, Int>()

    // 범위 선택: 길게 눌러 시작한 기준점과, 시작할 때 이미 선택돼 있던 항목입니다.
    private var lastRangeEnd: Long? = null
    private var rangeStart: Long? = null
    private var selectionBeforeRange = emptySet<Long>()

    private var savingCover = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val arranged = settings.settings.map(::queryFor).distinctUntilChanged()
        .flatMapLatest { query ->
            repository.mediaIn(bucketId, query.filter).distinctUntilChanged().map { it.arrange(query) }
        }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<MediaUiState> =
        combine(arranged, settings.settings, picked, refreshing) { library, config, selection, busy ->
            // 설정이 막 바뀌어 아직 이전 기준으로 정렬한 목록이면 내보내지 않습니다.
            if (library.query != queryFor(config)) return@combine null
            visible = library.shown
            indexById = library.indexById
            MediaUiState(
                title = route.title,
                items = library.items,
                totalCount = library.shown.size,
                totalSize = library.totalSize,
                selected = selection,
                settings = config,
                virtual = virtual,
                favoritesSelected = selection.isNotEmpty() && selection.all { id -> library.isFavorite(id) },
                refreshing = busy,
                choosingCover = route.chooseCover,
                loaded = true,
            )
        }
            .filterNotNull()
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                MediaUiState(title = route.title, virtual = virtual, choosingCover = route.chooseCover),
            )

    private fun queryFor(config: GallerySettings) = albumQuery(config, bucketId, route.chooseCover)

    internal suspend fun refreshVisibleFolder() {
        if (!isRemoteId(bucketId)) return
        repository.refreshVisibleFolders(setOf(bucketId), force = !checkedOnOpen)
        checkedOnOpen = true
    }

    /** 이 폴더를 다시 읽습니다. 가상 폴더(최근 항목·즐겨찾기)는 전체를 다시 읽습니다. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                repository.refresh(if (virtual) null else setOf(bucketId))
            } finally {
                refreshing.value = false
            }
        }
    }

    // ---- 선택 ----

    fun toggle(id: Long) {
        if (route.chooseCover) return
        picked.update { if (id in it) it - id else it + id }
    }

    /** 범위 선택을 시작합니다. 직전 범위의 끝이 아직 선택돼 있으면 거기서 이어서 고릅니다. */
    fun beginRange(id: Long) {
        if (route.chooseCover) return
        selectionBeforeRange = picked.value
        rangeStart = lastRangeEnd?.takeIf { it in selectionBeforeRange && it in indexById } ?: id
        selectRange(id)
    }

    /** 기준점부터 [to]까지를 시작 전 선택에 더합니다. 손가락을 움직이는 동안 계속 부릅니다. */
    fun selectRange(to: Long) {
        val from = rangeStart?.let { indexById[it] } ?: return
        val until = indexById[to] ?: return
        val span = visible.subList(minOf(from, until), maxOf(from, until) + 1)
        picked.value = selectionBeforeRange + span.map { it.id }
        lastRangeEnd = to
    }

    fun selectAll() {
        picked.value = visible.mapTo(HashSet()) { it.id }
    }

    fun clearSelection() {
        lastRangeEnd = null
        rangeStart = null
        selectionBeforeRange = emptySet()
        picked.value = emptySet()
    }

    fun medium(id: Long): Medium? = indexById[id]?.let { visible.getOrNull(it) }

    fun selectedMedia(): List<Medium> {
        val ids = picked.value
        return visible.filter { it.id in ids }
    }

    /** 선택한 항목이 하나라도 즐겨찾기가 아니면 모두 즐겨찾기로, 모두 즐겨찾기면 모두 해제합니다. */
    fun toggleFavorite() = viewModelScope.launch {
        val chosen = selectedMedia()
        val favorite = chosen.any { !it.isFavorite }
        for (medium in chosen) repository.setFavorite(medium, favorite)
        clearSelection()
    }

    /** 선택한 항목의 경로를 셸에 붙여 넣을 수 있는 형태로 복사합니다. 원격 항목은 서버 경로를 씁니다. */
    fun copySelectedPaths(copy: (String) -> Unit) = viewModelScope.launch {
        val (remote, local) = selectedMedia().partition { it.isRemote }
        val found = paths.absolute(local) + remote.mapNotNull { repository.remotePath(it) }
        if (found.isNotEmpty()) copy(shellQuoted(found))
        clearSelection()
    }

    // ---- 폴더 설정 ----

    fun setGrouping(grouping: Grouping) = viewModelScope.launch { settings.setMediaGrouping(bucketId, grouping) }

    fun setGroupingFolderOnly(enabled: Boolean) = viewModelScope.launch { settings.setMediaGroupingFolderOnly(bucketId, enabled) }

    fun setSortKey(key: SortKey) = viewModelScope.launch { settings.setMediaSortKey(bucketId, key) }

    fun setSortDescending(descending: Boolean) = viewModelScope.launch { settings.setMediaSortDescending(bucketId, descending) }

    fun setSortingFolderOnly(enabled: Boolean) = viewModelScope.launch { settings.setMediaSortingFolderOnly(bucketId, enabled) }

    fun zoomGrid(step: Int) = viewModelScope.launch { settings.zoomMediaGrid(step) }

    /** 표지 고르기 화면에서 사진을 표지로 저장합니다. 저장하는 동안 다시 누른 것은 무시합니다. */
    fun pickCover(medium: Medium, onSaved: () -> Unit) {
        val allowed = route.chooseCover && medium.type != MediaType.Video && medium.bucketId == bucketId
        if (!allowed || savingCover) return
        savingCover = true
        viewModelScope.launch {
            try {
                repository.setFolderCover(bucketId, medium.id)
                onSaved()
            } finally {
                savingCover = false
            }
        }
    }

    internal val hideTarget get() = mapOf(bucketId to route.title)

    internal suspend fun folderHideOptions(bucketIds: Set<Long>) = repository.folderHideOptions(bucketIds)

    internal suspend fun hideFolders(folders: Map<Long, String>) = repository.ignoreFolders(folders)
}

/** 목록을 다시 정렬해야 하는 설정 조합입니다. 이 값이 같으면 정렬 결과도 같습니다. */
internal data class AlbumQuery(
    val filter: MediaFilter,
    val sorting: Sorting,
    val grouping: Grouping,
    val scrollDirection: ScrollDirection,
)

/** [bucketId] 폴더 화면이 쓰는 정렬 기준입니다. 표지를 고를 때는 사진만 보여 줍니다. */
internal fun albumQuery(config: GallerySettings, bucketId: Long, chooseCover: Boolean = false) = AlbumQuery(
    filter = if (chooseCover) MediaFilter.Images else config.mediaFilter,
    sorting = config.sortingFor(bucketId),
    grouping = config.groupingFor(bucketId),
    scrollDirection = config.scrollDirection,
)

/**
 * 폴더를 처음 열었을 때 보이는 [cells]개 항목입니다. 위로 쌓는 설정이면 맨 끝을 먼저 보여 주므로 마지막 항목들입니다.
 * 날짜 머리글이 차지하는 줄은 빼지 않아 실제보다 조금 넉넉합니다.
 */
internal fun List<Medium>.firstAlbumPage(query: AlbumQuery, cells: Int): List<Medium> {
    val shown = arrange(query).shown
    return if (query.scrollDirection == ScrollDirection.Up) shown.takeLast(cells) else shown.take(cells)
}

/** 정렬·묶기를 마친 목록입니다. [shown]은 격자에 보이는 순서, [indexById]는 그 안의 위치입니다. */
private class ArrangedMedia(
    val shown: List<Medium>,
    val items: List<GridItem>,
    val indexById: Map<Long, Int>,
    val totalSize: Long,
    val query: AlbumQuery,
) {
    fun isFavorite(id: Long): Boolean = indexById[id]?.let { shown[it].isFavorite } == true
}

/**
 * 정렬하고 날짜로 묶습니다. 위로 스크롤하는 설정이면 정렬 방향을 뒤집어 최신 항목이 아래에 오게 합니다.
 * 날짜로 묶으면 같은 날짜가 한 묶음으로 모이므로, 보이는 순서는 묶은 결과를 따릅니다.
 */
private fun List<Medium>.arrange(query: AlbumQuery): ArrangedMedia {
    val flipped = query.scrollDirection == ScrollDirection.Up
    val order = query.sorting.copy(descending = query.sorting.descending != flipped)
    val sorted = sortedBy(order)
    val items = sorted.grouped(query.grouping, order)
    val shown = if (query.grouping == Grouping.None) sorted else items.mapNotNullTo(ArrayList(size)) { (it as? GridItem.Item)?.medium }

    val indexById = HashMap<Long, Int>(shown.size * 2)
    var bytes = 0L
    for ((index, medium) in shown.withIndex()) {
        indexById[medium.id] = index
        bytes += medium.size
    }
    return ArrangedMedia(shown, items, indexById, bytes, query)
}
