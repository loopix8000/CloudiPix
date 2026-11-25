package app.cloudipix.gallery.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.sync.SyncFileFilter
import app.cloudipix.gallery.core.sync.parseSyncExtensions
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.theme.GalleryTheme

@Composable
internal fun CloudFileFilterDialog(
    current: SyncFileFilter?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (SyncFileFilter?) -> Unit,
    inherited: SyncFileFilter? = null,
    folderName: String? = null,
) {
    val input = rememberCloudFileFilterInput(current, inherited)
    val filter = input.filter
    val colors = GalleryTheme.colors
    DialogCard(stringResource(R.string.remote_file_filter), onDismiss) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            folderName?.let { Text(it, style = GalleryTheme.type.meta, color = colors.textMuted) }
            CloudFileFilterFields(input, inherited != null, busy)
            if (!input.useInherited && filter == null) {
                Text(stringResource(R.string.remote_filter_invalid_extensions), style = GalleryTheme.type.meta, color = colors.text)
            }
        }
        DialogButtons(
            enabled = !busy && (input.useInherited || filter != null),
            onDismiss = onDismiss,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                onSave(if (input.useInherited) null else filter)
                onDismiss()
            },
        )
    }
}

internal class CloudFileFilterInput(useInherited: Boolean, mediaOnly: Boolean, included: String, excluded: String) {
    var useInherited by mutableStateOf(useInherited)
    var mediaOnly by mutableStateOf(mediaOnly)
    var included by mutableStateOf(included)
    var excluded by mutableStateOf(excluded)

    val filter: SyncFileFilter?
        get() {
            val includeExtensions = parseSyncExtensions(included) ?: return null
            val excludeExtensions = parseSyncExtensions(excluded) ?: return null
            return SyncFileFilter(mediaOnly, includeExtensions, excludeExtensions)
        }
}

private val cloudFileFilterSaver = listSaver<CloudFileFilterInput, Any>(
    save = { listOf(it.useInherited, it.mediaOnly, it.included, it.excluded) },
    restore = { CloudFileFilterInput(it[0] as Boolean, it[1] as Boolean, it[2] as String, it[3] as String) },
)

@Composable
internal fun rememberCloudFileFilterInput(current: SyncFileFilter?, inherited: SyncFileFilter?): CloudFileFilterInput =
    rememberSaveable(saver = cloudFileFilterSaver) {
        val initial = current ?: inherited ?: SyncFileFilter()
        CloudFileFilterInput(
            current == null && inherited != null, initial.mediaOnly,
            initial.includeExtensions.joinToString(", "), initial.excludeExtensions.joinToString(", "),
        )
    }

@Composable
internal fun CloudFileFilterFields(input: CloudFileFilterInput, canInherit: Boolean, busy: Boolean) {
    val colors = GalleryTheme.colors
    if (canInherit) {
        ToggleRow(
            label = stringResource(R.string.remote_filter_inherited),
            checked = input.useInherited,
            onChange = { input.useInherited = it },
            enabled = !busy,
            horizontalPadding = 0.dp,
        )
    }
    if (!input.useInherited) {
        ToggleRow(
            label = stringResource(R.string.remote_filter_media_only),
            checked = input.mediaOnly,
            onChange = { input.mediaOnly = it },
            enabled = !busy,
            horizontalPadding = 0.dp,
        )
        ServerField(input.included, { input.included = it }, R.string.remote_filter_include_extensions, busy,
            placeholder = { Text("jpg, png, mp4") })
        ServerField(input.excluded, { input.excluded = it }, R.string.remote_filter_exclude_extensions, busy,
            placeholder = { Text("tmp, bak") })
        Text(stringResource(R.string.remote_filter_extensions_hint),
            style = GalleryTheme.type.meta, color = colors.textMuted)
    }
}
