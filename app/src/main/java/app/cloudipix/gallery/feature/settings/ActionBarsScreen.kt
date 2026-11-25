package app.cloudipix.gallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.icon
import app.cloudipix.gallery.ui.component.iconSize
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

private val SlotHeight = 46.dp
private val EditorShape = RoundedCornerShape(6.dp)

/*
 * 편집기는 바 동작, 구분선, 메뉴 동작을 한 줄로 늘어놓은 목록으로 다룹니다.
 * 목록에서 `null`이 '메뉴로' 구분선이고, 그 위는 바, 아래는 메뉴입니다.
 */

private fun BarLayout.toSlots(): List<BarAction?> = buildList {
    addAll(bar)
    add(null)
    addAll(menu)
}

private fun List<BarAction?>.toBarLayout(hidden: Set<BarAction>): BarLayout {
    val divider = indexOf(null)
    return BarLayout(
        bar = take(divider).filterNotNull(),
        menu = drop(divider + 1).filterNotNull(),
        hidden = hidden,
    )
}

private fun List<BarAction?>.withSwapped(first: Int, second: Int): List<BarAction?> {
    val copy = toMutableList()
    copy[first] = this[second]
    copy[second] = this[first]
    return copy
}

/** 동작을 구분선 건너편으로 옮깁니다. 바에서는 메뉴 맨 앞으로, 메뉴에서는 바 맨 끝으로 갑니다. */
private fun List<BarAction?>.withCrossed(action: BarAction): List<BarAction?> {
    val wasInBar = indexOf(action) < indexOf(null)
    val remaining = toMutableList().apply { remove(action) }
    val divider = remaining.indexOf(null)
    remaining.add(if (wasInBar) divider + 1 else divider, action)
    return remaining
}

@Composable
fun ActionBarsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = GalleryTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.padding(start = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(GalleryIcons.Back, onClick = onBack)
            Text(stringResource(R.string.section_actions), style = GalleryTheme.type.title, color = colors.text)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (hint in listOf(R.string.actions_hint, R.string.actions_overflow_hint)) {
                Text(
                    stringResource(hint),
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                    style = GalleryTheme.type.meta,
                    color = colors.textMuted,
                )
            }
            BarEditor(stringResource(R.string.setting_selection_bar), settings.selectionBar, viewModel::setSelectionBar)
            BarEditor(stringResource(R.string.setting_viewer_bar), settings.viewerBar, viewModel::setViewerBar)
        }
    }
}

/**
 * 동작 바 하나의 편집기입니다. 줄을 누르면 바와 메뉴 사이를 오가고, 체크 상자로 보이기를 바꾸며,
 * 손잡이를 끌거나 줄을 길게 눌러 끌면 순서를 바꿉니다. 바뀐 배치는 바로 [onChange]로 저장합니다.
 */
@Composable
private fun BarEditor(title: String, layout: BarLayout, onChange: (BarLayout) -> Unit) {
    val colors = GalleryTheme.colors
    val slotPx = with(LocalDensity.current) { SlotHeight.toPx() }
    var slots by remember(layout) { mutableStateOf(layout.toSlots()) }
    // 끄는 중인 줄입니다. 구분선(null)도 끌 수 있어 끄는 중인지는 따로 둡니다.
    var held by remember { mutableStateOf<BarAction?>(null) }
    var holding by remember { mutableStateOf(false) }
    var shift by remember { mutableFloatStateOf(0f) }

    fun pick(slot: BarAction?) {
        held = slot
        holding = true
        shift = 0f
    }

    // 줄 높이의 절반 넘게 끌면 이웃 줄과 자리를 바꾸고, 바꾼 만큼 끈 거리를 되돌립니다.
    fun move(dy: Float) {
        shift += dy
        val at = slots.indexOf(held)
        val half = slotPx / 2
        when {
            shift > half && at < slots.lastIndex -> {
                slots = slots.withSwapped(at, at + 1)
                shift -= slotPx
            }
            shift < -half && at > 0 -> {
                slots = slots.withSwapped(at, at - 1)
                shift += slotPx
            }
        }
    }

    fun release() {
        if (!holding) return
        holding = false
        shift = 0f
        onChange(slots.toBarLayout(layout.hidden))
    }

    val onDrag = { change: PointerInputChange, amount: Offset ->
        change.consume()
        move(amount.y)
    }

    Column(Modifier.padding(horizontal = Spacing.screen, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = GalleryTheme.type.body, color = colors.text)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(EditorShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline, EditorShape),
        ) {
            for (slot in slots) {
                key(slot?.name ?: "divider") {
                    val lifted = holding && slot == held
                    val longPressDrag = Modifier.pointerInput(slot, layout) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pick(slot) },
                            onDrag = onDrag,
                            onDragEnd = ::release,
                            onDragCancel = ::release,
                        )
                    }
                    val crossOnClick = if (slot == null) {
                        Modifier
                    } else {
                        Modifier.clickable {
                            slots = slots.withCrossed(slot)
                            onChange(slots.toBarLayout(layout.hidden))
                        }
                    }
                    Row(
                        Modifier
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer { translationY = if (lifted) shift else 0f }
                            .fillMaxWidth()
                            .height(SlotHeight)
                            .background(if (lifted) colors.surfaceAlt else colors.surface)
                            .then(longPressDrag)
                            .then(crossOnClick)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (slot == null) {
                            MenuDivider()
                        } else {
                            val visible = slot !in layout.hidden
                            CheckBox(
                                checked = visible,
                                modifier = Modifier.clickable {
                                    onChange(slots.toBarLayout(layout.hidden).shown(slot, !visible))
                                },
                            )
                            val tone = if (visible) colors.text else colors.textFaint
                            Icon(slot.icon, contentDescription = null, tint = tone, modifier = Modifier.iconSize(18.dp))
                            Text(
                                stringResource(slot.labelRes),
                                modifier = Modifier.weight(1f),
                                style = GalleryTheme.type.body,
                                color = tone,
                            )
                        }
                        Icon(
                            GalleryIcons.Grip,
                            contentDescription = null,
                            tint = colors.textFaint,
                            modifier = Modifier
                                .iconSize(20.dp)
                                .pointerInput(slot, layout) {
                                    detectDragGestures(
                                        onDragStart = { pick(slot) },
                                        onDrag = onDrag,
                                        onDragEnd = ::release,
                                        onDragCancel = ::release,
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** 구분선 줄의 내용입니다. 양옆 가는 선 사이에 '메뉴로' 표시를 둡니다. */
@Composable
private fun RowScope.MenuDivider() {
    val colors = GalleryTheme.colors
    Box(Modifier.weight(1f).height(1.dp).background(colors.hairline))
    Icon(GalleryIcons.More, contentDescription = null, tint = colors.textFaint, modifier = Modifier.iconSize(14.dp))
    Text(stringResource(R.string.actions_in_menu), style = GalleryTheme.type.meta, color = colors.textFaint)
    Box(Modifier.weight(1f).height(1.dp).background(colors.hairline))
}
