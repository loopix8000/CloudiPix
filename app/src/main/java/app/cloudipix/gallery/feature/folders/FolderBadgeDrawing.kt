package app.cloudipix.gallery.feature.folders

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.graphics.withSave
import app.cloudipix.gallery.ui.component.CLOUD_ICON_ARGB
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.FavoriteRed

private fun ImageVector.singlePath() = (root[0] as VectorPath).pathData.toPath().asAndroidPath()

private val heart by lazy { GalleryIcons.HeartFilled.singlePath() }
private val lock by lazy { GalleryIcons.Lock.singlePath() }
private val pin by lazy { GalleryIcons.Pin.singlePath() }
private val hide by lazy { GalleryIcons.Hide.singlePath() }
private val check by lazy { GalleryIcons.Check.singlePath() }

internal data class FolderBadgesState(
    val favorite: Boolean, val empty: Boolean, val locked: Boolean, val pinned: Boolean, val hidden: Boolean,
    val selecting: Boolean, val selected: Boolean, val cloud: Path?,
)

internal fun Canvas.drawFolderBadges(
    state: FolderBadgesState, brush: Paint, width: Float, rtl: Boolean, accent: Int, onAccent: Int,
) {
    fun start(padding: Float, size: Float) = if (rtl) width - padding - size else padding
    fun end(padding: Float, size: Float) = if (rtl) padding else width - padding - size
    fun circle(x: Float, y: Float, radius: Float, color: Int) {
        brush.color = color
        brush.style = Paint.Style.FILL
        drawCircle(x, y, radius, brush)
    }
    fun glyph(path: Path, x: Float, y: Float, size: Float, color: Int, filled: Boolean = false, viewport: Float = 20f) {
        withSave {
            translate(x, y)
            scale(size / viewport, size / viewport)
            brush.color = color
            brush.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            brush.strokeWidth = if (viewport == 24f) 2f else 1.5f
            drawPath(path, brush)
        }
    }
    if (state.favorite) {
        val edge = if (state.empty) 32f else 12f
        val center = (width - edge) / 2f
        glyph(heart, if (state.empty) center else end(5f, edge), if (state.empty) center else 5f,
            edge, FavoriteRed.toArgb(), filled = true)
    }
    if (state.locked) glyph(lock, (width - 22f) / 2, (width - 22f) / 2, 22f, 0xd1ffffff.toInt())
    state.cloud?.let { glyph(it, start(if (state.selecting) 28f else 6f, 12f), 6f, 12f, CLOUD_ICON_ARGB, viewport = 24f) }
    fun statusBadge(path: Path, top: Float) {
        val x = end(5f, 20f)
        circle(x + 10f, top + 10f, 10f, 0x8c000000.toInt())
        glyph(path, x + 4f, top + 4f, 12f, 0xffededef.toInt())
    }
    if (state.pinned) statusBadge(pin, 5f)
    if (state.hidden) statusBadge(hide, if (state.pinned) 29f else 5f)
    if (state.selecting) {
        val x = start(5f, 17f)
        circle(x + 8.5f, 13.5f, 8.5f, if (state.selected) accent else 0x660b0b0c)
        if (state.selected) glyph(check, x + 3f, 8f, 11f, onAccent)
    }
}
