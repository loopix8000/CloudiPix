package app.cloudipix.gallery.feature.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.treeUriForPath
import app.cloudipix.gallery.core.model.withLeadingSlash
import app.cloudipix.gallery.core.remote.ConnectedFolder
import app.cloudipix.gallery.core.remote.isRemoteDescendantOf
import app.cloudipix.gallery.core.remote.remoteId
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.FolderSyncHistoryEntry
import app.cloudipix.gallery.core.sync.FolderSyncProgress
import app.cloudipix.gallery.core.sync.FolderSyncStatus
import app.cloudipix.gallery.core.sync.SyncConflictPolicy
import app.cloudipix.gallery.core.sync.SyncFileFilter
import app.cloudipix.gallery.core.sync.SyncFileFilterSettings
import app.cloudipix.gallery.core.sync.SyncFileCounts
import app.cloudipix.gallery.core.sync.SyncLocationMode
import app.cloudipix.gallery.core.sync.SyncRoot
import app.cloudipix.gallery.core.sync.SyncSetupException
import app.cloudipix.gallery.core.sync.SyncSetupIssue
import app.cloudipix.gallery.core.sync.SyncSubfolderPhase
import app.cloudipix.gallery.core.sync.filterForFolder
import app.cloudipix.gallery.core.sync.suggestedCustomLocationMode
import app.cloudipix.gallery.feature.permission.AllFilesAccessRequest
import app.cloudipix.gallery.feature.permission.DeviceFolderPicker
import app.cloudipix.gallery.navigation.FolderSyncOptions
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.MenuEntry
import app.cloudipix.gallery.ui.component.OverflowMenu
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class FolderSyncViewModel @Inject constructor(
    savedState: SavedStateHandle,
    sync: FolderSync,
) : SyncSettingsViewModel(sync) {
    private val route = savedState.toRoute<FolderSyncOptions>()
    internal val managingAll = route.albumId == null && route.serverId == null
    internal val managingServer = route.serverId != null
    internal val folders = when {
        route.albumId != null -> combine(sync.foldersForAlbum(route.albumId), sync.linkedFolders) { matched, all ->
            matched.map { it.syncRoot(all) }.distinctBy(ConnectedFolder::bucketId).withLinkedSubfolders(all)
        }
        managingServer -> sync.linkedFolders.map { folders -> folders.filter { it.serverId == route.serverId } }
        else -> sync.linkedFolders
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    internal val results = sync.results
    internal val progress = sync.progress
    internal val conflictPolicy = sync.conflictPolicy
    internal val cleanupPending = sync.localCleanupPending

    internal fun destination(folder: ConnectedFolder, mode: SyncLocationMode) = sync.relativeDestination(folder, mode)
    internal fun previewRoot(uri: Uri, onReady: (SyncRoot) -> Unit) = change { onReady(sync.describeRoot(uri)) }
    internal fun selectLocation(id: Long, uri: Uri, mode: SyncLocationMode, onSaved: () -> Unit) = change {
        sync.setLocation(id, uri, mode)
        onSaved()
    }
    internal fun confirmDefault(id: Long, root: SyncRoot, requestAccess: (Uri) -> Unit, onSaved: () -> Unit) = change {
        val uri = Uri.parse(root.treeUri)
        if (sync.prepareRootAndCheckAccess(uri)) {
            sync.setLocation(id, uri, SyncLocationMode.Default)
            onSaved()
        } else requestAccess(uri)
    }
    internal fun stop(folder: ConnectedFolder, deleteLocalCopies: Boolean) =
        change { sync.stopSync(folder, deleteLocalCopies) }
    internal fun setSubfolderIncluded(folder: ConnectedFolder, included: Boolean) = change {
        sync.setSubfolderIncluded(folder.bucketId, included)
    }
    internal fun unlink(folder: ConnectedFolder, deleteLocalCopies: Boolean) =
        change { sync.unlink(folder, deleteLocalCopies) }
    internal fun run(id: String, subfolder: String?) = change { sync.enqueue(id, subfolder) }
    internal fun history(folderId: Long) = sync.runHistory(folderId)
    internal val fileFilters = sync.fileFilters
    internal fun setFileFilter(folder: ConnectedFolder, filter: SyncFileFilter?) = change {
        sync.setFolderFileFilter(folder.serverId, folder.folder, filter)
    }
}

abstract class SyncSettingsViewModel(protected val sync: FolderSync) : ViewModel() {
    internal val builtInRoot = sync.builtInRoot
    internal val defaultRoot = sync.defaultRoot
    internal val busy = MutableStateFlow(false)
    internal val error = MutableStateFlow<Int?>(null)
    internal fun setDefaultRoot(uri: Uri?) = change { sync.setDefaultRoot(uri) }

    protected fun change(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: SyncSetupException) {
                error.value = when (exception.issue) {
                    SyncSetupIssue.Location -> R.string.sync_local_error
                    SyncSetupIssue.Overlap -> R.string.sync_overlap
                }
            } catch (_: SecurityException) {
                error.value = R.string.sync_local_error
            } catch (_: IOException) {
                error.value = R.string.sync_setup_error
            } finally {
                busy.value = false
            }
        }
    }
}

