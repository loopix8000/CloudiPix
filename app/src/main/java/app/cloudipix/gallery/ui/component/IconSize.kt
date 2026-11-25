package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.cloudipix.gallery.ui.theme.GalleryTheme

@Composable
fun Modifier.iconSize(size: Dp): Modifier = size(size * GalleryTheme.iconScale)
