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
import androidx.compose.ui.unit.IntSize
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.media.indexedMedia
import app.cloudipix.gallery.core.model.GridItem
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
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

/**
 * 화면에 보이는 칸 수를 한 페이지로 삼아 앞뒤 한 페이지의 작은 미리보기만 보관한다.
 * 보이는 칸의 고화질 썸네일이 디스크에 이미 있으면 작은 미리보기 대신 칸 크기로 바로 읽어 메모리에 올린다.
 */
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
        // 미리보기를 고를 수 있는 칸의 항목과 격자 칸 크기. 칸 크기는 고화질 요청과 같은 크기로 디스크 썸네일을 읽는 데 쓴다.
        val known = HashMap<Uri, Medium>()
        var cellSize: IntSize? = null

        fun photos(indices: IntProgression): List<Uri> = indices.mapNotNull { index ->
            (items.getOrNull(index) as? GridItem.Item)?.medium?.takeIf { it.type != MediaType.Video }?.let { medium ->
                medium.displayUri.also { known[it] = medium }
            }
        }

        launch {
            snapshotFlow {
                val cells = grid.layoutInfo.visibleItemsInfo
                val size = cells.firstOrNull { items.getOrNull(it.index - leadingItems) is GridItem.Item }?.size
                Triple(cells.groupBy({ it.row }, { it.index - leadingItems }).values.toList(), grid.lastScrolledBackward, size)
            }.collect { (rows, backward, size) ->
                cellSize = size
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
                known.keys.retainAll(retained)
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
                val indexed = known[uri]?.indexedMedia
                // 고화질 차례가 아직 오지 않은 보이는 칸만 디스크를 먼저 본다. 차례가 온 칸은 고화질 요청이 직접 읽는다.
                val cell = cellSize?.takeIf { !prefetch && uri !in album.full && it.width > 0 && it.height > 0 }
                val job = launch {
                    if (cell != null) {
                        // 고화질 요청과 같은 키·크기로 읽어 메모리에 두면 고화질 칸이 바로 그려지고 같은 파일을 두 번 디코딩하지 않는다.
                        val cached = ImageRequest.Builder(context)
                            .data(Thumbnail(uri, indexed = indexed, cachedOnly = true))
                            .size(cell.width, cell.height)
                            .scale(Scale.FILL)
                            .precision(Precision.EXACT)
                            .build()
                        if (loader.execute(cached) is SuccessResult) return@launch
                        ensureActive()
                    }
                    val request = ImageRequest.Builder(context)
                        .data(Thumbnail(uri, embedded = true, useCachedPreview = prefetch || uri !in album.full, indexed = indexed))
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
