package app.cloudipix.gallery.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
    val shape = RoundedCornerShape(GalleryTheme.corners.radius)
    val surface = GalleryTheme.colors.surface
    val tile = modifier.aspectRatio(aspectRatio).clip(shape).background(surface)
    val scale = if (crop) ContentScale.Crop else ContentScale.Fit
    val remotePhotoId = model?.takeIf { it.scheme == REMOTE_MEDIA_SCHEME }?.let { uri ->
        uri.pathSegments.firstOrNull()?.let { id -> uri.authority to id }
    }
    val displayed = if (remotePhotoId != null) {
        remember(remotePhotoId, blurred) { mutableStateOf<Image?>(null) }
    } else null
    val request = remember(context, model, blurred, coverPixels) {
        model?.let {
            ImageRequest.Builder(context).data(Thumbnail(it)).crossfade(false)
                .apply {
                    if (coverPixels != null) size(coverPixels, coverPixels)
                    if (blurred) transformations(CoverBlur)
                    else placeholderMemoryCacheKey(it.toString())
                    if (displayed != null) {
                        placeholder { displayed.value }
                        error { displayed.value }
                    }
                }
                .build()
        }
    }
    key(blurred) {
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = scale,
            modifier = tile,
            onState = displayed?.let { latest ->
                { state ->
                    if (state is AsyncImagePainter.State.Success) latest.value = state.result.image
                }
            },
        )
    }
}
