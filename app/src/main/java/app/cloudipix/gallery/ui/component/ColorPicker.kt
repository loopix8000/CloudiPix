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

/** 강조색의 명도 범위입니다. 너무 어둡거나 밝아 글자 대비가 무너지지 않도록 이 안에서만 고릅니다. */
private const val MIN_LIGHTNESS = 0.46f
private const val MAX_LIGHTNESS = 0.78f

/** 색상 막대에 보여 줄 무지개 띠입니다. 0°부터 360°까지 60°마다 색을 찍습니다. */
private val HueStrip = Brush.horizontalGradient(List(7) { step -> Color.hsl(step * 60f, 0.72f, 0.62f) })
private val HueStripShape = RoundedCornerShape(3.dp)

/** HSL로 나타낸 강조색입니다. 색상은 0~360°, 채도와 명도는 0~1입니다. */
@Immutable
internal data class AccentHsl(val hue: Float, val saturation: Float, val lightness: Float) {
    val color = Color.hsl(hue, saturation, lightness)
    val argb = color.toArgb()
}

/**
 * 강조색을 색상·채도·명도 막대로 고릅니다. [onChange]는 현재 값을 새 값으로 바꾸는 함수를 받고,
 * 손을 떼면 [onChangeFinished]를 부릅니다. 색상·채도를 바꿀 때도 명도는 허용 범위 안으로 맞춥니다.
 */
@Composable
internal fun AccentPicker(
    selection: AccentHsl,
    onChange: ((AccentHsl) -> AccentHsl) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GalleryTheme.colors
    val surface = colors.settingsSurface
    // 밝은 표면은 살짝, 어두운 표면은 더 어둡게 눌러 패널을 구분합니다.
    val panel = lerp(surface, Color.Black, if (surface.luminance() > 0.5f) 0.06f else 0.22f)
    val preview = selection.color

    Column(
        modifier.fillMaxWidth().background(panel).padding(Spacing.base),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(preview))
            Text(hexLabel(selection.argb), style = GalleryTheme.type.meta, color = colors.textMuted)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(HueStripShape).background(HueStrip))

        HslSlider("Hue", selection.hue / 360f, preview, onChangeFinished) { fraction ->
            onChange { it.copy(hue = fraction * 360f, lightness = it.lightness.clampLightness()) }
        }
        HslSlider("Saturation", selection.saturation, preview, onChangeFinished) { fraction ->
            onChange { it.copy(saturation = fraction, lightness = it.lightness.clampLightness()) }
        }
        HslSlider("Lightness", lightnessFraction(selection.lightness), preview, onChangeFinished) { fraction ->
            onChange { it.copy(lightness = lightnessAt(fraction)) }
        }
    }
}

@Composable
private fun HslSlider(
    label: String,
    fraction: Float,
    accent: Color,
    onChangeFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = GalleryTheme.type.meta, color = GalleryTheme.colors.textMuted, modifier = Modifier.weight(0.3f))
        GallerySlider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            accent = accent,
            modifier = Modifier.weight(0.7f).semantics { contentDescription = label },
        )
    }
}

/** 알파를 뺀 `#RRGGBB` 표기입니다. */
private fun hexLabel(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun Float.clampLightness(): Float = coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)

/** 허용 범위 안의 명도를 막대 위치(0~1)로 바꿉니다. */
private fun lightnessFraction(lightness: Float): Float =
    ((lightness - MIN_LIGHTNESS) / (MAX_LIGHTNESS - MIN_LIGHTNESS)).coerceIn(0f, 1f)

/** 막대 위치(0~1)를 허용 범위 안의 명도로 바꿉니다. */
private fun lightnessAt(fraction: Float): Float = MIN_LIGHTNESS + (MAX_LIGHTNESS - MIN_LIGHTNESS) * fraction

/** RGB 색을 HSL로 바꿉니다. 무채색이면 색상과 채도를 0으로 둡니다. */
internal fun Color.toAccentHsl(): AccentHsl {
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val lightness = (high + low) / 2f
    val chroma = high - low
    if (abs(chroma) < 1e-4f) return AccentHsl(0f, 0f, lightness)

    val saturation = chroma / (1f - abs(2f * lightness - 1f))
    val sector = when (high) {
        red -> ((green - blue) / chroma) % 6f
        green -> (blue - red) / chroma + 2f
        else -> (red - green) / chroma + 4f
    }
    val hue = (sector * 60f + 360f) % 360f
    return AccentHsl(hue, saturation.coerceIn(0f, 1f), lightness)
}
