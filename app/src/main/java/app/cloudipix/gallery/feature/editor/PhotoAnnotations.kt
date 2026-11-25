package app.cloudipix.gallery.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.min

internal sealed interface PhotoAnnotation

internal data class PhotoStroke(val points: List<Offset>, val color: Int, val width: Float) : PhotoAnnotation

internal data class PhotoText(
    val id: Long,
    val text: String,
    val center: Offset,
    val color: Int,
    val size: Float,
) : PhotoAnnotation

internal fun PhotoEdit.sourcePoint(position: Offset): Offset {
    val x = frame.left + position.x * frame.width
    val y = frame.top + position.y * frame.height
    val orientedX = if (mirrored) 1f - x else x
    return when (rotation) {
        1 -> Offset(y, 1f - orientedX)
        2 -> Offset(1f - orientedX, 1f - y)
        3 -> Offset(1f - y, orientedX)
        else -> Offset(orientedX, y)
    }
}

/** Annotation positions stay attached to the photo through crops, rotations and mirroring. */
internal class AnnotationProjection(private val edit: PhotoEdit, private val image: Rect) {
    val shortSide = min(image.width / edit.frame.width, image.height / edit.frame.height)

    fun sourcePoint(position: Offset): Offset = edit.sourcePoint(
        Offset(
            ((position.x - image.left) / image.width).coerceIn(0f, 1f),
            ((position.y - image.top) / image.height).coerceIn(0f, 1f),
        ),
    )

    fun imagePoint(source: Offset): Offset {
        val oriented = when (edit.rotation) {
            1 -> Offset(1f - source.y, source.x)
            2 -> Offset(1f - source.x, 1f - source.y)
            3 -> Offset(source.y, 1f - source.x)
            else -> source
        }
        val x = if (edit.mirrored) 1f - oriented.x else oriented.x
        return Offset(
            image.left + (x - edit.frame.left) * image.width / edit.frame.width,
            image.top + (oriented.y - edit.frame.top) * image.height / edit.frame.height,
        )
    }

    fun draw(canvas: Canvas, annotations: List<PhotoAnnotation>) {
        if (annotations.isEmpty()) return
        val save = canvas.save()
        try {
            canvas.clipRect(image.left, image.top, image.right, image.bottom)
            annotations.forEach { annotation ->
                when (annotation) {
                    is PhotoStroke -> drawStroke(canvas, annotation)
                    is PhotoText -> {
                        val layout = textLayout(annotation)
                        val origin = imagePoint(annotation.center) - Offset(layout.width / 2f, layout.height / 2f)
                        val textSave = canvas.save()
                        canvas.translate(origin.x, origin.y)
                        layout.draw(canvas)
                        canvas.restoreToCount(textSave)
                    }
                }
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    fun labelBounds(label: PhotoText): Rect {
        val layout = textLayout(label)
        val center = imagePoint(label.center)
        return Rect(center.x - layout.width / 2f, center.y - layout.height / 2f,
            center.x + layout.width / 2f, center.y + layout.height / 2f)
    }

    fun placeLabel(label: PhotoText, position: Offset, labelSize: Size = labelBounds(label).size): PhotoText {
        val center = Offset(
            position.x.keepLabelIn(image.left, image.right, labelSize.width / 2f),
            position.y.keepLabelIn(image.top, image.bottom, labelSize.height / 2f),
        )
        return label.copy(center = sourcePoint(center))
    }

    private fun textLayout(label: PhotoText): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = label.color
            textSize = (shortSide * label.size).coerceAtLeast(1f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val shadow = if (android.graphics.Color.luminance(color) > 0.45f) 0xAA000000.toInt()
                else 0xAAFFFFFF.toInt()
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.035f, shadow)
        }
        val maxWidth = (shortSide * 0.86f).toInt().coerceAtLeast(1)
        val lineWidth = ceil(Layout.getDesiredWidth(label.text, paint)).toInt().coerceIn(1, maxWidth)
        return StaticLayout.Builder.obtain(label.text, 0, label.text.length, paint, lineWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
    }

    private fun drawStroke(canvas: Canvas, stroke: PhotoStroke) {
        val start = stroke.points.firstOrNull()?.let(::imagePoint) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color
            strokeWidth = (shortSide * stroke.width).coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (stroke.points.size == 1) {
            canvas.drawCircle(start.x, start.y, paint.strokeWidth / 2f, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        val path = Path().apply {
            moveTo(start.x, start.y)
            for (index in 1 until stroke.points.size) {
                val end = imagePoint(stroke.points[index])
                lineTo(end.x, end.y)
            }
        }
        canvas.drawPath(path, paint)
    }
}

internal fun PhotoEdit.annotate(bitmap: Bitmap, reuse: Boolean = false): Bitmap {
    if (annotations.isEmpty()) return bitmap
    val result = if (reuse && bitmap.isMutable) bitmap else requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true))
    try {
        AnnotationProjection(this, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
            .draw(Canvas(result), annotations)
        return result
    } catch (error: Throwable) {
        if (result !== bitmap) result.recycle()
        throw error
    }
}

private fun Float.keepLabelIn(start: Float, end: Float, radius: Float): Float =
    if (radius * 2f >= end - start) (start + end) / 2f else coerceIn(start + radius, end - radius)
