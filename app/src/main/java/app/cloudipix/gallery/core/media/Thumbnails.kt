package app.cloudipix.gallery.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.core.remote.RemoteImages
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Scale
import coil3.size.pxOrElse
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/** 크기를 모를 때 쓰는 썸네일 한 변의 길이입니다. */
private const val DEFAULT_EDGE_PX = 512

/**
 * 격자 썸네일 요청입니다. Coil은 이 값을 [ThumbnailFetcher]로 불러오고,
 * 메모리 캐시 키는 URI 문자열입니다([ThumbnailKeyer]).
 */
data class Thumbnail(val uri: Uri) {
    /** 정사각형으로 자르지 않은 전체 구도 미리보기의 메모리 캐시 키입니다. */
    val uncroppedKey: MemoryCache.Key get() = MemoryCache.Key("uncropped-thumbnail:$uri")
}

/**
 * 뷰어를 열 때 먼저 보여 줄 전체 구도 미리보기입니다.
 * 메모리 캐시에 전체 구도 미리보기나 정사각형이 아닌 썸네일이 있으면 바로 쓰고,
 * 정사각형 타일만 있으면 원본에서 작은 미리보기를 새로 만듭니다. [cacheOnly]이면 새로 만들지 않습니다.
 */
internal suspend fun fullFramePreview(context: Context, uri: Uri, cacheOnly: Boolean = false): BitmapImage? {
    val loader = SingletonImageLoader.get(context)
    val uncropped = Thumbnail(uri).uncroppedKey
    val cache = loader.memoryCache
    if (cache != null) {
        (cache[uncropped]?.image as? BitmapImage)?.let { return it }
        val tile = cache[MemoryCache.Key(uri.toString())]?.image as? BitmapImage
        if (tile != null && tile.width != tile.height) return tile
    }
    if (cacheOnly) return null

    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(DEFAULT_EDGE_PX, DEFAULT_EDGE_PX)
        .scale(Scale.FIT)
        .memoryCacheKey(uncropped)
        .crossfade(false)
        .build()
    return (loader.execute(request) as? SuccessResult)?.image as? BitmapImage
}

/**
 * 썸네일을 불러옵니다. 원격 항목은 [RemoteImages]에서 받고, 기기 항목은 디스크 캐시를 거쳐
 * 직접 만들거나 시스템 썸네일을 씁니다. 둘 다 실패하면 원본을 Coil에 넘겨 디코딩하게 합니다.
 */
class ThumbnailFetcher internal constructor(
    private val context: Context,
    private val data: Thumbnail,
    private val options: Options,
    private val thumbnails: ThumbnailCache,
    private val remoteImages: RemoteImages,
) : Fetcher {

    private val target = ThumbnailTarget(
        size = Size(
            options.size.width.pxOrElse { DEFAULT_EDGE_PX },
            options.size.height.pxOrElse { DEFAULT_EDGE_PX },
        ),
        fill = options.scale == Scale.FILL,
    )

    override suspend fun fetch(): FetchResult {
        if (data.uri.scheme == REMOTE_MEDIA_SCHEME) {
            return sampled(remoteImages.thumbnail(data.uri, target))
        }
        return withContext(thumbnailDispatcher) { fetchLocal() }
    }

    private suspend fun fetchLocal(): FetchResult {
        val cached = thumbnails.get(cacheKey(), target) { wanted ->
            // 시스템 썸네일은 EXIF에 든 128px 미리보기를 다시 압축한 것일 수 있어 사진은 원본에서 직접 만듭니다.
            val isImage = context.contentResolver.getType(data.uri)?.startsWith("image/") == true
            val decoded = if (isImage) decodeFromOriginal(wanted) else null
            decoded ?: loadSystemThumbnail(wanted)
        }
        return if (cached != null) sampled(cached) else originalSource()
    }

    private fun sampled(bitmap: Bitmap): FetchResult =
        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)

    private fun decodeFromOriginal(wanted: ThumbnailTarget): Bitmap? = try {
        decodeThumbnail(ImageDecoder.createSource(context.contentResolver, data.uri), wanted.size, wanted.fill)
    } catch (_: IOException) {
        null
    }

    /**
     * 디스크 캐시 키입니다. 사진을 편집하거나 회전하면 MediaStore의 수정 세대가 바뀌어 키도 바뀝니다.
     * MediaStore가 아닌 제공자는 수정 여부를 알 수 없어 `null`을 돌려 캐시하지 않습니다.
     */
    private fun cacheKey(): String? {
        val projection = arrayOf(
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        val version = try {
            context.contentResolver.query(data.uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                "${cursor.getLong(0)}:${cursor.getLong(1)}:${cursor.getLong(2)}"
            }
        } catch (_: RuntimeException) {
            null
        }
        return version?.let { "${data.uri}|$it" }
    }

    /** 요청이 취소되면 시스템 썸네일 생성도 함께 취소합니다. */
    private suspend fun loadSystemThumbnail(wanted: ThumbnailTarget): Bitmap? {
        val signal = CancellationSignal()
        val cancelOnCompletion = coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
        return try {
            context.contentResolver.loadThumbnail(data.uri, wanted.size, signal).scaleAndRecycle(wanted.size, wanted.fill)
        } catch (_: IOException) {
            null
        } finally {
            cancelOnCompletion?.dispose()
        }
    }

    private fun originalSource(): FetchResult {
        val resolver = context.contentResolver
        val descriptor = checkNotNull(resolver.openAssetFileDescriptor(data.uri, "r")) { "Unable to open ${data.uri}." }
        return SourceFetchResult(
            source = ImageSource(descriptor.createInputStream().source().buffer(), options.fileSystem),
            mimeType = resolver.getType(data.uri),
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        context: Context,
        private val storage: ThumbnailStorage,
        private val remoteImages: RemoteImages,
    ) : Fetcher.Factory<Thumbnail> {
        private val appContext = context.applicationContext

        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(appContext, data, options, storage.local, remoteImages)
    }
}

/** 썸네일 메모리 캐시 키는 URI 문자열입니다. 뷰어가 같은 키로 캐시된 썸네일을 찾습니다. */
class ThumbnailKeyer : Keyer<Thumbnail> {
    override fun key(data: Thumbnail, options: Options): String = data.uri.toString()
}
