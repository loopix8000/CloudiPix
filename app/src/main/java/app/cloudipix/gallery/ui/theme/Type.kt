package app.cloudipix.gallery.ui.theme

import androidx.annotation.FontRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.cloudipix.gallery.R

/** 가변 글꼴 파일 하나에서 앱이 쓰는 굵기(400~700)를 각각 꺼내 글꼴 패밀리로 묶습니다. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(@FontRes file: Int): FontFamily {
    val fonts = listOf(400, 500, 600, 700).map { weight ->
        Font(
            resId = file,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    }
    return FontFamily(fonts)
}

/** 화면 글꼴입니다. 이름은 설정 값으로 저장되고, 순서는 설정 화면의 선택지 순서입니다. */
enum class GalleryFont(val label: String, @StringRes val labelRes: Int? = null) {
    SpaceGrotesk("Space Grotesk"),
    Outfit("Outfit"),
    PlexSans("IBM Plex Sans"),
    System("System", R.string.follow_system),
    ;

    /** 글꼴 패밀리는 고른 글꼴만 처음 쓸 때 만듭니다. */
    val family: FontFamily by lazy {
        when (this) {
            SpaceGrotesk -> variableFamily(R.font.space_grotesk)
            Outfit -> variableFamily(R.font.outfit)
            PlexSans -> variableFamily(R.font.ibm_plex_sans)
            System -> FontFamily.SansSerif
        }
    }
}

/**
 * 글자 크기 단계입니다. [factor]는 글자에, [iconFactor]는 아이콘과 터치 영역에 곱합니다.
 * 이름은 설정 값으로 저장되고, 순서는 설정 화면의 선택지 순서입니다.
 */
enum class TextScale(val label: String, val factor: Float, val iconFactor: Float) {
    Small("S", 0.9f, 1f),
    Default("M", 1f, 1f),
    Large("L", 1.12f, 1.2f),
    Huge("XL", 1.26f, 1.4f),
    Giant("XXL", 1.42f, 1.6f),
}

/** 날짜, 개수, 구역 제목처럼 숫자와 메타 정보에 쓰는 고정폭 글꼴입니다. */
private val MonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

@Immutable
data class GalleryType(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val meta: TextStyle,
    val section: TextStyle,
)

fun galleryType(font: GalleryFont, scale: TextScale): GalleryType {
    fun style(family: FontFamily, size: Float, weight: FontWeight, tracking: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontFamily = family, fontSize = (size * scale.factor).sp, fontWeight = weight, letterSpacing = tracking)

    val ui = font.family
    return GalleryType(
        display = style(ui, 27f, FontWeight.SemiBold, (-0.03).em),
        title = style(ui, 19f, FontWeight.SemiBold, (-0.02).em),
        body = style(ui, 13.5f, FontWeight.Normal),
        label = style(ui, 13f, FontWeight.Medium),
        meta = style(MonoFamily, 11f, FontWeight.Normal),
        section = style(MonoFamily, 10.5f, FontWeight.Medium, 0.09.em),
    )
}
