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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenedMedium(val uri: Uri, val isVideo: Boolean, val edit: Boolean)

private val VIEW_ACTIONS = setOf(Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW")

fun Intent.openedMedium(type: String?): OpenedMedium? {
    val uri = data ?: return null
    if (type == null) return null
    if (action == Intent.ACTION_EDIT && type.startsWith("image/")) return OpenedMedium(uri, isVideo = false, edit = true)
    if (action !in VIEW_ACTIONS) return null
    if (!type.startsWith("image/") && !type.startsWith("video/")) return null
    return OpenedMedium(uri, isVideo = type.startsWith("video/"), edit = false)
}

sealed interface OpenedState {
    data object Resolving : OpenedState
    data class InGallery(val route: Any) : OpenedState
    data class Standalone(val panoramaForAll: Boolean, val scrim: Float, val bucketId: Long?) : OpenedState
}

@HiltViewModel
class OpenedMediaViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OpenedState>(OpenedState.Resolving)
    val state: StateFlow<OpenedState> = _state

    fun resolve(uri: Uri, edit: Boolean) {
        if (_state.value != OpenedState.Resolving) return
        viewModelScope.launch {
            val bucketId = repository.mediaBucketOf(uri)
            val medium = runCatching { repository.locate(uri) }.getOrNull()?.takeIf { it.bucketId == bucketId }
            if (edit) {
                _state.value = OpenedState.InGallery(Editor(medium?.id, bucketId ?: RECENT_BUCKET_ID, uri.toString()))
                return@launch
            }
            val config = settings.settings.first()
            val browsable = medium != null &&
                repository.mediaIn(medium.bucketId, config.mediaFilter).first().any { it.id == medium.id }
            _state.value = if (browsable) OpenedState.InGallery(Viewer(medium.bucketId, medium.id))
            else OpenedState.Standalone(config.panoramaForAll, config.viewerScrim, bucketId)
        }
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
    if (state !is OpenedState.InGallery) {
        GallerySystemBarAppearance(if (opened.edit) settings.themeMode else ThemeMode.Black)
    }
    when (val current = state) {
        OpenedState.Resolving -> {
            val background = if (opened.edit) GalleryTheme.colors.background else Color.Black
            Box(Modifier.fillMaxSize().background(background))
        }
        is OpenedState.InGallery -> GalleryNavHost(start = current.route, onExit = onClose, settings = settings, openAlbum = openAlbum)
        is OpenedState.Standalone -> GalleryScreenTheme(ThemeMode.Black) {
            FolderAccessGate(current.bucketId, onBack = onClose) {
                StandaloneViewer(opened.uri, opened.isVideo, current.panoramaForAll, current.scrim, onBack = onClose)
            }
        }
    }
}
