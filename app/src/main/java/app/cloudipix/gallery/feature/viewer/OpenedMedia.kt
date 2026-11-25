package app.cloudipix.gallery.feature.viewer

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.feature.security.FolderAccessGate
import app.cloudipix.gallery.navigation.Editor
import app.cloudipix.gallery.navigation.GalleryNavHost
import app.cloudipix.gallery.navigation.Media
import app.cloudipix.gallery.navigation.Viewer
import app.cloudipix.gallery.ui.theme.GalleryScreenTheme
import app.cloudipix.gallery.ui.theme.GallerySystemBarAppearance
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 다른 앱이 보기나 편집을 요청한 사진·동영상입니다. */
data class OpenedMedium(val uri: Uri, val isVideo: Boolean, val edit: Boolean)

private val VIEW_ACTIONS = setOf(Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW")

/**
 * 보기·편집 인텐트에서 열 미디어를 꺼냅니다. 편집은 사진만, 보기는 사진과 동영상만 받습니다.
 * [type]은 인텐트가 가리키는 데이터의 MIME 형식입니다.
 */
fun Intent.openedMedium(type: String?): OpenedMedium? {
    val uri = data ?: return null
    val image = type?.startsWith("image/") == true
    val video = type?.startsWith("video/") == true
    return when {
        action == Intent.ACTION_EDIT -> if (image) OpenedMedium(uri, isVideo = false, edit = true) else null
        action in VIEW_ACTIONS && (image || video) -> OpenedMedium(uri, isVideo = video, edit = false)
        else -> null
    }
}

sealed interface OpenedState {
    /** 갤러리 색인에서 같은 항목을 찾는 중입니다. */
    data object Resolving : OpenedState

    /** 갤러리 안의 화면으로 열어 같은 폴더의 다른 항목도 넘겨 볼 수 있습니다. */
    data class InGallery(val route: Any) : OpenedState

    /** 갤러리 색인에 없거나 보이지 않는 항목이라 단독 뷰어로 엽니다. */
    data class Standalone(val panoramaForAll: Boolean, val scrim: Float, val bucketId: Long?) : OpenedState
}

@HiltViewModel
class OpenedMediaViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val current = MutableStateFlow<OpenedState>(OpenedState.Resolving)
    val state: StateFlow<OpenedState> = current.asStateFlow()

    fun resolve(uri: Uri, edit: Boolean) {
        if (current.value != OpenedState.Resolving) return
        viewModelScope.launch {
            val bucketId = repository.mediaBucketOf(uri)
            val medium = bucketId?.let { galleryMedium(uri, it) }
            current.value = if (edit) {
                OpenedState.InGallery(Editor(medium?.id, bucketId ?: RECENT_BUCKET_ID, uri.toString()))
            } else {
                viewTarget(medium, bucketId)
            }
        }
    }

    /**
     * URI가 가리키는 갤러리 항목입니다. 폴더가 다르면 다른 항목으로 보고 버립니다.
     * 폴더를 알 수 없으면 일치 여부를 확인할 수 없으므로 색인 조회와 재동기화를 하지 않습니다.
     */
    private suspend fun galleryMedium(uri: Uri, bucketId: Long): Medium? {
        val found = try {
            repository.locate(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return found?.takeIf { it.bucketId == bucketId }
    }

    /** 현재 필터로 폴더에 보이는 항목이면 갤러리 뷰어로, 아니면 단독 뷰어로 엽니다. */
    private suspend fun viewTarget(medium: Medium?, bucketId: Long?): OpenedState {
        val config = settings.settings.first()
        if (medium != null) {
            val folderItems = repository.mediaIn(medium.bucketId, config.mediaFilter).first()
            if (folderItems.any { it.id == medium.id }) return OpenedState.InGallery(Viewer(medium.bucketId, medium.id))
        }
        return OpenedState.Standalone(config.panoramaForAll, config.viewerScrim, bucketId)
    }
}

@Composable
fun OpenedMediaScreen(
    opened: OpenedMedium,
    settings: GallerySettings,
    onClose: () -> Unit,
    openAlbum: (Media) -> Unit,
    viewModel: OpenedMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(opened.uri) { viewModel.resolve(opened.uri, opened.edit) }

    // 갤러리 화면은 자체 시스템 바 설정을 쓰므로 그 밖의 상태에서만 지정합니다.
    if (state !is OpenedState.InGallery) {
        GallerySystemBarAppearance(if (opened.edit) settings.themeMode else ThemeMode.Black)
    }
    when (val resolved = state) {
        OpenedState.Resolving -> {
            val backdrop = if (opened.edit) GalleryTheme.colors.background else Color.Black
            Box(Modifier.fillMaxSize().background(backdrop))
        }
        is OpenedState.InGallery ->
            GalleryNavHost(start = resolved.route, onExit = onClose, settings = settings, openAlbum = openAlbum)
        is OpenedState.Standalone -> GalleryScreenTheme(ThemeMode.Black) {
            FolderAccessGate(resolved.bucketId, onBack = onClose) {
                StandaloneViewer(opened.uri, opened.isVideo, resolved.panoramaForAll, resolved.scrim, onBack = onClose)
            }
        }
    }
}