private enum class FolderAction { Location, StopSync, Unlink }

private data class LinkedFolderRow(val folder: ConnectedFolder, val depth: Int, val hasChildren: Boolean)

// 상위 폴더의 동기화 설정을 따르는 하위 폴더는 상위 폴더 기준의 전체 화면으로 연다.
private fun ConnectedFolder.syncRoot(all: List<ConnectedFolder>): ConnectedFolder {
    val root = inheritedFrom ?: return this
    return all.firstOrNull { it.serverId == serverId && it.folder.path == root.path } ?: this
}

// 하위 폴더까지 연결한 부모 폴더는 아래의 연결된 폴더를 함께 보여 전체 트리를 조회할 수 있게 한다.
private fun List<ConnectedFolder>.withLinkedSubfolders(all: List<ConnectedFolder>): List<ConnectedFolder> {
    val parents = filter { it.inheritedFrom == null && it.folder.includeSubfolders }
    if (parents.isEmpty()) return this
    val descendants = all.filter { candidate ->
        parents.any { parent ->
            candidate.serverId == parent.serverId &&
                candidate.folder.path.trimEnd('/').isRemoteDescendantOf(parent.folder.path.trimEnd('/'))
        }
    }
    return (this + descendants).distinctBy(ConnectedFolder::bucketId)
}

private fun List<ConnectedFolder>.childrenByParent(): Map<Long?, List<ConnectedFolder>> {
    val foldersByPath = associateBy { it.serverId to it.folder.path.trimEnd('/') }
    return groupBy { folder ->
        generateSequence(folder.folder.path.trimEnd('/')) { path ->
            path.substringBeforeLast('/').takeIf { it != path }
        }.drop(1).firstNotNullOfOrNull { foldersByPath[folder.serverId to it] }?.bucketId
    }
}

private fun Map<Long?, List<ConnectedFolder>>.visibleFolderRows(isExpanded: (Long) -> Boolean): List<LinkedFolderRow> =
    buildList {
        fun appendChildren(parentId: Long?, depth: Int) {
            for (child in this@visibleFolderRows[parentId].orEmpty()) {
                val hasChildren = this@visibleFolderRows.containsKey(child.bucketId)
                add(LinkedFolderRow(child, depth, hasChildren))
                if (hasChildren && isExpanded(child.bucketId)) {
                    appendChildren(child.bucketId, depth + 1)
                }
            }
        }
        appendChildren(null, 0)
    }

