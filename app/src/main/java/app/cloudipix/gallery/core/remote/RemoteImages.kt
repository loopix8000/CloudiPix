package app.cloudipix.gallery.core.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.cloudipix.gallery.core.cache.MEBIBYTE
import app.cloudipix.gallery.core.media.ThumbnailStorage
import app.cloudipix.gallery.core.media.ThumbnailTarget
import app.cloudipix.gallery.core.media.decodeThumbnail
import app.cloudipix.gallery.core.media.scaleAndRecycle
import app.cloudipix.gallery.core.media.thumbnailDispatcher
import app.cloudipix.gallery.core.model.MediaType
import coil3.decode.ImageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteImages @Inject constructor(
    @ApplicationContext context: Context,
    private val gallery: RemoteGallery,
    private val transport: RemoteTransport,
    private val cache: RemoteImageCache,
    private val videos: RemoteVideos,
    storage: ThumbnailStorage,
) {
    private val staging by lazy {
        File(context.cacheDir, "remote-staging").also { directory ->
            directory.listFiles()?.filter { it.isFile && it.name.startsWith("image-") && it.extension == "tmp" }
                ?.forEach { it.delete() }
            directory.mkdirs()
        }
    }
    private val originalTransfers = Semaphore(1)
    private val previewTransfers = Semaphore(2)
    private val originalLocks = Array(32) { Mutex() }
    private val thumbnails = storage.remote

    /** 서버 사진의 썸네일을 로컬 사진과 같은 캐시에서 [target]에 맞춰 돌려준다. */
    internal suspend fun thumbnail(uri: Uri, target: ThumbnailTarget): Bitmap =
        thumbnails.get("$uri$PREVIEW_KEY_SUFFIX", target) { createThumbnail(uri, it) } ?: throw IOException()

    internal suspend fun <T> withOriginal(uri: Uri, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        open(uri).use { block(it.file().toFile()) }
    }

    internal suspend fun withCachedOriginal(uri: Uri, block: suspend (File) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (gallery.source(uri) == null) return@withContext false
        val cached = cache.open(uri.toString()) ?: return@withContext false
        cached.use { block(it.file().toFile()) }
        true
    }

    private suspend fun open(uri: Uri): ImageSource {
        val (server, photo) = gallery.source(uri) ?: throw IOException()
        val key = uri.toString()
        val lock = originalLocks[(key.hashCode() and Int.MAX_VALUE) % originalLocks.size]
        return lock.withLock {
            cache.open(key) ?: originalTransfers.withPermit { downloadOriginal(server, photo, key) }
        }
    }

    private suspend fun downloadOriginal(server: RemoteServer, photo: RemotePhoto, key: String): ImageSource {
        if (photo.size > MAX_ORIGINAL_BYTES) throw IOException()
        val file = File.createTempFile("image-", ".tmp", staging)
        var temporarySource = false
        try {
            file.outputStream().use { transport.download(server, photo.path, it, MAX_ORIGINAL_BYTES) }
            if (photo.size > 0 && file.length() != photo.size) throw IOException("원본 크기가 일치하지 않습니다.")
            cache.store(key, file)?.let { return it }
            // 캐시에 담기 어려운 파일도 임시 파일에서 열 수 있다.
            return ImageSource(file.toOkioPath(), FileSystem.SYSTEM, closeable = AutoCloseable { file.delete() })
                .also { temporarySource = true }
        } finally {
            if (!temporarySource) file.delete()
        }
    }

    /** 받아 둔 원본, 서버 미리보기, 동영상 앞부분, 원본 순서로 원본 구도의 썸네일을 만든다. */
    private suspend fun createThumbnail(uri: Uri, target: ThumbnailTarget): Bitmap = withContext(Dispatchers.IO) {
        val (server, photo) = gallery.source(uri) ?: throw IOException()
        val video = photo.type == MediaType.Video
        if (!video) cache.open(uri.toString())?.use { return@withContext decode(it.file().toFile(), target) }
        // 내려받는 동안에는 로컬 썸네일의 디코딩 자리를 차지하지 않는다.
        previewTransfers.withPermit { downloadThumbnail(server, photo, video, target) }
    }

    private suspend fun downloadThumbnail(server: RemoteServer, photo: RemotePhoto, video: Boolean, target: ThumbnailTarget): Bitmap {
        val input = File.createTempFile("image-", ".tmp", staging)
        try {
            if (photo.preview != null) {
                try {
                    val preview = transport.sizedPreview(photo.preview, target.sourceEdge)
                    input.outputStream().use { transport.download(server, preview, it, MAX_PREVIEW_BYTES, preview = true) }
                    return decode(input, target)
                } catch (_: IOException) {
                    // 미리보기 API를 쓸 수 없으면 원본에서 썸네일을 만든다.
                }
            }
            if (video) {
                // 동영상은 전체를 받지 않고 앞부분에서 프레임을 뽑는다.
                val frame = videos.frame(server, photo, target.sourceEdge)
                return withContext(thumbnailDispatcher) {
                    frame.scaleAndRecycle(target.size, target.fill)
                }
            }
            input.outputStream().use { transport.download(server, photo.path, it, MAX_ORIGINAL_BYTES) }
            return decode(input, target)
        } finally {
            input.delete()
        }
    }

    private suspend fun decode(file: File, target: ThumbnailTarget): Bitmap = withContext(thumbnailDispatcher) {
        decodeThumbnail(ImageDecoder.createSource(file), target.size, target.fill)
    }
}

// 미리보기 생성 방식을 바꾸면 이전 방식으로 만든 캐시를 쓰지 않도록 올린다.
private const val PREVIEW_KEY_SUFFIX = "#preview-v3"
private const val MAX_PREVIEW_BYTES = 8 * MEBIBYTE
internal const val MAX_ORIGINAL_BYTES = 512 * MEBIBYTE
