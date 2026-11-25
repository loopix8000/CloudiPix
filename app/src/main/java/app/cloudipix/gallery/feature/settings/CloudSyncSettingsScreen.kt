package app.cloudipix.gallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.treeUriForPath
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.MIN_SYNC_INTERVAL
import app.cloudipix.gallery.core.sync.SyncConflictPolicy
import app.cloudipix.gallery.core.sync.SyncNotifications
import app.cloudipix.gallery.feature.permission.DeviceFolderPicker
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.MenuEntry
import app.cloudipix.gallery.ui.component.OverflowMenu
import app.cloudipix.gallery.ui.component.TextDialog
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CloudSyncSettingsViewModel @Inject constructor(sync: FolderSync) : SyncSettingsViewModel(sync) {
    internal val interval = sync.interval
    internal val conflictPolicy = sync.conflictPolicy
    internal fun setInterval(minutes: Int) = change { sync.setInterval(minutes) }
    internal fun setConflictPolicy(policy: SyncConflictPolicy) = change { sync.setConflictPolicy(policy) }
}

@Composable
fun CloudSyncSettingsScreen(onBack: () -> Unit, viewModel: CloudSyncSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val root by viewModel.defaultRoot.collectAsStateWithLifecycle()
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val conflictPolicy by viewModel.conflictPolicy.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var choosingRoot by rememberSaveable { mutableStateOf(false) }
    var editInterval by rememberSaveable { mutableStateOf(false) }
    var policyChoice by rememberSaveable { mutableStateOf<SyncConflictPolicy?>(null) }
    val colors = GalleryTheme.colors

    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ServerHeader(R.string.sync_preferences, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LinkRow(stringResource(R.string.sync_default_root), storagePathLabel(root.label),
                        horizontalPadding = 0.dp, enabled = !busy) { choosingRoot = true }
                }
                if (root.treeUri != viewModel.builtInRoot.treeUri && !busy) OverflowMenu { dismiss ->
                    MenuEntry(stringResource(R.string.sync_clear_root)) { dismiss(); viewModel.setDefaultRoot(null) }
                }
            }
            HorizontalDivider(color = colors.hairline)
            LinkRow(stringResource(R.string.sync_interval_value, interval), stringResource(R.string.sync_interval_hint),
                horizontalPadding = 0.dp, enabled = !busy) { editInterval = true }
            HorizontalDivider(color = colors.hairline)
            LinkRow(stringResource(R.string.sync_conflict_policy), stringResource(conflictPolicy.label),
                horizontalPadding = 0.dp, enabled = !busy) { policyChoice = conflictPolicy }
            HorizontalDivider(color = colors.hairline)
            LinkRow(stringResource(R.string.sync_notification_settings), hint = null, horizontalPadding = 0.dp) {
                context.startActivity(SyncNotifications.settingsIntent(context))
            }
            error?.let { Text(stringResource(it), style = GalleryTheme.type.body, color = colors.text) }
        }
    }
    if (choosingRoot) DeviceFolderPicker(
        title = stringResource(R.string.sync_default_root),
        initialPath = root.label,
        onPick = { path ->
            choosingRoot = false
            context.treeUriForPath(path)?.let(viewModel::setDefaultRoot)
        },
        onDismiss = { choosingRoot = false },
    )
    if (editInterval) TextDialog(
        title = stringResource(R.string.sync_interval_dialog),
        initial = interval.toString(),
        isValid = { (it.trim().toIntOrNull() ?: 0) >= MIN_SYNC_INTERVAL },
        onConfirm = { editInterval = false; viewModel.setInterval(it.trim().toInt()) },
        onDismiss = { editInterval = false },
    )
    policyChoice?.let { choice ->
        val dismiss = { policyChoice = null }
        DialogCard(stringResource(R.string.sync_conflict_policy), onDismiss = dismiss) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp).selectableGroup()) {
                for (policy in SyncConflictPolicy.entries) {
                    SyncOption(stringResource(policy.label), stringResource(policy.description), policy == choice, !busy) {
                        policyChoice = policy
                    }
                }
            }
            DialogButtons(enabled = !busy, onDismiss = dismiss, confirmLabel = stringResource(R.string.action_save), onConfirm = {
                dismiss()
                viewModel.setConflictPolicy(choice)
            })
        }
    }
}

internal val SyncConflictPolicy.label: Int get() = when (this) {
    SyncConflictPolicy.KeepBoth -> R.string.sync_conflict_keep_both
    SyncConflictPolicy.PreferLocal -> R.string.sync_conflict_prefer_local
    SyncConflictPolicy.PreferRemote -> R.string.sync_conflict_prefer_remote
    SyncConflictPolicy.PreferNewest -> R.string.sync_conflict_prefer_newest
    SyncConflictPolicy.KeepBothVersions -> R.string.sync_conflict_keep_both_versions
}

private val SyncConflictPolicy.description: Int get() = when (this) {
    SyncConflictPolicy.KeepBoth -> R.string.sync_conflict_keep_both_hint
    SyncConflictPolicy.PreferLocal -> R.string.sync_conflict_prefer_local_hint
    SyncConflictPolicy.PreferRemote -> R.string.sync_conflict_prefer_remote_hint
    SyncConflictPolicy.PreferNewest -> R.string.sync_conflict_prefer_newest_hint
    SyncConflictPolicy.KeepBothVersions -> R.string.sync_conflict_keep_both_versions_hint
}
