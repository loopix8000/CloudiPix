package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = GalleryTheme.colors.accent,
) {
    val colors = GalleryTheme.colors
    val sliderColors = if (colors.background.luminance() < 0.5f) {
        SliderDefaults.colors(activeTrackColor = accent, inactiveTrackColor = colors.textFaint)
    } else {
        SliderDefaults.colors(activeTrackColor = accent)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(Spacing.hitTarget),
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = sliderColors,
        thumb = {
            Box(
                Modifier.size(width = 4.dp, height = 24.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
        },
    )
}
