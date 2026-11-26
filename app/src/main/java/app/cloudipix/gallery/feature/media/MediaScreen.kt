package app.cloudipix.gallery.feature.media

import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.indexedMedia
import app.cloudipix.gallery.core.model.BarAction
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalConfiguration
import app.cloudipix.gallery.core.model.GridItem
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.HeaderKind
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.groupingFor
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.feature.files.FileAction
import app.cloudipix.gallery.feature.files.FileActions
import app.cloudipix.gallery.feature.folders.rememberFolderHideActions
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.ActionBar
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.MenuEntry
import app.cloudipix.gallery.ui.component.GridGroupPopup
import app.cloudipix.gallery.ui.component.GridSelectionIndicator
import app.cloudipix.gallery.ui.component.GridSortPopup
import app.cloudipix.gallery.ui.component.galleryGridCells
import app.cloudipix.gallery.ui.component.gridPinchZoom
import app.cloudipix.gallery.ui.component.OverflowMenu
import app.cloudipix.gallery.ui.component.MediaThumbnail
import app.cloudipix.gallery.ui.component.PullToRefresh
import app.cloudipix.gallery.ui.component.VolumeGridScroll
import app.cloudipix.gallery.ui.component.rememberGridRangeDrag
import app.cloudipix.gallery.ui.component.selectGridRange
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.FavoriteRed
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun MediaScreen(
    onBack: () -> Unit,
    onOpenMedium: (Medium) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    initialSettings: GallerySettings,
    photoTransition: @Composable (Long) -> Modifier = { Modifier },
    gridModifier: Modifier = Modifier,
    viewModel: MediaViewModel = hiltViewModel(),
) {
    val library by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingRemovals.collectAsStateWithLifecycle()
    val showSyncMenu by viewModel.showSyncMenu.collectAsStateWithLifecycle()
    // 삭제를 요청한 항목은 목록이 다시 읽히기 전에도 숨깁니다.
    val state = remember(library, pending) { library.withoutPending(pending) }
    val items = state.items
    // 첫 목록이 오기 전에는 화면을 열 때 넘겨받은 설정으로 그려 열 수와 간격이 튀지 않게 합니다.
    val settings = if (state.loaded) state.settings else initialSettings
    val bucketId = viewModel.bucketId
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(bucketId) {
        val refresh = scope.launch { viewModel.refreshVisibleFolder() }
        onPauseOrDispose { refresh.cancel() }
    }
    val folderPrefs = settings.folderMediaSettings[bucketId]
    val sorting = settings.sortingFor(bucketId)
    val grouping = settings.groupingFor(bucketId)
    val colors = GalleryTheme.colors
    val context = LocalContext.current
    val hideActions = rememberFolderHideActions(viewModel::folderHideOptions, viewModel::hideFolders, onBack)

    BackHandler(enabled = state.selecting, onBack = viewModel::clearSelection)

    val gridState = rememberLazyGridState()
    val thumbnails = rememberAlbumThumbnails(gridState, items, if (state.choosingCover && state.loaded) 1 else 0)
    var scrolledFor by rememberSaveable { mutableStateOf<ScrollDirection?>(null) }
    val gap = settings.thumbnailSpacing.gap
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    val rangeDrag = rememberGridRangeDrag(gridState, gapPx) { _, to -> viewModel.selectRange(to) }
    val popupOpen = fileAction != null || sortMenuOpen || groupMenuOpen
    VolumeGridScroll(gridState, enabled = settings.volumeButtonPaging && !popupOpen && !rangeDrag.active && hideActions.enabled)

    val onItemTap: (Medium) -> Unit = { medium ->
        when {
            state.choosingCover -> viewModel.pickCover(medium, onBack)
            state.selecting -> viewModel.toggle(medium.id)
            else -> onOpenMedium(medium)
        }
    }

    // 아래에서 위로 쌓는 설정이면 처음 배치할 때 맨 끝을 보여 줍니다. 돌아올 때는 저장된 스크롤 위치를 씁니다.
    val direction = settings.scrollDirection.takeIf { items.isNotEmpty() }
    if (state.loaded && direction != scrolledFor) {
        SideEffect {
            if (direction == ScrollDirection.Up) {
                val leadingRows = if (state.choosingCover) 1 else 0
                gridState.requestScrollToItem(items.lastIndex + leadingRows)
            }
            scrolledFor = direction
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.galleryListBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when {
            state.selecting -> SelectionHeader(state.selected.size, onClose = viewModel::clearSelection, onSelectAll = viewModel::selectAll)
            state.choosingCover -> AlbumHeader(stringResource(R.string.album_choose_cover), state.title, onBack)
            else -> AlbumHeader(
                title = state.title,
                subtitle = stringResource(R.string.media_summary, formatCount(state.totalCount), formatSize(state.totalSize)),
                onBack = onBack,
            ) {
                IconAction(GalleryIcons.Search, onClick = onOpenSearch, label = stringResource(R.string.action_search))
                Box {
                    OverflowMenu { close ->
                        MenuEntry(stringResource(R.string.menu_refresh)) { close(); viewModel.refresh() }
                        if (showSyncMenu) MenuEntry(stringResource(R.string.sync_menu)) { close(); onOpenSync() }
                        // 즐겨찾기 추가순으로 볼 때는 날짜로 묶지 않으므로 묶기 메뉴를 숨깁니다.
                        if (sorting.key != SortKey.FavoriteAdded) {
                            MenuEntry(stringResource(R.string.menu_group_by), value = stringResource(grouping.labelRes)) {
                                close(); groupMenuOpen = true
                            }
                        }
                        MenuEntry(stringResource(R.string.menu_sort_by), value = stringResource(sorting.key.labelRes)) {
                            close(); sortMenuOpen = true
                        }
                        if (!state.virtual && hideActions.enabled) {
                            MenuEntry(stringResource(R.string.menu_ignore_folder)) { close(); hideActions.hide(viewModel.hideTarget) }
                        }
                        MenuEntry(stringResource(R.string.menu_settings)) { close(); onOpenSettings() }
                    }
                    GridSortPopup(
                        open = sortMenuOpen,
                        onDismiss = { sortMenuOpen = false },
                        sorting = sorting,
                        onSortKey = viewModel::setSortKey,
                        onSortDescending = viewModel::setSortDescending,
                        folderOnly = folderPrefs?.sortingFolderOnly != false,
                        onFolderOnlyChange = viewModel::setSortingFolderOnly,
                        allowFavoriteSort = viewModel.isFavorites,
                    )
                    GridGroupPopup(
                        open = groupMenuOpen,
                        onDismiss = { groupMenuOpen = false },
                        grouping = grouping,
                        onGrouping = viewModel::setGrouping,
                        folderOnly = folderPrefs?.groupingFolderOnly != false,
                        onFolderOnlyChange = viewModel::setGroupingFolderOnly,
                    )
                }
            }
        }

        PullToRefresh(
            refreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            atTop = { !gridState.canScrollBackward },
            modifier = Modifier.weight(1f),
        ) { topPadding ->
            val gridGestures = Modifier
                .fillMaxSize()
                .then(gridModifier)
                .selectGridRange(
                    drag = rangeDrag,
                    allowHoldMovement = state.selecting,
                    onTap = { id -> viewModel.medium(id)?.let(onItemTap) },
                    onHold = if (state.choosingCover) null else viewModel::beginRange,
                )
                .gridPinchZoom(!state.selecting, viewModel::zoomGrid)
            LazyVerticalGrid(
                columns = galleryGridCells(settings.mediaColumns, gap),
                modifier = gridGestures,
                state = gridState,
                contentPadding = PaddingValues(start = gap, end = gap, top = topPadding),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                if (state.choosingCover && state.loaded) {
                    fullWidthNote(if (items.isEmpty()) R.string.album_cover_empty else R.string.album_cover_hint, emphasized = false)
                }
                if (viewModel.isFavorites && state.loaded && items.isEmpty()) {
                    fullWidthNote(R.string.favorites_empty, emphasized = true)
                }
                // 날짜로 묶으면 머리글이 한 줄을 다 차지합니다.
                val span: (LazyGridItemSpanScope.(Int) -> GridItemSpan)? = if (grouping == Grouping.None) {
                    null
                } else {
                    { index -> GridItemSpan(if (items[index] is GridItem.Header) maxLineSpan else 1) }
                }
                items(
                    count = items.size,
                    key = { index -> items[index].stableKey },
                    span = span,
                    contentType = { index -> items[index] is GridItem.Header },
                ) { index ->
                    when (val entry = items[index]) {
                        is GridItem.Header -> DateHeader(entry)
                        is GridItem.Item -> {
                            val medium = entry.medium
                            // 사진을 열 때 확대 전환은 고르기·선택 중이 아닐 때만 씁니다.
                            val sharedPhoto = medium.type != MediaType.Video && !state.choosingCover && !state.selecting
                            MediaTile(
                                medium = medium,
                                thumbnails = thumbnails,
                                selected = medium.id in state.selected,
                                selecting = state.selecting,
                                showDuration = settings.showVideoDuration,
                                onTap = { onItemTap(medium) },
                                onHold = { viewModel.beginRange(medium.id) },
                                thumbnailModifier = if (sharedPhoto) photoTransition(medium.id) else Modifier,
                                showFavoriteBadge = !viewModel.isFavorites,
                            )
                        }
                    }
                }
            }
        }

        if (state.selecting) {
            // 이름 바꾸기는 하나만 골랐을 때만 보입니다.
            val selectionLayout = settings.selectionBar.only { action -> action != BarAction.Rename || state.selected.size == 1 }
            ActionBar(
                layout = selectionLayout,
                onAction = { action ->
                    when (action) {
                        BarAction.Favorite -> viewModel.toggleFavorite()
                        BarAction.CopyPath -> viewModel.copySelectedPaths { context.copyToClipboard(it) }
                        else -> action.fileAction()?.let { fileAction = it }
                    }
                },
                modifier = Modifier.background(GalleryTheme.colors.surface).padding(vertical = Spacing.base),
                favoritesSelected = state.favoritesSelected,
            )
        }
    }

    fileAction?.let { action ->
        FileActions(
            action = action,
            media = viewModel.selectedMedia(),
            onDone = {
                fileAction = null
                viewModel.clearSelection()
            },
            onDismiss = { fileAction = null },
        )
    }
}

/** 선택 바 동작 중 파일 작업 화면을 여는 동작입니다. */
private fun BarAction.fileAction(): FileAction? = when (this) {
    BarAction.Share -> FileAction.Share
    BarAction.Copy -> FileAction.Copy
    BarAction.Move -> FileAction.Move
    BarAction.Rename -> FileAction.Rename
    BarAction.Delete -> FileAction.Trash
    else -> null
}

/** 격자 위에 한 줄을 다 쓰는 안내 문구입니다. */
private fun LazyGridScope.fullWidthNote(@StringRes text: Int, emphasized: Boolean) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(Spacing.screen),
            style = if (emphasized) GalleryTheme.type.body else GalleryTheme.type.meta,
            color = GalleryTheme.colors.textMuted,
        )
    }
}

