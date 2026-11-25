package app.cloudipix.gallery.ui.component

import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

@Composable
fun IconAction(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = GalleryTheme.colors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(GalleryTheme.hitTarget)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(ControlRadius))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.iconSize(20.dp))
    }
}

@Composable
fun CheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = GalleryTheme.colors
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(ControlRadius))
            .background(if (checked) colors.accent else colors.background)
            .border(1.5.dp, if (checked) colors.accent else colors.textFaint, RoundedCornerShape(ControlRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(GalleryIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun Modifier.island(on: Boolean): Modifier =
    if (on) background(GalleryTheme.colors.surface.copy(alpha = 0.55f), RoundedCornerShape(ControlRadius)) else this

fun islandGap(on: Boolean): Dp = if (on) 6.dp else 0.dp

@Composable
fun TextStyle.shadowed(on: Boolean): TextStyle =
    if (on) copy(shadow = Shadow(GalleryTheme.colors.background.copy(alpha = 0.9f), blurRadius = 14f)) else this

@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailingIcon: ImageVector? = null,
) {
    val colors = GalleryTheme.colors
    Row(
        modifier = modifier
            .sizeIn(minHeight = 32.dp)
            .clip(RoundedCornerShape(ControlRadius))
            .background(if (selected) colors.accent else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val content = if (selected) colors.onAccent else colors.textMuted
        Text(label, style = GalleryTheme.type.label, color = content)
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = content, modifier = Modifier.iconSize(12.dp))
        }
    }
}

@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String = { it.toString() },
) {
    val colors = GalleryTheme.colors
    val optionBounds = remember(options) { mutableStateMapOf<T, Rect>() }
    val indicator = optionBounds[selected]?.let { bounds ->
        animateRectAsState(bounds, tween(durationMillis = 150), label = "선택 표시")
    }
    Row(
        modifier = modifier
            .selectableGroup()
            .clip(RoundedCornerShape(5.dp))
            .background(colors.settingsControl)
            .padding(2.dp)
            .drawBehind {
                indicator?.value?.let { bounds ->
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        cornerRadius = CornerRadius(ControlRadius.toPx()),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .onPlaced { coordinates ->
                        optionBounds[option] = Rect(coordinates.positionInParent(), coordinates.size.toSize())
                    }
                    .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                    .clip(RoundedCornerShape(ControlRadius))
                    .background(if (on && indicator == null) colors.accent else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = GalleryTheme.type.label,
                    color = if (on) colors.onAccent else colors.settingsControlMuted,
                )
            }
        }
    }
}
