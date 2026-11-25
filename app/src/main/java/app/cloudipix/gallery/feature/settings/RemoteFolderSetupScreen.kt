package app.cloudipix.gallery.feature.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.treeUriForPath
import app.cloudipix.gallery.core.remote.RemoteFolder
import app.cloudipix.gallery.core.sync.FolderConnectionLocation
import app.cloudipix.gallery.core.sync.FolderConnectionSettings
import app.cloudipix.gallery.core.sync.SyncLocationMode
import app.cloudipix.gallery.core.sync.destinationParts
import app.cloudipix.gallery.core.sync.locationMode
import app.cloudipix.gallery.core.sync.suggestedCustomLocationMode
import app.cloudipix.gallery.feature.permission.AllFilesAccessRequest
import app.cloudipix.gallery.feature.permission.DeviceFolderPicker
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

@Composable
internal fun RemoteFolderSetupContent(
    settings: FolderConnectionSettings,
    selection: RemoteFolderPickerState,
    busy: Boolean,
    viewModel: RemoteFolderPickerViewModel,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val colors = GalleryTheme.colors
    val defaultRoot by viewModel.defaultRoot.collectAsStateWithLifecycle()
    val folder = settings.folder
    val previous = folder.sync
    var enableSync by rememberSaveable { mutableStateOf(previous != null) }
    var mode by rememberSaveable { mutableStateOf(previous?.locationMode ?: SyncLocationMode.Default) }
    val previousCustom = previous?.takeIf { it.locationMode != SyncLocationMode.Default }
    var customUri by rememberSaveable { mutableStateOf(previousCustom?.treeUri) }
    var customPath by rememberSaveable {
        mutableStateOf(previousCustom?.let { if (it.useTreeRoot) it.localLabel else it.localLabel.substringBeforeLast('/') })
    }
    var usePreviousDefault by rememberSaveable { mutableStateOf(previous?.locationMode == SyncLocationMode.Default) }
    var choosingCustom by rememberSaveable { mutableStateOf(false) }
    var requestingAccess by rememberSaveable { mutableStateOf(false) }
    var confirmingExclusions by rememberSaveable { mutableStateOf(false) }
    val filterInput = rememberCloudFileFilterInput(settings.filter, settings.inheritedFilter)
    val useDefault = mode == SyncLocationMode.Default
    val defaultUri = if (usePreviousDefault) previous?.treeUri ?: defaultRoot.treeUri else defaultRoot.treeUri
    val chosenUri = if (useDefault) defaultUri else customUri
    val keepLocation = previous != null && previous.locationMode == mode && previous.treeUri == chosenUri
    val defaultPath = if (usePreviousDefault && previous != null) {
        previous.localLabel.substringBeforeLast('/').substringBeforeLast('/')
    } else defaultRoot.label
    val basePath = if (useDefault) defaultPath else customPath
    val removedSyncFolders = selection.subfolderChanges.values.count { child ->
        !child.linked && selection.links.byPath[child.path]?.sync != null
    }
    val save = {
        val filter = if (filterInput.useInherited) null else filterInput.filter
        if (!filterInput.useInherited && filter == null) {
            Toast.makeText(context, R.string.remote_filter_invalid_extensions, Toast.LENGTH_LONG).show()
        } else {
            val location = chosenUri?.takeIf { enableSync && !settings.inheritsSync && !keepLocation }
                ?.let { FolderConnectionLocation(Uri.parse(it), mode) }
            viewModel.save(enableSync && !settings.inheritsSync, location, filter,
                onAccessRequired = { requestingAccess = true }, onSaved = onSaved)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(folder.name, color = colors.text, style = GalleryTheme.type.title)
            Text(folder.displayPath, color = colors.textMuted, style = GalleryTheme.type.meta)
            if (selection.subfolderChanges.isNotEmpty()) {
                val added = selection.subfolderChanges.values.count(RemoteFolder::linked)
                Text(
                    stringResource(R.string.remote_subfolder_changes, added, selection.subfolderChanges.size - added),
                    color = colors.textMuted, style = GalleryTheme.type.meta,
                )
            }
            HorizontalDivider(color = colors.hairline)
            Text(stringResource(R.string.sync_title), color = colors.text, style = GalleryTheme.type.label)
            if (settings.inheritsSync) {
                Text(stringResource(R.string.remote_folder_sync_inherited), color = colors.textMuted, style = GalleryTheme.type.meta)
            } else {
                FolderPickerCheckRow(stringResource(R.string.sync_enable), enableSync, !busy, onChange = { enableSync = it })
                if (enableSync) {
                    Column(Modifier.selectableGroup()) {
                        SyncOption(
                            stringResource(R.string.sync_default_root), storagePathLabel(defaultPath), useDefault, !busy,
                        ) {
                            mode = SyncLocationMode.Default
                            usePreviousDefault = false
                        }
                        SyncOption(
                            stringResource(R.string.sync_custom_root),
                            customPath?.let { storagePathLabel(it) } ?: stringResource(R.string.sync_pick_location),
                            !useDefault, !busy,
                        ) { choosingCustom = true }
                    }
                    if (!useDefault && customPath != null) {
                        FolderPickerCheckRow(stringResource(R.string.sync_use_child_folder), mode == SyncLocationMode.ChildFolder, !busy,
                            onChange = { mode = if (it) SyncLocationMode.ChildFolder else SyncLocationMode.SelectedFolder })
                    }
                    if (basePath != null) {
                        val parts = destinationParts(settings.serverName, folder, mode)
                        val path = if (keepLocation) previous.localLabel
                            else if (parts.isEmpty()) basePath else "${basePath.trimEnd('/')}/${parts.joinToString("/")}"
                        Text(stringResource(R.string.sync_final_destination), style = GalleryTheme.type.meta, color = colors.textMuted)
                        Text(storagePathLabel(path), style = GalleryTheme.type.body, color = colors.text)
                    }
                    Text(stringResource(R.string.remote_folder_setup_sync_hint), style = GalleryTheme.type.meta, color = colors.textMuted)
                }
            }
            HorizontalDivider(color = colors.hairline)
            Text(stringResource(R.string.remote_file_filter), color = colors.text, style = GalleryTheme.type.label)
            CloudFileFilterFields(filterInput, canInherit = true, busy = busy)
        }
        HorizontalDivider(color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderPickerButton(
                if (busy) R.string.remote_saving_folders else R.string.action_save,
                !busy && (!enableSync || settings.inheritsSync || chosenUri != null),
            ) {
                if (removedSyncFolders > 0 && (filterInput.useInherited || filterInput.filter != null)) {
                    confirmingExclusions = true
                } else save()
            }
        }
    }

    if (choosingCustom) DeviceFolderPicker(
        title = stringResource(R.string.sync_custom_root),
        initialPath = customPath ?: defaultRoot.label,
        onPick = { path ->
            choosingCustom = false
            val uri = context.treeUriForPath(path)
            if (uri == null) {
                Toast.makeText(context, R.string.sync_local_error, Toast.LENGTH_LONG).show()
            } else viewModel.previewRoot(uri) { chosen ->
                customUri = chosen.treeUri
                customPath = chosen.label
                mode = suggestedCustomLocationMode(chosen.label,
                    destinationParts(settings.serverName, folder, SyncLocationMode.ChildFolder).single())
            }
        },
        onDismiss = { choosingCustom = false },
    )
    if (requestingAccess) AllFilesAccessRequest(
        onGranted = {
            requestingAccess = false
            save()
        },
        onDismiss = { requestingAccess = false },
    )
    if (confirmingExclusions) {
        val dismiss = { confirmingExclusions = false }
        DialogCard(stringResource(R.string.remote_exclude_sync_folders_title), onDismiss = dismiss) {
            Text(
                stringResource(R.string.remote_exclude_sync_folders_hint, removedSyncFolders),
                modifier = Modifier.padding(top = 8.dp), style = GalleryTheme.type.body, color = colors.text,
            )
            DialogButtons(enabled = !busy, onDismiss = dismiss, confirmLabel = stringResource(R.string.action_save),
                onConfirm = { dismiss(); save() })
        }
    }
}
