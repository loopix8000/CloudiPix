package app.cloudipix.gallery.feature.files

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.core.media.acceptsMedia
import app.cloudipix.gallery.core.media.pathDocumentId
import app.cloudipix.gallery.core.media.storageRoots
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.remote.TrashChange
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.feature.permission.AllFilesAccessRequest
import app.cloudipix.gallery.feature.permission.DeviceFolderPicker
import app.cloudipix.gallery.feature.permission.MediaManagementOffer
import app.cloudipix.gallery.feature.permission.canManageGalleryMedia
import app.cloudipix.gallery.feature.permission.markMoveMediaLocationRequested
import app.cloudipix.gallery.feature.permission.shouldOfferMediaManagement
import app.cloudipix.gallery.feature.permission.shouldRequestMoveMediaLocation
import app.cloudipix.gallery.ui.component.GallerySheet
import app.cloudipix.gallery.ui.component.TextDialog
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.folderPathLabel
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

enum class FileAction(@StringRes val labelRes: Int, @StringRes val failedRes: Int) {
    Copy(R.string.action_copy_to, R.string.files_copy_failed),
    Move(R.string.action_move_to, R.string.files_move_failed),
    Rename(R.string.action_rename, R.string.files_rename_failed),
    Share(R.string.action_share, R.string.cloud_file_error),
    SetAs(R.string.action_set_as, R.string.cloud_file_error),
    Trash(R.string.action_delete, R.string.cloud_file_error),
    Restore(R.string.action_restore, R.string.cloud_file_error),
    Delete(R.string.action_delete_forever, R.string.cloud_file_error),
}

private class MoveDestination(
    val name: String,
    val bucketId: Long?,
    val isCloud: Boolean,
    val folder: FolderEntry?,
    val resolve: suspend () -> FolderEntry,
)