/** 앨범 제목 줄입니다. [actions]는 오른쪽 끝에 놓을 버튼들입니다. */
@Composable
private fun AlbumHeader(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(GalleryIcons.Back, onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = GalleryTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = GalleryTheme.type.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** 선택 중 제목 줄입니다. 고른 개수와 '모두 선택'을 보여 줍니다. */
@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = Spacing.screen, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(GalleryIcons.Back, onClick = onClose)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.selection_count, count), style = GalleryTheme.type.title, color = colors.accent)
            Text(stringResource(R.string.selection_hint_tap), style = GalleryTheme.type.meta, color = colors.textMuted)
        }
        Text(
            stringResource(R.string.action_select_all),
            modifier = Modifier.clickable(onClick = onSelectAll).padding(8.dp),
            style = GalleryTheme.type.label,
            color = colors.text,
        )
    }
}

/** 경로를 클립보드에 복사합니다. Android 13부터는 시스템이 복사 알림을 띄우므로 토스트를 생략합니다. */
private fun Context.copyToClipboard(text: String) {
    val clip = ClipData.newPlainText(getString(R.string.action_copy_path), text)
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.paths_copied, Toast.LENGTH_SHORT).show()
}

/** 날짜 머리글 형식입니다. 한국어는 '2025년 3월 1일'처럼 쓰고, 그 밖의 언어는 로캘의 월 이름을 씁니다. */
private fun headerPattern(kind: HeaderKind, korean: Boolean): String = when (kind) {
    HeaderKind.Year -> if (korean) "yyyy년" else "yyyy"
    HeaderKind.Month -> if (korean) "yyyy년 M월" else "LLLL yyyy"
    HeaderKind.Day, HeaderKind.Today, HeaderKind.Yesterday -> if (korean) "yyyy년 M월 d일" else "d MMMM yyyy"
}

