package app.cloudipix.gallery.feature.files

import android.content.IntentSender
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.R
import dagger.hilt.android.lifecycle.HiltViewModel
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.data.MediaWriter
import app.cloudipix.gallery.core.media.MediaPaths
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.manualSortPosition
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.remote.RemoteFiles
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.TrashChange
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.sync.FolderSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FileActionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val writer: MediaWriter,
    private val remoteFiles: RemoteFiles,
    private val gallery: RemoteGallery,
    private val paths: MediaPaths,
    private val folderSync: FolderSync,
    settings: SettingsRepository,
) : ViewModel() {

    val confirmTrash = settings.settings.map { it.confirmTrash }

    val folders: StateFlow<List<FolderEntry>> =
        repository.folderEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 표시 필터와 관계없이 모든 폴더를 폴더 화면 순서로 보여 주고, 숨긴 폴더는 따로 모은다.
    internal val pickerFolders: StateFlow<PickerFolders?> =
        combine(repository.folders(MediaFilter.All), repository.hiddenFolders(), settings.settings) { shown, hidden, config ->
            PickerFolders(shown.inFolderScreenOrder(config), hidden.sortedBy { it.name.lowercase() }, config)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal val albumSyncStates = folderSync.albumSyncStates(repository.folderEntries.map { folders ->
        folders.filter { it.itemCount > 0 }.mapNotNull { it.bucketId.takeUnless(::isRemoteId) }.toSet()
    }.distinctUntilChanged()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun writeRequest(media: List<Medium>): IntentSender = writer.writeRequest(media)
    suspend fun generations(media: List<Medium>) = writer.generations(media)
    suspend fun deleteMovedSources(media: List<Medium>, versions: Map<Long, Long>) = writer.deleteMovedSources(media, versions)

    internal suspend fun newFolderVolume(media: List<Medium>): String {
        val ids = media.map(Medium::id)
        val volumes = paths.volumes(ids)
        return ids.map { volumes[it] }.distinct().singleOrNull() ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
    }

    suspend fun newFolder(parent: FolderEntry?, name: String, volume: String?): FolderEntry = if (parent != null) {
        gallery.createFolder(parent.bucketId, name.trim())
    } else FolderEntry(Long.MAX_VALUE, name.trim(), newFolderPath(name), 0, requireNotNull(volume))

    internal suspend fun localSourcesToCopyForMove(media: List<Medium>, folder: FolderEntry): List<Medium> =
        repository.localSourcesToCopyForMove(media, folder)

    internal suspend fun localMoveTarget(folder: FolderEntry, media: List<Medium>) =
        folderSync.localMoveTarget(folder, media)

    internal suspend fun transfer(
        media: List<Medium>, folder: FolderEntry, move: Boolean, crossVolumeIds: Set<Long>, byPath: Boolean,
    ): Set<String> {
        val result = repository.transfer(media, folder, move, crossVolumeIds, byPath)
        check(result.completed == media.size)
        return result.sourceFavorites
    }

    internal suspend fun finishCopiedMove(sourceFavorites: Set<String>, sources: List<Medium>) =
        repository.finishCopiedMove(sourceFavorites, sources.mapTo(mutableSetOf(), Medium::id))

    suspend fun rename(medium: Medium, name: String) { check(repository.rename(medium, name)) }

    fun trashRequest(media: List<Medium>, change: TrashChange): IntentSender = when (change) {
        TrashChange.Delete -> writer.deleteRequest(media)
        else -> writer.trashRequest(media, change == TrashChange.Trash)
    }

    suspend fun changeRemoteTrash(media: List<Medium>, change: TrashChange) = repository.changeRemoteTrash(media, change)

    suspend fun submitRemoval(media: List<Medium>, change: TrashChange, approve: suspend (List<Medium>) -> Boolean) =
        repository.submitRemoval(media, change, approve) {
            Toast.makeText(context, R.string.cloud_file_error, Toast.LENGTH_LONG).show()
        }

    suspend fun export(media: List<Medium>, setAs: Boolean): Intent {
        val uris = ArrayList(media.map { remoteFiles.shareUri(it) })
        val mime = when {
            media.all { it.type == MediaType.Video } -> "video/*"
            media.none { it.type == MediaType.Video } -> "image/*"
            else -> "*/*"
        }
        val intent = when {
            setAs -> Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uris.single(), mime).putExtra("mimeType", mime)
            uris.size == 1 -> Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris.single())
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.clipData = ClipData.newUri(context.contentResolver, null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }
}

internal data class PickerFolders(val shown: List<Folder>, val hidden: List<Folder>, val settings: GallerySettings)

private fun List<Folder>.inFolderScreenOrder(config: GallerySettings): List<Folder> {
    val ordered = if (config.manualFolderSort) {
        val positions = config.folderOrder.withIndex().associate { it.value to it.index }
        sortedBy { it.manualSortPosition(positions) ?: Int.MAX_VALUE }
    } else sortedBy(config.sorting)
    return ordered.sortedByDescending(Folder::isPinned)
}

internal fun destinationsFor(media: List<Medium>, folders: List<FolderEntry>): List<FolderEntry> {
    val primary = MediaStore.VOLUME_EXTERNAL_PRIMARY
    val localFallback = if (media.any { it.isRemote } && folders.none { it.path == "Pictures/" && it.volumeName == primary }) {
        listOf(FolderEntry(Long.MAX_VALUE, "Pictures", "Pictures/", 0, primary))
    } else emptyList()
    return (folders + localFallback).filter { folder ->
        folder.path.isNotEmpty() &&
            media.any { it.bucketId != folder.bucketId } &&
            (isRemoteId(folder.bucketId) || folder.volumeName != null)
    }
}

fun newFolderPath(name: String) = "Pictures/${name.trim()}/"

fun isValidName(name: String) = name.isNotBlank() && name.none { it in "/\\:*?\"<>|" } && name.trim() !in setOf(".", "..")