@Composable
fun FileActions(
    action: FileAction?,
    media: List<Medium>,
    onDone: (FileAction) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FileActionsViewModel = hiltViewModel(),
) {
    if (action == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember(action) { media.toList() }
    val local = remember(selected) { selected.filterNot(Medium::isRemote) }
    val cloud = remember(selected) { selected.filter(Medium::isRemote) }
    val movingLocal = action == FileAction.Move && local.isNotEmpty()
    val moveVolume by produceState<String?>(null, movingLocal, local) {
        value = if (movingLocal) viewModel.newFolderVolume(local) else null
    }
    val newLocalVolume = if (movingLocal) moveVolume else MediaStore.VOLUME_EXTERNAL_PRIMARY
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var busy by remember(action) { mutableStateOf(false) }
    var naming by remember(action) { mutableStateOf(action == FileAction.Rename) }
    var choosingParent by remember(action) { mutableStateOf(false) }
    var newFolderParent by remember(action) { mutableStateOf<FolderEntry?>(null) }
    var moveDestination by remember(action) { mutableStateOf<MoveDestination?>(null) }
    var browsingDevice by remember(action) { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pending?.complete(it.resultCode == Activity.RESULT_OK)
    }
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.complete(it)
    }
    var allFilesRequest by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    suspend fun requestAllFiles(): Boolean {
        val result = CompletableDeferred<Boolean>()
        allFilesRequest = result
        return try { result.await() } finally { allFilesRequest = null }
    }

    suspend fun awaitPermissionResult(launchRequest: () -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        pending = result
        return try {
            launchRequest()
            result.await()
        } finally { pending = null }
    }
    suspend fun requestConsent(sender: IntentSender): Boolean =
        awaitPermissionResult { consent.launch(IntentSenderRequest.Builder(sender).build()) }

    suspend fun approve(items: List<Medium>, request: (List<Medium>) -> IntentSender): Boolean =
        items.chunked(2000).all { requestConsent(request(it)) }
    fun perform(work: suspend () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (work()) onDone(action) else onDismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, action.failedRes, Toast.LENGTH_LONG).show()
                onDismiss()
            } finally {
                busy = false
            }
        }
    }
    fun commit(requestMediaLocation: Boolean = false, destination: suspend () -> FolderEntry) = perform {
        val requested = destination()
        val moving = action == FileAction.Move
        val localSources = local.filterNot { it.bucketId == requested.bucketId }
        val syncedTarget = if (moving && cloud.isEmpty()) viewModel.localMoveTarget(requested, localSources) else null
        val folder = syncedTarget ?: requested
        val cloudTarget = isRemoteId(folder.bucketId) && syncedTarget == null
        // 사진·동영상 컬렉션이 받지 않는 위치로는 모든 파일 접근 권한이 있어야 옮기거나 저장할 수 있다.
        val localTarget = !cloudTarget && folder.volumeName != null
        val needsAllFiles = localTarget && selected.any { !folder.path.acceptsMedia(it.type) }
        if (needsAllFiles && !hasAllFilesAccess() && !requestAllFiles()) return@perform false
        // 권한이 있으면 기기 사진을 파일 경로로 처리해 수정 허락과 원본 삭제 확인을 묻지 않는다.
        val byPath = localTarget && hasAllFilesAccess()
        val copiedSources = if (moving) viewModel.localSourcesToCopyForMove(localSources, folder) else emptyList()
        val crossVolumeIds = if (cloudTarget) emptySet() else copiedSources.mapTo(hashSetOf(), Medium::id)
        val needsOriginalAccess = !byPath && crossVolumeIds.isNotEmpty() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (needsOriginalAccess || (!byPath && !cloudTarget && requestMediaLocation && context.shouldRequestMoveMediaLocation())) {
            context.markMoveMediaLocationRequested()
            awaitPermissionResult { mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        }
        if (moving && !cloudTarget && !byPath) {
            val inPlace = localSources.filterNot { it.id in crossVolumeIds }
            if (!approve(inPlace, viewModel::writeRequest)) return@perform false
        }
        val versions = if (copiedSources.isEmpty() || byPath) emptyMap() else viewModel.generations(copiedSources)
        var sourceFavorites = emptySet<String>()
        try {
            sourceFavorites = viewModel.transfer(selected, folder, moving, crossVolumeIds, byPath)
            if (copiedSources.isNotEmpty() && !byPath) {
                if (versions != viewModel.generations(copiedSources)) {
                    Toast.makeText(context, R.string.cloud_source_changed, Toast.LENGTH_LONG).show()
                    return@perform false
                }
                val needsConsent = viewModel.deleteMovedSources(copiedSources, versions)
                for (batch in needsConsent.chunked(2000)) {
                    if (viewModel.generations(batch).any { (id, version) -> versions[id] != version }) {
                        Toast.makeText(context, R.string.cloud_source_changed, Toast.LENGTH_LONG).show()
                        return@perform false
                    }
                    if (!requestConsent(viewModel.trashRequest(batch, TrashChange.Delete))) {
                        Toast.makeText(context, R.string.cloud_original_kept, Toast.LENGTH_LONG).show()
                        return@perform false
                    }
                }
            }
        } finally {
            if (copiedSources.isNotEmpty()) viewModel.finishCopiedMove(sourceFavorites, copiedSources)
        }
        true
    }
    fun chooseDestination(
        name: String,
        bucketId: Long?,
        isCloud: Boolean = bucketId?.let(::isRemoteId) == true,
        folder: FolderEntry? = null,
        resolve: suspend () -> FolderEntry,
    ) {
        if (action == FileAction.Move) moveDestination = MoveDestination(name, bucketId, isCloud, folder, resolve)
        else commit(destination = resolve)
    }
    fun trash(change: TrashChange) = perform {
        if (change != TrashChange.Restore) {
            return@perform viewModel.submitRemoval(selected, change) { batch ->
                requestConsent(viewModel.trashRequest(batch, change))
            }
        }
        if (!approve(local) { viewModel.trashRequest(it, change) }) return@perform false
        if (cloud.isNotEmpty()) viewModel.changeRemoteTrash(cloud, change)
        true
    }
    if (selected.isEmpty()) {
        LaunchedEffect(action) { onDismiss() }
        return
    }
    allFilesRequest?.let { request ->
        AllFilesAccessRequest(onGranted = { request.complete(true) }, onDismiss = { request.complete(false) })
    }
    if (busy) {
        if (pending == null && allFilesRequest == null) DialogCard(stringResource(R.string.cloud_working), onDismiss = {}) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        return
    }

    val destination = moveDestination
    when {
        action == FileAction.Share || action == FileAction.SetAs -> LaunchedEffect(action) {
            perform {
                context.startActivity(viewModel.export(selected, setAs = action == FileAction.SetAs))
                true
            }
        }
        action in listOf(FileAction.Trash, FileAction.Restore, FileAction.Delete) -> {
            val change = when (action) {
                FileAction.Restore -> TrashChange.Restore
                FileAction.Delete -> TrashChange.Delete
                else -> TrashChange.Trash
            }
            val confirmRemoval = when (change) {
                TrashChange.Restore -> false
                TrashChange.Delete -> true
                TrashChange.Trash -> {
                    val confirm by viewModel.confirmTrash.collectAsStateWithLifecycle(initialValue = null)
                    confirm ?: return
                }
            }
            var offerMediaManagement by remember(action, confirmRemoval) {
                mutableStateOf(confirmRemoval && local.isNotEmpty() && context.shouldOfferMediaManagement())
            }
            when {
                offerMediaManagement -> MediaManagementOffer(
                    onContinue = { offerMediaManagement = false },
                    onDismiss = onDismiss,
                )
                confirmRemoval && (cloud.isNotEmpty() || context.canManageGalleryMedia()) -> {
                    val message = if (change == TrashChange.Delete) R.string.cloud_delete_confirm else R.string.cloud_trash_confirm
                    DialogCard(stringResource(message, selected.size), onDismiss) {
                        DialogButtons(true, onDismiss, { trash(change) }, confirmLabel = stringResource(action.labelRes))
                    }
                }
                else -> LaunchedEffect(action) { trash(change) }
            }
        }
        action == FileAction.Rename -> {
            val medium = selected.single()
            val extension = medium.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val stem = medium.name.removeSuffix(extension)
            TextDialog(
                title = stringResource(R.string.action_rename),
                initial = stem,
                isValid = { isValidName(it) && it.trim() != stem },
                onConfirm = { stem -> perform {
                    if (!hasAllFilesAccess() && !approve(local, viewModel::writeRequest)) return@perform false
                    viewModel.rename(medium, stem.trim() + extension)
                    true
                } },
                onDismiss = onDismiss,
            )
        }
        else -> {
            val folders by viewModel.folders.collectAsStateWithLifecycle()
            val pickerFolders by viewModel.pickerFolders.collectAsStateWithLifecycle()
            val syncStates by viewModel.albumSyncStates.collectAsStateWithLifecycle()
            val destinations = remember(selected, folders) { destinationsFor(selected, folders) }
            val canCreateRemote = folders.any { isRemoteId(it.bucketId) && it.path.isNotEmpty() }
            FolderDestinationPicker(
                title = stringResource(action.labelRes),
                subtitle = stringResource(R.string.selection_count, selected.size),
                folders = pickerFolders,
                destinations = destinations,
                syncStates = syncStates,
                onPick = { folder -> chooseDestination(folder.name, folder.bucketId, folder = folder) { folder } },
                onBrowseDevice = { browsingDevice = true },
                onNewFolder = if (canCreateRemote || newLocalVolume != null) {
                    { if (canCreateRemote) choosingParent = true else naming = true }
                } else null,
                onDismiss = onDismiss,
            )
            // 확인·새 폴더·위치 선택 창은 폴더 선택 화면 위에 띄우고, 취소하면 선택 화면으로 돌아간다.
            when {
                destination != null -> MoveConfirmation(
                    destination = destination,
                    selected = selected,
                    local = local,
                    onDismiss = { moveDestination = null },
                    onConfirm = { requestMediaLocation -> commit(requestMediaLocation, destination.resolve) },
                )
                browsingDevice -> DeviceFolderPicker(
                    title = stringResource(R.string.sync_pick_location),
                    initialPath = null,
                    onPick = { path ->
                        browsingDevice = false
                        val roots = context.storageRoots()
                        val id = pathDocumentId(roots, path)
                        val root = roots.firstOrNull { it.id == id?.substringBefore(':') }
                        val relative = id?.substringAfter(':').orEmpty()
                        if (root != null && relative.isNotEmpty()) {
                            val folderPath = "$relative/"
                            val folder = folders.firstOrNull { it.volumeName == root.volumeName && it.path == folderPath }
                                ?: FolderEntry(Long.MAX_VALUE, relative.substringAfterLast('/'), folderPath, 0, root.volumeName)
                            chooseDestination(folder.name, folder.bucketId, folder = folder) { folder }
                        } else Toast.makeText(context, action.failedRes, Toast.LENGTH_LONG).show()
                    },
                    onDismiss = { browsingDevice = false },
                    selectStorageRoot = false,
                )
                choosingParent -> {
                    val parents = folders.filter { isRemoteId(it.bucketId) && it.path.isNotEmpty() }
                    GallerySheet(stringResource(R.string.files_new_folder), { choosingParent = false }) {
                        LazyColumn {
                            if (newLocalVolume != null) item {
                                SheetRow(
                                    stringResource(R.string.cloud_device),
                                    { choosingParent = false; naming = true },
                                    subtitle = storagePathLabel(LocalStoragePath.relative("Pictures", newLocalVolume)),
                                )
                            }
                            items(parents, key = FolderEntry::bucketId) { parent ->
                                SheetRow(parent.name, {
                                    newFolderParent = parent
                                    choosingParent = false
                                    naming = true
                                }, subtitle = folderPathLabel(parent, preferSyncLocalPath = action == FileAction.Move))
                            }
                        }
                    }
                }
                naming -> TextDialog(
                    title = stringResource(R.string.files_new_folder),
                    initial = "",
                    isValid = ::isValidName,
                    onConfirm = { name ->
                        val parent = newFolderParent
                        naming = false
                        newFolderParent = null
                        chooseDestination(name.trim(), bucketId = null, isCloud = parent != null) {
                            viewModel.newFolder(parent, name, newLocalVolume)
                        }
                    },
                    onDismiss = { naming = false; newFolderParent = null },
                )
            }
        }
    }
}

