package app.cloudipix.gallery.feature.media

import android.net.Uri
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.model.GridItem
import app.cloudipix.gallery.core.model.MediaType
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val PREVIEW_EDGE_PX = 192

internal class AlbumThumbnails {
    val previews = mutableStateMapOf<Uri, ImageBitmap>()
    // 표시를 허용한 칸만 등록한다. 값은 해당 칸의 고화질 요청이 성공 또는 실패로 끝났는지 나타낸다.
    val full = mutableStateMapOf<Uri, Boolean>()
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun finish(uri: Uri) {
        if (full[uri] == false) {
            full[uri] = true
            wake.trySend(Unit)
        }
    }
}

/** 화면에 보이는 칸 수를 한 페이지로 삼아 앞뒤 한 페이지의 작은 미리보기만 보관한다. */
@Composable
internal fun rememberAlbumThumbnails(
    grid: LazyGridState,
    items: List<GridItem>,
    leadingItems: Int,
): AlbumThumbnails {
    val context = LocalContext.current
    val album = remember(grid, items, leadingItems, context) { AlbumThumbnails() }
    LaunchedEffect(grid, items, leadingItems, context) {
        val loader = SingletonImageLoader.get(context)
        val previews = album.previews
        val wake = album.wake
        val running = mutableMapOf<Uri, Job>()
        val visibleMisses = mutableSetOf<Uri>()
        val nearbyMisses = mutableSetOf<Uri>()
        val visible = linkedSetOf<Uri>()
        var visibleRows = emptyList<List<Uri>>()
        var nearby = emptyList<Uri>()
        var nearbyBatch = emptyList<Uri>()
        var scrollingBackward = false

        fun photos(indices: IntProgression): List<Uri> = indices.mapNotNull { index ->
            (items.getOrNull(index) as? GridItem.Item)?.medium?.takeIf { it.type != MediaType.Video }?.displayUri
        }

        launch {
            snapshotFlow {
                val cells = grid.layoutInfo.visibleItemsInfo
                cells.groupBy({ it.row }, { it.index - leadingItems }).values.toList() to grid.lastScrolledBackward
            }.collect { (rows, backward) ->
                visibleRows = rows.map { row ->
                    row.mapNotNull { index -> (items.getOrNull(index) as? GridItem.Item)?.medium?.displayUri }
                }
                val indices = rows.flatten()
                val first = indices.firstOrNull() ?: 0
                val last = indices.lastOrNull() ?: -1
                val page = indices.size
                val onScreen = photos(first..last)
                // 계속 보이는 칸은 대기 순서를 유지한다. 새로 들어온 줄이 기존 줄의 남은 칸을 추월하지 않는다.
                visible.retainAll(onScreen.toSet())
                visible.addAll(onScreen)
                album.full.keys.retainAll(visibleRows.flatten().toSet())
                val before = photos(first - 1 downTo maxOf(0, first - page))
                val after = photos(last + 1..minOf(items.lastIndex, last + page))
                nearby = if (backward) before + after else after + before
                val retained = (visible + nearby).toSet()
                nearbyBatch = if (backward == scrollingBackward) nearbyBatch.filter { it in retained } else emptyList()
                scrollingBackward = backward
                previews.keys.retainAll(retained)
                visibleMisses.retainAll(retained)
                nearbyMisses.retainAll(retained)
                running.forEach { (uri, job) -> if (uri !in retained) job.cancel() }
                wake.trySend(Unit)
            }
        }

        fun needsPreview(uri: Uri, misses: Set<Uri>): Boolean =
            uri !in previews && uri !in misses && loader.memoryCache?.get(MemoryCache.Key(uri.toString())) == null

        fun loadNext(candidates: Iterable<Uri>, slots: Int, prefetch: Boolean) {
            val misses = if (prefetch) nearbyMisses else visibleMisses
            var available = slots
            for (uri in candidates) {
                if (available <= 0) break
                if (uri in running || !needsPreview(uri, misses)) continue
                val job = launch {
                    val request = ImageRequest.Builder(context)
                        .data(Thumbnail(uri, embedded = true, useCachedPreview = prefetch || uri !in album.full))
                        .size(PREVIEW_EDGE_PX, PREVIEW_EDGE_PX)
                        .scale(Scale.FIT)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build()
                    val result = loader.execute(request)
                    ensureActive()
                    val image = (result as? SuccessResult)?.image as? BitmapImage
                    if (image != null) previews[uri] = image.bitmap.asImageBitmap() else misses.add(uri)
                }
                running[uri] = job
                job.invokeOnCompletion { wake.trySend(Unit) }
                available--
            }
        }

        while (true) {
            wake.receive()
            running.entries.removeAll { it.value.isCompleted }
            // 메모리에 있는 사진은 즉시 표시하고, 읽어야 하는 고화질 사진은 실제 격자의 한 줄씩 요청한다.
            for (row in visibleRows) {
                for (uri in row) {
                    if (uri !in album.full && loader.memoryCache?.get(MemoryCache.Key(uri.toString())) != null) {
                        album.full[uri] = true
                    }
                }
            }
            if (album.full.values.count { !it } < 2) {
                visibleRows.firstOrNull { row -> row.any { it !in album.full } }?.forEach { uri ->
                    if (uri !in album.full) album.full[uri] = false
                }
            }
            val onScreen = running.keys.count { it in visible }
            val slots = minOf(2 - onScreen, 3 - running.size)
            val entering = nearbyBatch.filter { it in visible }
            loadNext(if (entering.isEmpty()) visible else entering + visible, slots, prefetch = false)
            // 화면 안의 빈칸이 우선이다. 한 번 고른 이웃 칸들은 줄 경계가 움직여도 이어 읽는다.
            if (running.isEmpty()) {
                nearbyBatch = nearbyBatch.filter { it in nearby && needsPreview(it, nearbyMisses) }
                if (nearbyBatch.isEmpty()) {
                    nearbyBatch = nearby.asSequence().filter { needsPreview(it, nearbyMisses) }
                        .take(grid.layoutInfo.maxSpan.coerceAtLeast(1)).toList()
                }
                loadNext(nearbyBatch, 1, prefetch = true)
            }
        }
    }
    return album
}
