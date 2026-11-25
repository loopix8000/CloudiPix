package app.cloudipix.gallery.core.cache

import coil3.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

internal const val MEBIBYTE = 1024L * 1024
internal val CACHE_LIMIT_RANGE = 1L..(Long.MAX_VALUE / MEBIBYTE)

internal data class CacheUsage(val usedBytes: Long, val limitMiB: Long)

/** 설정에서 한도를 바꿀 수 있는 디스크 캐시. 처음 쓸 때 저장된 한도로 연다. */
internal class LimitedDiskCache(
    private val directory: File,
    private val loadLimitMiB: suspend () -> Long,
    private val saveLimitMiB: suspend (Long) -> Unit,
    private val beforeOpen: () -> Unit = {},
) {
    private val access = Mutex()
    private val users = MutableStateFlow(0)
    private var opened: Opened? = null

    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        access.withLock { current().usage() }
    }

    suspend fun clear(): CacheUsage = withContext(Dispatchers.IO) {
        access.withLock {
            users.first { it == 0 }
            val current = current()
            current.cache.clear()
            current.usage()
        }
    }

    suspend fun resize(limitMiB: Long): CacheUsage = withContext(Dispatchers.IO) {
        require(limitMiB in CACHE_LIMIT_RANGE)
        access.withLock {
            val current = current()
            if (current.limitMiB == limitMiB) return@withLock current.usage()
            // 읽고 쓰던 항목이 모두 닫힌 뒤에만 같은 디렉터리에서 캐시를 다시 연다.
            users.first { it == 0 }
            withContext(NonCancellable) {
                saveLimitMiB(limitMiB)
                current.cache.shutdown()
                opened = null
                open(limitMiB).usage()
            }
        }
    }

    /** 캐시를 빌린다. [release]를 부르기 전에는 한도를 바꾸려고 캐시를 다시 열지 않는다. */
    suspend fun acquire(): DiskCache = access.withLock {
        current().cache.also { users.update { it + 1 } }
    }

    fun release() = users.update { it - 1 }

    suspend fun <T> use(block: (DiskCache) -> T): T {
        val cache = acquire()
        try {
            return block(cache)
        } finally {
            release()
        }
    }

    private suspend fun current(): Opened = opened ?: open(loadLimitMiB())

    private fun open(limitMiB: Long): Opened {
        beforeOpen()
        return Opened(limitMiB, openDiskCache(directory, limitMiB * MEBIBYTE)).also { opened = it }
    }

    private class Opened(val limitMiB: Long, val cache: DiskCache) {
        fun usage() = CacheUsage(cache.size, limitMiB)
    }
}

internal fun openDiskCache(directory: File, maxBytes: Long): DiskCache {
    val builder = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(maxBytes)
    val cache = builder.build()
    try {
        if (cache.size <= maxBytes) return cache
    } catch (error: Throwable) {
        cache.shutdown()
        throw error
    }
    // 기존 항목을 읽어 온 뒤 캐시를 닫으면 새 한도에 맞춰 정리된다.
    cache.shutdown()
    return builder.build()
}
