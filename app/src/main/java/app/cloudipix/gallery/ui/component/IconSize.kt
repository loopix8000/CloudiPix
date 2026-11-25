package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.cloudipix.gallery.ui.theme.GalleryTheme

/** 기준 크기에 글자 크기 설정의 아이콘 배율을 곱한 정사각형 크기를 지정합니다. */
@Composable
fun Modifier.iconSize(size: Dp): Modifier {
    val scaled = size * GalleryTheme.iconScale
    return this.size(scaled)
}
