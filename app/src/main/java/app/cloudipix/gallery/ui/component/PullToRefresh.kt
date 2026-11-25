package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    atTop: () -> Boolean,
    modifier: Modifier = Modifier,
    overlayBar: (@Composable () -> Unit)? = null,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val state = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
    val currentRefreshing by rememberUpdatedState(refreshing)
    val currentRefresh by rememberUpdatedState(onRefresh)
    val currentAtTop by rememberUpdatedState(atTop)
    val pull = remember(state, thresholdPx) {
        PullConnection(state, scope, thresholdPx, { currentRefreshing }, { currentRefresh() })
    }

    LaunchedEffect(refreshing) {
        if (refreshing) state.animateToThreshold() else state.animateToHidden()
    }

    val panel = modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pull.armed = currentAtTop()
        }
    }.nestedScroll(pull)
    val indicator: @Composable (Modifier) -> Unit = { alignment ->
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            containerColor = GalleryTheme.colors.surface,
            color = GalleryTheme.colors.accent,
            threshold = PULL_THRESHOLD,
            modifier = alignment,
        )
    }
    if (overlayBar == null) {
        Box(panel) {
            content(0.dp)
            indicator(Modifier.align(Alignment.TopCenter))
        }
    } else {
        SubcomposeLayout(panel) { constraints ->
            // 툴바 높이와 그리드 여백을 같은 배치에서 확정한다.
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val bar = subcompose(GridSlot.Bar) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                    overlayBar()
                }
            }.single().measure(loose)
            val topPadding = bar.height.toDp()
            val grid = subcompose(GridSlot.Content) { Box { content(topPadding) } }
                .single().measure(constraints)
            val refresh = subcompose(GridSlot.Refresh) { indicator(Modifier) }.single().measure(loose)
            layout(grid.width, grid.height) {
                grid.place(0, 0)
                bar.place(0, 0)
                refresh.place((grid.width - refresh.width) / 2, bar.height)
            }
        }
    }
}

private enum class GridSlot { Bar, Content, Refresh }

@OptIn(ExperimentalMaterial3Api::class)
private class PullConnection(
    private val state: PullToRefreshState,
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val refreshing: () -> Boolean,
    private val onRefresh: () -> Unit,
) : NestedScrollConnection {

    var armed = false
    private var pulled = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (pulled <= 0f || available.y >= 0f) return Offset.Zero
        val taken = maxOf(available.y, -pulled / DRAG_RATE)
        moveTo(pulled + taken * DRAG_RATE)
        return Offset(0f, taken)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!armed || source != NestedScrollSource.UserInput || available.y <= 0f || refreshing()) return Offset.Zero
        moveTo(pulled + available.y * DRAG_RATE)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        if (pulled <= 0f) return Velocity.Zero
        val reached = pulled >= thresholdPx
        pulled = 0f
        if (reached) {
            onRefresh()
            state.animateToThreshold()
        } else {
            state.animateToHidden()
        }
        return available
    }

    private fun moveTo(distance: Float) {
        pulled = distance.coerceIn(0f, thresholdPx * MAX_STRETCH)
        scope.launch { state.snapTo(pulled / thresholdPx) }
    }
}

private val PULL_THRESHOLD = 80.dp
private const val DRAG_RATE = 0.5f
private const val MAX_STRETCH = 1.5f
