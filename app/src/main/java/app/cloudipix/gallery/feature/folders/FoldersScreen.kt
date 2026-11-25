package app.cloudipix.gallery.feature.folders

import android.widget.Toast
import app.cloudipix.gallery.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import app.cloudipix.gallery.feature.files.FileAction
import app.cloudipix.gallery.feature.files.FileActions
import app.cloudipix.gallery.core.model.Medium
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.IOException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderTab
import app.cloudipix.gallery.core.model.containsFolder
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.ui.component.CloudFolderIcon
import app.cloudipix.gallery.core.sync.AlbumSyncState
import app.cloudipix.gallery.ui.component.FolderCaption
import app.cloudipix.gallery.ui.component.folderCaptionOverlay
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.MediaThumbnail
import app.cloudipix.gallery.ui.component.MenuEntry
import app.cloudipix.gallery.ui.component.GridSortPopup
import app.cloudipix.gallery.ui.component.GridSelectionIndicator
import app.cloudipix.gallery.ui.component.galleryGridCells
import app.cloudipix.gallery.ui.component.gridPinchZoom
import app.cloudipix.gallery.ui.component.OverflowMenu
import app.cloudipix.gallery.ui.component.PullToRefresh
import app.cloudipix.gallery.ui.component.VolumeGridScroll
import app.cloudipix.gallery.feature.security.LocalGalleryAccess
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.FavoriteRed
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