@Composable
private fun MoveConfirmation(
    destination: MoveDestination,
    selected: List<Medium>,
    local: List<Medium>,
    onDismiss: () -> Unit,
    onConfirm: (requestMediaLocation: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val hasLocalSources = local.any { it.bucketId != destination.bucketId }
    var offerMediaManagement by remember(destination) {
        mutableStateOf(hasLocalSources && !hasAllFilesAccess() && context.shouldOfferMediaManagement())
    }
    if (offerMediaManagement) {
        MediaManagementOffer(
            onContinue = { offerMediaManagement = false },
            onDismiss = onDismiss,
        )
        return
    }
    val requestMediaLocation =
        hasLocalSources && !destination.isCloud && !hasAllFilesAccess() && context.shouldRequestMoveMediaLocation()
    val count = selected.count { it.bucketId != destination.bucketId }
    DialogCard(stringResource(R.string.files_move_confirm, count, destination.name), onDismiss) {
        destination.folder?.let {
            Text(
                folderPathLabel(it, preferSyncLocalPath = true),
                style = GalleryTheme.type.meta,
                color = GalleryTheme.colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (requestMediaLocation) {
            Text(
                stringResource(R.string.files_move_location_hint),
                modifier = Modifier.padding(top = 8.dp),
                style = GalleryTheme.type.body,
                color = GalleryTheme.colors.textMuted,
            )
        }
        DialogButtons(
            enabled = true,
            onDismiss = onDismiss,
            onConfirm = { onConfirm(requestMediaLocation) },
            confirmLabel = stringResource(R.string.action_move),
        )
    }
}

@Composable
private fun SheetRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val colors = GalleryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = GalleryTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = GalleryTheme.type.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

