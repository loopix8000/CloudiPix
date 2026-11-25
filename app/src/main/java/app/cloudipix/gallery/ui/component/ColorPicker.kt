package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val SAFE_LIGHTNESS = 0.46f..0.78f

private val HUE_TRACK = Brush.horizontalGradient(
    (0..6).map { Color.hsl(it * 60f, 0.72f, 0.62f) }
)

@Immutable
internal data class AccentHsl(val hue: Float, val saturation: Float, val lightness: Float) {
    val color = Color.hsl(hue, saturation, lightness)
    val argb = color.toArgb()
}

@Composable
internal fun AccentPicker(
    selection: AccentHsl,
    onChange: ((AccentHsl) -> AccentHsl) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GalleryTheme.colors
    val (hue, saturation, lightness) = selection
    val surface = colors.settingsSurface
    val panelBackground = lerp(surface, Color.Black, if (surface.luminance() > 0.5f) 0.06f else 0.22f)

    Column(
        modifier = modifier.fillMaxWidth()
            .background(panelBackground)
            .padding(Spacing.base),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(selection.color))
            Text(
                "#%06X".format(selection.argb and 0xFFFFFF),
                style = GalleryTheme.type.meta,
                color = colors.textMuted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(HUE_TRACK)
        )
        Track("Hue", hue / 360f, selection.color, onChangeFinished) { value ->
            onChange { it.copy(hue = value * 360f, lightness = it.lightness.coerceIn(SAFE_LIGHTNESS)) }
        }
        Track("Saturation", saturation, selection.color, onChangeFinished) { value ->
            onChange { it.copy(saturation = value, lightness = it.lightness.coerceIn(SAFE_LIGHTNESS)) }
        }
        Track("Lightness", lightness.normalize(), selection.color, onChangeFinished) { value ->
            onChange { it.copy(lightness = SAFE_LIGHTNESS.lerp(value)) }
        }
    }
}

@Composable
private fun Track(
    label: String,
    value: Float,
    accent: Color,
    onChangeFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val colors = GalleryTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = GalleryTheme.type.meta, color = colors.textMuted, modifier = Modifier.weight(0.3f))
        GallerySlider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            accent = accent,
            modifier = Modifier.weight(0.7f).semantics { contentDescription = label },
        )
    }
}

private fun ClosedFloatingPointRange<Float>.lerp(t: Float) = start + (endInclusive - start) * t

private fun Float.normalize() =
    ((this - SAFE_LIGHTNESS.start) / (SAFE_LIGHTNESS.endInclusive - SAFE_LIGHTNESS.start)).coerceIn(0f, 1f)

internal fun Color.toAccentHsl(): AccentHsl {
    val max = max(red, max(green, blue))
    val min = min(red, min(green, blue))
    val lightness = (max + min) / 2f
    if (abs(max - min) < 1e-4f) return AccentHsl(0f, 0f, lightness)

    val delta = max - min
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return AccentHsl((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
}