@Composable
fun FolderSyncScreen(
    onBack: () -> Unit,
    onOpenFolderSync: (Long) -> Unit = {},
    viewModel: FolderSyncViewModel = hiltViewModel(),
) {
    val colors = GalleryTheme.colors
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val fileFilters by viewModel.fileFilters.collectAsStateWithLifecycle<SyncFileFilterSettings?>(null)
    val results by viewModel.results.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val conflictPolicy by viewModel.conflictPolicy.collectAsStateWithLifecycle()
    val cleanupPending by viewModel.cleanupPending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var action by rememberSaveable { mutableStateOf<FolderAction?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedFolderIds by key("expandedFolders") {
        rememberSaveable(
            stateSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() }),
        ) { mutableStateOf(emptySet<Long>()) }
    }

    val managingAll = viewModel.managingAll
    val expandedIds = expandedFolderIds
    val childrenByParent = remember(folders) { folders.childrenByParent() }
    val folderRows = remember(childrenByParent, managingAll, expandedIds) {
        childrenByParent.visibleFolderRows { id -> !managingAll || id in expandedIds }
    }
    val indentPerLevel = remember(childrenByParent) {
        fun subtreeDepth(parentId: Long): Int =
            childrenByParent[parentId]?.maxOf { 1 + subtreeDepth(it.bucketId) } ?: 0

        val maxDepth = childrenByParent[null]?.maxOf { subtreeDepth(it.bucketId) } ?: 0
        minOf(14.dp, 72.dp / maxDepth.coerceAtLeast(1))
    }
    val showSyncDetails = !viewModel.managingAll
    val historyFolder = if (showSyncDetails) folders.firstOrNull { it.bucketId == historyId } else null
    if (historyFolder != null) {
        val closeHistory = { historyId = null }
        BackHandler(onBack = closeHistory)
        SyncHistoryScreen(historyFolder, viewModel, closeHistory)
        return
    }

    val chooseAction: (ConnectedFolder, FolderAction) -> Unit = { folder, choice ->
        viewModel.error.value = null
        selectedId = folder.bucketId
        action = choice
    }

    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ServerHeader(when {
            viewModel.managingAll -> R.string.sync_manage_linked_folders
            viewModel.managingServer -> R.string.sync_folder_locations
            else -> R.string.sync_details_title
        }, onBack)
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), state = listState) {
            error?.let { message -> item { SyncText(stringResource(message), muted = false) } }
            if (cleanupPending) item { SyncText(stringResource(R.string.sync_cleanup_pending)) }
            if (folders.isEmpty()) {
                item { SyncText(stringResource(R.string.sync_no_folders)) }
            }
            items(folderRows, key = { it.folder.bucketId }) { (folder, depth, hasChildren) ->
                val childrenExpanded = folder.bucketId in expandedIds
                val sync = folder.effectiveSync
                val subfolder = folder.inheritedRelativePath
                val syncProgress = sync?.let { progress[it.id] }?.takeIf { !folder.excludedByAncestor && it.includes(subfolder) }
                // 상위 폴더는 전체 현황을, 연결된 하위 폴더는 그 폴더 안의 현황만 보여준다.
                val subfolderSummary = if (subfolder == null && syncProgress != null) {
                    val linked = folders.mapNotNull { child ->
                        child.inheritedRelativePath?.takeIf {
                            child.serverId == folder.serverId && child.inheritedFrom?.path == folder.folder.path &&
                                !child.excludedFromSync && !child.excludedByAncestor && syncProgress.includes(it)
                        }
                    }
                    if (linked.isEmpty()) null else linked.count(syncProgress.finishedSubfolders::contains) to linked.size
                } else null
                Column(Modifier.fillMaxWidth().padding(start = indentPerLevel * depth)) {
                    HorizontalDivider(color = colors.hairline)
                    LinkedFolderSettings(
                        folder = folder,
                        result = sync?.let { results[it.id] }?.let { if (subfolder == null) it else it.forSubfolder(subfolder) },
                        progress = syncProgress?.let { if (subfolder == null) it else it.forSubfolder(subfolder) },
                        subfolderPhase = subfolder?.let { syncProgress?.phaseOf(it) },
                        subfolderSummary = subfolderSummary,
                        conflictPolicy = conflictPolicy,
                        busy = busy,
                        inFolderList = viewModel.managingAll || viewModel.managingServer,
                        showSyncDetails = showSyncDetails,
                        allowSyncToggle = !viewModel.managingAll,
                        childrenExpanded = childrenExpanded,
                        onToggleChildren = if (managingAll && hasChildren) {
                            {
                                val id = folder.bucketId
                                expandedFolderIds = if (id in expandedFolderIds) {
                                    expandedFolderIds - id
                                } else {
                                    expandedFolderIds + id
                                }
                            }
                        } else null,
                        onChoose = { choice -> chooseAction(folder, choice) },
                        onSetIncluded = { included -> viewModel.setSubfolderIncluded(folder, included) },
                        onRun = { sync?.let { viewModel.run(it.id, subfolder) } },
                        onHistory = { historyId = folder.bucketId },
                        onOpenFolderSync = if (viewModel.managingAll) {
                            { onOpenFolderSync(folder.bucketId) }
                        } else null,
                        onEditFilter = if (viewModel.managingAll && fileFilters != null) {
                            { filterFolderId = folder.bucketId }
                        } else null,
                    )
                }
            }
        }
    }

    val filters = fileFilters
    val filterFolder = folders.firstOrNull { it.bucketId == filterFolderId }
    if (filters != null && filterFolder != null) {
        val folder = filterFolder
        val path = folder.folder.displayPath.trimEnd('/')
        val overrides = filters.overridesFor(folder.serverId)
        val inherited = if (path.isEmpty()) filters.global else
            overrides.filterForFolder(path.substringBeforeLast('/', ""), filters.global)
        CloudFileFilterDialog(
            current = overrides[path], inherited = inherited, folderName = folder.folder.name,
            busy = busy,
            onDismiss = { filterFolderId = null },
            onSave = { viewModel.setFileFilter(folder, it) },
        )
    }

    val currentAction = action
    val selected = folders.firstOrNull { it.bucketId == selectedId }
    if (currentAction != null && selected != null && selected.inheritedFrom == null) {
        val title = when (currentAction) {
            FolderAction.Location -> if (selected.folder.sync == null) R.string.sync_enable else R.string.sync_change_location
            FolderAction.StopSync -> R.string.sync_disable
            FolderAction.Unlink -> R.string.sync_disconnect
        }
        val dismiss = {
            action = null
            selectedId = null
        }
        if (currentAction == FolderAction.Location) {
            SyncLocationDialog(selected, title, viewModel, dismiss)
            return
        }
        val location = selected.folder.sync
        val canDeleteLocalCopies = location?.keepLocalFiles == false
        var deleteLocalCopies by rememberSaveable(currentAction, selected.bucketId, location?.id) { mutableStateOf(false) }
        DialogCard(stringResource(title), onDismiss = dismiss) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (currentAction == FolderAction.Unlink) {
                    SyncText(stringResource(R.string.sync_disconnect_confirm, selected.folder.name))
                    if (selected.folder.includeSubfolders) SyncText(stringResource(R.string.remote_disconnect_subfolders))
                } else {
                    SyncText(stringResource(R.string.sync_disable_confirm, selected.folder.name))
                }
                if (canDeleteLocalCopies) {
                    Column(Modifier.selectableGroup()) {
                        SyncOption(
                            stringResource(R.string.sync_keep_local_files),
                            stringResource(R.string.sync_keep_selected_folder_hint),
                            selected = !deleteLocalCopies, enabled = !busy,
                        ) { deleteLocalCopies = false }
                        SyncOption(
                            stringResource(R.string.sync_delete_local_copies),
                            stringResource(R.string.sync_delete_local_copies_hint),
                            selected = deleteLocalCopies, enabled = !busy,
                        ) { deleteLocalCopies = true }
                    }
                } else if (location != null) {
                    SyncText(stringResource(R.string.sync_keep_selected_folder_hint))
                }
            }
            DialogButtons(
                enabled = !busy,
                onDismiss = dismiss,
                onConfirm = {
                    val delete = canDeleteLocalCopies && deleteLocalCopies
                    dismiss()
                    if (currentAction == FolderAction.Unlink) viewModel.unlink(selected, delete) else viewModel.stop(selected, delete)
                },
                confirmLabel = stringResource(title),
            )
        }
    }
}