@Composable
fun FoldersScreen(
    onOpenFolder: (Folder) -> Unit,
    onChooseCover: (Folder) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    requestLocalAccess: (() -> Unit)? = null,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val access = LocalGalleryAccess.current
    val state = viewModel.state.collectAsStateWithLifecycle().value ?: return
    val showingHiddenFolders by viewModel.showingHiddenFolders.collectAsState()
    LifecycleStartEffect(viewModel, lifecycleOwner = ProcessLifecycleOwner.get()) {
        onStopOrDispose { viewModel.stopShowingHiddenFolders() }
    }
    val syncStates by viewModel.albumSyncStates.collectAsStateWithLifecycle()
    val colors = GalleryTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileActions = rememberFolderFileActions(viewModel)
    val hideActions = rememberFolderHideActions(viewModel::folderHideOptions, viewModel::hideFolders, viewModel::clearSelection)
    var unhiding by remember { mutableStateOf(false) }
    var trashItems by remember { mutableStateOf(emptyList<Medium>()) }
    if (trashItems.isNotEmpty()) {
        FileActions(FileAction.Trash, trashItems, onDone = {
            trashItems = emptyList()
            viewModel.clearSelection()
        }, onDismiss = { trashItems = emptyList() })
    }
    BackHandler(enabled = state.selecting, onBack = viewModel::clearSelection)
    val recentName = stringResource(R.string.folder_recent)
    val favoritesName = stringResource(R.string.folder_favorites)
    val customTabs = state.settings.folderTabs
    val homeName = state.settings.homeTabName ?: stringResource(R.string.tab_home)
    val homePosition = state.settings.homeTabPosition.coerceIn(0, customTabs.size)
    val tabs = remember(customTabs, homeName, homePosition) {
        customTabs.toMutableList().apply { add(homePosition, FolderTab.Home.copy(name = homeName)) }
    }
    val pager = rememberPagerState(initialPage = homePosition) { tabs.size }
    val currentPage = pager.currentPage.coerceIn(tabs.indices)
    // 메인 탭이 아니면 뒤로가기로 메인 탭에 돌아가고, 메인 탭에서 한 번 더 누르면 앱을 닫는다.
    BackHandler(enabled = tabs.size > 1 && !state.selecting && pager.targetPage != homePosition) {
        scope.launch { pager.animateScrollToPage(homePosition) }
    }
    val grids = tabs.map { tab -> key(tab.id) { rememberFolderViewport() } }
    val drag = rememberFolderDrag(grids[currentPage], pager, viewModel::moveFolder)
    val held = drag.active
    val previewTab = if (held != null && held.sourceTab != tabs[currentPage].id) tabs[currentPage].id else null
    val labeledFolders = remember(state.folders, recentName, favoritesName, showingHiddenFolders) {
        state.folders.mapNotNull {
            when {
                it.isHidden && !showingHiddenFolders -> null
                it.isRecent -> it.copy(name = recentName)
                it.isFavorites -> it.copy(name = favoritesName)
                else -> it
            }
        }
    }
    val pageFolders = remember(labeledFolders, tabs, held, previewTab) {
        val assigned = customTabs.flatMapTo(mutableSetOf()) { it.folderIds }
        tabs.map { tab ->
            if (tab.id == FolderTab.Home.id) {
                labeledFolders.filter {
                    it.isRecent || !assigned.containsFolder(it.bucketId, it.localBucketId) ||
                        (previewTab == tab.id && it.bucketId == held?.id)
                }
            } else {
                labeledFolders.filter {
                    !it.isRecent && (tab.folderIds.containsFolder(it.bucketId, it.localBucketId) ||
                        (previewTab == tab.id && it.bucketId == held?.id))
                }
            }
        }
    }
    var manageTabs by rememberSaveable { mutableStateOf(false) }
    var pickTab by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val moveFailed = { Toast.makeText(context, R.string.metadata_save_failed, Toast.LENGTH_SHORT).show() }
    if (manageTabs) {
        FolderTabManager(tabs, viewModel, onDismiss = { manageTabs = false })
    } else if (pickTab && state.selecting) {
        val source = tabs[currentPage].id
        FolderTabDestinations(tabs, source, enabled = !moving, onSelect = { destination ->
            if (!moving) {
                moving = true
                scope.launch {
                    try {
                        if (viewModel.moveSelectedToTab(source, destination)) {
                            pickTab = false
                            pager.scrollToPage(tabs.indexOfFirst { it.id == destination })
                        } else moveFailed()
                    } catch (_: IOException) {
                        moveFailed()
                    } finally {
                        moving = false
                    }
                }
            }
        }, onManageTabs = { manageTabs = true }, onDismiss = { if (!moving) pickTab = false })
    }
    var sortOpen by remember { mutableStateOf(false) }
    VolumeGridScroll(
        grids[currentPage].scroll, height = { grids[currentPage].view?.height ?: 0 },
        backward = grids[currentPage].canScrollBackward, forward = grids[currentPage].canScrollForward,
        enabled = state.settings.volumeButtonPaging && held == null && !sortOpen && !manageTabs &&
            !(pickTab && state.selecting) && trashItems.isEmpty() && fileActions.enabled && hideActions.enabled,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.galleryListBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (state.selecting) {
            FolderSelectionHeader(
                count = state.selected.size,
                onClose = viewModel::clearSelection,
                onSelectAll = { viewModel.selectAll(pageFolders[currentPage]) },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderTabStrip(
                    tabs = tabs,
                    selected = currentPage,
                    // 탭이 하나뿐이면 누를 곳이 없습니다.
                    onSelect = { page -> if (tabs.size > 1) scope.launch { pager.animateScrollToPage(page) } },
                    overlay = false,
                    modifier = Modifier.weight(1f),
                )
                IconAction(GalleryIcons.Search, onClick = onOpenSearch, label = stringResource(R.string.action_search))
                Box {
                    OverflowMenu { close ->
                        MenuEntry(stringResource(R.string.tabs_manage)) { close(); manageTabs = true }
                        MenuEntry(stringResource(R.string.menu_refresh)) { close(); viewModel.refresh() }
                        val sortLabel = if (state.settings.manualFolderSort) R.string.folder_sort_manual else state.settings.sorting.key.labelRes
                        MenuEntry(stringResource(R.string.menu_sort_by), value = stringResource(sortLabel)) { close(); sortOpen = true }
                        // 보기 필터는 메뉴를 닫지 않고 눌러 가며 바꿉니다.
                        MenuEntry(stringResource(R.string.menu_show), value = stringResource(state.settings.mediaFilter.labelRes)) {
                            viewModel.cycleFilter()
                        }
                        val hiddenToggle = if (showingHiddenFolders) R.string.menu_hide_ignored_folders else R.string.menu_show_ignored_folders
                        MenuEntry(stringResource(hiddenToggle)) { close(); viewModel.toggleHiddenFolders() }
                        MenuEntry(stringResource(R.string.trash_title)) { close(); onOpenTrash() }
                        requestLocalAccess?.let { request ->
                            MenuEntry(stringResource(R.string.remote_local_permission)) { close(); request() }
                        }
                        MenuEntry(stringResource(R.string.menu_settings)) { close(); onOpenSettings() }
                    }
                    GridSortPopup(
                        open = sortOpen,
                        onDismiss = { sortOpen = false },
                        sorting = state.settings.sorting,
                        onSortKey = viewModel::setSortKey,
                        onSortDescending = viewModel::setSortDescending,
                        manualSort = state.settings.manualFolderSort,
                        onManualSort = viewModel::useManualSort,
                    )
                }
            }
        }

        PullToRefresh(
            refreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            atTop = { !grids[currentPage].canScrollBackward },
            modifier = Modifier.weight(1f),
        ) { topPadding ->
            // 드래그를 페이지 바깥에서 받아 출발 탭이 화면에서 사라져도 손가락을 계속 추적한다.
            Box(Modifier.fillMaxSize().clipToBounds().reorderFolders(
                drag = drag, grid = grids[currentPage],
                currentTab = { tabs[pager.currentPage.coerceIn(tabs.indices)].id },
                selecting = state.selecting,
                onSelect = { viewModel.selectRange(pageFolders[currentPage], it) },
                onStart = viewModel::beginFolderDrag, onMove = viewModel::moveFolder,
                onFinish = { id, from, to -> viewModel.finishFolderDrag(id, from, to, moveFailed) },
            )) {
                if (tabs.size == 1) {
                    FolderGrid(pageFolders[0], grids[0], topPadding, state, viewModel, held?.id, syncStates, onOpenFolder)
                } else HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    key = { tabs[it].id },
                    userScrollEnabled = !state.selecting && held == null,
                ) { page ->
                    FolderGrid(pageFolders[page], grids[page], topPadding, state, viewModel, held?.id, syncStates, onOpenFolder)
                }
                if (held != null) {
                    labeledFolders.firstOrNull { it.bucketId == held.id }?.let { folder ->
                        FolderCard(
                            folder, state.settings, locked = folder.isLocked && !access.hasFolderAccess(folder.bucketId),
                            syncState = syncStates[folder.bucketId],
                            modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                                .width(with(LocalDensity.current) { held.width.toDp() })
                                .graphicsLayer {
                                    translationX = drag.point.x - held.grab.x
                                    translationY = drag.point.y - held.grab.y
                                },
                        )
                    }
                }
            }
        }

        if (state.selecting) {
            val hiddenSelection = state.folders.mapNotNullTo(mutableSetOf()) { folder ->
                folder.bucketId.takeIf { folder.isHidden && it in state.selected }
            }
            val canEditSelection = fileActions.enabled && !unhiding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(vertical = Spacing.base),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.selectionHasFavorites) {
                    IconAction(
                        GalleryIcons.Pin,
                        onClick = viewModel::pinSelected,
                        label = stringResource(if (state.selectionPinned) R.string.action_unpin else R.string.action_pin),
                        enabled = canEditSelection,
                    )
                }
                if (hiddenSelection.isNotEmpty()) {
                    IconAction(
                        GalleryIcons.Show,
                        label = stringResource(R.string.action_unhide_folder),
                        enabled = canEditSelection && hideActions.enabled,
                        onClick = {
                            if (!unhiding) {
                                unhiding = true
                                scope.launch {
                                    try {
                                        viewModel.unhideFolders(hiddenSelection)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        Toast.makeText(context, R.string.folder_unhide_failed, Toast.LENGTH_SHORT).show()
                                    } finally {
                                        unhiding = false
                                    }
                                }
                            }
                        },
                    )
                } else if (!state.selectionHasFavorites) {
                    IconAction(
                        GalleryIcons.Hide,
                        onClick = {
                            val selected = state.folders.filter { it.bucketId in state.selected && !it.isVirtual }
                            hideActions.hide(selected.associate { it.bucketId to it.name })
                        },
                        label = stringResource(R.string.action_ignore),
                        enabled = canEditSelection && hideActions.enabled,
                    )
                }
                if (!state.selectionHasFavorites && !state.selectionHasLocked) {
                    IconAction(GalleryIcons.Trash, label = stringResource(R.string.action_delete), enabled = canEditSelection, onClick = {
                        scope.launch { trashItems = viewModel.selectedMedia() }
                    })
                }
                state.selectedFolder?.let { folder ->
                    IconAction(
                        icon = if (folder.isLocked) GalleryIcons.Unlock else GalleryIcons.Lock,
                        label = stringResource(if (folder.isLocked) R.string.remove_folder_lock else R.string.enable_folder_lock),
                        enabled = canEditSelection,
                        onClick = {
                            viewModel.clearSelection()
                            access.changeFolderLock(folder.bucketId, !folder.isLocked)
                        },
                    )
                }
                if (canEditSelection) {
                    OverflowMenu(label = stringResource(R.string.album_options), shape = RectangleShape) { dismiss ->
                        MenuEntry(stringResource(R.string.tab_move), icon = GalleryIcons.Move) {
                            dismiss(); pickTab = true
                        }
                        state.selectedFolder?.takeUnless { it.isLocked }?.let { folder ->
                            if (!folder.isRemote) {
                                MenuEntry(stringResource(R.string.album_rename), icon = GalleryIcons.Rename) {
                                    dismiss(); fileActions.rename(folder)
                                }
                                MenuEntry(stringResource(R.string.album_move), icon = GalleryIcons.Move) {
                                    dismiss(); fileActions.move(folder)
                                }
                            }
                            MenuEntry(stringResource(R.string.album_choose_cover), icon = GalleryIcons.Photo) {
                                dismiss(); viewModel.clearSelection(); onChooseCover(folder)
                            }
                            if (folder.manualCover != null) {
                                MenuEntry(stringResource(R.string.album_automatic_cover), icon = GalleryIcons.Reset) {
                                    dismiss(); viewModel.resetCover(folder)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(
    folders: List<Folder>,
    gridState: FolderViewport,
    topPadding: Dp,
    state: FoldersUiState,
    viewModel: FoldersViewModel,
    draggedId: Long?,
    syncStates: Map<Long, AlbumSyncState>,
    onOpenFolder: (Folder) -> Unit,
) {
    val access = LocalGalleryAccess.current
    val settings = state.settings
    val gap = settings.thumbnailSpacing.gap
    val below = settings.detailsPlacement == DetailsPlacement.Below
    val captioned = settings.showFolderNames || settings.showFolderCount || settings.showFolderSize
    val cells = galleryGridCells(settings.folderColumns, gap)
    val style = folderTileStyle(settings.folderColumns >= 4)
    val locked by remember(folders, access) {
        androidx.compose.runtime.derivedStateOf {
            folders.mapNotNullTo(hashSetOf()) { folder ->
                folder.bucketId.takeIf { folder.isLocked && !access.hasFolderAccess(it) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val gapPx = with(density) { gap.roundToPx() }
        val width = constraints.maxWidth
        val widths = remember(cells, width, density, gapPx) {
            with(density) {
                with(cells) { calculateCrossAxisCellSizes((width - 2 * gapPx).coerceAtLeast(1), gapPx) }
            }
        }
        RecyclingFolderGrid(
            gridState, folders, FolderBinding(style, settings, locked, state.selected, syncStates, draggedId,
                (widths.maxOrNull() ?: 1).coerceAtLeast(1)), widths.size, gapPx,
            with(density) { (if (captioned && below) gap + 14.dp else gap).roundToPx() },
            with(density) { topPadding.roundToPx() },
            onClick = { if (state.selecting) viewModel.toggle(it.bucketId) else onOpenFolder(it) },
            onHold = { viewModel.selectRange(folders, it.bucketId) },
            modifier = Modifier.fillMaxSize().gridPinchZoom(!state.selecting, viewModel::zoomGrid),
        )
        if (folders.isEmpty() && settings.folderTabs.isNotEmpty()) {
            Text(stringResource(R.string.tab_empty), style = GalleryTheme.type.body, color = GalleryTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center).padding(top = topPadding))
        }
    }
}

@Composable
internal fun FolderCard(
    folder: Folder,
    settings: GallerySettings,
    locked: Boolean,
    syncState: AlbumSyncState?,
    modifier: Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    coverPixels: Int? = null,
    captionMeasurer: TextMeasurer? = null,
) {
    val name = folder.name.takeIf { settings.showFolderNames }
    val count = folder.itemCount.takeIf { settings.showFolderCount }
    val size = folder.size.takeIf { settings.showFolderSize }
    val compact = settings.folderColumns >= 4
    val cardModifier = if (folder.isFavorites && name == null) {
        modifier.semantics { contentDescription = folder.name }
    } else modifier

    if (settings.detailsPlacement == DetailsPlacement.Overlay) {
        FolderArtwork(folder, settings, locked, syncState, selected, selecting, coverPixels,
            cardModifier.folderCaptionOverlay(name, count, size, compact, captionMeasurer ?: rememberTextMeasurer()))
    } else {
        Column(cardModifier, verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
            FolderArtwork(folder, settings, locked, syncState, selected, selecting, coverPixels)
            FolderCaption(name, count, size, compact = compact)
        }
    }
}

@Composable
private fun FolderArtwork(
    folder: Folder,
    settings: GallerySettings,
    locked: Boolean,
    syncState: AlbumSyncState?,
    selected: Boolean,
    selecting: Boolean,
    coverPixels: Int?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        val emptyFavorites = folder.isFavorites && folder.itemCount == 0
        MediaThumbnail(
            model = if (emptyFavorites) null else folder.coverUri,
            modifier = Modifier.fillMaxWidth(),
            blurred = locked,
            coverPixels = coverPixels,
        )
        FolderBadges(folder, settings, locked, syncState, selected, selecting)
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.FolderBadges(
    folder: Folder, settings: GallerySettings, locked: Boolean, syncState: AlbumSyncState?,
    selected: Boolean, selecting: Boolean,
) {
        val emptyFavorites = folder.isFavorites && folder.itemCount == 0
        if (folder.isFavorites) {
            Icon(
                GalleryIcons.HeartFilled, contentDescription = null, tint = FavoriteRed,
                modifier = if (emptyFavorites) Modifier.align(Alignment.Center).size(32.dp)
                else Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp),
            )
        }
        if (locked) {
            Icon(
                GalleryIcons.Lock, contentDescription = stringResource(R.string.folder_locked),
                tint = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.align(Alignment.Center).size(22.dp),
            )
        }
        val offset = if (selecting) Modifier.padding(start = 22.dp) else Modifier
        CloudFolderIcon(
            settings = settings,
            isRemote = folder.isRemote,
            syncState = syncState,
            modifier = Modifier.align(Alignment.TopStart).then(offset),
        )
        if (folder.isPinned || folder.isHidden) {
            Column(Modifier.align(Alignment.TopEnd).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (folder.isPinned) FolderStatusBadge(GalleryIcons.Pin, stringResource(R.string.pinned))
                if (folder.isHidden) FolderStatusBadge(GalleryIcons.Hide, stringResource(R.string.ignored_title))
            }
        }
        if (selecting) GridSelectionIndicator(selected, Modifier.align(Alignment.TopStart))
}

@Composable
private fun FolderStatusBadge(icon: ImageVector, label: String) {
    Icon(
        icon,
        contentDescription = label,
        tint = Color(0xFFEDEDEF),
        modifier = Modifier
            .background(Color(0x8C000000), CircleShape)
            .padding(4.dp)
            .size(12.dp),
    )
}

@Composable
private fun Modifier.selectable(selected: Boolean, selecting: Boolean, onClick: () -> Unit, onHold: (() -> Unit)?): Modifier {
    val accent = GalleryTheme.colors.accent
    return this
        .clickable(onClick = onClick)
        .semantics {
            if (selecting) this.selected = selected
            if (onHold != null) onLongClick { onHold(); true }
        }
        .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(4.dp)).padding(3.dp) else Modifier)
}

/** 폴더를 고르는 중의 제목 줄입니다. 고른 개수와 '모두 선택'을 보여 줍니다. */
@Composable
private fun FolderSelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = Spacing.screen, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(GalleryIcons.Close, onClick = onClose)
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
