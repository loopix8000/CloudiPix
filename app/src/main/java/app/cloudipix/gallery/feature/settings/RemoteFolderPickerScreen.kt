package app.cloudipix.gallery.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.remote.RemoteFolder
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
fun RemoteFolderPickerScreen(
    onBack: () -> Unit,
    viewModel: RemoteFolderPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val browserState = rememberSaveableStateHolder()
    val settings = setup.settings
    val back: () -> Unit = {
        when {
            setup.busy -> Unit
            settings != null -> viewModel.closeSetup()
            state.loading && state.trail.isNotEmpty() -> viewModel.cancelBrowse()
            state.trail.size > 1 -> viewModel.up()
            else -> onBack()
        }
    }
    BackHandler(
        enabled = setup.busy || settings != null || state.trail.size > 1 || (state.loading && state.trail.isNotEmpty()),
        onBack = back,
    )
    LaunchedEffect(setup.error) {
        setup.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.browseError) {
        if (state.trail.isNotEmpty()) state.browseError?.let {
            Toast.makeText(context, it.labelRes, Toast.LENGTH_SHORT).show()
        }
    }

    val colors = GalleryTheme.colors
    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (settings != null) {
            ServerHeader(R.string.remote_folder_setup, back)
            browserState.SaveableStateProvider("setup:${settings.folder.path}") {
                RemoteFolderSetupContent(settings, state, setup.busy, viewModel, onSaved = onBack)
            }
        } else {
            ServerHeader(R.string.remote_choose_folders, back)
            browserState.SaveableStateProvider("folderBrowser") {
                FolderBrowserContent(state, setup.busy, viewModel)
            }
        }
    }
}

@Composable
private fun FolderBrowserContent(
    state: RemoteFolderPickerState,
    busy: Boolean,
    viewModel: RemoteFolderPickerViewModel,
) {
    val colors = GalleryTheme.colors
    val current = state.trail.lastOrNull()
    val selected = state.selected
    val showProgress by produceState(false, state.loading) {
        value = false
        if (state.loading) {
            delay(200)
            value = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (current != null) {
            if (state.trail.size > 1) {
                ServerButton(R.string.remote_parent_folder, enabled = !busy, onClick = viewModel::up)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.screen, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    val label = if (state.isSubfolder(current)) R.string.remote_current_subfolder else R.string.remote_current_folder
                    Text(stringResource(label), style = GalleryTheme.type.body, color = colors.text)
                    Text(current.displayPath, style = GalleryTheme.type.meta, color = colors.textMuted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FolderCheckbox(current, state.isChecked(current), !busy) {
                    viewModel.toggle(current)
                }
            }
            HorizontalDivider(color = colors.hairline)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.browseError
            when {
                current == null && state.loading -> {
                    if (showProgress) CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
                }
                current == null && error != null -> Column(
                    Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(error.labelRes), style = GalleryTheme.type.body, color = colors.text)
                    ServerButton(R.string.remote_retry, enabled = !busy, onClick = viewModel::reload)
                }
                state.children.isEmpty() -> Text(
                    stringResource(R.string.remote_no_subfolders), style = GalleryTheme.type.body,
                    color = colors.textMuted, modifier = Modifier.padding(Spacing.screen),
                )
                else -> key(current?.path) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.children, key = RemoteFolder::path) { folder ->
                            FolderPickerRow(folder, state.isChecked(folder), busy,
                                onOpen = { viewModel.open(folder) }, onToggle = { viewModel.toggle(folder) })
                        }
                    }
                }
            }
            if (current != null && state.loading && showProgress) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = colors.accent)
            }
        }
        HorizontalDivider(color = colors.hairline)
        if (selected != null && !state.discoverAll) {
            FolderPickerCheckRow(
                stringResource(R.string.remote_include_subfolders_default), selected.includeSubfolders, !busy,
                onChange = viewModel::setIncludeSubfolders,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected == null) stringResource(R.string.remote_select_one_folder)
                else stringResource(R.string.remote_root_folder_selection, selected.displayPath),
                modifier = Modifier.weight(1f), style = GalleryTheme.type.meta, color = colors.textMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            FolderPickerButton(R.string.remote_folder_next, selected != null && !busy, viewModel::openSetup)
        }
    }
}

@Composable
private fun FolderPickerRow(
    folder: RemoteFolder,
    checked: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = GalleryTheme.colors
    val rotation = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(enabled = !busy, onClick = onOpen).sizeIn(minHeight = 50.dp)
                .padding(start = Spacing.screen, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(GalleryIcons.Folder, null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            Text(folder.name, modifier = Modifier.weight(1f), style = GalleryTheme.type.body, color = colors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(GalleryIcons.Chevron, null, tint = colors.textMuted, modifier = Modifier.size(16.dp).rotate(rotation))
        }
        FolderCheckbox(folder, checked, !busy, onToggle)
    }
    HorizontalDivider(color = colors.hairline)
}

@Composable
private fun FolderCheckbox(folder: RemoteFolder, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.remote_folder_checkbox, folder.name)
    Box(
        Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CheckBox(checked) }
}

@Composable
internal fun FolderPickerCheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked)
        Text(label, style = GalleryTheme.type.body, color = GalleryTheme.colors.text)
    }
}

@Composable
internal fun FolderPickerButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Button(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
    ) { Text(stringResource(label), maxLines = 1) }
}
