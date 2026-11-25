package app.cloudipix.gallery.feature.viewer

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.data.MediaWriter
import app.cloudipix.gallery.core.media.MediaDetails
import app.cloudipix.gallery.core.media.MediaDetailsLoader
import app.cloudipix.gallery.core.media.MediaPaths
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.remote.RemoteFiles
import app.cloudipix.gallery.core.remote.photoMime
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.navigation.Viewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ViewerUiState(
    val media: List<Medium> = emptyList(),
    val libraryLoaded: Boolean = false,
    val startIndex: Int = 0,
    val details: MediaDetails = MediaDetails(),
    val autoplayNextVideo: Boolean = false,
    val backgroundPlayback: Boolean = false,
    val volumeButtonPaging: Boolean = false,
    val panoramaForAll: Boolean = false,
    val scrim: Float = 0f,
    val actions: BarLayout = BarLayout.Viewer,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val writer: MediaWriter,
    private val detailsLoader: MediaDetailsLoader,
    private val mediaPaths: MediaPaths,
    private val remoteFiles: RemoteFiles,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Viewer>()
    val isFavorites = route.bucketId == FAVORITES_BUCKET_ID
    private val details = MutableStateFlow(MediaDetails())

    private val library = settings.settings.flatMapLatest { config ->
        repository.mediaIn(route.bucketId, config.mediaFilter).map { media ->
            val sorted = media.sortedBy(config.sortingFor(route.bucketId))
            ViewerUiState(
                media = sorted,
                libraryLoaded = true,
                startIndex = sorted.indexOfFirst { it.id == route.mediumId }.coerceAtLeast(0),
                autoplayNextVideo = config.autoplayNextVideo,
                backgroundPlayback = config.backgroundPlayback,
                volumeButtonPaging = config.volumeButtonPaging,
                panoramaForAll = config.panoramaForAll,
                scrim = config.viewerScrim,
                actions = config.viewerBar,
            )
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ViewerUiState> =
        combine(library, details) { viewer, info -> viewer.copy(details = info) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    internal suspend fun shareEditorPhoto(photo: Medium): Uri = remoteFiles.shareUri(photo)

    internal fun setPhotoEditorPackage(packageName: String) = viewModelScope.launch {
        settings.setPhotoEditorPackage(packageName)
    }

    suspend fun withViewerOriginal(
        uri: Uri,
        awaitSelection: suspend () -> Medium,
        display: suspend (file: Uri, cached: Boolean) -> Unit,
    ) {
        if (remoteFiles.withCachedOriginalUri(uri) { display(it, true) }) return
        val medium = awaitSelection()
        remoteFiles.withReadableUri(medium) { display(it, false) }
    }

    suspend fun loadDetails(medium: Medium, original: Uri?) {
        if (!medium.isRemote) {
            details.value = MediaDetails()
            details.value = detailsLoader.load(medium.uri, medium.name)
            return
        }

        val network = MediaDetails(path = repository.remotePathLabel(medium))
        details.value = network
        val localPath = medium.localCopyUri?.let { mediaPaths.absolute(it) }
        val initial = network.copy(localPath = localPath?.let(LocalStoragePath::absolute))
        details.value = initial
        if (original != null) {
            details.value = try {
                detailsLoader.fromOriginal(original, initial, photoMime(medium.name))
            } catch (_: java.io.IOException) {
                initial
            } catch (_: SecurityException) {
                initial
            }
        }
    }

    fun toggleFavorite(medium: Medium) = viewModelScope.launch {
        repository.setFavorite(medium, !medium.isFavorite)
    }

    fun writeRequest(medium: Medium): IntentSender = writer.writeRequest(listOf(medium))

    fun editMetadata(medium: Medium, edit: MetadataEdit, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val saved = try { repository.editMetadata(medium, edit) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { false }
        if (saved && !medium.isRemote) loadDetails(medium, medium.uri)
        onDone(saved)
    }

}
