package app.cloudipix.gallery.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import app.cloudipix.gallery.R
import com.t8rin.cropper.crop.roundedInBounds
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageExposureFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGrayscaleFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSepiaToneFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageWhiteBalanceFilter
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

internal enum class Adjustment(@StringRes val label: Int) {
    Brightness(R.string.editor_brightness),
    Exposure(R.string.editor_exposure),
    Contrast(R.string.editor_contrast),
    Saturation(R.string.editor_saturation),
    Temperature(R.string.editor_temperature),
}

internal enum class PhotoFilter(@StringRes val label: Int) {
    Original(R.string.photo_editor_original),
    Monochrome(R.string.photo_editor_mono),
    Sepia(R.string.photo_editor_sepia),
    Warm(R.string.photo_editor_warm),
    Cool(R.string.photo_editor_cool),
    Vivid(R.string.photo_editor_vivid),
}

private val fullFrame = Rect(0f, 0f, 1f, 1f)

internal data class PhotoEdit(
    val frame: Rect = fullFrame,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val filter: PhotoFilter = PhotoFilter.Original,
    val adjustments: Map<Adjustment, Int> = emptyMap(),
    val annotations: List<PhotoAnnotation> = emptyList(),
) {
    fun withoutAnnotations() = if (annotations.isEmpty()) this else copy(annotations = emptyList())

    fun addAnnotation(annotation: PhotoAnnotation) = copy(annotations = annotations + annotation)

    fun replaceText(id: Long, replacement: PhotoText?) = copy(
        annotations = annotations.mapNotNull { if (it is PhotoText && it.id == id) replacement else it },
    )

    fun rotated(clockwise: Boolean): PhotoEdit {
        val step = if (clockwise) 1 else -1
        val angle = (rotation + if (mirrored) -step else step).mod(4)
        val rect = if (clockwise) Rect(1 - frame.bottom, frame.left, 1 - frame.top, frame.right)
            else Rect(frame.top, 1 - frame.right, frame.bottom, 1 - frame.left)
        return copy(frame = rect, rotation = angle)
    }

    fun mirror() = copy(mirrored = !mirrored, frame = Rect(1 - frame.right, frame.top, 1 - frame.left, frame.bottom))

    fun cropped(rect: Rect) = copy(frame = Rect(
        left = frame.left + frame.width * rect.left,
        top = frame.top + frame.height * rect.top,
        right = frame.left + frame.width * rect.right,
        bottom = frame.top + frame.height * rect.bottom,
    ))

    fun adjusted(adjustment: Adjustment, value: Int) = copy(
        adjustments = if (value == 0) adjustments - adjustment else adjustments + (adjustment to value)
    )
}

internal fun decodeEditorImage(context: Context, uri: Uri, preview: Boolean): Bitmap {
    val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path)))
        else ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (preview) {
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > 1440) {
                val scale = 1440f / largest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
    }
}

internal suspend fun PhotoEdit.render(context: Context, original: Bitmap, releaseOriginal: Boolean = false): Bitmap {
    val oriented = if (rotation == 0 && !mirrored) original else {
        val transform = Matrix().apply {
            postRotate(rotation * 90f)
            if (mirrored) postScale(-1f, 1f)
        }
        Bitmap.createBitmap(original, 0, 0, original.width, original.height, transform, false)
    }
    if (releaseOriginal && oriented !== original) original.recycle()
    val framed = if (frame == fullFrame) oriented else {
        val rect = Rect(frame.left * oriented.width, frame.top * oriented.height,
            frame.right * oriented.width, frame.bottom * oriented.height)
            .roundedInBounds(oriented.width, oriented.height)
        Bitmap.createBitmap(oriented, rect.left, rect.top, rect.width, rect.height)
    }
    if (oriented !== framed && (oriented !== original || releaseOriginal)) oriented.recycle()
    return try {
        val colored = applyFilters(context, framed)
        if (colored !== framed && (framed !== original || releaseOriginal)) framed.recycle()
        colored
    } catch (error: Throwable) {
        if (framed !== original || releaseOriginal) framed.recycle()
        throw error
    }
}

private suspend fun PhotoEdit.applyFilters(context: Context, image: Bitmap): Bitmap {
    if (filter == PhotoFilter.Original && adjustments.isEmpty()) return image
    val engine = GPUImage(context)
    fun apply(tile: Bitmap): Bitmap {
        val filters = buildList {
            when (filter) {
                PhotoFilter.Original -> Unit
                PhotoFilter.Monochrome -> add(GPUImageGrayscaleFilter())
                PhotoFilter.Sepia -> add(GPUImageSepiaToneFilter(0.85f))
                PhotoFilter.Warm -> add(GPUImageWhiteBalanceFilter(6500f, 0f))
                PhotoFilter.Cool -> add(GPUImageWhiteBalanceFilter(3900f, 0f))
                PhotoFilter.Vivid -> {
                    add(GPUImageSaturationFilter(1.35f))
                    add(GPUImageContrastFilter(1.08f))
                }
            }
            Adjustment.entries.forEach { adjustment ->
                val value = (adjustments[adjustment] ?: 0) / 100f
                if (value == 0f) return@forEach
                add(when (adjustment) {
                    Adjustment.Brightness -> GPUImageBrightnessFilter(value * 0.28f)
                    Adjustment.Exposure -> GPUImageExposureFilter(value * 1.5f)
                    Adjustment.Contrast -> GPUImageContrastFilter(1f + value * 0.7f)
                    Adjustment.Saturation -> GPUImageSaturationFilter(1f + value)
                    Adjustment.Temperature -> GPUImageWhiteBalanceFilter(5000f + value * 1600f, 0f)
                })
            }
        }
        val pipeline: GPUImageFilter = filters.singleOrNull() ?: GPUImageFilterGroup(filters)
        engine.setFilter(pipeline)
        return requireNotNull(engine.getBitmapWithFilterApplied(tile))
    }
    val edge = 2048
    if (image.width <= edge && image.height <= edge) return apply(image)

    // These filters only use each pixel's own color, so large exports can use the same pipeline in tiles.
    val result = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    try {
        for (top in 0 until image.height step edge) {
            for (left in 0 until image.width step edge) {
                coroutineContext.ensureActive()
                val tile = Bitmap.createBitmap(image, left, top, minOf(edge, image.width - left), minOf(edge, image.height - top))
                try {
                    val colored = apply(tile)
                    try {
                        canvas.drawBitmap(colored, left.toFloat(), top.toFloat(), null)
                    } finally {
                        if (colored !== tile) colored.recycle()
                    }
                } finally {
                    tile.recycle()
                }
            }
        }
    } catch (error: Throwable) {
        result.recycle()
        throw error
    }
    return result
}
