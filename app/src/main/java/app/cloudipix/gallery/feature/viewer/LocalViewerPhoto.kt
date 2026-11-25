package app.cloudipix.gallery.feature.viewer

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import app.cloudipix.gallery.core.media.Thumbnail
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState

@Composable
internal fun LocalViewerPhoto(
    uri: Uri, modified: Long, viewport: IntSize, zoom: ZoomableImageState?,
    initial: BitmapImage?, onDecoded: (BitmapImage) -> Unit, onError: () -> Unit, onClick: () -> Unit,
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    val context = LocalContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val request = remember(context, uri, modified, viewport) {
        ImageRequest.Builder(context).data(uri)
            .size(viewport.width, viewport.height).scale(Scale.FIT).precision(Precision.INEXACT)
            .memoryCacheKey("viewer-fit:$uri:$modified:${viewport.width}x${viewport.height}")
            .crossfade(false).build()
    }
    var result by remember(request) { mutableStateOf<SuccessResult?>(null) }
    val fallback = remember(request, initial) {
        val cache = loader.memoryCache
        cache?.get(MemoryCache.Key(request.memoryCacheKey!!))?.image ?: initial ?: cache?.get(Thumbnail(uri).uncroppedKey)?.image
            ?: cache?.get(MemoryCache.Key(uri.toString()))?.image
    }
    val decoded by rememberUpdatedState(onDecoded)
    val failed by rememberUpdatedState(onError)
    LaunchedEffect(request) {
        val loaded = loader.execute(request) as? SuccessResult
        if (loaded == null) failed() else {
            result = loaded
            (loaded.image as? BitmapImage)?.let(decoded)
        }
    }
    val image = result?.image ?: fallback ?: return
    val painter = remember(context, image) { image.asPainter(context) }
    if (zoom == null || result == null) {
        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else if (result?.isSampled == true && image is BitmapImage) {
        // 줌 좌표계는 처음부터 원본 기준으로 유지한다. 타일 렌더러가 준비될 때까지 같은 사진이 아래에 보인다.
        Box(Modifier.fillMaxSize()) {
            if (!zoom.isImageDisplayed) Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            ZoomablePhoto(request, zoom, onClick)
        }
    } else {
        val source = remember(painter) { FittedPhotoSource(painter) }
        ZoomableImage(
            image = source, contentDescription = null, state = zoom, modifier = Modifier.fillMaxSize(),
            onClick = { onClick() }, onDoubleClick = FillScreenOnDoubleTap,
        )
    }
}

private class FittedPhotoSource(painter: Painter) : ZoomableImageSource {
    private val ready = ZoomableImageSource.ResolveResult(ZoomableImageSource.PainterDelegate(painter))
    @Composable
    override fun resolve(canvasSize: Flow<Size>) = ready
}
