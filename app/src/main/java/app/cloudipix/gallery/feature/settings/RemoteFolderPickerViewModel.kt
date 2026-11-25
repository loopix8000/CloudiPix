package app.cloudipix.gallery.feature.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.remote.RemoteFailure
import app.cloudipix.gallery.core.remote.RemoteFolder
import app.cloudipix.gallery.core.remote.RemoteFolderLinks
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.RemoteRequestException
import app.cloudipix.gallery.core.remote.isRemoteDescendantOf
import app.cloudipix.gallery.core.sync.FolderConnectionLocation
import app.cloudipix.gallery.core.sync.FolderConnectionSettings
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.SyncFileFilter
import app.cloudipix.gallery.core.sync.SyncLocationMode
import app.cloudipix.gallery.core.sync.SyncRoot
import app.cloudipix.gallery.core.sync.SyncSetupException
import app.cloudipix.gallery.core.sync.SyncSetupIssue
import app.cloudipix.gallery.navigation.RemoteFolderPicker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

internal data class RemoteFolderPickerState(
    val trail: List<RemoteFolder> = emptyList(),
    val children: List<RemoteFolder> = emptyList(),
    val selected: RemoteFolder? = null,
    val subfolderChanges: Map<String, RemoteFolder> = emptyMap(),
    val links: RemoteFolderLinks = RemoteFolderLinks(emptyList()),
    val discoverAll: Boolean = false,
    val loading: Boolean = true,
    val browseError: RemoteFailure? = null,
) {
    fun isSubfolder(folder: RemoteFolder): Boolean =
        selected?.let { folder.path.isRemoteDescendantOf(it.path) } == true

    fun isChecked(folder: RemoteFolder): Boolean = folder.path == selected?.path ||
        (isSubfolder(folder) && (subfolderChanges[folder.path]?.linked ?: isIncludedByDefault(folder)))

    fun toggle(folder: RemoteFolder): RemoteFolderPickerState {
        if (isSubfolder(folder)) {
            val checked = !isChecked(folder)
            val changes = if (checked == isIncludedByDefault(folder)) subfolderChanges - folder.path else
                subfolderChanges + (folder.path to folder.copy(linked = checked, includeSubfolders = false))
            return copy(subfolderChanges = changes)
        }
        val root = if (folder.path == selected?.path) null else
            folder.copy(includeSubfolders = links.byPath[folder.path]?.takeIf(RemoteFolder::linked)?.includeSubfolders ?: true)
        return copy(selected = root, subfolderChanges = emptyMap())
    }

    private fun isIncludedByDefault(folder: RemoteFolder): Boolean =
        links.byPath[folder.path]?.linked ?: (discoverAll || selected?.includeSubfolders == true || links.selected.any {
            it.path != selected?.path && it.includeSubfolders &&
                subfolderChanges[it.path]?.linked != false && folder.path.isRemoteDescendantOf(it.path)
        })
}

internal data class RemoteFolderSetupState(
    val settings: FolderConnectionSettings? = null,
    val busy: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class RemoteFolderPickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val gallery: RemoteGallery,
    private val folderSync: FolderSync,
) : ViewModel() {
    private val id = savedState.toRoute<RemoteFolderPicker>().serverId
    private val current = MutableStateFlow(RemoteFolderPickerState())
    internal val state = current.asStateFlow()
    private val setupState = MutableStateFlow(RemoteFolderSetupState())
    internal val setup = setupState.asStateFlow()
    internal val defaultRoot = folderSync.defaultRoot
    private var listing: Job? = null

    init { reload() }

    fun reload() = browse(current.value.trail)

    fun open(folder: RemoteFolder) = browse(current.value.trail + folder)

    fun up() = browse(current.value.trail.dropLast(1))

    fun cancelBrowse() {
        listing?.cancel()
        current.update { it.copy(loading = false) }
    }

    fun toggle(folder: RemoteFolder) {
        if (setupState.value.busy || setupState.value.settings != null) return
        current.update { it.toggle(folder) }
    }

    internal fun setIncludeSubfolders(include: Boolean) {
        if (setupState.value.busy || setupState.value.settings != null) return
        current.update { it.copy(selected = it.selected?.copy(includeSubfolders = include)) }
    }

    internal fun openSetup() {
        val folder = current.value.selected ?: return
        changeSetup {
            val settings = folderSync.folderConnectionSettings(id, folder)
            setupState.update { it.copy(settings = settings) }
        }
    }

    internal fun closeSetup() {
        if (!setupState.value.busy) setupState.value = RemoteFolderSetupState()
    }

    internal fun clearError() = setupState.update { it.copy(error = null) }

    internal fun previewRoot(uri: Uri, onReady: (SyncRoot) -> Unit) =
        changeSetup { onReady(folderSync.describeRoot(uri)) }

    internal fun save(
        enableSync: Boolean,
        location: FolderConnectionLocation?,
        filter: SyncFileFilter?,
        onAccessRequired: () -> Unit,
        onSaved: () -> Unit,
    ) {
        val folder = setupState.value.settings?.folder ?: return
        val selection = current.value
        val selected = selection.selected ?: return
        if (selected.path != folder.path) return
        changeSetup {
            if (location?.mode == SyncLocationMode.Default && !folderSync.prepareRootAndCheckAccess(location.uri)) {
                onAccessRequired()
            } else {
                folderSync.saveFolderConnection(
                    id, folder, selected.includeSubfolders, selection.subfolderChanges.values.toList(), enableSync, location, filter,
                )
                onSaved()
            }
        }
    }

    private fun changeSetup(action: suspend () -> Unit) {
        if (setupState.value.busy) return
        setupState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteRequestException) {
                setupState.update { it.copy(error = error.failure.labelRes) }
            } catch (error: SyncSetupException) {
                val message = when (error.issue) {
                    SyncSetupIssue.Location -> R.string.sync_local_error
                    SyncSetupIssue.Overlap -> R.string.sync_overlap
                }
                setupState.update { it.copy(error = message) }
            } catch (_: SecurityException) {
                setupState.update { it.copy(error = R.string.sync_local_error) }
            } catch (_: IOException) {
                setupState.update { it.copy(error = R.string.remote_storage_error) }
            } finally {
                setupState.update { it.copy(busy = false) }
            }
        }
    }

    private fun browse(trail: List<RemoteFolder>) {
        if (setupState.value.busy || setupState.value.settings != null) return
        listing?.cancel()
        current.update { it.copy(loading = true, browseError = null) }
        listing = viewModelScope.launch {
            try {
                val selection = if (trail.isEmpty()) gallery.folderSelection(id) else null
                val openedTrail = if (selection == null) trail else listOf(selection.root)
                val children = gallery.foldersIn(id, openedTrail.last().path)
                ensureActive()
                current.update {
                    it.copy(
                        trail = openedTrail, children = children, loading = false,
                        links = selection?.links ?: it.links,
                        discoverAll = selection?.discoverAll ?: it.discoverAll,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteRequestException) {
                ensureActive()
                current.update { it.copy(browseError = error.failure, loading = false) }
            } catch (_: IOException) {
                ensureActive()
                current.update { it.copy(browseError = RemoteFailure.Connection, loading = false) }
            }
        }
    }
}
