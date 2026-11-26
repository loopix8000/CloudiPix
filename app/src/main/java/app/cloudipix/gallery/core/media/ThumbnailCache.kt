package app.cloudipix.gallery.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Process
import android.util.Size
import app.cloudipix.gallery.core.cache.CacheUsage
import app.cloudipix.gallery.core.cache.LimitedDiskCache
import app.cloudipix.gallery.core.settings.SettingsRepository
import coil3.disk.DiskCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** 썸네일 요청 크기. [fill]이면 칸을 덮고, 아니면 칸 안에 들어간다. */
internal data class ThumbnailTarget(val size: Size, val fill: Boolean) {
    /** 디스크에 둘 미리보기 크기. 뷰어처럼 큰 요청이 캐시를 키우지 않도록 긴 변을 제한한다. */
    fun stored(): ThumbnailTarget {
        val edge = max(size.width, size.height)
        if (edge <= MAX_STORED_EDGE) return this
        val ratio = MAX_STORED_EDGE.toFloat() / edge
        return copy(size = Size(
            (size.width * ratio).roundToInt().coerceAtLeast(1),
            (size.height * ratio).roundToInt().coerceAtLeast(1),
        ))
    }

    /** 비율을 모르는 원본에서 이 크기를 덮으려면 긴 변이 얼마나 되어야 하는지 어림한다. */
    val sourceEdge: Int get() = max(size.width, size.height) * if (fill) 2 else 1
}

/** 로컬 사진과 서버 사진이 함께 쓰는 썸네일 캐시. 한도는 설정의 고급 항목에서 바꾼다. */
@Singleton
class ThumbnailStorage @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
) {
    private val disk = LimitedDiskCache(
        File(context.cacheDir, "thumbnails"),
        loadLimitMiB = { settings.thumbnailCacheLimitMiB.first() },
        saveLimitMiB = settings::setThumbnailCacheLimitMiB,
        // 로컬과 서버가 따로 쓰던 이전 썸네일 캐시는 공용 캐시가 대신한다.
        beforeOpen = { LEGACY_DIRECTORIES.forEach { File(context.cacheDir, it).deleteRecursively() } },
    )
    // 디스크는 함께 쓰되, 로컬 썸네일이 서버에서 내려받는 썸네일의 잠금을 기다리지 않도록 잠금은 따로 둔다.
    internal val local = ThumbnailCache(disk)
    internal val remote = ThumbnailCache(disk)

    internal suspend fun usage(): CacheUsage = disk.usage()

    internal suspend fun clear(): CacheUsage = disk.clear()

    internal suspend fun resize(limitMiB: Long): CacheUsage = disk.resize(limitMiB)
}

/**
 * 사진마다 원본 구도의 미리보기 하나를 디스크에 두고 요청한 칸 크기로 줄여 돌려준다.
 * 미리보기는 지금까지 요청된 가장 큰 칸에 맞춰 커지므로, 화면을 돌리거나 열 수를 바꿔도 같은 사진을 따로 저장하지 않는다.
 */
internal class ThumbnailCache(private val disk: LimitedDiskCache) {
    private val locks = Array(32) { Mutex() }
    private val writer = CoroutineScope(SupervisorJob() + thumbnailWriter)
    private val pendingWrites = Semaphore(3)

    /**
     * [key]의 미리보기가 [target]을 덮으면 칸 크기로 줄여 돌려주고, 없거나 작으면 [generate]로 새로 만들어 바꿔 둔다.
     * [generate]는 주어진 크기에 맞춘 원본 구도의 이미지를 돌려준다. [key]가 null이면 수정 여부를 알 수 없어 캐시하지 않는다.
     */
    suspend fun get(key: String?, target: ThumbnailTarget, generate: suspend (ThumbnailTarget) -> Bitmap?): Bitmap? {
        val stored = target.stored()
        if (key == null) return generate(stored)
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.lock()
        var writing = false
        try {
            withContext(thumbnailDispatcher) { disk.use { it.read(key, stored, target) } }?.let { return it }
            val bitmap = generate(stored) ?: return null
            pendingWrites.acquire()
            try {
                val cache = disk.acquire()
                // 먼저 화면에 돌려준다. 기록 중인 키의 후속 요청과 캐시 크기 변경은 완료까지 기다린다.
                writer.launch {
                    try {
                        cache.write(key, bitmap, !covers(bitmap.width, bitmap.height, stored.size, stored.fill))
                    } catch (error: RuntimeException) {
                        android.util.Log.w("ThumbnailCache", "Unable to persist thumbnail", error)
                    } finally {
                        disk.release()
                        pendingWrites.release()
                        lock.unlock()
                    }
                }
                writing = true
            } finally {
                if (!writing) pendingWrites.release()
            }
            return bitmap
        } finally {
            if (!writing) lock.unlock()
        }
    }

