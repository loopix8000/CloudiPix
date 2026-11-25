package app.cloudipix.gallery.feature.media

import app.cloudipix.gallery.R
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.cloudipix.gallery.ui.component.island
import app.cloudipix.gallery.ui.component.islandGap
import app.cloudipix.gallery.ui.component.rememberGridRangeDrag
import app.cloudipix.gallery.ui.component.selectGridRange
import app.cloudipix.gallery.ui.component.shadowed
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.FavoriteRed
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

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
    val state = remember(library, pending) { library.withoutPending(pending) }
    val items = state.items
    val settings = if (state.loaded) state.settings else initialSettings
    val folderSettings = settings.folderMediaSettings[viewModel.bucketId]
    val sorting = settings.sortingFor(viewModel.bucketId)
    val grouping = settings.groupingFor(viewModel.bucketId)
    val colors = GalleryTheme.colors
    val context = LocalContext.current
    val hideActions = rememberFolderHideActions(viewModel::folderHideOptions, viewModel::hideFolders, onBack)

    BackHandler(enabled = state.selecting, onBack = viewModel::clearSelection)

    val overlay = settings.overlayUi
    val gridState = rememberLazyGridState()
    var positionedDirection by rememberSaveable { mutableStateOf<ScrollDirection?>(null) }
    val gap = settings.thumbnailSpacing.gap
    val leftPadding = with(LocalDensity.current) { gap.roundToPx() }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var groupOpen by remember { mutableStateOf(false) }
    val selectionDrag = rememberGridRangeDrag(gridState, leftPadding) { _, to -> viewModel.selectRange(to) }
    VolumeGridScroll(
        gridState,
        enabled = settings.volumeButtonPaging && fileAction == null && !sortOpen && !groupOpen &&
            !selectionDrag.active && hideActions.enabled,
    )
    val openOrToggle: (Medium) -> Unit = { medium ->
        when {
            state.choosingCover -> viewModel.pickCover(medium, onBack)
            state.selecting -> viewModel.toggle(medium.id)
            else -> onOpenMedium(medium)
        }
    }

    val direction = settings.scrollDirection.takeIf { items.isNotEmpty() }
    if (state.loaded && direction != positionedDirection) {
        SideEffect {
            if (direction == ScrollDirection.Up) {
                // 첫 배치부터 끝쪽 행을 보여 주고 이전 화면으로 돌아올 때는 저장한 위치를 사용한다.
                val headerCount = if (state.choosingCover) 1 else 0
                gridState.requestScrollToItem(items.lastIndex + headerCount)
            }
            positionedDirection = direction
        }
    }

    val bar: @Composable () -> Unit = {
        if (state.selecting) {
            SelectionBar(
                count = state.selected.size,
                island = overlay,
                onClose = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
            )
        } else if (state.choosingCover) {
            BrowseBar(
                title = stringResource(R.string.album_choose_cover),
                subtitle = state.title,
                island = overlay,
                onBack = onBack,
            )
        } else {
            BrowseBar(
                title = state.title,
                subtitle = stringResource(
                    R.string.media_summary,
                    formatCount(state.totalCount),
                    formatSize(state.totalSize),
                ),
                island = overlay,
                onBack = onBack,
                menu = {
                    IconAction(
                        GalleryIcons.Search,
                        onClick = onOpenSearch,
                        modifier = Modifier.island(overlay),
                        label = stringResource(R.string.action_search),
                    )
                    Box {
                        OverflowMenu(Modifier.island(overlay)) { dismiss ->
                            MenuEntry(stringResource(R.string.menu_refresh)) { dismiss(); viewModel.refresh() }
                            if (showSyncMenu) MenuEntry(stringResource(R.string.sync_menu)) { dismiss(); onOpenSync() }
                            if (sorting.key != SortKey.FavoriteAdded) {
                                MenuEntry(stringResource(R.string.menu_group_by), value = stringResource(grouping.labelRes)) {
                                    dismiss(); groupOpen = true
                                }
                            }
                            MenuEntry(stringResource(R.string.menu_sort_by), value = stringResource(sorting.key.labelRes)) {
                                dismiss(); sortOpen = true
                            }
                            if (!state.virtual && hideActions.enabled) {
                                MenuEntry(stringResource(R.string.menu_ignore_folder)) {
                                    dismiss()
                                    hideActions.hide(viewModel.hideTarget)
                                }
                            }
                            MenuEntry(stringResource(R.string.menu_settings)) { dismiss(); onOpenSettings() }
                        }
                        GridSortPopup(
                            open = sortOpen,
                            onDismiss = { sortOpen = false },
                            sorting = sorting,
                            onSortKey = viewModel::setSortKey,
                            onSortDescending = viewModel::setSortDescending,
                            folderOnly = folderSettings?.sortingFolderOnly != false,
                            onFolderOnlyChange = viewModel::setSortingFolderOnly,
                            allowFavoriteSort = viewModel.isFavorites,
                        )
                        GridGroupPopup(
                            open = groupOpen,
                            onDismiss = { groupOpen = false },
                            grouping = grouping,
                            onGrouping = viewModel::setGrouping,
                            folderOnly = folderSettings?.groupingFolderOnly != false,
                            onFolderOnlyChange = viewModel::setGroupingFolderOnly,
                        )
                    }
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.galleryListBackground)
            .windowInsetsPadding(
                if (overlay) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                else WindowInsets.safeDrawing
            ),
    ) {
        if (!overlay) bar()

        PullToRefresh(
            refreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            atTop = { !gridState.canScrollBackward },
            overlayBar = bar.takeIf { overlay },
            modifier = Modifier.weight(1f),
        ) { topPadding ->
            LazyVerticalGrid(
                columns = galleryGridCells(settings.mediaColumns, gap),
                state = gridState,
                modifier = Modifier.fillMaxSize().then(gridModifier)
                    .selectGridRange(
                        drag = selectionDrag,
                        allowHoldMovement = state.selecting,
                        onTap = { id -> viewModel.medium(id)?.let(openOrToggle) },
                        onHold = if (state.choosingCover) null else viewModel::beginRange,
                    )
                    .gridPinchZoom(!state.selecting, viewModel::zoomGrid),
                contentPadding = PaddingValues(start = gap, end = gap, top = topPadding),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                if (state.choosingCover && state.loaded) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(if (state.items.isEmpty()) R.string.album_cover_empty else R.string.album_cover_hint),
                            style = GalleryTheme.type.meta, color = colors.textMuted,
                            modifier = Modifier.padding(Spacing.screen),
                        )
                    }
                }
                if (viewModel.isFavorites && state.loaded && items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.favorites_empty),
                            style = GalleryTheme.type.body, color = colors.textMuted,
                            modifier = Modifier.padding(Spacing.screen),
                        )
                    }
                }
                items(
                    count = items.size,
                    key = { index -> items[index].key },
                    span = when (grouping) {
                        Grouping.None -> null
                        else -> { index -> GridItemSpan(if (items[index] is GridItem.Header) maxLineSpan else 1) }
                    },
                    contentType = { index -> items[index] is GridItem.Header },
                ) { index ->
                    when (val item = items[index]) {
                        is GridItem.Header -> GroupHeader(item)
                        is GridItem.Item -> MediaTile(
                            medium = item.medium,
                            selected = item.medium.id in state.selected,
                            selecting = state.selecting,
                            showDuration = settings.showVideoDuration,
                            showFavoriteBadge = !viewModel.isFavorites,
                            onTap = { openOrToggle(item.medium) },
                            onHold = { viewModel.beginRange(item.medium.id) },
                            thumbnailModifier = if (item.medium.type != MediaType.Video && !state.choosingCover && !state.selecting) {
                                photoTransition(item.medium.id)
                            } else Modifier,
                        )
                    }
                }
            }
        }

        if (state.selecting) {
            ActionBar(
                layout = settings.selectionBar.only { action ->
                    when (action) {
                        BarAction.Rename -> state.selected.size == 1
                        else -> true
                    }
                },
                onAction = { action ->
                    when (action) {
                        BarAction.Share -> fileAction = FileAction.Share
                        BarAction.Favorite -> viewModel.toggleFavorite()
                        BarAction.Copy -> fileAction = FileAction.Copy
                        BarAction.Move -> fileAction = FileAction.Move
                        BarAction.Rename -> fileAction = FileAction.Rename
                        BarAction.CopyPath -> viewModel.copySelectedPaths { context.copyToClipboard(it) }
                        BarAction.Delete -> fileAction = FileAction.Trash
                        else -> Unit
                    }
                },
                modifier = Modifier
                    .background(GalleryTheme.colors.surface)
                    .padding(vertical = Spacing.base),
                favoritesSelected = state.favoritesSelected,
            )
        }
    }

    if (fileAction != null) FileActions(
        action = fileAction,
        media = viewModel.selectedMedia(),
        onDone = {
            fileAction = null
            viewModel.clearSelection()
        },
        onDismiss = { fileAction = null },
    )
}

