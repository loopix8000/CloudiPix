package app.cloudipix.gallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.cache.CACHE_LIMIT_RANGE
import app.cloudipix.gallery.core.cache.MEBIBYTE
import app.cloudipix.gallery.core.remote.RemoteFailure
import app.cloudipix.gallery.core.remote.RemoteProtocol
import app.cloudipix.gallery.core.remote.RemoteServerStatus
import app.cloudipix.gallery.core.sync.SyncFileFilterSettings
import app.cloudipix.gallery.ui.component.DropdownSetting
import app.cloudipix.gallery.ui.component.GalleryAlertDialog
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.iconSize
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

@Composable
fun RemoteSourcesScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (String) -> Unit,
    onManageLinkedFolders: () -> Unit,
    onSyncSettings: () -> Unit,
    viewModel: RemoteSourcesViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val usage by viewModel.cacheUsage.collectAsStateWithLifecycle()
    val filters by viewModel.fileFilters.collectAsStateWithLifecycle(SyncFileFilterSettings())
    var choosingCacheLimit by rememberSaveable { mutableStateOf(false) }
    var editingFileFilter by rememberSaveable { mutableStateOf(false) }
    val colors = GalleryTheme.colors

    Column(
        Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ServerHeader(R.string.remote_sources, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                servers.orEmpty().forEachIndexed { index, server ->
                    if (index > 0) HorizontalDivider(color = colors.hairline)
                    LinkRow(server.name, hint = null, horizontalPadding = 0.dp, leadingIcon = {
                        Icon(
                            if (server.protocol == RemoteProtocol.WebDav) GalleryIcons.Globe else GalleryIcons.Transfer,
                            contentDescription = stringResource(server.protocol.labelRes),
                            tint = colors.textMuted, modifier = Modifier.iconSize(20.dp),
                        )
                    }) { onOpenServer(server.id) }
                }
                ServerButton(R.string.remote_add, enabled = !busy, onClick = onAddServer)
            }
            HorizontalDivider(color = colors.hairline)
            LinkRow(
                stringResource(R.string.sync_manage_linked_folders),
                hint = stringResource(R.string.remote_linked_folders_hint),
                horizontalPadding = 0.dp,
                onClick = onManageLinkedFolders,
            )
            LinkRow(stringResource(R.string.sync_preferences), stringResource(R.string.sync_settings_hint),
                horizontalPadding = 0.dp, onClick = onSyncSettings)
            LinkRow(stringResource(R.string.remote_file_filter), stringResource(R.string.remote_file_filter_hint),
                horizontalPadding = 0.dp, enabled = !busy) { editingFileFilter = true }
            usage?.let { cache ->
                HorizontalDivider(color = colors.hairline)
                Text(
                    stringResource(R.string.remote_cache_size, formatSize(cache.usedBytes), cache.limitMiB),
                    style = GalleryTheme.type.label, color = colors.text,
                )
                val limitBytes = cache.limitMiB * MEBIBYTE
                val usedFraction = (cache.usedBytes.toDouble() / limitBytes).coerceIn(0.0, 1.0).toFloat()
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(colors.hairline),
                ) {
                    Box(Modifier.fillMaxWidth(usedFraction).fillMaxHeight().background(colors.accent))
                }
                ServerButton(R.string.remote_cache_limit, enabled = !busy) {
                    viewModel.clearError()
                    choosingCacheLimit = true
                }
                ServerButton(R.string.remote_clear_cache, enabled = !busy && cache.usedBytes > 0L, onClick = viewModel::clearCache)
            }
            if (error != null) {
                Text(stringResource(error!!.labelRes), color = colors.text, style = GalleryTheme.type.meta)
            }
        }
    }

    if (editingFileFilter) CloudFileFilterDialog(
        current = filters.global,
        busy = busy,
        onDismiss = { editingFileFilter = false },
        onSave = { filter -> filter?.let(viewModel::setGlobalFileFilter) },
    )

    usage?.takeIf { choosingCacheLimit }?.let { cache ->
        CacheLimitDialog(
            currentLimitMiB = cache.limitMiB,
            hint = stringResource(R.string.remote_cache_limit_hint),
            busy = busy,
            error = error?.let { stringResource(it.labelRes) },
            onDismiss = { choosingCacheLimit = false },
            onSave = { limit -> viewModel.setCacheLimit(limit) { choosingCacheLimit = false } },
        )
    }
}

