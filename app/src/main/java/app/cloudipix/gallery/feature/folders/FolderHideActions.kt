package app.cloudipix.gallery.feature.folders

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.FolderHideOption
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.toggleHideScope
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class FolderHideActions(val enabled: Boolean, val hide: (Map<Long, String>) -> Unit)

private data class FolderHideRequest(val selected: Map<Long, String>, val parents: List<FolderHideOption>)

@Composable
internal fun rememberFolderHideActions(
    loadParents: suspend (Set<Long>) -> List<FolderHideOption>,
    hideFolders: suspend (Map<Long, String>) -> Unit,
    onHidden: () -> Unit,
): FolderHideActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<FolderHideRequest?>(null) }

    fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.folder_hide_failed, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    suspend fun finish(folders: Map<Long, String>) {
        hideFolders(folders)
        request = null
        onHidden()
    }

    request?.let { pending ->
        FolderHideDialog(pending, enabled = !busy, onDismiss = { if (!busy) request = null }) { folders ->
            perform { finish(folders) }
        }
    }

    return FolderHideActions(enabled = !busy) { selected ->
        if (selected.isNotEmpty()) perform {
            val parents = loadParents(selected.keys)
            if (parents.isEmpty()) finish(selected)
            else request = FolderHideRequest(selected, parents)
        }
    }
}

@Composable
private fun FolderHideDialog(
    request: FolderHideRequest,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Map<Long, String>) -> Unit,
) {
    var checked by remember(request) { mutableStateOf(emptySet<Int>()) }
    val singleChoice = request.selected.size == 1
    val colors = GalleryTheme.colors
    DialogCard(stringResource(R.string.menu_ignore_folder), onDismiss) {
        Text(
            stringResource(R.string.folder_hide_parent_hint),
            style = GalleryTheme.type.meta,
            color = colors.textMuted,
            modifier = Modifier.padding(top = Spacing.base),
        )
        val choices = if (singleChoice) Modifier.selectableGroup() else Modifier
        LazyColumn(choices.fillMaxWidth().weight(1f, fill = false).padding(top = Spacing.base)) {
            if (singleChoice) item {
                FolderHideScopeRow(
                    title = stringResource(R.string.folder_hide_selected_only),
                    subtitle = request.selected.values.single(),
                    count = null,
                    selected = checked.isEmpty(),
                    singleChoice = true,
                    enabled = enabled,
                    onClick = { checked = emptySet() },
                )
            }
            itemsIndexed(request.parents) { index, option ->
                val localParent = option.localRoot?.let { LocalStoragePath.absolute("$it/${option.parentPath}") }
                val subtitle = localParent?.takeIf { it.path.isNotEmpty() || it.isRemovable }
                    ?.let { storagePathLabel(it) } ?: option.context
                FolderHideScopeRow(
                    title = option.title,
                    subtitle = subtitle,
                    count = option.folders.size,
                    selected = index in checked,
                    singleChoice = singleChoice,
                    enabled = enabled,
                ) {
                    checked = if (singleChoice) setOf(index) else request.parents.toggleHideScope(checked, index)
                }
            }
        }
        DialogButtons(enabled, onDismiss, confirmLabel = stringResource(R.string.action_ignore), onConfirm = {
            val folders = request.selected.toMutableMap()
            checked.forEach { index -> folders.putAll(request.parents[index].folders) }
            onConfirm(folders)
        })
    }
}

@Composable
private fun FolderHideScopeRow(
    title: String,
    subtitle: String?,
    count: Int?,
    selected: Boolean,
    singleChoice: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = GalleryTheme.colors
    val selection = if (singleChoice) {
        Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() }
    }
    Row(
        modifier = Modifier.fillMaxWidth().then(selection).heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.base),
    ) {
        if (singleChoice) {
            RadioButton(selected, onClick = null, enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textMuted))
        } else {
            CheckBox(selected)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = GalleryTheme.type.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = GalleryTheme.type.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count != null) {
            Text(pluralStringResource(R.plurals.folder_hide_count, count, count), style = GalleryTheme.type.meta, color = colors.textMuted)
        }
    }
}
