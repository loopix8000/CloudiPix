package app.cloudipix.gallery.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
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
import coil3.request.bitmapConfig
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
 *
 * [embedded]이면 고화질 썸네일이 준비되기 전에 보여 줄 작은 미리보기를 요청합니다.
 * [useCachedPreview]이면 디스크에 있는 썸네일도 작은 크기로 읽어 격자의 미리보기 버퍼에 쓸 수 있습니다.
 * [indexed]가 있으면 디스크 캐시 키와 종류를 색인 값으로 정해 미디어 저장소에 다시 묻지 않습니다.
 * [cachedOnly]이면 디스크에 있는 고화질 썸네일만 읽고, 없으면 새로 만들지 않고 실패합니다.
 */
data class Thumbnail(
    val uri: Uri,
    val embedded: Boolean = false,
    val useCachedPreview: Boolean = false,
    val indexed: IndexedMedia? = null,
    val cachedOnly: Boolean = false,
) {
    /** 정사각형으로 자르지 않은 전체 구도 미리보기의 메모리 캐시 키입니다. */
    val uncroppedKey: MemoryCache.Key get() = MemoryCache.Key("uncropped-thumbnail:$uri")
}

/**
 * 색인에 있는 기기 항목의 수정 버전과 종류입니다. [version]은 `수정 세대:수정 시각(초):크기`로,
 * [ThumbnailFetcher]가 미디어 저장소에서 읽어 디스크 캐시 키로 쓰는 값과 같은 형식입니다.
 */
data class IndexedMedia(val version: String, val video: Boolean)

/**
 * 화면에 그리는 주소가 색인의 기기 항목이면 그 수정 버전을 돌려줍니다.
 * 원격 항목과 받아 둔 사본은 색인 값이 그 주소의 파일을 나타내지 않으므로 null입니다.
 */