    /** [key]의 미리보기가 [target]을 덮으면 칸 크기로 줄여 돌려준다. 없거나 작으면 새로 만들지 않고 null을 돌려준다. */
    suspend fun read(key: String?, target: ThumbnailTarget): Bitmap? {
        if (key == null) return null
        return withContext(embeddedThumbnailDispatcher) { disk.use { it.read(key, target.stored(), target) } }
    }

    /** [key]에 [target]을 덮는 현재 형식의 미리보기가 있는지 이미지는 읽지 않고 확인한다. */
    suspend fun contains(key: String, target: ThumbnailTarget): Boolean = withContext(embeddedThumbnailDispatcher) {
        disk.use { cache ->
            try {
                cache.openSnapshot(key)?.use { snapshot ->
                    val preview = StoredPreview.parse(cache.fileSystem.read(snapshot.metadata) { readUtf8() })
                    preview != null && preview.current && preview.covers(target.stored())
                } == true
            } catch (_: IOException) {
                false
            }
        }
    }

    /** 같은 칸의 고화질 요청이 이미 진행 중이면 현재 디스크 캐시를 두 번 디코딩하지 않는다. */
    suspend fun readQuickPreview(key: String?, target: ThumbnailTarget, useCachedPreview: Boolean): Bitmap? {
        if (key == null) return null
        return withContext(embeddedThumbnailDispatcher) {
            disk.use { cache ->
                cache.openSnapshot(key)?.use snapshot@{ snapshot ->
                    val preview = cache.fileSystem.read(snapshot.metadata) { StoredPreview.parse(readUtf8()) }
                        ?: return@snapshot null
                    if (preview.current && !useCachedPreview) {
                        if (preview.covers(target.stored())) throw IOException("고화질 썸네일이 이미 있습니다.")
                        null
                    } else {
                        decodeThumbnail(ImageDecoder.createSource(snapshot.data.toFile()), target.size, target.fill)
                    }
                }
            }
        }
    }
}

private fun DiskCache.read(key: String, stored: ThumbnailTarget, target: ThumbnailTarget): Bitmap? = try {
    openSnapshot(key)?.use { snapshot ->
        val preview = StoredPreview.parse(fileSystem.read(snapshot.metadata) { readUtf8() })
        if (preview == null || !preview.current || !preview.covers(stored)) null
        else decodeThumbnail(ImageDecoder.createSource(snapshot.data.toFile()), target.size, target.fill)
    }
} catch (_: IOException) {
    null
}

private fun DiskCache.write(key: String, bitmap: Bitmap, complete: Boolean) {
    val editor = openEditor(key) ?: return
    try {
        fileSystem.write(editor.metadata) { writeUtf8(StoredPreview(bitmap.width, bitmap.height, complete).format()) }
        val written = editor.data.toFile().outputStream().use { bitmap.compressThumbnail(it) }
        if (written) editor.commit()
    } catch (_: IOException) {
        // 캐시에 쓰지 못해도 이번에 만든 썸네일은 그대로 보여 준다.
    } finally {
        // 기록에 실패한 항목은 캐시에 남기지 않는다.
        runCatching { editor.abort() }
    }
}

/** 저장한 미리보기의 크기와, 원본이 작아 더 크게 만들 수 없는지 여부. */
private data class StoredPreview(val width: Int, val height: Int, val complete: Boolean, val current: Boolean = true) {
    fun covers(target: ThumbnailTarget): Boolean = complete || covers(width, height, target.size, target.fill)

    fun format() = "$width $height ${if (complete) 1 else 0} $VERSION"

    companion object {
        private const val VERSION = "2"

        fun parse(text: String): StoredPreview? {
            val parts = text.trim().split(' ')
            if (parts.size != 3 && (parts.size != 4 || parts[3] != VERSION)) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            val complete = when (parts[2]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            return StoredPreview(width, height, complete, current = parts.size == 4)
        }
    }
}

private val LEGACY_DIRECTORIES = listOf("local-thumbnails", "remote-thumbnails")
// 뷰어용 큰 미리보기가 목록 썸네일을 밀어내지 않도록 한다.
private const val MAX_STORED_EDGE = 1024
// 첫 화면의 썸네일들이 미디어 제공자와 디코더를 한꺼번에 점유하지 않도록 한다.
// 우선순위는 화면 스레드보다 낮게 두되 BACKGROUND(10)보다 한 단계 높인다. 10 이상이면 시스템이 백그라운드
// 작업 프로필을 적용해 기기에 따라 작은 코어에서만 돌 수 있다.
private fun thumbnailWorkers(count: Int, name: String) = Executors.newFixedThreadPool(count) { work ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
        work.run()
    }, name)
}.asCoroutineDispatcher()

internal val thumbnailDispatcher = thumbnailWorkers(2, "Gallery thumbnail decode")
// 내장 썸네일과 디스크 캐시 읽기는 작고 금방 끝나므로 원본 디코딩 대기열 뒤에 서지 않도록 따로 처리한다.
internal val embeddedThumbnailDispatcher = thumbnailWorkers(2, "Gallery embedded thumbnail")
private val thumbnailWriter = thumbnailWorkers(1, "Gallery thumbnail write")
