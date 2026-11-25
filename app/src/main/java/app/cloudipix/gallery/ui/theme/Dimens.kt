package app.cloudipix.gallery.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R

/** 화면 전반에서 쓰는 여백과 최소 터치 영역입니다. */
object Spacing {
    val base = 8.dp
    val screen = 20.dp
    val hitTarget = 44.dp
}

/** 버튼, 체크 상자, 선택 표시처럼 작은 조작 요소의 모서리 반경입니다. */
val ControlRadius = 4.dp

/** 썸네일과 폴더 타일의 모서리입니다. 사용자 설정 없이 한 가지 값만 씁니다. */
@Immutable
class CornerStyle private constructor(val radius: Dp) {
    companion object {
        val Default = CornerStyle(ControlRadius)
    }
}

/** 미디어 격자에서 썸네일 사이 간격입니다. 사용자 설정 없이 한 가지 값만 씁니다. */
@Immutable
class ThumbnailSpacing private constructor(val gap: Dp) {
    companion object {
        val Small = ThumbnailSpacing(4.dp)
    }
}

/** 폴더 타일의 이름·개수 같은 정보를 사진 아래에 둘지 사진 위에 겹칠지 정합니다. 이름은 설정 값으로 저장됩니다. */
enum class DetailsPlacement(@StringRes val labelRes: Int) {
    Below(R.string.placement_below),
    Overlay(R.string.placement_overlay),
}
