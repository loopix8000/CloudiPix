package app.cloudipix.gallery.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlin.math.min

internal const val PHOTO_ANNOTATION_LAYER = "photo_annotation_layer"

@Composable
internal fun PhotoAnnotationLayer(
    edit: PhotoEdit,
    photoSize: Size,
    drawing: Boolean,
    editingText: Boolean,
    pen: AnnotationInk,
    penWidth: Float,
    selectedTextId: Long?,
    onStroke: (PhotoStroke) -> Unit,
    onSelectText: (Long?) -> Unit,
    onMoveText: (PhotoText) -> Unit,
    onPlaceText: (Offset) -> Unit,
) {
    val accent = GalleryTheme.colors.accent
    val currentEdit by rememberUpdatedState(edit)
    val currentStrokeCallback by rememberUpdatedState(onStroke)
    val currentSelectCallback by rememberUpdatedState(onSelectText)
    val currentMoveCallback by rememberUpdatedState(onMoveText)
    val currentPlaceCallback by rememberUpdatedState(onPlaceText)
    val points = remember { mutableStateListOf<Offset>() }
    var moving by remember { mutableStateOf<PhotoText?>(null) }
    val input = if (drawing || editingText) {
        Modifier.pointerInput(drawing, photoSize, pen, penWidth) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val bounds = fittedPhotoRect(Size(size.width.toFloat(), size.height.toFloat()), photoSize)
                if (bounds.isEmpty || !bounds.contains(down.position)) return@awaitEachGesture
                val projection = AnnotationProjection(currentEdit, bounds)
                down.consume()
                if (drawing) {
                    points.add(projection.sourcePoint(down.position))
                    try {
                        val completed = drag(down.id) { change ->
                            val point = projection.sourcePoint(change.position)
                            if ((projection.imagePoint(points.last()) - projection.imagePoint(point)).getDistanceSquared() >= 1f) {
                                points.add(point)
                            }
                            change.consume()
                        }
                        if (completed) currentStrokeCallback(PhotoStroke(points.toList(), pen.argb, penWidth))
                    } finally {
                        points.clear()
                    }
                } else {
                    val target = currentEdit.annotations.asReversed().firstOrNull {
                        it is PhotoText && projection.labelBounds(it).inflate(8.dp.toPx()).contains(down.position)
                    } as? PhotoText
                    if (target == null) {
                        var tapped = true
                        val completed = drag(down.id) { change ->
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) tapped = false
                            change.consume()
                        }
                        if (completed && tapped) currentPlaceCallback(projection.sourcePoint(down.position))
                        return@awaitEachGesture
                    }
                    currentSelectCallback(target.id)
                    val center = projection.imagePoint(target.center)
                    val labelSize = projection.labelBounds(target).size
                    try {
                        val completed = drag(down.id) { change ->
                            val position = center + (change.position - down.position)
                            moving = projection.placeLabel(target, position, labelSize)
                            change.consume()
                        }
                        if (completed) moving?.let(currentMoveCallback)
                    } finally {
                        moving = null
                    }
                }
            }
        }
    } else Modifier

    Canvas(Modifier.fillMaxSize().then(input).testTag(PHOTO_ANNOTATION_LAYER)) {
        val bounds = fittedPhotoRect(size, photoSize)
        if (bounds.isEmpty) return@Canvas
        val projection = AnnotationProjection(edit, bounds)
        val moved = moving
        val annotations = if (moved == null) edit.annotations else edit.replaceText(moved.id, moved).annotations
        projection.draw(drawContext.canvas.nativeCanvas, annotations)
        if (drawing && points.isNotEmpty()) {
            projection.draw(drawContext.canvas.nativeCanvas, listOf(PhotoStroke(points, pen.argb, penWidth)))
        }
        if (editingText && selectedTextId != null) {
            val selected = annotations.filterIsInstance<PhotoText>().firstOrNull { it.id == selectedTextId }
            selected?.let {
                val outline = projection.labelBounds(it).inflate(4.dp.toPx()).intersect(bounds)
                if (!outline.isEmpty) drawRect(accent, topLeft = outline.topLeft, size = outline.size,
                    style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}

private fun fittedPhotoRect(container: Size, photo: Size): Rect {
    val scale = min(container.width / photo.width, container.height / photo.height)
    val fitted = Size(photo.width * scale, photo.height * scale)
    return Rect(Offset((container.width - fitted.width) / 2f, (container.height - fitted.height) / 2f), fitted)
}
