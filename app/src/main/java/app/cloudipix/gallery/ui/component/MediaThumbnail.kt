package app.cloudipix.gallery.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.cloudipix.gallery.core.media.CoverBlur
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.ui.theme.GalleryTheme
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations

/**
 * 격자 타일의 썸네일입니다. [coverPixels]를 주면 그 크기로 불러오고, [blurred]이면 흐리게 처리합니다.
 *
 * 원격 사진은 같은 사진의 새 버전을 받는 동안이나 받지 못했을 때 마지막으로 보여 준 이미지를
 * 그대로 두어 타일이 비거나 깜박이지 않게 합니다.
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
) {
    val context = LocalPlatformContext.current
    val radius = GalleryTheme.corners.radius
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val tile = modifier.aspectRatio(aspectRatio).clip(shape).background(GalleryTheme.colors.surface)

    // 원격 사진은 서버와 사진 ID가 같으면 같은 사진으로 봅니다.
    val remoteIdentity = model?.takeIf { it.scheme == REMOTE_MEDIA_SCHEME }?.let { uri ->
        uri.pathSegments.firstOrNull()?.let { photoId -> uri.authority to photoId }
    }
    val lastShown: MutableState<Image?>? = remoteIdentity?.let {
        remember(it, blurred) { mutableStateOf<Image?>(null) }
    }

    val request = remember(context, model, blurred, coverPixels) {
        model?.let { uri ->
            val builder = ImageRequest.Builder(context).data(Thumbnail(uri)).crossfade(false)
            coverPixels?.let { builder.size(it, it) }
            if (blurred) builder.transformations(CoverBlur) else builder.placeholderMemoryCacheKey(uri.toString())
            if (lastShown != null) {
                builder.placeholder { lastShown.value }
                builder.error { lastShown.value }
            }
            builder.build()
        }
    }

    key(blurred) {
        AsyncImage(
            model = request,
            contentDescription = description,
            modifier = tile,
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            onState = lastShown?.let { memory ->
                { state -> if (state is AsyncImagePainter.State.Success) memory.value = state.result.image }
            },
        )
    }
}
