package app.cloudipix.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.feature.files.FileAction
import app.cloudipix.gallery.feature.files.FileActions
import app.cloudipix.gallery.feature.search.SearchViewModel
import app.cloudipix.gallery.feature.trash.TrashViewModel
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.MediaThumbnail
import app.cloudipix.gallery.ui.component.VolumeGridScroll
import app.cloudipix.gallery.ui.component.galleryGridCells
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.GalleryTheme

@Composable
internal fun PageFrame(color: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = color, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
    }
}

@Composable
internal fun PageHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(GalleryIcons.Back, stringResource(R.string.cloudipix_back)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium)
        }
        actions()
    }
    HorizontalDivider()
}

private const val SEARCH_OPEN_MS = 320
private const val SEARCH_CLOSE_MS = 240
private val searchRevealEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 검색 아이콘과 검색 입력란의 배치 좌표입니다. 펼침 애니메이션의 시작 위치를 구하는 데만 쓰므로 상태로 추적하지 않습니다. */
private class SearchRevealAnchor {
    var icon: LayoutCoordinates? = null
    var field: LayoutCoordinates? = null

    /** 입력란 기준으로 본 검색 아이콘 자리의 정사각형입니다. 아직 배치 전이면 `null`입니다. */
    fun iconBoundsInField(): Rect? {
        val from = icon?.takeIf { it.isAttached } ?: return null
        val to = field?.takeIf { it.isAttached } ?: return null
        val bounds = to.localBoundingBoxOf(from, clipBounds = false)
        val side = minOf(bounds.width, bounds.height)
        return Rect(center = bounds.center, radius = side / 2f)
    }
}

/**
 * 검색 중이면 평소 제목 줄 위에 검색 입력란을 펼쳐 보여 줍니다.
 * 입력란은 검색 아이콘 자리의 원에서 시작해 제목 줄 너비의 알약 모양으로 늘어나고, 닫으면 같은 길로 접힙니다.
 * 평소 제목 줄은 그대로 두고 흐리게만 하므로 높이가 바뀌지 않아 아래 화면이 움직이지 않습니다.
 * [header]는 검색 아이콘에 붙일 수식자를 받습니다. [bottomPadding]은 평소 제목 줄의 아래 여백과 같게 줍니다.
 */
@Composable
internal fun SearchableHeader(
    search: SearchViewModel,
    bottomPadding: Dp,
    placeholder: String = stringResource(R.string.search_placeholder),
    onSearchBarBottom: (Float) -> Unit = {},
    header: @Composable (searchAnchor: Modifier) -> Unit,
) {
    val activeState = search.active.collectAsStateWithLifecycle()
    val active = activeState.value
    // 진행도는 그리기 단계에서만 읽어 애니메이션 중에 다시 구성하지 않습니다.
    val progress = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) SEARCH_OPEN_MS else SEARCH_CLOSE_MS, easing = searchRevealEasing),
        label = "검색 입력란 펼침",
    )
    val barVisible by remember { derivedStateOf { activeState.value || progress.value > 0f } }
    val anchor = remember { SearchRevealAnchor() }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { onSearchBarBottom(it.positionInWindow().y + it.size.height) }) {
        Box(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = 1f - (progress.value * 2f).coerceAtMost(1f) }
                .then(if (active) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            header(Modifier.onGloballyPositioned { anchor.icon = it })
        }
        // 검색 중에는 흐려진 제목 줄의 탭·메뉴가 눌리지 않게 막습니다.
        if (active) Box(Modifier.matchParentSize().pointerInput(Unit) {})
        if (barVisible) {
            SearchField(
                search = search,
                placeholder = placeholder,
                active = active,
                progress = progress,
                anchor = anchor,
                modifier = Modifier.align(Alignment.Center).padding(start = 12.dp, end = 12.dp, bottom = bottomPadding),
            )
        }
    }
}

/**
 * 알약 모양의 검색 입력란입니다. 안쪽 왼쪽에 뒤로 가기, 오른쪽에 지우기 버튼이 있습니다.
 * 열 때마다 입력란에 포커스를 주어 키보드를 띄우고, 사진을 보고 돌아왔을 때는 다시 띄우지 않습니다.
 */
