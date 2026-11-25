package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import app.cloudipix.gallery.ui.theme.GalleryTheme

@Composable
internal fun GridSelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = GalleryTheme.colors
    Box(
        modifier.padding(5.dp).size(17.dp)
            .background(if (selected) colors.accent else Color(0x660B0B0C), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(GalleryIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(11.dp))
        }
    }
}

internal fun LazyGridLayoutInfo.itemPosition(item: LazyGridItemInfo, leftPadding: Int): Offset =
    item.offset.toOffset() + Offset(leftPadding.toFloat(), beforeContentPadding.toFloat())

internal fun LazyGridState.gridItemAt(point: Offset, leftPadding: Int): LazyGridItemInfo? {
    val layout = layoutInfo
    return layout.visibleItemsInfo.firstOrNull { item ->
        val local = point - layout.itemPosition(item, leftPadding)
        local.x >= 0f && local.x < item.size.width && local.y >= 0f && local.y < item.size.height
    }
}

internal class GridRangeDrag(
    private val grid: LazyGridState,
    private val leftPadding: Int,
    private val isSelectable: (Long) -> Boolean,
    private val selectRange: (Long, Long) -> Unit,
) {
    private var anchor by mutableStateOf<Long?>(null)
    private var target: Long? = null
    val active: Boolean get() = anchor != null
    var point by mutableStateOf<Offset?>(null)
        private set

    fun idAt(position: Offset): Long? = (grid.gridItemAt(position, leftPadding)?.key as? Long)?.takeIf(isSelectable)

    fun begin(id: Long) {
        anchor = id
        target = id
    }

    fun move(position: Offset) {
        val start = anchor ?: return
        point = position
        val bottom = (grid.layoutInfo.viewportSize.height - 1).coerceAtLeast(0).toFloat()
        val id = idAt(position.copy(y = position.y.coerceIn(0f, bottom))) ?: return
        if (target == id) return
        target = id
        selectRange(start, id)
    }

    fun end() {
        anchor = null
        point = null
        target = null
    }
}

@Composable
internal fun rememberGridRangeDrag(
    grid: LazyGridState,
    leftPadding: Int,
    isSelectable: (Long) -> Boolean = { true },
    onSelectRange: (Long, Long) -> Unit,
): GridRangeDrag {
    val canSelect by rememberUpdatedState(isSelectable)
    val selectRange by rememberUpdatedState(onSelectRange)
    val drag = remember(grid, leftPadding) {
        GridRangeDrag(grid, leftPadding, { canSelect(it) }, { from, to -> selectRange(from, to) })
    }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val autoscroll by remember(drag, edge) {
        derivedStateOf {
            val y = drag.point?.y ?: return@derivedStateOf 0f
            val height = grid.layoutInfo.viewportSize.height
            val zone = edge.coerceAtMost(height / 2f)
            when {
                zone == 0f -> 0f
                y < zone && grid.canScrollBackward -> ((y - zone) / zone).coerceAtLeast(-1f)
                y > height - zone && grid.canScrollForward -> ((y - height + zone) / zone).coerceAtMost(1f)
                else -> 0f
            }
        }
    }
    LaunchedEffect(drag, autoscroll != 0f) {
        while (autoscroll != 0f) {
            withFrameNanos { }
            if (grid.scrollBy(autoscroll * 24f) != 0f) drag.point?.let(drag::move)
        }
    }
    return drag
}

@Composable
internal fun Modifier.selectGridRange(
    drag: GridRangeDrag,
    enabled: Boolean = true,
    allowHoldMovement: Boolean = false,
    onTap: ((Long) -> Unit)? = null,
    onHold: ((Long) -> Unit)?,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val allowMovement by rememberUpdatedState(allowHoldMovement)
    val haptics = LocalHapticFeedback.current
    if (!enabled) return this
    return pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val id = drag.idAt(down.position) ?: return@awaitEachGesture
            val held = awaitGridHold(
                down,
                canKeepHolding = { allowMovement && drag.idAt(it) == id },
                onTap = { tap?.invoke(id) },
            )
            if (!held) return@awaitEachGesture
            val holdPosition = currentEvent.changes.firstOrNull { it.id == down.id }?.position ?: down.position
            val select = hold
            if (select != null) {
                select(id)
                drag.begin(id)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            try {
                var moving = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val change = event.changes.firstOrNull { it.id == down.id && !it.isConsumed } ?: break
                    change.consume()
                    if (!moving) moving = (change.position - holdPosition).getDistance() >= viewConfiguration.touchSlop
                    if (select != null && moving) {
                        drag.move(change.position)
                    }
                    if (!change.pressed) break
                }
            } finally {
                drag.end()
            }
        }
    }
}

internal suspend fun AwaitPointerEventScope.awaitGridHold(
    down: PointerInputChange,
    canKeepHolding: (Offset) -> Boolean = { false },
    onTap: () -> Unit = {},
): Boolean =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var canTap = true
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.any { it.id != down.id && it.pressed } || change.isConsumed ||
                change.isOutOfBounds(size, extendedTouchPadding)
            ) break
            if ((change.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                canTap = false
                if (!canKeepHolding(change.position)) break
            }
            awaitPointerEvent(PointerEventPass.Final)
            // 그리드가 세로 스크롤로 처리한 움직임은 길게 누르기에서 제외한다.
            if (change.isConsumed) break
            if (!change.pressed) {
                if (canTap) onTap()
                break
            }
        }
        false
    } ?: true
