package app.cloudipix.gallery.ui.component

import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme

/** 작은 조작 요소가 함께 쓰는 모양입니다. 그릴 때마다 새로 만들지 않도록 한 번만 만듭니다. */
private val ControlShape = RoundedCornerShape(ControlRadius)
private val PickerTrackShape = RoundedCornerShape(5.dp)

private const val DISABLED_ALPHA = 0.38f
private const val INDICATOR_MOVE_MS = 150

/** 아이콘 하나짜리 버튼입니다. 터치 영역은 글자 크기 설정에 맞춰 커집니다. */
@Composable
fun IconAction(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = GalleryTheme.colors.text,
    enabled: Boolean = true,
) {
    val target = modifier
        .size(GalleryTheme.hitTarget)
        .alpha(if (enabled) 1f else DISABLED_ALPHA)
        .clip(ControlShape)
        .clickable(enabled = enabled, onClick = onClick)
    Box(target, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.iconSize(20.dp))
    }
}

/** 선택 상태만 보여 주는 체크 상자입니다. 누르는 동작은 감싼 쪽에서 처리합니다. */
@Composable
fun CheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = GalleryTheme.colors
    val fill = if (checked) colors.accent else colors.background
    val outline = if (checked) colors.accent else colors.textFaint
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(ControlShape)
            .background(fill)
            .border(1.5.dp, outline, ControlShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(GalleryIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

/** 사진 위에 겹친 글자에 배경색 그림자를 둘러 읽기 쉽게 합니다. [on]이 아니면 그대로 둡니다. */
@Composable
fun TextStyle.shadowed(on: Boolean): TextStyle {
    if (!on) return this
    return copy(shadow = Shadow(GalleryTheme.colors.background.copy(alpha = 0.9f), blurRadius = 14f))
}

/**
 * 여러 선택지 중 하나를 고르는 분할 버튼입니다.
 * 선택지가 배치된 뒤에는 강조 바탕이 선택한 칸으로 미끄러지듯 옮겨 가고,
 * 배치 전 첫 프레임에는 선택한 칸 자체에 강조 바탕을 칠합니다.
 */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String = { it.toString() },
) {
    val colors = GalleryTheme.colors
    val placed = remember(options) { mutableStateMapOf<T, Rect>() }
    val highlight = placed[selected]?.let { target ->
        animateRectAsState(target, tween(durationMillis = INDICATOR_MOVE_MS), label = "선택 표시")
    }

    val track = modifier
        .selectableGroup()
        .clip(PickerTrackShape)
        .background(colors.settingsControl)
        .padding(2.dp)
        .drawBehind {
            val area = highlight?.value ?: return@drawBehind
            drawRoundRect(
                color = colors.accent,
                topLeft = area.topLeft,
                size = area.size,
                cornerRadius = CornerRadius(ControlRadius.toPx()),
            )
        }
    Row(track, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (option in options) {
            val isSelected = option == selected
            val segment = Modifier
                .onPlaced { coordinates ->
                    placed[option] = Rect(coordinates.positionInParent(), coordinates.size.toSize())
                }
                .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                .clip(ControlShape)
                .background(if (isSelected && highlight == null) colors.accent else Color.Transparent)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                .padding(horizontal = 9.dp)
            Box(segment, contentAlignment = Alignment.Center) {
                Text(
                    label(option),
                    style = GalleryTheme.type.label,
                    color = if (isSelected) colors.onAccent else colors.settingsControlMuted,
                )
            }
        }
    }
}
