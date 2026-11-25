package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

private const val LANDSCAPE_THUMBNAIL_SCALE = 1.10

@Composable
internal fun galleryGridCells(portraitColumns: Int, edgePadding: Dp): GridCells {
    // 짧은 변을 세로 기준으로 삼고, 그리드가 실제로 더 넓어졌을 때 열 수를 계산한다.
    val window = LocalWindowInfo.current.containerSize
    val portraitWindowWidth = minOf(window.width, window.height)
    return if (portraitWindowWidth > 0) {
        GalleryGridCells(portraitColumns, portraitWindowWidth, edgePadding)
    } else {
        GridCells.Fixed(portraitColumns)
    }
}

private data class GalleryGridCells(
    val portraitColumns: Int,
    val portraitWindowWidth: Int,
    val edgePadding: Dp,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val portraitWidth = portraitWindowWidth - 2 * edgePadding.roundToPx()
        val columns = landscapeGridColumnCount(portraitColumns, portraitWidth, availableSize, spacing)
        return with(GridCells.Fixed(columns)) { calculateCrossAxisCellSizes(availableSize, spacing) }
    }
}

internal fun landscapeGridColumnCount(
    portraitColumns: Int,
    portraitWidth: Int,
    availableWidth: Int,
    spacing: Int,
): Int {
    if (availableWidth <= portraitWidth) return portraitColumns

    val portraitCellWidth = (portraitWidth + spacing).toDouble() / portraitColumns - spacing
    if (portraitCellWidth <= 0) return portraitColumns

    val targetCellWidth = portraitCellWidth * LANDSCAPE_THUMBNAIL_SCALE
    val widthWithSpacing = availableWidth.toDouble() + spacing
    val fewerColumns = (widthWithSpacing / (targetCellWidth + spacing)).toInt().coerceAtLeast(portraitColumns)
    val moreColumns = fewerColumns + 1
    val smallerCellWidth = widthWithSpacing / moreColumns - spacing
    if (smallerCellWidth < portraitCellWidth) return fewerColumns

    val largerCellWidth = widthWithSpacing / fewerColumns - spacing
    return if (targetCellWidth - smallerCellWidth <= largerCellWidth - targetCellWidth) {
        moreColumns
    } else {
        fewerColumns
    }
}
