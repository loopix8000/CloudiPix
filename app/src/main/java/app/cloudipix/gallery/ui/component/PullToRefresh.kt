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

/** 이만큼 당긴 뒤 놓으면 새로 고칩니다. */
private val RefreshTrigger = 80.dp

/** 손가락이 움직인 거리 대비 표시기가 내려오는 비율입니다. */
private const val PULL_RESISTANCE = 0.5f

/** 표시기를 기준 거리의 몇 배까지 끌어내릴 수 있는지입니다. */
private const val MAX_OVERPULL = 1.5f

/**
 * 목록 맨 위에서 아래로 당겨 새로 고칩니다. 당기기는 손가락을 댈 때 목록이 맨 위([atTop])였던
 * 경우에만 시작하므로, 위로 스크롤하다 맨 위에 닿은 관성으로는 시작하지 않습니다.
 *
 * [overlayBar]를 주면 목록 위에 겹쳐 놓고, 그 높이를 [content]에 위쪽 여백으로 넘깁니다.
 */
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
    val triggerPx = with(LocalDensity.current) { RefreshTrigger.toPx() }
    val latestRefreshing by rememberUpdatedState(refreshing)
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    val latestAtTop by rememberUpdatedState(atTop)
    val connection = remember(state, triggerPx) {
        PullConnection(
            state = state,
            scope = scope,
            triggerPx = triggerPx,
            isRefreshing = { latestRefreshing },
            refresh = { latestOnRefresh() },
        )
    }

    LaunchedEffect(refreshing) {
        if (refreshing) state.animateToThreshold() else state.animateToHidden()
    }

    val container = modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                // 자식이 터치를 처리하기 전 단계에서 확인해야 손가락을 댄 순간의 위치를 알 수 있습니다.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                connection.armed = latestAtTop()
            }
        }
        .nestedScroll(connection)

    val indicator = @Composable { position: Modifier ->
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            modifier = position,
            containerColor = GalleryTheme.colors.surface,
            color = GalleryTheme.colors.accent,
            threshold = RefreshTrigger,
        )
    }

    if (overlayBar == null) {
        Box(container) {
            content(0.dp)
            indicator(Modifier.align(Alignment.TopCenter))
        }
        return
    }

    SubcomposeLayout(container) { constraints ->
        // 겹친 막대의 높이를 먼저 재고, 같은 배치 단계에서 그 높이를 목록 여백으로 넘깁니다.
        val wrap = constraints.copy(minWidth = 0, minHeight = 0)
        val bar = subcompose(Slot.Bar) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                overlayBar()
            }
        }.single().measure(wrap)
        val barHeight = bar.height.toDp()
        val list = subcompose(Slot.Content) { Box { content(barHeight) } }.single().measure(constraints)
        val spinner = subcompose(Slot.Indicator) { indicator(Modifier) }.single().measure(wrap)
        layout(list.width, list.height) {
            list.place(0, 0)
            bar.place(0, 0)
            spinner.place((list.width - spinner.width) / 2, bar.height)
        }
    }
}

private enum class Slot { Bar, Content, Indicator }

/**
 * 목록이 다 쓰지 못한 아래 방향 스크롤을 당김 거리로 바꿉니다. 당긴 상태에서 위로 스크롤하면
 * 목록보다 먼저 당김 거리를 줄이고, 손을 떼면 기준 거리를 넘었는지에 따라 새로 고치거나 접습니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class PullConnection(
    private val state: PullToRefreshState,
    private val scope: CoroutineScope,
    private val triggerPx: Float,
    private val isRefreshing: () -> Boolean,
    private val refresh: () -> Unit,
) : NestedScrollConnection {

    /** 이번 손가락 동작이 목록 맨 위에서 시작했는지입니다. */
    var armed = false

    /** 지금까지 당긴 거리(px)입니다. */
    private var distance = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val pushingUp = available.y < 0f
        if (distance <= 0f || !pushingUp) return Offset.Zero
        val consumed = maxOf(available.y, -distance / PULL_RESISTANCE)
        pullTo(distance + consumed * PULL_RESISTANCE)
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val draggingDown = source == NestedScrollSource.UserInput && available.y > 0f
        if (!armed || !draggingDown || isRefreshing()) return Offset.Zero
        pullTo(distance + available.y * PULL_RESISTANCE)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        if (distance <= 0f) return Velocity.Zero
        val triggered = distance >= triggerPx
        distance = 0f
        if (triggered) {
            refresh()
            state.animateToThreshold()
        } else {
            state.animateToHidden()
        }
        // 당기던 중의 관성은 목록에 넘기지 않습니다.
        return available
    }

    private fun pullTo(newDistance: Float) {
        val clamped = newDistance.coerceIn(0f, triggerPx * MAX_OVERPULL)
        // 값이 같으면 이미 예약한 갱신이 같은 위치로 옮기므로 다시 예약하지 않습니다.
        if (clamped == distance) return
        distance = clamped
        // 실행 시점의 거리를 읽습니다. 손을 뗀 뒤 늦게 실행되면 0으로 초기화된 거리를 따라 접힙니다.
        scope.launch { state.snapTo(distance / triggerPx) }
    }
}
