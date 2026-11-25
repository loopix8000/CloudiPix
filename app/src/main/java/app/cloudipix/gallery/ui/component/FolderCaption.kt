package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.GalleryTheme

/** 오버레이는 셀과 크기가 같다. 별도 텍스트 레이아웃 노드 없이 사진과 같은 셀에 그린다. */
@Composable
internal fun Modifier.folderCaptionOverlay(
    name: String?, count: Int?, size: Long?, compact: Boolean, measurer: TextMeasurer,
): Modifier {
    if (name == null && count == null && size == null) return this
    val title = remember(name) { name?.let(::AnnotatedString) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val details = remember(count, size, locale) {
        listOfNotNull(count?.let(::formatCount), size?.let(::formatSize)).takeIf { it.isNotEmpty() }
            ?.let { AnnotatedString(it.joinToString(" · ")) }
    }
    val titleStyle = if (compact) GalleryTheme.type.meta else GalleryTheme.type.label
    val detailStyle = GalleryTheme.type.meta
    val radius = GalleryTheme.corners.radius
    return semantics { this[SemanticsProperties.Text] = listOfNotNull(title, details) }.drawWithCache {
        val x = (if (compact) 4.dp else 6.dp).roundToPx()
        val bottom = (if (compact) 3.dp else 5.dp).roundToPx()
        val width = Constraints(maxWidth = (this.size.width.toInt() - 2 * x).coerceAtLeast(0))
        val heading = title?.let {
            measurer.measure(it, titleStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = width)
        }
        val info = details?.let {
            measurer.measure(it, detailStyle, TextOverflow.Ellipsis, maxLines = if (count != null && size != null) 2 else 1,
                constraints = width)
        }
        val detailY = this.size.height - bottom - (info?.size?.height ?: 0)
        val spacing = if (heading != null && info != null) 2.dp.roundToPx() else 0
        val headingY = detailY - spacing - (heading?.size?.height ?: 0)
        val gradient = Brush.verticalGradient(0.48f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.43f))
        val corner = CornerRadius(radius.toPx())
        val startX = { lineWidth: Int ->
            if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) x.toFloat()
            else this.size.width - x - lineWidth
        }
        onDrawWithContent {
            drawContent()
            drawRoundRect(gradient, cornerRadius = corner)
            if (heading != null) drawText(heading, Color(0xFFEDEDEF), Offset(startX(heading.size.width), headingY))
            if (info != null) drawText(info, Color(0xFFD8D8DB), Offset(startX(info.size.width), detailY))
        }
    }
}

@Composable
internal fun FolderCaption(
    name: String?,
    count: Int?,
    size: Long?,
    compact: Boolean,
) {
    if (name == null && count == null && size == null) return

    val colors = GalleryTheme.colors
    val type = GalleryTheme.type

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (name != null) {
            BasicText(
                name,
                style = if (compact) type.meta else type.label,
                color = { colors.text },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count != null || size != null) {
            val details = when {
                count == null -> formatSize(checkNotNull(size))
                size == null -> formatCount(count)
                else -> "${formatCount(count)} · ${formatSize(size)}"
            }
            BasicText(
                details,
                style = type.meta,
                color = { colors.textMuted },
                maxLines = if (count != null && size != null) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
