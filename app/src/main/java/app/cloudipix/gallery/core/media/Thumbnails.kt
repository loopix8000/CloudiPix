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
import coil3.ImageLoader
import coil3.BitmapImage
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
import coil3.request.Options
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Scale
import coil3.size.pxOrElse
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class Thumbnail(val uri: Uri) {
    val uncroppedKey: MemoryCache.Key get() = MemoryCache.Key("uncropped-thumbnail:$uri")
}

internal suspend fun fullFramePreview(context: Context, uri: Uri, cacheOnly: Boolean = false): BitmapImage? {
    val loader = SingletonImageLoader.get(context)
    val fullKey = Thumbnail(uri).uncroppedKey
    val cache = loader.memoryCache
    (cache?.get(fullKey)?.image as? BitmapImage)?.let { return it }
    val tile = cache?.get(MemoryCache.Key(uri.toString()))?.image as? BitmapImage
    if (tile != null && tile.width != tile.height) return tile
    if (cacheOnly) return null

    // 정사각형 타일만 남으면 원본에서 전체 구도의 작은 미리보기를 만든다.
    val request = ImageRequest.Builder(context).data(uri)
        .size(FALLBACK_PX, FALLBACK_PX)
        .scale(Scale.FIT)
        .memoryCacheKey(fullKey)
        .crossfade(false)
        .build()
    return (loader.execute(request) as? SuccessResult)?.image as? BitmapImage
}

class ThumbnailFetcher internal constructor(
    private val context: Context,
    private val data: Thumbnail,
    private val options: Options,
    private val thumbnails: ThumbnailCache,
    private val remoteImages: RemoteImages,
) : Fetcher {
    private val target = ThumbnailTarget(
        Size(options.size.width.pxOrElse { FALLBACK_PX }, options.size.height.pxOrElse { FALLBACK_PX }),
        fill = options.scale == Scale.FILL,
    )

    override suspend fun fetch(): FetchResult {
        return if (data.uri.scheme == REMOTE_MEDIA_SCHEME) {
            ImageFetchResult(remoteImages.thumbnail(data.uri, target).asImage(), isSampled = true, dataSource = DataSource.DISK)
        } else {
            withContext(thumbnailDispatcher) { localThumbnail() }
        }
    }

    private suspend fun localThumbnail(): FetchResult {
        val bitmap = thumbnails.get(versionKey(), target) { stored ->
            val image = context.contentResolver.getType(data.uri)?.startsWith("image/") == true
            // 시스템 썸네일은 EXIF에 든 128px 미리보기를 재압축한 것일 수 있어 사진은 원본에서 직접 만든다.
            (if (image) imageThumbnail(stored) else null) ?: systemThumbnail(stored)
        } ?: return original()
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun imageThumbnail(target: ThumbnailTarget): Bitmap? = try {
        decodeThumbnail(ImageDecoder.createSource(context.contentResolver, data.uri), target.size, target.fill)
    } catch (_: IOException) {
        null
    }

    /** 사진이 편집·회전되면 MediaStore의 수정 세대가 바뀌어 새 썸네일을 만든다. */
    private fun versionKey(): String? {
        val projection = arrayOf(
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        val version = try {
            context.contentResolver.query(data.uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) "${cursor.getLong(0)}:${cursor.getLong(1)}:${cursor.getLong(2)}" else null
            }
        } catch (_: RuntimeException) {
            // MediaStore가 아닌 제공자는 수정 여부를 알 수 없어 캐시하지 않는다.
            null
        } ?: return null
        return "${data.uri}|$version"
    }

    private suspend fun systemThumbnail(target: ThumbnailTarget): Bitmap? {
        val signal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
        return try {
            context.contentResolver.loadThumbnail(data.uri, target.size, signal).scaleAndRecycle(target.size, target.fill)
        } catch (_: IOException) {
            null
        }
    }

    private fun original(): FetchResult {
        val resolver = context.contentResolver
        val descriptor = checkNotNull(resolver.openAssetFileDescriptor(data.uri, "r")) {
            "Unable to open ${data.uri}."
        }
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
        private val context = context.applicationContext

        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(context, data, options, storage.local, remoteImages)
    }
}

class ThumbnailKeyer : Keyer<Thumbnail> {
    override fun key(data: Thumbnail, options: Options): String = data.uri.toString()
}

private const val FALLBACK_PX = 512
