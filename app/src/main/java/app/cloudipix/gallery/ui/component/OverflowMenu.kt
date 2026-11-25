package app.cloudipix.gallery.ui.component

import app.cloudipix.gallery.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

private val SettingsControlShape = RoundedCornerShape(8.dp)

@Composable
internal fun GalleryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    shape: Shape,
    containerColor: Color = GalleryTheme.colors.run { menuSurface ?: surface },
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

@Composable
fun OverflowMenu(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = GalleryTheme.colors.text,
    label: String = stringResource(R.string.action_more),
    shape: Shape = RoundedCornerShape(14.dp),
    icon: ImageVector = GalleryIcons.More,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconAction(icon, onClick = { open = true }, label = label, tint = tint)
        GalleryDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = shape,
            containerColor = GalleryTheme.colors.overflowMenuSurface,
            modifier = Modifier.clip(shape),
        ) {
            content { open = false }
        }
    }
}

@Composable
fun MenuEntry(
    label: String,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = GalleryTheme.colors.textMuted,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(label, style = GalleryTheme.type.body, color = GalleryTheme.colors.text)
        },
        leadingIcon = icon?.let {
            { Icon(it, contentDescription = null, tint = iconTint, modifier = Modifier.iconSize(18.dp)) }
        },
        trailingIcon = value?.let {
            {
                val colors = GalleryTheme.colors
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .background(colors.menuValueSurface, shape)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(it, style = GalleryTheme.type.meta, color = colors.menuValueText)
                }
            }
        },
        onClick = onClick,
    )
}

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
    var open by remember { mutableStateOf(false) }
    val colors = GalleryTheme.colors
    val menuShape = shape ?: RoundedCornerShape(12.dp)
    Row(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }
            .heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clip(shape ?: SettingsControlShape)
                    .background(colors.settingsControl)
                    .heightIn(min = 32.dp)
                    .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(optionLabel(selected), style = GalleryTheme.type.label, color = colors.text)
                Icon(GalleryIcons.Chevron, contentDescription = null, tint = colors.textMuted, modifier = Modifier.iconSize(14.dp))
            }
            GalleryDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = menuShape,
                containerColor = colors.menuSurface ?: colors.settingsSurface,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = GalleryTheme.type.body, color = colors.text) },
                        modifier = if (option == selected) {
                            Modifier.background(colors.settingsControl)
                        } else Modifier,
                        trailingIcon = if (option == selected) {
                            { Icon(GalleryIcons.Check, null, Modifier.iconSize(18.dp), tint = colors.accent) }
                        } else null,
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

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
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Step("\u2212", value > range.first, Alignment.CenterStart) { onChange(value - 1) }
            Text(
                value.toString(),
                style = GalleryTheme.type.label,
                color = colors.text,
                modifier = Modifier.widthIn(min = 18.dp),
                textAlign = TextAlign.Center,
            )
            Step("+", value < range.last, Alignment.CenterEnd) { onChange(value + 1) }
        }
    }
}

@Composable
private fun Step(glyph: String, enabled: Boolean, alignment: Alignment, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = Spacing.hitTarget, height = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = alignment,
    ) {
        Box(
            Modifier.size(32.dp)
                .clip(SettingsControlShape)
                .background(colors.settingsControl)
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = GalleryTheme.type.label, color = if (enabled) colors.text else colors.textFaint)
        }
    }
}
