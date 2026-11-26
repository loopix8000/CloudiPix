package app.cloudipix.gallery.feature.viewer

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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
import app.cloudipix.gallery.core.model.sortedBy
import app.cloudipix.gallery.core.remote.RemoteFiles
import app.cloudipix.gallery.core.remote.photoMime
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.settings.sortingFor
import app.cloudipix.gallery.navigation.Viewer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 뷰어 상태입니다. [media]는 폴더 화면과 같은 순서로 정렬한 목록이고, [startIndex]는 처음 열 항목의 위치입니다.
 * [libraryLoaded]가 거짓이면 아직 목록을 읽는 중입니다.
 */
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

    /** 지금 보고 있는 항목의 정보 시트 내용입니다. 목록과 따로 갱신합니다. */
    private val shownDetails = MutableStateFlow(MediaDetails())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val gallery: Flow<ViewerUiState> = settings.settings
        .flatMapLatest { config -> repository.mediaIn(route.bucketId, config.mediaFilter).map { viewerState(it, config) } }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<ViewerUiState> = combine(gallery, shownDetails) { base, details -> base.copy(details = details) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    private fun viewerState(media: List<Medium>, config: GallerySettings): ViewerUiState {
        val ordered = media.sortedBy(config.sortingFor(route.bucketId))
        val opened = ordered.indexOfFirst { it.id == route.mediumId }
        return ViewerUiState(
            media = ordered,
            libraryLoaded = true,
            startIndex = maxOf(opened, 0),
            autoplayNextVideo = config.autoplayNextVideo,
            backgroundPlayback = config.backgroundPlayback,
            volumeButtonPaging = config.volumeButtonPaging,
            panoramaForAll = config.panoramaForAll,
            scrim = config.viewerScrim,
            actions = config.viewerBar,
        )
    }

    internal suspend fun refreshOpenedPhoto(uri: Uri, localCopyModified: Long?) =
        repository.refreshOpenedPhoto(uri, localCopyModified)

    /** 외부 편집 앱에 넘길 원격 사진의 공유 주소입니다. */
    internal suspend fun shareEditorPhoto(photo: Medium): Uri = remoteFiles.shareUri(photo)

    internal fun setPhotoEditorPackage(packageName: String) = viewModelScope.launch {
        settings.setPhotoEditorPackage(packageName)
    }

    /**
     * 원격 원본을 보여 줍니다. 이미 받아 둔 원본이 있으면 선택을 기다리지 않고 바로 보여 주고,
     * 없으면 사용자가 그 항목에 머물 때까지 기다린 뒤 받아서 보여 줍니다.
     */
    suspend fun withViewerOriginal(
        uri: Uri,
        awaitSelection: suspend () -> Medium,
        display: suspend (file: Uri, cached: Boolean) -> Unit,
    ) {
        val shownFromCache = remoteFiles.withCachedOriginalUri(uri) { display(it, true) }
        if (shownFromCache) return
        val medium = awaitSelection()
        remoteFiles.withReadableUri(medium) { display(it, false) }
    }

    /**
     * 정보 시트 내용을 읽습니다. 기기 항목은 MediaStore와 EXIF에서 읽습니다.
     * 원격 항목은 서버 경로를 먼저 보여 준 뒤, 기기 사본 경로와 [original]의 EXIF를 차례로 덧붙입니다.
     */
    suspend fun loadDetails(medium: Medium, original: Uri?) {
        if (medium.isRemote) loadRemoteDetails(medium, original) else loadDeviceDetails(medium)
    }

    private suspend fun loadDeviceDetails(medium: Medium) {
        shownDetails.value = MediaDetails()
        shownDetails.value = detailsLoader.load(medium.uri, medium.name)
    }

    private suspend fun loadRemoteDetails(medium: Medium, original: Uri?) {
        val serverOnly = MediaDetails(path = repository.remotePathLabel(medium))
        shownDetails.value = serverOnly
        val copyPath = medium.localCopyUri?.let { mediaPaths.absolute(it) }
        val withCopy = serverOnly.copy(localPath = copyPath?.let(LocalStoragePath::absolute))
        shownDetails.value = withCopy
        if (original == null) return
        shownDetails.value = try {
            detailsLoader.fromOriginal(original, withCopy, photoMime(medium.name))
        } catch (_: IOException) {
            withCopy
        } catch (_: SecurityException) {
            withCopy
        }
    }

    fun toggleFavorite(medium: Medium) = viewModelScope.launch {
        repository.setFavorite(medium, favorite = !medium.isFavorite)
    }

    /** 다른 앱이 만든 파일을 고치기 전에 받을 쓰기 허락 요청입니다. */
    fun writeRequest(medium: Medium): IntentSender = writer.writeRequest(listOf(medium))

    /** 메타데이터를 저장하고 성공 여부를 알려 줍니다. 기기 항목은 저장 뒤 정보 시트를 다시 읽습니다. */
    fun editMetadata(medium: Medium, edit: MetadataEdit, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val saved = try {
            repository.editMetadata(medium, edit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (saved && !medium.isRemote) loadDetails(medium, medium.uri)
        onDone(saved)
    }
}