@Composable
fun RemoteServerDetailsScreen(
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChooseFolders: () -> Unit,
    onFolderLocations: () -> Unit,
    viewModel: RemoteSourcesViewModel,
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val server = servers?.firstOrNull { it.id == id }
    var removing by rememberSaveable(id) { mutableStateOf(false) }
    val colors = GalleryTheme.colors
    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ServerHeader(server?.name ?: stringResource(R.string.remote_sources), onBack)
        if (server == null) {
            if (servers == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.screen), color = colors.accent)
            } else {
                Text(stringResource(R.string.remote_missing), modifier = Modifier.padding(Spacing.screen),
                    style = GalleryTheme.type.body, color = colors.textMuted)
            }
            return
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen)) {
            Text(stringResource(server.protocol.labelRes), color = colors.text, style = GalleryTheme.type.label)
            Text(server.address, color = colors.textMuted, style = GalleryTheme.type.meta)
            val status = when {
                server.refreshing -> stringResource(R.string.remote_scanning)
                server.unavailable -> stringResource(R.string.remote_unavailable)
                server.folderCount == 0 -> stringResource(R.string.remote_no_selected)
                server.folderCount != null -> stringResource(R.string.remote_selected_count, server.folderCount)
                else -> stringResource(R.string.remote_album_count, server.albumCount)
            }
            LinkRow(stringResource(R.string.remote_choose_folders), status, horizontalPadding = 0.dp, enabled = !busy, onClick = onChooseFolders)
            LinkRow(stringResource(R.string.sync_folder_locations), hint = null, horizontalPadding = 0.dp, enabled = !busy, onClick = onFolderLocations)
            HorizontalDivider(color = colors.hairline)
            if (server.folderCount != 0) ServerButton(R.string.menu_refresh, enabled = !busy && !server.refreshing) { viewModel.refresh(id) }
            ServerButton(R.string.remote_edit, enabled = !busy, onClick = onEdit)
            ServerButton(R.string.remote_remove, enabled = !busy) { removing = true }
            error?.let { Text(stringResource(it.labelRes), color = colors.text, style = GalleryTheme.type.meta) }
        }
    }
    if (removing && server != null) {
        GalleryAlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.remote_remove), color = colors.text) },
            text = { Text(stringResource(R.string.remote_remove_confirm, server.name), color = colors.textMuted) },
            confirmButton = {
                ServerButton(R.string.remote_remove, enabled = !busy) {
                    removing = false
                    viewModel.remove(id, onRemoved = onBack)
                }
            },
            dismissButton = { ServerButton(R.string.action_cancel) { removing = false } },
        )
    }
}

@Composable
internal fun CacheLimitDialog(
    currentLimitMiB: Long,
    hint: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var input by rememberSaveable(currentLimitMiB) { mutableStateOf(currentLimitMiB.toString()) }
    val limit = input.trim().toLongOrNull()?.takeIf { it in CACHE_LIMIT_RANGE }
    val colors = GalleryTheme.colors
    GalleryAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.remote_cache_limit), color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField(input, { input = it }, R.string.remote_cache_limit_mib, busy, KeyboardType.Number)
                Text(hint, color = colors.textMuted, style = GalleryTheme.type.meta)
                if (error != null) Text(error, color = colors.text, style = GalleryTheme.type.meta)
            }
        },
        confirmButton = {
            ServerButton(R.string.action_save, enabled = !busy && limit != null) { limit?.let(onSave) }
        },
        dismissButton = { ServerButton(R.string.action_cancel, enabled = !busy, onClick = onDismiss) },
    )
}