@Composable
private fun DateHeader(header: GridItem.Header) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (header.kind) {
        HeaderKind.Today -> stringResource(R.string.group_today)
        HeaderKind.Yesterday -> stringResource(R.string.group_yesterday)
        else -> {
            // 형식기는 로캘과 머리글 종류마다 한 번만 만듭니다.
            val format = remember(locale, header.kind) {
                DateTimeFormatter.ofPattern(headerPattern(header.kind, korean = locale.language == Locale.KOREAN.language), locale)
            }
            header.date.format(format)
        }
    }
    Row(
        Modifier.padding(start = 13.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.base),
    ) {
        Text(label, style = GalleryTheme.type.label, color = GalleryTheme.colors.text)
        Text(formatCount(header.count), style = GalleryTheme.type.meta, color = GalleryTheme.colors.textFaint)
    }
}

/** 선택했을 때 썸네일 둘레에 보이는 강조색 테두리 두께입니다. */
private val SelectedInset = 3.dp
private val TileShape = RoundedCornerShape(4.dp)
private val BadgeShape = RoundedCornerShape(2.dp)
private val BadgeBackground = Color(0x990B0B0C)
private val BadgeContent = Color(0xFFEDEDEF)

/**
 * 격자 칸 하나입니다. 동영상은 재생 시간(모르면 재생 아이콘)을, 즐겨찾기는 하트를 모서리에 표시합니다.
 * 접근성 서비스에는 누르기·길게 누르기 동작을 알려 줍니다. 실제 터치는 격자 전체가 처리합니다.
 */
