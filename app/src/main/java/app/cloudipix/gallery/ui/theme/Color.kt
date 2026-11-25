package app.cloudipix.gallery.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.cloudipix.gallery.R

/** 앱 전체 밝기 모드입니다. 이름은 설정 값으로 저장되고, 순서는 설정 화면의 선택지 순서입니다. */
enum class ThemeMode(@StringRes val labelRes: Int) {
    System(R.string.mode_auto),
    Light(R.string.mode_light),
    Dark(R.string.mode_dark),
    Black(R.string.mode_black),
}

/** 화면별 밝기 모드입니다. [Gallery]는 앱 전체 설정을 따릅니다. 이름은 설정 값으로 저장됩니다. */
enum class ScreenTheme(@StringRes val labelRes: Int, val mode: ThemeMode?) {
    Gallery(R.string.settings_theme_follow_gallery, null),
    System(R.string.follow_system, ThemeMode.System),
    Light(R.string.mode_light, ThemeMode.Light),
    Dark(R.string.mode_dark, ThemeMode.Dark),
    Black(R.string.mode_black, ThemeMode.Black),
}

/** 설정 화면에서 바로 고를 수 있는 강조색 견본입니다. */
enum class Accent(val color: Color) {
    Indigo(Color(0xFF7493E0)),
    Amber(Color(0xFFE0A458)),
    Jade(Color(0xFF5FBF8F)),
    Rose(Color(0xFFD96A8B)),
    Violet(Color(0xFFA98BFF)),
}

internal val FavoriteRed = Color(0xFFEF4055)

/** 강조색 위 글자색입니다. 밝은 강조색에는 거의 검은색, 어두운 강조색에는 흰색을 씁니다. */
private val InkOnLight = Color(0xFF0B0B0C)
private val InkOnDark = Color.White

private val DarkControl = Color(0xFF303034)

@Immutable
data class GalleryColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val hairline: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val menuValueSurface: Color,
    val menuValueText: Color,
    /** 메뉴·시트 바탕입니다. `null`이면 화면 표면색을 씁니다. */
    val menuSurface: Color? = null,
    val menuDivider: Color = hairline,
    val galleryListBackground: Color = background,
    val settingsSurface: Color = surface,
    val settingsControl: Color = surfaceAlt,
) {
    private val isLight: Boolean get() = background.luminance() > 0.5f

    val overflowMenuSurface: Color get() = menuSurface ?: surfaceAlt
    val dialogSurface: Color get() = menuSurface ?: surface
    val settingsBackground: Color get() = if (isLight) surfaceAlt else galleryListBackground
    val settingsControlMuted: Color get() = if (isLight) textMuted else menuValueText
    val controlOutline: Color get() = textMuted
}

private val DarkPalette = GalleryColors(
    background = Color(0xFF0B0B0C),
    surface = Color(0xFF141416),
    surfaceAlt = Color(0xFF1C1C1F),
    hairline = Color(0xFF2A2A2E),
    text = Color(0xFFEDEDEF),
    textMuted = Color(0xFF8A8A92),
    textFaint = Color(0xFF5A5A62),
    accent = Accent.Indigo.color,
    onAccent = InkOnLight,
    danger = Color(0xFFFF7A7A),
    menuValueSurface = DarkControl,
    menuValueText = Color(0xFF9B9BA3),
    menuSurface = Color(0xFF242427),
    menuDivider = Color(0xFF55555C),
    galleryListBackground = Color(0xFF151517),
    settingsSurface = Color(0xFF1F1F21),
    settingsControl = DarkControl,
)

/** OLED용 검은 테마입니다. 어두운 테마에서 바탕과 표면만 더 어둡게 바꿉니다. */
private val BlackPalette = DarkPalette.copy(
    background = Color.Black,
    surface = Color(0xFF0C0C0D),
    surfaceAlt = Color(0xFF161618),
    hairline = Color(0xFF232326),
    galleryListBackground = Color.Black,
)

private val LightPalette = GalleryColors(
    background = Color.White,
    surface = Color(0xFFF5F5F6),
    surfaceAlt = Color(0xFFEDEDEF),
    hairline = Color(0xFFE3E3E6),
    text = Color(0xFF131315),
    textMuted = Color(0xFF6E6E76),
    textFaint = Color(0xFF9A9AA2),
    accent = Accent.Indigo.color,
    onAccent = InkOnLight,
    danger = Color(0xFFC22B2B),
    menuValueSurface = Color(0xFFDADAE2),
    menuValueText = Color(0xFF565660),
    settingsSurface = Color.White,
    settingsControl = Color(0xFFF5F5F6),
)

/** 밝기 모드에 맞는 팔레트에 강조색을 입힙니다. [black]은 어두운 모드에서만 의미가 있습니다. */
fun galleryColors(dark: Boolean, black: Boolean, accent: Color): GalleryColors {
    val palette = when {
        !dark -> LightPalette
        black -> BlackPalette
        else -> DarkPalette
    }
    return palette.copy(
        accent = accent,
        onAccent = if (accent.luminance() > 0.5f) InkOnLight else InkOnDark,
    )
}