@Composable
private fun BrowseBar(
    title: String,
    subtitle: String,
    island: Boolean,
    onBack: () -> Unit,
    menu: @Composable () -> Unit = {},
) {
    val colors = GalleryTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 14.dp, top = islandGap(island), bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(islandGap(island)),
    ) {
        IconAction(GalleryIcons.Back, onClick = onBack, modifier = Modifier.island(island))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                title,
                style = GalleryTheme.type.title.shadowed(island),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = GalleryTheme.type.meta.shadowed(island), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        menu()
    }
}

@Composable
private fun SelectionBar(count: Int, island: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = Spacing.screen, top = islandGap(island), bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(islandGap(island)),
    ) {
        IconAction(GalleryIcons.Back, onClick = onClose, modifier = Modifier.island(island))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.selection_count, count), style = GalleryTheme.type.title.shadowed(island), color = colors.accent)
            Text(stringResource(R.string.selection_hint_tap), style = GalleryTheme.type.meta.shadowed(island), color = colors.textMuted)
        }
        Text(
            stringResource(R.string.action_select_all),
            style = GalleryTheme.type.label,
            color = colors.text,
            modifier = Modifier.island(island).clickable(onClick = onSelectAll).padding(8.dp),
        )
    }
}

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.action_copy_path), text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.paths_copied, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun GroupHeader(header: GridItem.Header) {
    val locale = LocalConfiguration.current.locales[0]
    val useKoreanDateOrder = locale.language == Locale.KOREAN.language
    val label = when (header.kind) {
        HeaderKind.Today -> stringResource(R.string.group_today)
        HeaderKind.Yesterday -> stringResource(R.string.group_yesterday)
        HeaderKind.Year -> {
            val pattern = if (useKoreanDateOrder) "yyyy년" else "yyyy"
            header.date.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
        HeaderKind.Month -> {
            val pattern = if (useKoreanDateOrder) "yyyy년 M월" else "LLLL yyyy"
            header.date.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
        HeaderKind.Day -> {
            val pattern = if (useKoreanDateOrder) "yyyy년 M월 d일" else "d MMMM yyyy"
            header.date.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
    Row(
        modifier = Modifier.padding(start = 13.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.base),
    ) {
        Text(label, style = GalleryTheme.type.label, color = GalleryTheme.colors.text)
        Text(formatCount(header.count), style = GalleryTheme.type.meta, color = GalleryTheme.colors.textFaint)
    }
}

@Composable
internal fun MediaTile(
    medium: Medium,
    selected: Boolean,
    selecting: Boolean,
    showDuration: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    thumbnailModifier: Modifier = Modifier,
    showFavoriteBadge: Boolean = true,
) {
    val colors = GalleryTheme.colors
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(if (selected) colors.accent else Color.Transparent, RoundedCornerShape(4.dp))
            .semantics {
                onClick { onTap(); true }
                onLongClick { onHold(); true }
            },
    ) {
        MediaThumbnail(
            model = medium.displayUri,
            description = medium.name,
            modifier = Modifier.fillMaxSize().then(if (selected) Modifier.padding(SELECTION_BORDER) else Modifier)
                .then(thumbnailModifier),
        )
        if (medium.type == MediaType.Video && (showDuration || medium.durationMs <= 0)) {
            val badge = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .background(Color(0x990B0B0C), RoundedCornerShape(2.dp))
            if (medium.durationMs > 0) {
                Text(
                    formatDuration(medium.durationMs),
                    style = GalleryTheme.type.meta,
                    color = Color(0xFFEDEDEF),
                    modifier = badge.padding(horizontal = 4.dp),
                )
            } else {
                // 서버 동영상은 길이를 미리 알 수 없어 동영상임만 표시한다.
                Icon(
                    GalleryIcons.Play,
                    contentDescription = null,
                    tint = Color(0xFFEDEDEF),
                    modifier = badge.padding(3.dp).size(10.dp),
                )
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

private val SELECTION_BORDER = 3.dp

private val GridItem.key: Any
    get() = when (this) {
        is GridItem.Header -> date
        is GridItem.Item -> medium.id
    }