@Composable
private fun SearchField(
    search: SearchViewModel,
    placeholder: String,
    active: Boolean,
    progress: State<Float>,
    anchor: SearchRevealAnchor,
    modifier: Modifier,
) {
    val colors = GalleryTheme.colors
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    // 입력 상태는 화면에 바로 반영되도록 여기서 들고, 검색은 뷰모델에 맡깁니다.
    var text by rememberSaveable { mutableStateOf(search.query.value) }
    val update = { value: String -> text = value; search.setQuery(value) }
    var focusPending by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (active) {
            if (focusPending) {
                requester.requestFocus()
                focusPending = false
            }
        } else {
            // 접히는 동안 키보드부터 내리고, 다음에 열 때 빈 입력란에서 다시 시작합니다.
            focus.clearFocus()
            text = ""
            focusPending = true
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = GalleryTheme.hitTarget)
            .onGloballyPositioned { anchor.field = it }
            .graphicsLayer {
                clip = true
                shape = SearchRevealShape(progress.value, anchor.iconBoundsInField())
                // 펼치는 앞부분 동안 입력란 바탕이 서서히 나타나고, 접을 때는 끝부분에서 서서히 사라집니다.
                alpha = (progress.value / 0.5f).coerceIn(0f, 1f)
            }
            .background(colors.settingsControl)
            // 입력란이 거의 다 펼쳐진 뒤에 내용이 드러나게 합니다.
            .graphicsLayer { alpha = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(GalleryIcons.Back, onClick = search::close, label = stringResource(R.string.cloudipix_back))
        val textStyle = GalleryTheme.type.body
        BasicTextField(
            value = text,
            onValueChange = update,
            modifier = Modifier.weight(1f).focusRequester(requester),
            textStyle = textStyle.copy(color = colors.text),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        if (text.isNotEmpty()) {
            IconAction(GalleryIcons.Close, onClick = { update(""); requester.requestFocus() }, label = stringResource(R.string.action_reset))
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

/**
 * 검색 아이콘 자리의 원 [start]에서 입력란 전체의 알약 모양까지 [progress]만큼 펼친 모양입니다.
 * 아이콘 위치를 아직 모르면 입력란 끝쪽의 원에서 시작합니다.
 */
private class SearchRevealShape(private val progress: Float, private val start: Rect?) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = Rect(Offset.Zero, size)
        val from = start ?: if (layoutDirection == LayoutDirection.Ltr) {
            Rect(size.width - size.height, 0f, size.width, size.height)
        } else {
            Rect(0f, 0f, size.height, size.height)
        }
        val bounds = lerp(from, full, progress)
        val radius = minOf(bounds.width, bounds.height) / 2f
        return Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
    }
}


/**
 * 검색어를 입력해 결과를 받은 뒤에만 참입니다.
 * 그 전에는 원래 화면을 그대로 두어 입력 중에도 보던 항목을 계속 쓸 수 있게 합니다.
 */
@Composable
internal fun searchResultsVisible(search: SearchViewModel): Boolean {
    val active by search.active.collectAsStateWithLifecycle()
    val query by search.query.collectAsStateWithLifecycle()
    val results by search.results.collectAsStateWithLifecycle()
    return active && query.isNotBlank() && results.term.isNotEmpty()
}

/** 검색 결과 개수와 결과 격자입니다. 결과를 누르면 키보드를 내리고 엽니다. */
@Composable
internal fun SearchResults(
    search: SearchViewModel,
    settings: GallerySettings,
    onOpenMedium: (Medium) -> Unit,
    modifier: Modifier = Modifier,
    photoTransition: @Composable (Long) -> Modifier = { Modifier },
    gridModifier: Modifier = Modifier,
) {
    val colors = GalleryTheme.colors
    val results by search.results.collectAsStateWithLifecycle()
    val removing by search.pendingRemovals.collectAsStateWithLifecycle()
    val visible = remember(results, removing) { results.items.filterNot { it.id in removing } }
    val focus = LocalFocusManager.current
    Column(modifier) {
        Text(
            if (visible.isEmpty()) stringResource(R.string.search_no_matches)
            else stringResource(R.string.search_results, formatCount(visible.size)),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = GalleryTheme.type.meta,
            color = colors.textMuted,
        )
        MediaTiles(
            visible,
            settings,
            Modifier.weight(1f).fillMaxWidth().then(gridModifier),
            onSelect = { focus.clearFocus(); onOpenMedium(it) },
            transition = photoTransition,
        )
    }
}

@Composable
internal fun TrashPage(onBack: () -> Unit, model: TrashViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf<FileAction?>(null) }
    val size = remember(state.items) { state.items.sumOf { it.size.coerceAtLeast(0L) } }
    val subtitle = stringResource(R.string.cloudipix_trash_summary, formatCount(state.items.size), formatSize(size))
    BackHandler(state.selecting && request == null) { model.clearSelection() }
    PageFrame {
        PageHeader(
            title = if (state.selecting) stringResource(R.string.selection_count, state.selected.size)
            else stringResource(R.string.trash_title),
            subtitle = subtitle,
            onBack = if (state.selecting) model::clearSelection else onBack,
        ) {
            if (state.items.isNotEmpty()) {
                TextButton(onClick = model::selectAll, enabled = state.selected.size < state.items.size) {
                    Text(stringResource(R.string.action_select_all))
                }
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.trash_empty)) }
        } else {
            Column(Modifier.fillMaxSize()) {
                MediaTiles(state.items, state.settings, Modifier.weight(1f).fillMaxWidth(), state.selected, onSelect = { model.toggle(it.id) })
                if (state.selecting) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { request = FileAction.Restore }) {
                            Icon(GalleryIcons.Undo, stringResource(R.string.action_restore))
                        }
                        IconButton(onClick = { request = FileAction.Delete }) {
                            Icon(GalleryIcons.Trash, stringResource(R.string.action_delete_forever))
                        }
                    }
                }
            }
        }
    }
    FileActions(request, model.selectedMedia(), onDone = { model.clearSelection(); request = null }, onDismiss = { request = null })
}

@Composable
private fun MediaTiles(
    items: List<Medium>,
    config: GallerySettings,
    modifier: Modifier,
    selection: Set<Long> = emptySet(),
    onSelect: (Medium) -> Unit,
    transition: @Composable (Long) -> Modifier = { Modifier },
) {
    val gap = config.thumbnailSpacing.gap
    val scroll = rememberLazyGridState()
    VolumeGridScroll(scroll, config.volumeButtonPaging)
    LazyVerticalGrid(
        columns = galleryGridCells(config.mediaColumns, gap),
        state = scroll,
        reverseLayout = config.scrollDirection == ScrollDirection.Up,
        modifier = modifier,
        contentPadding = PaddingValues(gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        items(items, key = Medium::id) { item ->
            val selected = item.id in selection
            Box(Modifier.clickable { onSelect(item) }.semantics { this.selected = selected }) {
                MediaThumbnail(item.displayUri, transition(item.id), config.cropThumbnails, description = item.name)
                if (selected) {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)))
                    Icon(GalleryIcons.Check, null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (config.showVideoDuration && item.type == MediaType.Video && item.durationMs > 0) {
                    Text(
                        formatDuration(item.durationMs),
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
