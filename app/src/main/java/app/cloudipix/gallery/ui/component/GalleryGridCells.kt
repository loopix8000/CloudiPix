package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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

/** 사진 격자 칸 한 변의 픽셀 수와 한 화면에 보이는 칸 수의 어림값입니다. */
@Immutable
internal data class MediaGridPage(val cellPx: Int, val cells: Int)

/**
 * 지금 창에서 사진 격자를 열면 쓰일 칸 크기와 한 화면 칸 수를 [galleryGridCells]와 같은 방법으로 어림합니다.
 * 칸 너비는 남는 픽셀을 앞 칸부터 1픽셀씩 나눠 받으므로 가장 넓은 칸에 맞춥니다.
 * 머리글 높이는 빼지 않아 칸 수가 실제보다 조금 넉넉합니다.
 */
@Composable
internal fun rememberMediaGridPage(portraitColumns: Int, edgePadding: Dp): MediaGridPage? {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val horizontalInsets = insets.getLeft(density, direction) + insets.getRight(density, direction)
    val verticalInsets = insets.getTop(density) + insets.getBottom(density)
    return remember(window, density, horizontalInsets, verticalInsets, portraitColumns, edgePadding) {
        if (window.width <= 0 || window.height <= 0) return@remember null
        val gap = with(density) { edgePadding.roundToPx() }
        val available = window.width - horizontalInsets - 2 * gap
        val portraitWidth = minOf(window.width, window.height) - 2 * gap
        val columns = landscapeGridColumnCount(portraitColumns, portraitWidth, available, gap)
        val cell = (available - gap * (columns - 1) + columns - 1) / columns
        val height = window.height - verticalInsets
        if (cell <= 0 || height <= 0) return@remember null
        val rows = (height + gap + cell + gap - 1) / (cell + gap)
        MediaGridPage(cell, rows * columns)
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