val Medium.indexedMedia: IndexedMedia?
    get() = if (remoteUri == null && localCopyUri == null && generation > 0) {
        IndexedMedia("$generation:${dateModified / 1000}:$size", type == MediaType.Video)
    } else {
        null
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
        if (data.embedded) return sampled(fetchEmbedded(), owned = true)
        if (data.cachedOnly) return sampled(fetchCached(), owned = true)
        if (data.uri.scheme == REMOTE_MEDIA_SCHEME) {
            return sampled(remoteImages.thumbnail(data.uri, target))
        }
        return withContext(thumbnailDispatcher) { fetchLocal() }
    }

    /** 디스크에 있는 고화질 썸네일만 읽습니다. 금방 끝나므로 원본에서 새로 만드는 작업 뒤에 줄 세우지 않습니다. */
    private suspend fun fetchCached(): Bitmap {
        val cached = if (data.uri.scheme == REMOTE_MEDIA_SCHEME) {
            remoteImages.cachedThumbnail(data.uri, target)
        } else {
            withContext(embeddedThumbnailDispatcher) { thumbnails.read(cacheKey(), target) }
        }
        return cached ?: throw IOException("디스크에 고화질 썸네일이 없습니다.")
    }

    private suspend fun fetchLocal(): FetchResult {
        val cached = thumbnails.get(cacheKey(), target) { wanted ->
            // 시스템 썸네일은 EXIF에 든 128px 미리보기를 다시 압축한 것일 수 있어 사진은 원본에서 직접 만듭니다.
            val isImage = data.indexed?.let { !it.video }
                ?: (context.contentResolver.getType(data.uri)?.startsWith("image/") == true)
            val decoded = if (isImage) decodeFromOriginal(wanted) else null
            decoded ?: loadSystemThumbnail(wanted)
        }
        return if (cached != null) sampled(cached) else originalSource()
    }

    /**
     * 고화질 썸네일을 만드는 동안 잠깐 보여 줄 썸네일입니다. 고화질 썸네일이 이미 디스크에 있으면 바로 준비되므로 만들지 않습니다.
     * JPEG·HEIF·RAW는 파일에 든 썸네일을 서버 사진과 같은 방법으로 꺼냅니다. 내장 썸네일이 없거나 PNG처럼 담을 수 없는
     * 형식이면 시스템 썸네일을 씁니다. 동영상은 고화질 썸네일도 시스템 썸네일이라 따로 만들지 않습니다.
     */
    private suspend fun fetchEmbedded(): Bitmap {
        if (data.uri.scheme == REMOTE_MEDIA_SCHEME) return remoteImages.embeddedThumbnail(data.uri, target, data.useCachedPreview)
        return withContext(embeddedThumbnailDispatcher) {
            val resolver = context.contentResolver
            val indexed = data.indexed
            // 색인에서 종류를 알면 형식은 파일을 직접 읽어야 할 때만 묻습니다.
            var mimeType = if (indexed == null) resolver.getType(data.uri) else null
            val video = indexed?.video ?: (mimeType?.startsWith("video/") == true)
            if (video) throw IOException("동영상은 시스템 썸네일을 바로 씁니다.")
            thumbnails.readQuickPreview(cacheKey(), target, data.useCachedPreview)?.let { return@withContext it }
            if (indexed != null) mimeType = resolver.getType(data.uri)
            val embedded = embeddedFormat(mimeType, data.uri.lastPathSegment)?.let { format ->
                try {
                    resolver.openFileDescriptor(data.uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }?.use { input ->
                        readEmbeddedThumbnail(format, FileRangeReader(input.channel), target)
                    }
                } catch (_: IOException) {
                    null
                }
            }
            // 시스템 썸네일은 미디어 저장소 항목만 저장소가 따로 보관하므로 그 밖의 제공자에는 쓰지 않습니다.
            val quick = embedded ?: if (data.uri.authority == MediaStore.AUTHORITY) loadSystemThumbnail(target) else null
            quick ?: throw IOException("빠르게 보여 줄 썸네일이 없습니다.")
        }
    }

    /**
     * [owned]이면 [bitmap]을 다른 곳에서 쓰지 않아 하드웨어 비트맵으로 바꾼 뒤 바로 해제할 수 있다.
     * 디스크 캐시에 새로 만든 썸네일은 기록이 끝날 때까지 원래 비트맵을 쓰므로 해제하지 않는다.
     */
    private fun sampled(bitmap: Bitmap, owned: Boolean = false): FetchResult =
        ImageFetchResult(bitmap.forDisplay(owned).asImage(), isSampled = true, dataSource = DataSource.DISK)

    /**
     * 요청이 허용하면 하드웨어 비트맵으로 바꾼다. 처음 그릴 때 렌더 스레드가 하던 GPU 업로드를 이 작업 스레드에서 끝낸다.
     * 변환이 있거나 소프트웨어 비트맵을 요구한 요청은 Coil이 비트맵 설정을 ARGB_8888로 주므로 그대로 둔다.
     */
    private fun Bitmap.forDisplay(owned: Boolean): Bitmap {
        if (options.bitmapConfig != Bitmap.Config.HARDWARE || config == Bitmap.Config.HARDWARE) return this
        val hardware = copy(Bitmap.Config.HARDWARE, false) ?: return this
        if (owned) recycle()
        return hardware
    }

    private fun decodeFromOriginal(wanted: ThumbnailTarget): Bitmap? = try {
        decodeThumbnail(ImageDecoder.createSource(context.contentResolver, data.uri), wanted.size, wanted.fill)
    } catch (_: IOException) {
        null
    }

    /**
     * 디스크 캐시 키입니다. 사진을 편집하거나 회전하면 MediaStore의 수정 세대가 바뀌어 키도 바뀝니다.
     * 색인에서 버전을 받았으면 그 값을 쓰고, 아니면 미디어 저장소에서 읽습니다.
     * MediaStore가 아닌 제공자는 수정 여부를 알 수 없어 `null`을 돌려 캐시하지 않습니다.
     */
    private fun cacheKey(): String? {
        data.indexed?.let { return "${data.uri}|${it.version}" }
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

/**
 * 썸네일 메모리 캐시 키는 URI 문자열입니다. 뷰어가 같은 키로 캐시된 썸네일을 찾습니다.
 * 디스크 캐시만 읽는 요청도 같은 키를 써서, 읽어 둔 썸네일을 고화질 요청이 메모리에서 바로 씁니다.
 * 내장 썸네일 요청은 메모리 캐시를 쓰지 않지만, 고화질 썸네일과 섞이지 않도록 키를 따로 둡니다.
 */
class ThumbnailKeyer : Keyer<Thumbnail> {
    override fun key(data: Thumbnail, options: Options): String =
        if (data.embedded) "embedded-thumbnail:${data.uri}" else data.uri.toString()
}
