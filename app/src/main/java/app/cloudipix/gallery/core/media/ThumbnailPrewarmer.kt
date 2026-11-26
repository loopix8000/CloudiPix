package app.cloudipix.gallery.core.media

import android.content.Context
import android.net.Uri
import android.util.Size
import app.cloudipix.gallery.core.cache.MEBIBYTE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 폴더를 열기 전에 첫 화면에 보일 기기 사진의 썸네일을 디스크 캐시에 미리 만들어 둔다.
 * 이미 있는 썸네일은 이미지를 읽지 않고 건너뛰며, 만든 썸네일은 메모리 캐시에 올리지 않는다.
 */
@Singleton
class ThumbnailPrewarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: ThumbnailStorage,
) {
    private val running = Mutex()
    // 이번 실행 중에 디스크에 있다고 확인한 항목. 폴더 목록에 돌아올 때마다 같은 항목의 캐시를 다시 확인하지 않는다.
    private val ready = HashSet<String>()

    /**
     * [items]의 썸네일을 [cellPx] 크기의 정사각형 칸에 맞춰 차례로 만든다. 한 번에 새로 만드는 양은 캐시 한도의
     * 4분의 1 안으로 제한해 실제로 본 썸네일을 밀어내지 않게 한다. 호출한 코루틴이 취소되면 바로 멈춘다.
     */
    suspend fun prewarm(items: List<Pair<Uri, IndexedMedia>>, cellPx: Int) = running.withLock {
        if (cellPx <= 0 || items.isEmpty()) return@withLock
        val target = ThumbnailTarget(Size(cellPx, cellPx), fill = true)
        val budget = storage.usage().limitMiB * MEBIBYTE / 4 / ESTIMATED_THUMBNAIL_BYTES
        if (ready.size > MAX_READY_ENTRIES) ready.clear()
        var created = 0L
        for ((uri, indexed) in items) {
            if (created >= budget) break
            val key = localThumbnailKey(uri, indexed)
            val readyKey = "$key@$cellPx"
            if (readyKey in ready) continue
            if (!storage.local.contains(key, target)) {
                // 앨범 화면의 썸네일과 같은 작업 스레드에서 하나씩 만든다. 디스크 기록이 쓰는 비트맵이라 해제하지 않는다.
                withContext(thumbnailDispatcher) {
                    storage.local.get(key, target) { wanted -> createLocalThumbnail(context, uri, !indexed.video, wanted) }
                }
                created++
            }
            // 만들지 못한 파일도 다시 시도하지 않는다. 앨범을 열면 평소처럼 다시 만든다.
            ready += readyKey
        }
    }
}

// 격자 칸 크기 JPEG 썸네일 한 장의 넉넉한 어림값
private const val ESTIMATED_THUMBNAIL_BYTES = 64L * 1024
private const val MAX_READY_ENTRIES = 20_000