@Composable
fun RemoteServerScreen(
    id: String?,
    onBack: () -> Unit,
    onConnected: (String) -> Unit,
    viewModel: RemoteSourcesViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val editing = servers?.firstOrNull { it.id == id }
    val colors = GalleryTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.settingsBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        ServerHeader(if (id == null) R.string.remote_add else R.string.remote_edit, onBack)
        if (id != null && editing == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.screen), color = colors.accent)
            return
        }
        ServerForm(editing, busy, error, Modifier.weight(1f)) { name, protocol, address, username, password ->
            viewModel.connect(id, name, protocol, address, username, password, onConnected)
        }
    }
}

@Composable
internal fun ServerHeader(title: Int, onBack: () -> Unit) {
    ServerHeader(stringResource(title), onBack)
}

@Composable
private fun ServerHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.padding(start = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(GalleryIcons.Back, onClick = onBack)
        Text(title, style = GalleryTheme.type.title, color = GalleryTheme.colors.text)
    }
}

@Composable
private fun ServerForm(
    editing: RemoteServerStatus?,
    busy: Boolean,
    error: RemoteFailure?,
    modifier: Modifier = Modifier,
    onSave: (String, RemoteProtocol, String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var protocol by rememberSaveable { mutableStateOf(editing?.protocol ?: RemoteProtocol.WebDav) }
    var address by rememberSaveable { mutableStateOf(editing?.address.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(editing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val colors = GalleryTheme.colors
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DropdownSetting(
                label = stringResource(R.string.remote_protocol),
                options = RemoteProtocol.entries,
                selected = protocol,
                onSelect = { if (!busy) protocol = it },
                shape = RectangleShape,
            ) { stringResource(it.labelRes) }
            HorizontalDivider(color = colors.hairline)
            ServerField(name, { name = it }, R.string.remote_name, busy)
            ServerField(address, { address = it }, R.string.remote_address, busy, KeyboardType.Uri) {
                Text(stringResource(if (protocol == RemoteProtocol.WebDav) R.string.remote_webdav_example else R.string.remote_ftp_example))
            }
            if (protocol == RemoteProtocol.Ftp || address.startsWith("http://", ignoreCase = true)) {
                Text(stringResource(R.string.remote_cleartext), color = colors.textMuted, style = GalleryTheme.type.meta)
            }
            ServerField(username, { username = it }, R.string.remote_username, busy)
            ServerField(password, { password = it }, R.string.remote_password, busy, KeyboardType.Password)
        }
        HorizontalDivider(color = colors.hairline)
        Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) Text(stringResource(error.labelRes), color = colors.text, style = GalleryTheme.type.body)
            Button(
                onClick = { onSave(name, protocol, address, username, password) },
                enabled = !busy && address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            ) {
                Text(stringResource(if (busy) R.string.remote_connecting else R.string.remote_connect))
            }
        }
    }
}

@Composable
internal fun ServerField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    busy: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: @Composable (() -> Unit)? = null,
) {
    val colors = GalleryTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = !busy,
        singleLine = true,
        shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            focusedLabelColor = colors.accent, unfocusedLabelColor = colors.textMuted,
            focusedPlaceholderColor = colors.textMuted, unfocusedPlaceholderColor = colors.textMuted,
            focusedBorderColor = colors.accent, unfocusedBorderColor = colors.hairline,
            cursorColor = colors.accent,
        ),
    )
}

@Composable
internal fun ServerButton(label: Int, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = RectangleShape) {
        Text(stringResource(label), color = if (enabled) GalleryTheme.colors.accent else Color.Gray)
    }
}

private val RemoteProtocol.labelRes: Int get() = when (this) {
    RemoteProtocol.WebDav -> R.string.remote_webdav
    RemoteProtocol.Ftp -> R.string.remote_ftp
}

internal val RemoteFailure.labelRes: Int get() = when (this) {
    RemoteFailure.Address -> R.string.remote_address_error
    RemoteFailure.Credentials -> R.string.remote_credentials_error
    RemoteFailure.Connection -> R.string.remote_connection_error
    RemoteFailure.Storage -> R.string.remote_storage_error
}