@Composable
private fun SyncHistoryScreen(link: ConnectedFolder, viewModel: FolderSyncViewModel, onBack: () -> Unit) {
    val folder = link.inheritedFrom ?: link.folder
    val folderId = remoteId(link.serverId, folder.path)
    val history by remember(folderId) { viewModel.history(folderId) }
        .collectAsStateWithLifecycle<Result<List<FolderSyncHistoryEntry>>?>(initialValue = null)
    val entries = history?.getOrNull().orEmpty()
    val colors = GalleryTheme.colors
    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ServerHeader(R.string.sync_history_title, onBack)
        Column(Modifier.padding(horizontal = Spacing.screen, vertical = 8.dp)) {
            SyncText(folder.name, muted = false)
            SyncText("${link.serverName} · ${folder.displayPath}")
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
            if (entries.isEmpty()) {
                item {
                    val message = when {
                        history == null -> R.string.sync_history_loading
                        history?.isFailure == true -> R.string.sync_history_error
                        else -> R.string.sync_history_empty
                    }
                    SyncText(stringResource(message))
                }
            }
            items(entries) { entry ->
                HorizontalDivider(color = colors.hairline)
                SyncHistoryRow(entry)
            }
        }
    }
}

@Composable
internal fun SyncHistoryRow(entry: FolderSyncHistoryEntry, folderLabel: String? = null) {
    val result = entry.result
    val outcome = when {
        result.failed -> R.string.sync_history_failed
        result.conflicts > 0 -> R.string.sync_history_conflicts
        else -> R.string.sync_history_completed
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        folderLabel?.let { SyncText(it, muted = false) }
        val label = stringResource(outcome)
        val trigger = entry.automatic?.let { stringResource(if (it) R.string.sync_history_automatic else R.string.sync_history_manual) }
        val duration = entry.durationMs?.let {
            if (it < 1_000L) stringResource(R.string.sync_history_under_second)
            else stringResource(R.string.sync_history_duration, formatDuration(it))
        }
        SyncText(listOfNotNull(label, trigger, duration).joinToString(" · "), muted = false)
        result.lastRun?.let { SyncText(syncDate(it)) }
        result.counts?.let { SyncCountsText(it) }
        val resolved = result.resolvedConflicts
        if (result.conflicts > 0 || resolved.total > 0) {
            SyncText(stringResource(R.string.sync_conflict_result, resolved.total, result.conflicts))
            if (resolved.total > 0) {
                SyncText(stringResource(R.string.sync_conflict_result_detail, resolved.device, resolved.server, resolved.both))
            }
        }
        entry.localLabel?.let { SyncText(storagePathLabel(it)) }
    }
}

