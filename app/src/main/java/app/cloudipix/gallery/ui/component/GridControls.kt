package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.model.Sorting
import app.cloudipix.gallery.ui.theme.GalleryTheme

@Composable
fun GridSortPopup(
    open: Boolean,
    onDismiss: () -> Unit,
    sorting: Sorting,
    onSortKey: (SortKey) -> Unit,
    onSortDescending: (Boolean) -> Unit,
    manualSort: Boolean = false,
    onManualSort: (() -> Unit)? = null,
    folderOnly: Boolean = false,
    onFolderOnlyChange: ((Boolean) -> Unit)? = null,
    allowFavoriteSort: Boolean = false,
) {
    GalleryDropdownMenu(
        expanded = open, onDismissRequest = onDismiss,
        shape = RectangleShape,
    ) {
        val keys = if (allowFavoriteSort) SortKey.entries else SortKey.general
        keys.forEach { key ->
            GridMenuItem(key.labelRes, !manualSort && key == sorting.key) { onSortKey(key); onDismiss() }
        }
        if (onManualSort != null) {
            GridMenuItem(R.string.folder_sort_manual, manualSort) { onManualSort(); onDismiss() }
        }
        if (!manualSort) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = GalleryTheme.colors.menuDivider)
            GridMenuItem(R.string.sort_ascending, !sorting.descending) { onSortDescending(false); onDismiss() }
            GridMenuItem(R.string.sort_descending, sorting.descending) { onSortDescending(true); onDismiss() }
        }
        if (onFolderOnlyChange != null) {
            FolderOnlyItem(folderOnly, onFolderOnlyChange)
        }
    }
}

@Composable
fun GridGroupPopup(
    open: Boolean,
    onDismiss: () -> Unit,
    grouping: Grouping,
    onGrouping: (Grouping) -> Unit,
    folderOnly: Boolean,
    onFolderOnlyChange: (Boolean) -> Unit,
) {
    GalleryDropdownMenu(
        expanded = open, onDismissRequest = onDismiss,
        shape = RectangleShape,
    ) {
        Grouping.entries.forEach { option ->
            GridMenuItem(option.labelRes, option == grouping) { onGrouping(option); onDismiss() }
        }
        FolderOnlyItem(folderOnly, onFolderOnlyChange)
    }
}

@Composable
private fun FolderOnlyItem(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = GalleryTheme.colors.menuDivider)
    DropdownMenuItem(
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
        text = {
            Text(stringResource(R.string.menu_this_folder_only), style = GalleryTheme.type.body, color = GalleryTheme.colors.text)
        },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
private fun GridMenuItem(label: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = GalleryTheme.type.body, color = GalleryTheme.colors.text) },
        trailingIcon = if (selected) {
            { androidx.compose.material3.Icon(GalleryIcons.Check, contentDescription = null, tint = GalleryTheme.colors.accent) }
        } else null,
        onClick = onClick,
    )
}

@Composable
fun Modifier.gridPinchZoom(enabled: Boolean, onStep: (Int) -> Unit): Modifier {
    val step by rememberUpdatedState(onStep)
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    zoom *= event.calculateZoom()
                    when {
                        zoom >= 1.2f -> { step(-1); zoom = 1f }
                        zoom <= 1f / 1.2f -> { step(1); zoom = 1f }
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else zoom = 1f
            } while (pressed > 0)
        }
    }
}