@Composable
internal fun MediaTile(
    medium: Medium,
    thumbnails: AlbumThumbnails,
    selected: Boolean,
    selecting: Boolean,
    showDuration: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    thumbnailModifier: Modifier = Modifier,
    showFavoriteBadge: Boolean = true,
) {
    val cell = Modifier
        .aspectRatio(1f)
        .background(if (selected) GalleryTheme.colors.accent else Color.Transparent, TileShape)
        .semantics {
            onClick { onTap(); true }
            onLongClick { onHold(); true }
        }
    Box(cell) {
        val inset = if (selected) Modifier.padding(SelectedInset) else Modifier
        val uri = medium.displayUri
        // 맵을 직접 읽으면 어느 칸의 값이 바뀌어도 보이는 칸이 모두 다시 구성되므로, 이 칸의 값이 바뀔 때만 다시 구성한다.
        val preview by remember(thumbnails, uri) { derivedStateOf { thumbnails.previews[uri] } }
        val loadFull by remember(thumbnails, uri) { derivedStateOf { uri in thumbnails.full } }
        MediaThumbnail(
            model = uri,
            modifier = Modifier.fillMaxSize().then(inset).then(thumbnailModifier),
            description = medium.name,
            quickPreview = false,
            bufferedPreview = preview,
            loadFull = loadFull,
            onFullFinished = { thumbnails.finish(uri) },
            indexed = medium.indexedMedia,
        )
        val knownLength = medium.durationMs > 0
        // 길이를 모르는 동영상(서버 동영상)은 설정과 관계없이 동영상임을 표시합니다.
        if (medium.type == MediaType.Video && (showDuration || !knownLength)) {
            val badge = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(BadgeBackground, BadgeShape)
            if (knownLength) {
                Text(
                    formatDuration(medium.durationMs),
                    modifier = badge.padding(horizontal = 4.dp),
                    style = GalleryTheme.type.meta,
                    color = BadgeContent,
                )
            } else {
                Icon(GalleryIcons.Play, contentDescription = null, tint = BadgeContent, modifier = badge.padding(3.dp).size(10.dp))
            }
        }
        if (showFavoriteBadge && medium.isFavorite) {
            Icon(
                GalleryIcons.HeartFilled,
                contentDescription = null,
                tint = FavoriteRed,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp),
            )
        }
        if (selecting || selected) GridSelectionIndicator(selected, Modifier.align(Alignment.TopStart))
    }
}

/** 격자 항목 키입니다. 머리글은 날짜, 항목은 미디어 ID라 서로 겹치지 않습니다. */
private val GridItem.stableKey: Any
    get() = when (this) {
        is GridItem.Header -> date
        is GridItem.Item -> medium.id
    }