@Composable
private fun SyncLocationDialog(folder: ConnectedFolder, title: Int, viewModel: FolderSyncViewModel, onDismiss: () -> Unit) {
    val root by viewModel.defaultRoot.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val previous = folder.folder.sync?.takeIf { it.directChild || it.useTreeRoot }
    var mode by rememberSaveable(folder.bucketId) {
        mutableStateOf(when {
            previous?.useTreeRoot == true -> SyncLocationMode.SelectedFolder
            previous?.directChild == true -> SyncLocationMode.ChildFolder
            else -> SyncLocationMode.Default
        })
    }
    var customUri by rememberSaveable(folder.bucketId) { mutableStateOf(previous?.treeUri) }
    var customPath by rememberSaveable(folder.bucketId) {
        mutableStateOf(previous?.let { if (it.useTreeRoot) it.localLabel else it.localLabel.substringBeforeLast('/') })
    }
    val context = LocalContext.current
    var choosingCustom by rememberSaveable { mutableStateOf(false) }
    var requestingAccess by rememberSaveable { mutableStateOf(false) }
    val useDefault = mode == SyncLocationMode.Default
    val basePath = if (useDefault) root.label else customPath
    val subfolder = viewModel.destination(folder, mode)
    val dismiss = { if (!busy) onDismiss() }
    DialogCard(stringResource(title), onDismiss = dismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            SyncText("${folder.serverName} · ${folder.folder.name}")
            Column(Modifier.padding(vertical = 8.dp).selectableGroup()) {
                SyncOption(stringResource(R.string.sync_default_root), storagePathLabel(root.label), useDefault, !busy) {
                    mode = SyncLocationMode.Default
                }
                SyncOption(stringResource(R.string.sync_custom_root), customPath?.let { storagePathLabel(it) } ?: stringResource(R.string.sync_pick_location),
                    !useDefault, !busy) {
                    choosingCustom = true
                }
            }
            if (!useDefault && customPath != null) {
                val createChildFolder = mode == SyncLocationMode.ChildFolder
                Row(
                    Modifier.fillMaxWidth().toggleable(createChildFolder, enabled = !busy, role = Role.Checkbox) { checked ->
                        mode = if (checked) SyncLocationMode.ChildFolder else SyncLocationMode.SelectedFolder
                    }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sync_use_child_folder), style = GalleryTheme.type.body,
                        color = GalleryTheme.colors.text, modifier = Modifier.weight(1f),
                    )
                    CheckBox(checked = createChildFolder, modifier = Modifier.alpha(if (busy) 0.38f else 1f))
                }
            }
            if (basePath != null) {
                SyncText(stringResource(R.string.sync_final_destination))
                val finalPath = if (subfolder.isEmpty()) basePath else "${basePath.trimEnd('/')}/$subfolder"
                SyncText(storagePathLabel(finalPath), muted = false)
                if (mode == SyncLocationMode.SelectedFolder && previous?.directChild == true && previous.treeUri == customUri) {
                    SyncText(stringResource(R.string.sync_excluded_previous_folder, previous.localLabel.substringAfterLast('/')))
                }
            }
            Column(Modifier.padding(top = 10.dp)) {
                SyncText(stringResource(R.string.sync_setup_hint))
                error?.let { SyncText(stringResource(it), muted = false) }
            }
        }
        DialogButtons(enabled = !busy && basePath != null, onDismiss = dismiss, confirmLabel = stringResource(title), onConfirm = {
            if (useDefault) {
                viewModel.confirmDefault(folder.bucketId, root, requestAccess = { requestingAccess = true }, onSaved = onDismiss)
            } else customUri?.let { viewModel.selectLocation(folder.bucketId, Uri.parse(it), mode, onDismiss) }
        })
    }
    if (choosingCustom) DeviceFolderPicker(
        title = stringResource(R.string.sync_custom_root),
        initialPath = customPath ?: root.label,
        onPick = { path ->
            choosingCustom = false
            context.treeUriForPath(path)?.let { uri ->
                viewModel.previewRoot(uri) { chosen ->
                    customUri = chosen.treeUri
                    customPath = chosen.label
                    mode = suggestedCustomLocationMode(chosen.label, viewModel.destination(folder, SyncLocationMode.ChildFolder))
                }
            }
        },
        onDismiss = { choosingCustom = false },
    )
    // 기본 위치에 접근할 수 없으면 폴더 선택기 대신 모든 파일 접근을 요청하고 다시 저장한다.
    if (requestingAccess) AllFilesAccessRequest(
        onGranted = {
            requestingAccess = false
            viewModel.confirmDefault(folder.bucketId, root, requestAccess = {}, onSaved = onDismiss)
        },
        onDismiss = { requestingAccess = false },
    )
}

