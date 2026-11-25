package app.cloudipix.gallery.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat

/** 시스템 설정을 따르는 모드면 현재 시스템 밝기에 따라 정합니다. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark, ThemeMode.Black -> true
    ThemeMode.System -> isSystemInDarkTheme()
}

private val LocalColors = staticCompositionLocalOf<GalleryColors> { error("GalleryTheme 밖에서 색을 읽었습니다.") }
private val LocalType = staticCompositionLocalOf { galleryType(GalleryFont.SpaceGrotesk, TextScale.Default) }
private val LocalIconScale = staticCompositionLocalOf { TextScale.Default.iconFactor }

/** 현재 테마 값에 접근하는 진입점입니다. */
object GalleryTheme {
    val colors: GalleryColors
        @Composable @ReadOnlyComposable get() = LocalColors.current

    val type: GalleryType
        @Composable @ReadOnlyComposable get() = LocalType.current

    /** 모서리는 설정 없이 한 가지 값만 쓰므로 컴포지션 로컬을 거치지 않습니다. */
    val corners: CornerStyle get() = CornerStyle.Default

    /** 글자 크기 설정에 따른 아이콘 배율입니다. */
    val iconScale: Float
        @Composable @ReadOnlyComposable get() = LocalIconScale.current

    /** 아이콘 배율을 반영한 최소 터치 영역입니다. */
    val hitTarget: Dp
        @Composable @ReadOnlyComposable get() = Spacing.hitTarget * LocalIconScale.current
}

/** 앱 전체 테마입니다. 앱 색·글꼴과 그에 맞춘 Material 테마를 함께 제공합니다. */
@Composable
fun GalleryTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Color = Accent.Indigo.color,
    font: GalleryFont = GalleryFont.SpaceGrotesk,
    textScale: TextScale = TextScale.Default,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    val black = mode == ThemeMode.Black
    val colors = remember(dark, black, accent) { galleryColors(dark, black, accent) }
    val scheme = remember(colors, dark) { colors.materialScheme(dark) }
    val type = remember(font, textScale) { galleryType(font, textScale) }
    val typography = remember(type) { type.materialTypography() }

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides type,
        LocalIconScale provides textScale.iconFactor,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/**
 * 화면 하나만 다른 밝기 모드로 그립니다. [mode]가 `null`이면 바깥 테마를 그대로 쓰고,
 * 지정하면 바깥의 강조색은 유지한 채 그 모드의 팔레트로 바꿉니다.
 */
@Composable
internal fun GalleryScreenTheme(mode: ThemeMode?, content: @Composable () -> Unit) {
    val outer = GalleryTheme.colors
    val dark = mode?.isDark()
    val colors = if (dark == null) outer else remember(mode, dark, outer.accent) {
        galleryColors(dark, mode == ThemeMode.Black, outer.accent)
    }
    val scheme = if (dark == null) MaterialTheme.colorScheme else remember(colors, dark) { colors.materialScheme(dark) }
    // 모드를 바꿔도 화면 내용의 상태가 유지되도록 내용은 항상 같은 위치에서 그립니다.
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 상태 표시줄과 내비게이션 바 아이콘을 [mode]의 밝기에 맞춥니다. */
@Composable
internal fun GallerySystemBarAppearance(mode: ThemeMode) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val lightBars = !mode.isDark()
    val insets = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    SideEffect {
        insets.isAppearanceLightStatusBars = lightBars
        insets.isAppearanceLightNavigationBars = lightBars
    }
}

private fun GalleryColors.materialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = textMuted,
        outline = hairline,
        outlineVariant = hairline,
    )
}

private fun GalleryType.materialTypography() = Typography(
    titleLarge = title,
    titleMedium = title,
    bodyMedium = body,
    bodySmall = meta,
    labelMedium = label,
    labelSmall = section,
)
