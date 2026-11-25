package app.cloudipix.gallery.feature.folders

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import app.cloudipix.gallery.ui.component.awaitGridHold
import kotlinx.coroutines.delay

internal data class DraggedFolder(val id: Long, val sourceTab: String, val grab: Offset, val width: Int)

private fun FolderViewport.folderAt(position: Offset) = hit(position)?.takeIf { it.id != RECENT_BUCKET_ID }

internal class FolderDrag {
    var active: DraggedFolder? by mutableStateOf(null)
        private set
    var point by mutableStateOf(Offset.Zero)
        private set
    private var lastTarget: Long? = null
    private var lastGrid: FolderViewport? = null

    fun begin(folder: DraggedFolder, position: Offset) {
        active = folder
        point = position
        lastTarget = null
    }

    fun move(grid: FolderViewport, position: Offset, onMove: (Long, Long) -> Unit) {
        val id = active?.id ?: return
        point = position
        val target = grid.folderAt(position)?.id
        if (grid !== lastGrid || target == id) lastTarget = null
        lastGrid = grid
        if (target != null && target != id && target != lastTarget) {
            lastTarget = target
            onMove(id, target)
        }
    }

    fun end() {
        active = null
        lastGrid = null
        lastTarget = null
    }
}

@Composable
internal fun rememberFolderDrag(
    grid: FolderViewport,
    pager: PagerState,
    onMove: (Long, Long) -> Unit,
): FolderDrag {
    val drag = remember { FolderDrag() }
    val move by rememberUpdatedState(onMove)
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val tabEdge = with(LocalDensity.current) { 28.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val direction by remember(drag, grid, tabEdge, rtl) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (drag.active == null) return@derivedStateOf 0
            val width = grid.view?.width ?: 0
            val physical = when {
                width == 0 -> 0
                drag.point.x < tabEdge -> -1
                drag.point.x > width - tabEdge -> 1
                else -> 0
            }
            if (rtl) -physical else physical
        }
    }
    val page = pager.currentPage
    LaunchedEffect(drag.active, direction, page, pager.pageCount) {
        val next = page + direction
        if (direction != 0 && next in 0 until pager.pageCount) {
            delay(550)
            if (drag.active != null && pager.currentPage == page) pager.scrollToPage(next)
        }
    }
    LaunchedEffect(drag.active, grid, edge) {
        if (drag.active == null) return@LaunchedEffect
        // 새 탭의 칸이 배치된 뒤 정지해 있는 손가락 아래 위치도 반영한다.
        withFrameNanos { }
        drag.move(grid, drag.point, move)
        while (drag.active != null) {
            val height = grid.view?.height ?: 0
            val y = drag.point.y
            val distance = when {
                y < edge -> -(edge - y)
                y > height - edge -> y - height + edge
                else -> 0f
            }
            if (distance != 0f && grid.scroll.scrollBy((distance / edge).coerceIn(-1f, 1f) * 18f) != 0f) {
                drag.move(grid, drag.point, move)
            }
            delay(16)
        }
    }
    return drag
}

@Composable
internal fun Modifier.reorderFolders(
    drag: FolderDrag,
    grid: FolderViewport,
    currentTab: () -> String,
    selecting: Boolean,
    onSelect: (Long) -> Unit,
    onStart: (Long) -> Boolean,
    onMove: (Long, Long) -> Unit,
    onFinish: (Long, String, String?) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentGrid by rememberUpdatedState(grid)
    val tab by rememberUpdatedState(currentTab)
    val selectionActive by rememberUpdatedState(selecting)
    val selectFolder by rememberUpdatedState(onSelect)
    val finish by rememberUpdatedState(onFinish)
    return pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val item = currentGrid.folderAt(down.position) ?: return@awaitEachGesture
            val folderId = item.id
            val grab = down.position - item.origin
            val sourceTab = tab()
            val held = awaitGridHold(down, canKeepHolding = { position ->
                selectionActive && currentGrid.folderAt(position)?.id == folderId
            })
            if (!held) return@awaitEachGesture
            val selectRange = selectionActive
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (selectRange) selectFolder(folderId)

            var started = false
            var commit = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val pointer = event.changes.firstOrNull { it.id == down.id && !it.isConsumed } ?: break
                    pointer.consume()
                    if (!pointer.pressed) {
                        if (started) {
                            drag.move(currentGrid, pointer.position, onMove)
                            commit = true
                        } else if (!selectRange) selectFolder(folderId)
                        break
                    }
                    if (!started && (pointer.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                        if (selectRange || grab.y >= item.width || !onStart(folderId)) break
                        drag.begin(DraggedFolder(folderId, sourceTab, grab, item.width), down.position)
                        started = true
                    }
                    if (started) drag.move(currentGrid, pointer.position, onMove)
                }
            } finally {
                if (started) {
                    drag.end()
                    finish(folderId, sourceTab, if (commit) tab() else null)
                }
            }
        }
    }
}
