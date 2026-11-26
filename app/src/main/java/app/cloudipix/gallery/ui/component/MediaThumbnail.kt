package app.cloudipix.gallery.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.cloudipix.gallery.core.media.CoverBlur
import app.cloudipix.gallery.core.media.IndexedMedia
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.ui.theme.GalleryTheme
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.size.Precision

/**
 * 격자 타일의 썸네일입니다. [coverPixels]를 주면 그 크기로 불러오고, [blurred]이면 흐리게 처리합니다.
 * [quickPreview]이면 고화질 썸네일이 준비될 때까지 내장 썸네일을 먼저 보여 줍니다. 동영상처럼 먼저 보여 줄
 * 썸네일이 없는 항목은 false로 두어 실패할 요청을 만들지 않습니다.
 *
 * 원격 사진은 같은 사진의 새 버전을 받는 동안이나 받지 못했을 때 마지막으로 보여 준 이미지를
 * 그대로 두어 타일이 비거나 깜박이지 않게 합니다.
 *
 * [indexed]는 색인에서 이미 아는 기기 항목의 수정 버전입니다. 주면 불러올 때 미디어 저장소에 다시 묻지 않습니다.
 */
@Composable
fun MediaThumbnail(
    model: Uri?,
    modifier: Modifier = Modifier,
    crop: Boolean = true,
    aspectRatio: Float = 1f,
    description: String? = null,
    blurred: Boolean = false,
    coverPixels: Int? = null,
    quickPreview: Boolean = true,
    bufferedPreview: ImageBitmap? = null,
    loadFull: Boolean = true,
    onFullFinished: (() -> Unit)? = null,
    indexed: IndexedMedia? = null,
) {
    val context = LocalPlatformContext.current
    val radius = GalleryTheme.corners.radius
    val shape = remember(radius) { RoundedCornerShape(radius) }

    // 원격 사진은 서버와 사진 ID가 같으면 같은 사진으로 봅니다.
    val remoteIdentity = model?.takeIf { it.scheme == REMOTE_MEDIA_SCHEME }?.let { uri ->
        uri.pathSegments.firstOrNull()?.let { photoId -> uri.authority to photoId }
    }
    val lastShown: MutableState<Image?>? = remoteIdentity?.let {
        remember(it, blurred) { mutableStateOf<Image?>(null) }
    }

    val request = remember(context, model, blurred, coverPixels, indexed) {
        model?.let { uri ->
            val builder = ImageRequest.Builder(context).data(Thumbnail(uri, indexed = indexed)).precision(Precision.EXACT).crossfade(false)
            coverPixels?.let { builder.size(it, it) }
            if (blurred) builder.transformations(CoverBlur) else builder.placeholderMemoryCacheKey(uri.toString())
            if (lastShown != null) {
                builder.placeholder { lastShown.value }
                builder.error { lastShown.value }
            }
            builder.build()
        }
    }

    // 고화질 썸네일이 메모리에 없으면 그동안 내장 썸네일을 아래에 깔아 둡니다. 잠깐 보여 주는 용도라 캐시하지 않습니다.
    // 고화질 썸네일을 보여 주면 내장 썸네일은 내려놓고, 고화질 썸네일을 받지 못하면 계속 보여 줍니다.
    var fullShown by remember(model, blurred, loadFull) { mutableStateOf(false) }
    val embeddedRequest = remember(context, model, blurred, coverPixels, quickPreview, indexed) {
        model?.takeIf { quickPreview && !blurred && SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(it.toString())) == null }
            ?.let { uri ->
                val builder = ImageRequest.Builder(context)
                    .data(Thumbnail(uri, embedded = true, indexed = indexed))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .crossfade(false)
                coverPixels?.let { builder.size(it, it) }
                builder.build()
            }
    }
    val contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
    var embeddedShown by remember(embeddedRequest) { mutableStateOf(false) }
    val showPlaceholder = model != null && !fullShown && !embeddedShown &&
        (bufferedPreview == null || blurred) && lastShown?.value == null
    val colors = GalleryTheme.colors
    val tile = modifier.aspectRatio(aspectRatio).clip(shape)
        .background(if (showPlaceholder) colors.hairline else colors.surface)

    key(blurred) {
        Box(if (!loadFull && description != null) tile.semantics { contentDescription = description } else tile) {
            if (bufferedPreview != null && !blurred && !fullShown) {
                androidx.compose.foundation.Image(
                    bitmap = bufferedPreview,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            } else if (embeddedRequest != null && !fullShown) {
                AsyncImage(
                    model = embeddedRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    onState = { embeddedShown = it.painter != null },
                )
            }
            if (loadFull) AsyncImage(
                model = request,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) {
                        fullShown = true
                        lastShown?.value = state.result.image
                        onFullFinished?.invoke()
                    } else if (state is AsyncImagePainter.State.Error) onFullFinished?.invoke()
                },
            )
        }
    }
}
