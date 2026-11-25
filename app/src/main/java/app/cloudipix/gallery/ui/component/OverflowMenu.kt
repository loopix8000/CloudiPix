package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

/** 설정 화면의 값 상자와 증감 버튼 모양입니다. */
private val SettingsControlShape = RoundedCornerShape(8.dp)
private val OverflowMenuShape = RoundedCornerShape(14.dp)
private val SettingMenuShape = RoundedCornerShape(12.dp)
private val ValueBadgeShape = RoundedCornerShape(6.dp)

/** 앱 색을 입힌 드롭다운 메뉴입니다. */
@Composable
internal fun GalleryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    shape: Shape,
    containerColor: Color = GalleryTheme.colors.let { it.menuSurface ?: it.surface },
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        content = content,
    )
}

/** 더보기 아이콘 버튼과 그 메뉴입니다. [content]는 항목을 고른 뒤 메뉴를 닫는 함수를 받습니다. */
@Composable
fun OverflowMenu(
    modifier: Modifier = Modifier,
    tint: Color = GalleryTheme.colors.text,
    label: String = stringResource(R.string.action_more),
    shape: Shape = OverflowMenuShape,
    icon: ImageVector = GalleryIcons.More,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val close = { expanded = false }
    Box(modifier) {
        IconAction(icon, onClick = { expanded = true }, label = label, tint = tint)
        GalleryDropdownMenu(
            expanded = expanded,
            onDismissRequest = close,
            shape = shape,
            containerColor = GalleryTheme.colors.overflowMenuSurface,
            modifier = Modifier.clip(shape),
        ) {
            content(close)
        }
    }
}

/** 메뉴 항목입니다. [value]를 주면 오른쪽에 현재 값을 작은 상자로 보여 줍니다. */
@Composable
fun MenuEntry(
    label: String,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = GalleryTheme.colors.textMuted,
    onClick: () -> Unit,
) {
    val leading: (@Composable () -> Unit)? = icon?.let { image ->
        { Icon(image, contentDescription = null, tint = iconTint, modifier = Modifier.iconSize(18.dp)) }
    }
    val trailing: (@Composable () -> Unit)? = value?.let { text -> { ValueBadge(text) } }
    DropdownMenuItem(
        text = { Text(label, style = GalleryTheme.type.body, color = GalleryTheme.colors.text) },
        onClick = onClick,
        leadingIcon = leading,
        trailingIcon = trailing,
    )
}

@Composable
private fun ValueBadge(text: String) {
    val colors = GalleryTheme.colors
    Box(Modifier.background(colors.menuValueSurface, ValueBadgeShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, style = GalleryTheme.type.meta, color = colors.menuValueText)
    }
}

/**
 * 이름과 현재 값을 한 줄에 보여 주고, 누르면 선택지 메뉴를 여는 설정 항목입니다.
 * [shape]를 주면 값 상자와 메뉴 모두 그 모양을 씁니다.
 */
@Composable
fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    optionLabel: @Composable (T) -> String = { it.toString() },
) {
    val colors = GalleryTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { expanded = true }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Box {
            CurrentValue(optionLabel(selected), shape ?: SettingsControlShape)
            GalleryDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = shape ?: SettingMenuShape,
                containerColor = colors.menuSurface ?: colors.settingsSurface,
            ) {
                for (option in options) {
                    val chosen = option == selected
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = GalleryTheme.type.body, color = colors.text) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        modifier = if (chosen) Modifier.background(colors.settingsControl) else Modifier,
                        trailingIcon = if (chosen) {
                            { Icon(GalleryIcons.Check, null, Modifier.iconSize(18.dp), tint = colors.accent) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentValue(text: String, shape: Shape) {
    val colors = GalleryTheme.colors
    Row(
        Modifier
            .clip(shape)
            .background(colors.settingsControl)
            .heightIn(min = 32.dp)
            .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = GalleryTheme.type.label, color = colors.text)
        Icon(GalleryIcons.Chevron, contentDescription = null, tint = colors.textMuted, modifier = Modifier.iconSize(14.dp))
    }
}

/** 정수 값을 −/+ 버튼으로 [range] 안에서 하나씩 바꾸는 설정 항목입니다. */
@Composable
fun MenuStepper(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val colors = GalleryTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", enabled = value > range.first, Alignment.CenterStart) { onChange(value - 1) }
            Text(
                value.toString(),
                modifier = Modifier.widthIn(min = 18.dp),
                color = colors.text,
                textAlign = TextAlign.Center,
                style = GalleryTheme.type.label,
            )
            StepButton("+", enabled = value < range.last, Alignment.CenterEnd) { onChange(value + 1) }
        }
    }
}

/**
 * 증감 버튼입니다. 누르는 영역은 넉넉하게 두고, 눌림 효과는 안쪽의 작은 상자에만 보입니다.
 * [alignment]는 넓은 영역 안에서 작은 상자의 위치입니다.
 */
@Composable
private fun StepButton(glyph: String, enabled: Boolean, alignment: Alignment, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val touchArea = Modifier
        .size(width = Spacing.hitTarget, height = 48.dp)
        .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    Box(touchArea, contentAlignment = alignment) {
        val face = Modifier
            .size(32.dp)
            .clip(SettingsControlShape)
            .background(colors.settingsControl)
            .indication(interactions, LocalIndication.current)
        Box(face, contentAlignment = Alignment.Center) {
            Text(glyph, style = GalleryTheme.type.label, color = if (enabled) colors.text else colors.textFaint)
        }
    }
}
