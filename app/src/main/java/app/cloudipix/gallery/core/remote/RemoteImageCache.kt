package app.cloudipix.gallery.core.remote

import android.content.Context
import app.cloudipix.gallery.core.cache.CacheUsage
import app.cloudipix.gallery.core.cache.LimitedDiskCache
import app.cloudipix.gallery.core.settings.SettingsRepository
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 서버 사진 원본 캐시. 썸네일은 로컬 사진과 같은 [app.cloudipix.gallery.core.media.ThumbnailStorage]에 둔다. */
@Singleton
class RemoteImageCache @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
) {
    private val originals = LimitedDiskCache(
        File(context.cacheDir, "remote-originals"),
        loadLimitMiB = { settings.remoteCacheLimitMiB.first() },
        saveLimitMiB = settings::setRemoteCacheLimitMiB,
    )

    internal suspend fun usage(): CacheUsage = originals.usage()

    internal suspend fun clear(): CacheUsage = originals.clear()

    internal suspend fun resize(limitMiB: Long): CacheUsage = originals.resize(limitMiB)

    internal suspend fun open(key: String): ImageSource? = source { it.openSnapshot(key) }

    internal suspend fun store(key: String, file: File): ImageSource? = source { cache ->
        if (file.length() > cache.maxSize) return@source null
        val editor = cache.openEditor(key) ?: return@source null
        try {
            cache.fileSystem.write(editor.metadata) { }
            file.inputStream().use { input ->
                editor.data.toFile().outputStream().use { output -> input.copyTo(output) }
            }
            editor.commitAndOpenSnapshot()
        } finally {
            // 전송이나 기록에 실패한 항목은 캐시에 남기지 않는다.
            runCatching { editor.abort() }
        }
    }

    /** 돌려준 원본을 닫을 때까지 캐시를 빌려 둔다. */
    private suspend fun source(openSnapshot: (DiskCache) -> DiskCache.Snapshot?): ImageSource? {
        val cache = originals.acquire()
        var handedOff = false
        try {
            val snapshot = openSnapshot(cache) ?: return null
            val closed = AtomicBoolean()
            return ImageSource(snapshot.data, cache.fileSystem, closeable = AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    try {
                        snapshot.close()
                    } finally {
                        originals.release()
                    }
                }
            }).also { handedOff = true }
        } finally {
            if (!handedOff) originals.release()
        }
    }
}