@Composable
internal fun SyncOption(label: String, hint: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textMuted))
        Column(Modifier.weight(1f)) {
            SyncText(label, muted = false)
            SyncText(hint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedFolderSettings(
    folder: ConnectedFolder,
    result: FolderSyncStatus?,
    progress: FolderSyncProgress?,
    subfolderPhase: SyncSubfolderPhase?,
    subfolderSummary: Pair<Int, Int>?,
    conflictPolicy: SyncConflictPolicy,
    busy: Boolean,
    inFolderList: Boolean,
    showSyncDetails: Boolean,
    // 연결된 폴더 관리에서는 동기화 켜기·끄기 메뉴를 보이지 않는다.
    allowSyncToggle: Boolean,
    childrenExpanded: Boolean,
    onToggleChildren: (() -> Unit)?,
    onChoose: (FolderAction) -> Unit,
    onSetIncluded: (Boolean) -> Unit,
    onRun: () -> Unit,
    onHistory: () -> Unit,
    onOpenFolderSync: (() -> Unit)?,
    onEditFilter: (() -> Unit)?,
) {
    val location = folder.effectiveSync
    val lastRun = result?.lastRun
    val running = progress != null
    val parent = folder.inheritedFrom
    val excluded = folder.excludedFromSync
    val excludedByAncestor = folder.excludedByAncestor
    val canRun = location != null && !running && !excludedByAncestor &&
        (parent == null || folder.inheritedRelativePath != null)
    val inclusionLabel = if (excluded) R.string.sync_include_subfolder else R.string.sync_exclude_subfolder
    val toggleInclusion = { onSetIncluded(excluded) }
    val toggleLabel = if (location == null) R.string.sync_enable else R.string.sync_disable
    val toggleSync = { onChoose(if (location == null) FolderAction.Location else FolderAction.StopSync) }
    val colors = GalleryTheme.colors
    val status = when {
        excludedByAncestor -> stringResource(R.string.sync_ancestor_excluded)
        excluded -> stringResource(R.string.sync_subfolder_excluded)
        subfolderPhase == SyncSubfolderPhase.Waiting -> stringResource(R.string.sync_subfolder_waiting)
        subfolderPhase == SyncSubfolderPhase.Finished -> stringResource(R.string.sync_subfolder_finished)
        running -> stringResource(R.string.sync_notification_title)
        location == null -> stringResource(R.string.sync_disabled)
        !showSyncDetails -> null
        result?.failed == true -> stringResource(R.string.sync_failed)
        lastRun == null -> stringResource(R.string.sync_waiting)
        else -> stringResource(R.string.sync_last_run, syncDate(lastRun))
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            val toggleModifier = if (onToggleChildren != null) {
                val label = if (childrenExpanded) R.string.sync_collapse_subfolders else R.string.sync_expand_subfolders
                Modifier.heightIn(min = 48.dp).clickable(
                    role = Role.Button, onClickLabel = stringResource(label), onClick = onToggleChildren,
                )
            } else Modifier
            Row(Modifier.weight(1f).then(toggleModifier), verticalAlignment = Alignment.CenterVertically) {
                if (onToggleChildren != null) {
                    val collapsedRotation = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f
                    Icon(
                        GalleryIcons.Chevron,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.padding(end = 4.dp).size(20.dp)
                            .rotate(if (childrenExpanded) 0f else collapsedRotation),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(folder.folder.name, style = GalleryTheme.type.body, color = colors.text)
                    Text(listOfNotNull(folder.serverName, status).joinToString(" · "),
                        style = GalleryTheme.type.meta, color = colors.textMuted)
                }
            }
            if (inFolderList) {
                if (!busy && (!excludedByAncestor || onEditFilter != null)) {
                    OverflowMenu(label = "${folder.folder.name} · ${stringResource(R.string.action_more)}") { dismiss ->
                        if (parent != null) {
                            if (!excludedByAncestor) MenuEntry(stringResource(inclusionLabel)) { dismiss(); toggleInclusion() }
                        } else if (allowSyncToggle) {
                            MenuEntry(stringResource(toggleLabel)) { dismiss(); toggleSync() }
                        }
                        if (canRun) {
                            MenuEntry(stringResource(R.string.sync_now)) { dismiss(); onRun() }
                        }
                        if (onOpenFolderSync != null) {
                            MenuEntry(stringResource(R.string.sync_preferences)) { dismiss(); onOpenFolderSync() }
                        }
                        if (onEditFilter != null) {
                            MenuEntry(stringResource(R.string.remote_file_filter)) { dismiss(); onEditFilter() }
                        }
                        if (parent == null) {
                            if (location != null) {
                                MenuEntry(stringResource(R.string.sync_change_location)) { dismiss(); onChoose(FolderAction.Location) }
                            }
                            if (showSyncDetails) MenuEntry(stringResource(R.string.sync_history_title)) { dismiss(); onHistory() }
                            MenuEntry(stringResource(R.string.sync_disconnect)) { dismiss(); onChoose(FolderAction.Unlink) }
                        }
                    }
                }
            } else if (parent != null) {
                ServerButton(inclusionLabel, enabled = !busy && !excludedByAncestor, onClick = toggleInclusion)
            } else {
                val description = "${folder.folder.name} · ${stringResource(toggleLabel)}"
                ServerButton(toggleLabel, enabled = !busy, modifier = Modifier.semantics { contentDescription = description }, onClick = toggleSync)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SyncPath(R.string.sync_network_path, folder.folder.displayPath)
            if (location != null) {
                folder.configuredLocalPath?.let { SyncPath(R.string.sync_local_path, storagePathLabel(it)) }
            }
        }
        if (parent != null) SyncText(stringResource(R.string.sync_inherited, parent.displayPath))
        location?.excludedLocalChild?.let { SyncText(stringResource(R.string.sync_excluded_previous_folder, it)) }
        if (location != null) {
            val policy = progress?.conflictPolicy ?: conflictPolicy
            SyncText(stringResource(R.string.sync_conflict_policy_value, stringResource(policy.label)))
        }
        if (showSyncDetails && subfolderPhase != SyncSubfolderPhase.Waiting) {
            if (progress != null) {
                subfolderSummary?.let { (finished, total) ->
                    SyncText(stringResource(R.string.sync_subfolder_progress, finished, total))
                }
                val comparing = progress.comparingFolders.size
                if (comparing > 0) {
                    SyncText(pluralStringResource(R.plurals.sync_comparing_folders, comparing, comparing))
                } else {
                    if (progress.relativePath.isNotEmpty()) SyncText(progress.relativePath.withLeadingSlash())
                    if (progress.directoryEntries > 0) {
                        SyncText(stringResource(R.string.sync_directory_position, progress.entryNumber, progress.directoryEntries))
                    }
                }
            }
            val counts = progress?.counts ?: result?.counts
            counts?.let { SyncCountsText(it) }
            val conflicts = progress?.conflicts ?: result?.conflicts ?: 0
            if (progress != null || result?.conflictPolicy != null) {
                val resolved = progress?.resolvedConflicts ?: result?.resolvedConflicts
                SyncText(stringResource(R.string.sync_conflict_result, resolved?.total ?: 0, conflicts))
                if (resolved != null && resolved.total > 0) {
                    SyncText(stringResource(R.string.sync_conflict_result_detail, resolved.device, resolved.server, resolved.both))
                }
                if (progress == null && result?.conflictPolicy != null && result.conflictPolicy != conflictPolicy) {
                    SyncText(stringResource(R.string.sync_previous_conflict_policy, stringResource(result.conflictPolicy.label)))
                }
            } else if (conflicts > 0) {
                SyncText(stringResource(R.string.sync_conflicts, conflicts))
            }
        }
        if (!inFolderList) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canRun) ServerButton(R.string.sync_now, enabled = !busy, onClick = onRun)
                if (location != null && parent == null) {
                    ServerButton(R.string.sync_change_location, enabled = !busy) { onChoose(FolderAction.Location) }
                }
                ServerButton(R.string.sync_history_open, onClick = onHistory)
            }
        }
    }
}

private fun syncDate(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

@Composable
private fun SyncCountsText(counts: SyncFileCounts) {
    SyncText(stringResource(R.string.sync_file_counts, counts.completed, counts.uploaded, counts.downloaded, counts.deleted, counts.unchanged))
}

@Composable
private fun SyncPath(@StringRes label: Int, path: String) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(label),
            modifier = Modifier.alignByBaseline(),
            style = GalleryTheme.type.label,
            color = colors.textMuted,
            maxLines = 1,
        )
        Text(
            path,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = GalleryTheme.type.meta,
            color = colors.text,
            textAlign = TextAlign.Left,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

@Composable
internal fun SyncText(text: String, muted: Boolean = true) {
    val colors = GalleryTheme.colors
    Text(text, style = if (muted) GalleryTheme.type.meta else GalleryTheme.type.body,
        color = if (muted) colors.textMuted else colors.text)
}
