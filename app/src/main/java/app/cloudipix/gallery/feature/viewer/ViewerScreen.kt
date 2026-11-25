package app.cloudipix.gallery.feature.viewer

import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.ui.component.iconSize
import coil3.compose.AsyncImage
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import app.cloudipix.gallery.core.media.MediaDetails
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.feature.security.LocalGalleryLocks
import app.cloudipix.gallery.ui.component.ActionBar
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.VolumeNavigation
import app.cloudipix.gallery.ui.component.VolumeStepAnimation
import app.cloudipix.gallery.feature.files.FileAction
import app.cloudipix.gallery.feature.files.FileActions
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.abs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TOP_INSETS
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

private val BOTTOM_INSETS
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

@Composable
private fun chromeScrim(strength: Float, top: Boolean): Brush {
    val background = GalleryTheme.colors.background
    val light = background.luminance() > 0.5f
    val outer = lerp(background.copy(alpha = if (light) 0.96f else if (top) 0.68f else 0.72f), background, strength)
    val inner = lerp(background.copy(alpha = if (light) 0.78f else 0f), background, strength)
    return Brush.verticalGradient(if (top) listOf(outer, inner) else listOf(inner, outer))
}

internal data class ViewerPhotoPreview(val id: Long, val uri: Uri)

private data class ViewerExit(val photo: ViewerPhotoPreview?)

private class ViewerRemoval(val medium: Medium, val media: List<Medium>) {
    fun nextPageIn(updated: List<Medium>): Int {
        val originalPage = media.indexOfFirst { it.id == medium.id }.coerceAtLeast(0)
        val neighbor = media.getOrNull(originalPage + 1) ?: media.getOrNull(originalPage - 1)
        val neighborPage = updated.indexOfFirst { it.id == neighbor?.id }
        if (neighborPage >= 0) return neighborPage

        // 목록 갱신과 휴지통 처리 결과는 어느 쪽이든 먼저 도착할 수 있다.
        val removedPage = updated.indexOfFirst { it.id == medium.id }
        return when {
            removedPage < 0 -> originalPage.coerceAtMost(updated.lastIndex)
            removedPage < updated.lastIndex -> removedPage + 1
            else -> removedPage - 1
        }
    }
}

@Composable
internal fun ViewerScreen(
    onBack: (photoId: Long?) -> Unit,
    onMediaRemoved: () -> Unit,
    onEdit: (Medium) -> Unit,
    initialMedium: Medium?,
    initialSettings: GallerySettings,
    initialPreview: ViewerPhotoPreview? = null,
    initialBitmap: BitmapImage? = null,
    chromeOverlay: Modifier = Modifier,
    photoTransition: @Composable (Long) -> Modifier = { Modifier },
    entering: Boolean = false,
    leaving: Boolean = false,
    photoInMotion: Boolean = false,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locks = LocalGalleryLocks.current
    val context = LocalContext.current
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var bottomBarHeight by remember { mutableStateOf(VIDEO_CONTROLS_BOTTOM) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var photoToEdit by remember { mutableStateOf<Medium?>(null) }
    // 삭제·이동 승인을 기다리는 동안에는 선택한 항목과 페이지 순서를 유지한다.
    var removal by remember { mutableStateOf<ViewerRemoval?>(null) }
    var completedTrash by remember { mutableStateOf<ViewerRemoval?>(null) }
    val media = removal?.media ?: state.media
    var metadata by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<Pair<Medium, MetadataEdit>?>(null) }
    val pull = remember { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { SWIPE_THRESHOLD.toPx() }
    val scope = rememberCoroutineScope()
    // 탐색 상태가 목록으로 바뀐 뒤에도 닫는 사진의 전체 구도를 유지한다.
    val openingBitmap = remember(initialPreview) { initialBitmap }

    // 다른 앱이 만든 사진은 쓰기 허락을 받은 뒤에 메타데이터를 저장합니다.
    val metadataConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val waiting = pendingEdit
        pendingEdit = null
        if (waiting == null || result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        viewModel.editMetadata(waiting.first, waiting.second) { saved ->
            if (!saved) Toast.makeText(context, R.string.metadata_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val openingPhoto = entering && initialPreview != null
    val pagerState = if (media.isNotEmpty()) {
        rememberPagerState(initialPage = state.startIndex) { media.size }
    } else null
    LaunchedEffect(completedTrash, media, state.libraryLoaded) {
        if (!state.libraryLoaded) return@LaunchedEffect
        val trashed = completedTrash
        if (trashed != null) {
            val nextPage = trashed.nextPageIn(media)
            if (nextPage >= 0) pagerState?.scrollToPage(nextPage) else onMediaRemoved()
            completedTrash = null
        } else if (viewModel.isFavorites && media.isEmpty()) {
            onMediaRemoved()
        }
    }
    // 드래그 중에는 새 페이지의 재생기, 원본 요청, 상세 정보 작업을 시작하지 않는다.
    val selectedPage = pagerState?.settledPage
    val current = selectedPage?.let { media.getOrNull(it) }
    val chromeMedium = current ?: initialMedium.takeUnless { state.libraryLoaded }
    val scrim = if (state.libraryLoaded) state.scrim else initialSettings.viewerScrim
    val actions = if (state.libraryLoaded) state.actions else initialSettings.viewerBar
    val unreadableCopies = remember { mutableStateMapOf<Uri, Uri>() }
    fun displaySource(medium: Medium): Uri {
        val copy = medium.localCopyUri ?: return medium.uri
        val cloud = medium.remoteUri ?: return copy
        return if (unreadableCopies[copy] == cloud) cloud else copy
    }
    val currentUri = current?.let(::displaySource)
    // 원격 동영상은 원본을 미리 받지 않고 재생기에서 필요한 구간만 읽는다.
    val showingVideo = (current ?: initialMedium)?.type == MediaType.Video
    val remoteUri = (currentUri ?: initialMedium?.displayUri ?: initialPreview?.uri)
        ?.takeIf { it.scheme == REMOTE_MEDIA_SCHEME && !showingVideo }
    var attempt by remember(remoteUri) { mutableStateOf(0) }
    var remoteOriginal by remember(remoteUri, attempt) { mutableStateOf<Uri?>(null) }
    var originalFailed by remember(remoteUri, attempt) { mutableStateOf(false) }
    var originalPreview by remember(context, remoteUri) { mutableStateOf(remoteUri?.let { cachedViewerBitmap(context, it) }) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val canEdit = chromeMedium?.type == MediaType.Image
    val availableActions = remember(actions, canEdit) { actions.only { it != BarAction.Edit || canEdit } }
    val onToolbarAction: (BarAction) -> Unit = action@{ action ->
        val medium = chromeMedium ?: return@action
        when (action) {
            BarAction.Share -> fileAction = FileAction.Share
            BarAction.Favorite -> viewModel.toggleFavorite(medium)
            BarAction.Edit -> {
                if (initialSettings.photoEditorPackage == context.packageName) onEdit(medium) else photoToEdit = medium
            }
            BarAction.SetAs -> fileAction = FileAction.SetAs
            BarAction.Delete, BarAction.Move -> {
                removal = ViewerRemoval(medium, media)
                fileAction = if (action == BarAction.Delete) FileAction.Trash else FileAction.Move
            }
            BarAction.Copy -> fileAction = FileAction.Copy
            BarAction.Rename -> fileAction = FileAction.Rename
            BarAction.Metadata -> metadata = true
            BarAction.CopyPath -> Unit
        }
    }
    val downloadSelection by rememberUpdatedState(current?.takeIf { remoteUri != null && it.remoteUri == remoteUri })
    LaunchedEffect(remoteUri, attempt) {
        if (remoteUri == null) return@LaunchedEffect
        try {
            viewModel.withViewerOriginal(remoteUri, awaitSelection = {
                snapshotFlow { downloadSelection }.filterNotNull().first { it.uri == remoteUri }
            }) { file, cached ->
                // 확대 영역의 크기 대신 최종 화면 크기로 한 번 디코딩해 전환과 확대 뷰어에서 함께 쓴다.
                if (cached && originalPreview == null) {
                    val size = snapshotFlow { viewport }.first { it.width > 0 && it.height > 0 }
                    val request = viewerOriginalRequest(context, remoteUri, file).size(size.width, size.height).build()
                    val result = SingletonImageLoader.get(context).execute(request)
                    originalPreview = (result as? SuccessResult)?.image as? BitmapImage
                }
                remoteOriginal = file
                try { awaitCancellation() } finally { remoteOriginal = null }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { originalFailed = true }
    }
    val readableUri = if (currentUri?.scheme == REMOTE_MEDIA_SCHEME) remoteOriginal else currentUri
    val panorama = if (readableUri == null) PanoramaMode(false, null) {}
    else androidx.compose.runtime.key(readableUri) { rememberPanoramaMode(readableUri, current?.type == MediaType.Video, state.panoramaForAll) }
    val sphere = panorama.sphere
    val locked by rememberUpdatedState(zoomed || sphere != null)
    fun photoPreview(medium: Medium?): ViewerPhotoPreview? {
        if (openingPhoto || pagerState == null) return initialPreview
        return medium
            ?.takeIf { it.type != MediaType.Video && sphere == null }
            ?.let { ViewerPhotoPreview(it.id, displaySource(it)) }
    }

    fun photoAtCurrentPage() = photoPreview(pagerState?.let { media.getOrNull(it.currentPage) })

    var requestedExit by remember { mutableStateOf<ViewerExit?>(null) }
    val closeViewer by rememberUpdatedState<() -> Unit> {
        if (!leaving && requestedExit == null) {
            val photo = photoAtCurrentPage()
            requestedExit = ViewerExit(photo)
            onBack(photo?.id)
        }
    }
    // 닫는 동안 스와이프 관성으로 페이지가 바뀌어도 출발 사진과 도착 칸을 유지한다.
    val exit = remember(leaving, requestedExit) {
        requestedExit ?: if (leaving) ViewerExit(photoAtCurrentPage()) else null
    }
    if (state.volumeButtonPaging && pagerState != null && current != null && current.type != MediaType.Video) {
        val hasMultiplePhotos = remember(media) {
            media.asSequence().filter { it.type != MediaType.Video }.take(2).count() == 2
        }
        VolumeNavigation(
            enabled = hasMultiplePhotos && exit == null &&
                !openingPhoto && !photoInMotion && fileAction == null && photoToEdit == null && !metadata && pendingEdit == null,
        ) { step ->
            scope.launch {
                val page = pagerState.currentPage
                if (media.getOrNull(page)?.type == MediaType.Video) return@launch
                var target = pagerState.targetPage + step
                while (media.getOrNull(target)?.type == MediaType.Video) target += step
                if (target !in media.indices) return@launch
                if (abs(target - page) == 1) {
                    pagerState.animateScrollToPage(target, animationSpec = VolumeStepAnimation)
                } else {
                    // 중간 동영상이 현재 페이지가 되어 재생되는 것을 막는다.
                    pagerState.scrollToPage(target)
                }
            }
        }
    }
    val sharedPhoto = if (exit == null) photoPreview(current) else exit.photo
    var initialPhotoDisplayed by remember(initialPreview) { mutableStateOf(false) }
    val waitingForInitialPhoto = initialPreview != null && sharedPhoto == initialPreview &&
        !initialPhotoDisplayed && pagerState?.isScrollInProgress != true
    val decodedPhotos = remember { mutableStateMapOf<Uri, BitmapImage>() }

    LaunchedEffect(currentUri) { zoomed = false }
    val needsDetails = info || metadata || current?.isRemote == true
    LaunchedEffect(current?.id, current?.localCopyUri, currentUri, readableUri, needsDetails) {
        if (current != null && needsDetails) viewModel.loadDetails(current, readableUri)
    }

    // 세로로 끌기: 아래로 끌면 사진이 손가락을 따라 내려갑니다(정보 패널이 열려 있으면 움직이지 않음).
    // 기준 거리를 넘겨 아래로 놓으면 정보 패널을 닫거나 뷰어를 닫고, 위로 놓으면 정보 패널을 엽니다.
    // 확대했거나 파노라마로 보는 중에는 끌기를 받지 않습니다.
    fun followSwipe(dy: Float) {
        if (!info) scope.launch { pull.snapTo(maxOf(dy, 0f)) }
    }

    fun finishSwipe(dy: Float) {
        if (dy > swipeThreshold) {
            if (info) info = false else closeViewer()
        } else if (dy < -swipeThreshold) {
            chrome = true
            info = true
        }
        scope.launch { pull.animateTo(0f) }
    }

    SystemBars(visible = chrome)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .background(GalleryTheme.colors.background)
            .verticalSwipe(enabled = { !locked }, onDrag = ::followSwipe, onRelease = ::finishSwipe),
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationY = pull.value
                    val shrink = 1f - (pull.value / size.height).coerceIn(0f, 1f) * 0.3f
                    scaleX = shrink
                    scaleY = shrink
                },
            contentAlignment = Alignment.Center,
        ) photo@{
            if (sharedPhoto != null) {
                val sharedBitmap by remember(sharedPhoto.uri) {
                    derivedStateOf(referentialEqualityPolicy()) { decodedPhotos[sharedPhoto.uri] }
                }
                val decoded = sharedBitmap ?: originalPreview.takeIf { sharedPhoto.uri == remoteUri }
                val keepOpeningBounds = openingPhoto || (photoInMotion && sharedPhoto == initialPreview)
                val boundsImage = if (leaving || keepOpeningBounds) {
                    remember(sharedPhoto, leaving) { decoded }
                } else {
                    decoded
                }
                val previewVisible = leaving || openingPhoto || pagerState == null || photoInMotion || waitingForInitialPhoto
                PhotoTransitionPreview(
                    uri = sharedPhoto.uri,
                    fullPreview = openingBitmap.takeIf { sharedPhoto.uri == initialPreview?.uri },
                    modifier = photoTransition(sharedPhoto.id),
                    visible = previewVisible,
                    closing = leaving,
                    boundsImage = boundsImage,
                    original = if (leaving) boundsImage else decoded,
                )
            }
            if (pagerState == null || (openingPhoto && sphere != null)) return@photo
            val hidePager = openingPhoto || (photoInMotion && sharedPhoto != null)
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { page -> media[page].id },
                userScrollEnabled = sphere == null && !hidePager,
                // 전환이 진행되는 동안에도 뷰어를 배치해 원본 디코딩을 시작한다.
                modifier = Modifier.fillMaxSize().drawWithContent {
                    if (!hidePager) drawContent()
                },
            ) { page ->
                val medium = media[page]
                val active = page == selectedPage
                if (medium.type == MediaType.Video) {
                    if (active) {
                        VideoPlayer(
                            uri = medium.displayUri,
                            title = medium.name,
                            controlsVisible = chrome,
                            autoplayNext = state.autoplayNextVideo,
                            backgroundPlayback = state.backgroundPlayback && !locks.app && medium.bucketId !in locks.folders,
                            onToggleChrome = { chrome = !chrome },
                            onEnded = {
                                scope.launch {
                                    snapshotFlow { pagerState.isScrollInProgress }.first { !it }
                                    if (pagerState.settledPage != page) return@launch
                                    val next = (page + 1 until media.size).firstOrNull { media[it].type == MediaType.Video }
                                    if (next != null) pagerState.animateScrollToPage(next)
                                }
                            },
                            onZoomedChange = { zoomed = it },
                            panorama = sphere,
                            controlsBottom = bottomBarHeight,
                            scrim = state.scrim,
                        )
                    } else {
                        VideoPoster(medium) { chrome = !chrome }
                    }
                } else if (active && sphere != null && readableUri != null) {
                    PanoramaPhoto(readableUri, sphere, onTap = { chrome = !chrome })
                } else {
                    val uri = displaySource(medium)
                    val zoom = if (active) rememberDeepZoomState() else null
                    val isInitialPhoto = medium.id == initialPreview?.id
                    val onOriginalDecoded: (BitmapImage) -> Unit = remember(uri) { { decodedPhotos[uri] = it } }
                    DisposableEffect(uri) {
                        onDispose { decodedPhotos.remove(uri) }
                    }
                    if (isInitialPhoto && !initialPhotoDisplayed && zoom?.isImageDisplayed == true &&
                        zoom.zoomableState.contentTransformation.isSpecified
                    ) {
                        SideEffect { initialPhotoDisplayed = true }
                    }
                    if (zoom != null) {
                        LaunchedEffect(zoom) {
                            snapshotFlow { zoom.zoomableState.isPhotoZoomed }.collect { zoomed = it }
                        }
                    }
                    if (uri.scheme == REMOTE_MEDIA_SCHEME) {
                        RemoteViewerPhoto(
                            uri = uri,
                            original = if (active) remoteOriginal else null,
                            preparedPreview = if (active) originalPreview else null,
                            showPreview = !isInitialPhoto || !waitingForInitialPhoto,
                            opened = active,
                            downloadFailed = originalFailed,
                            onRetry = { attempt++ },
                            zoom = zoom,
                            onOriginalDecoded = onOriginalDecoded,
                            onClick = { chrome = !chrome },
                        )
                    } else {
                        LocalViewerPhoto(
                            uri, medium.dateModified, viewport, zoom, openingBitmap.takeIf { isInitialPhoto },
                            onDecoded = onOriginalDecoded,
                            onError = { medium.remoteUri?.let { cloud -> unreadableCopies[uri] = cloud } },
                            onClick = { chrome = !chrome },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && chromeMedium != null,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).then(chromeOverlay),
        ) {
            val medium = chromeMedium ?: return@AnimatedVisibility
            TopChrome(
                medium = medium,
                dateTaken = if (medium.isRemote) state.details.taken ?: medium.dateTaken else medium.dateTaken,
                infoOpen = info,
                panorama = panorama.available,
                panoramaOn = sphere != null,
                scrim = scrim,
                onTogglePanorama = panorama.toggle,
                onBack = closeViewer,
                onToggleInfo = { info = !info },
            )
        }

        AnimatedVisibility(
            visible = chrome && info && chromeMedium != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(
                bottom = if (chromeMedium?.type == MediaType.Video) {
                    bottomBarHeight + VIDEO_CONTROLS_HEIGHT + 12.dp
                } else {
                    bottomBarHeight + 12.dp
                },
            ).then(chromeOverlay),
        ) {
            val medium = chromeMedium ?: return@AnimatedVisibility
            DetailsPanel(medium, state.details, onClose = { info = false })
        }

        AnimatedVisibility(
            visible = chrome && chromeMedium != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { size ->
                if (size.height > 0) bottomBarHeight = with(density) { size.height.toDp() }
            }.then(chromeOverlay),
        ) {
            val medium = chromeMedium ?: return@AnimatedVisibility
            BottomChrome(
                scrim = scrim,
                layout = availableActions,
                favorite = medium.isFavorite,
                onAction = onToolbarAction,
            )
        }
    }

    FileActions(
        action = fileAction,
        media = listOfNotNull(removal?.medium ?: chromeMedium),
        onDone = {
            fileAction = null
            when (it) {
                FileAction.Trash -> {
                    completedTrash = removal
                    removal = null
                }
                FileAction.Move -> onMediaRemoved()
                else -> Unit
            }
        },
        onDismiss = {
            fileAction = null
            removal = null
        },
    )
    photoToEdit?.let { photo ->
        EditPhotoAppDialog(
            photo = photo,
            defaultPackage = initialSettings.photoEditorPackage,
            readableUri = { viewModel.shareEditorPhoto(photo) },
            onSetDefault = { viewModel.setPhotoEditorPackage(it) },
            onBuiltIn = { photoToEdit = null; onEdit(photo) },
            onDismiss = { photoToEdit = null },
        )
    }
    if (metadata && chromeMedium != null) {
        MetadataSheet(
            medium = chromeMedium,
            details = state.details,
            onSave = { edit ->
                metadata = false
                if (chromeMedium.isRemote) {
                    viewModel.editMetadata(chromeMedium, edit) { saved ->
                        if (!saved) Toast.makeText(context, R.string.metadata_save_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    pendingEdit = chromeMedium to edit
                    metadataConsent.launch(IntentSenderRequest.Builder(viewModel.writeRequest(chromeMedium)).build())
                }
            },
            onDismiss = { metadata = false },
        )
    }

}

@Composable
private fun PhotoTransitionPreview(
    uri: Uri,
    fullPreview: BitmapImage?,
    modifier: Modifier,
    visible: Boolean,
    closing: Boolean,
    boundsImage: BitmapImage?,
    original: BitmapImage?,
) {
    val context = LocalContext.current
    val thumbnail = remember(context, uri) {
        SingletonImageLoader.get(context).memoryCache?.let { cache ->
            (cache[Thumbnail(uri).uncroppedKey] ?: cache[MemoryCache.Key(uri.toString())])?.image
        }
    }
    val reference = boundsImage ?: fullPreview ?: thumbnail
    val aspect = reference?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
    val image = original?.takeIf {
        // 썸네일 치수의 반올림 오차를 허용하면서 전환 도중에는 같은 비율의 원본만 겹쳐 그린다.
        val ratio = it.width.toFloat() / it.height
        reference == null || aspect == null || abs(reference.width - reference.height * ratio) <= maxOf(1f, ratio)
    } ?: reference
    val bounds = if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()
    Box(bounds.then(modifier)) {
        if (visible) {
            val previewModifier = Modifier.fillMaxSize()
                .then(if (closing) Modifier else Modifier.background(GalleryTheme.colors.background))
            if (image != null) {
                Image(
                    painter = remember(context, image) { image.asPainter(context) },
                    contentDescription = null,
                    // 테두리가 사진 비율일 때는 Crop과 Fit이 같습니다. 닫을 때도 Crop으로 그려야 정사각형 칸으로
                    // 줄어드는 동안 썸네일처럼 잘려 도착하고, Fit처럼 칸보다 작아졌다가 썸네일로 바뀌며 깜빡이지 않습니다.
                    contentScale = if (aspect == null) ContentScale.Fit else ContentScale.Crop,
                    modifier = previewModifier,
                )
            } else {
                AsyncImage(model = viewerThumbnailRequest(uri), contentDescription = null, modifier = previewModifier)
            }
        }
    }
}

@Composable
private fun RemoteViewerPhoto(
    uri: Uri,
    original: Uri?,
    preparedPreview: BitmapImage?,
    showPreview: Boolean,
    opened: Boolean,
    downloadFailed: Boolean,
    onRetry: () -> Unit,
    zoom: ZoomableImageState?,
    onOriginalDecoded: (BitmapImage) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var failed by remember(uri, original) { mutableStateOf(false) }
    var previewReady by remember(uri) { mutableStateOf(false) }
    val memoryPreview = remember(context, uri) { cachedViewerBitmap(context, uri) }
    val bitmap = preparedPreview ?: memoryPreview
    val originalDisplayed = original != null && zoom?.isImageDisplayed == true
    val preview = viewerThumbnailRequest(uri)
    val request = remember(uri, original, context) {
        original?.let { file ->
            viewerOriginalRequest(context, uri, file)
                .listener(
                    onStart = { failed = false },
                    onError = { _, _ -> failed = true },
                    onSuccess = { _, result -> (result.image as? BitmapImage)?.let(onOriginalDecoded) },
                )
                .build()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!originalDisplayed) {
            val previewModifier = Modifier.fillMaxSize().drawWithContent {
                if (showPreview) drawContent()
            }
            if (bitmap != null) {
                Image(remember(context, bitmap) { bitmap.asPainter(context) }, null, previewModifier, contentScale = ContentScale.Fit)
            } else {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    modifier = previewModifier,
                    onSuccess = { previewReady = true },
                )
            }
        }
        if (request != null && zoom != null) {
            ZoomablePhoto(request, zoom, onClick)
        }
        if (opened) {
            if (failed || downloadFailed) {
                Column(
                    Modifier.background(GalleryTheme.colors.background.copy(alpha = 0.9f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.remote_photo_error), color = GalleryTheme.colors.text)
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.remote_retry), color = GalleryTheme.colors.text)
                    }
                }
            } else if (!originalDisplayed && bitmap == null && !previewReady) {
                CircularProgressIndicator(Modifier.size(24.dp), color = GalleryTheme.colors.text)
            }
        }
    }
}

private fun viewerOriginalKey(uri: Uri) = "remote-original:$uri"

private fun cachedViewerBitmap(context: Context, uri: Uri): BitmapImage? =
    SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(viewerOriginalKey(uri)))?.image as? BitmapImage

private fun viewerOriginalRequest(context: Context, uri: Uri, file: Uri) = ImageRequest.Builder(context)
    .data(file)
    .memoryCacheKey(viewerOriginalKey(uri))
    .scale(Scale.FIT)
    .precision(Precision.INEXACT)
    .crossfade(false)

@Composable
private fun viewerThumbnailRequest(uri: Uri): ImageRequest {
    val context = LocalContext.current
    return remember(context, uri) {
        ImageRequest.Builder(context)
            .data(Thumbnail(uri))
            .placeholderMemoryCacheKey(uri.toString())
            .crossfade(false)
            .build()
    }
}

/**
 * 갤러리 색인에 없는 항목을 다른 앱이 열었을 때 쓰는 단독 뷰어입니다. 같은 폴더의 다른 항목으로
 * 넘길 수 없고, 상단에는 뒤로 가기와(가능하면) 파노라마 전환만 둡니다. 화면을 누르면 상단 막대를 숨깁니다.
 */
@Composable
fun StandaloneViewer(uri: Uri, isVideo: Boolean, panoramaForAll: Boolean, scrim: Float, onBack: () -> Unit) {
    var chromeShown by remember { mutableStateOf(true) }
    val toggleChrome = { chromeShown = !chromeShown }
    val panorama = rememberPanoramaMode(uri, isVideo, panoramaForAll)
    val sphere = panorama.sphere
    SystemBars(visible = chromeShown)
    Box(Modifier.fillMaxSize().background(GalleryTheme.colors.background)) {
        when {
            isVideo -> VideoPlayer(
                uri = uri,
                title = uri.lastPathSegment.orEmpty(),
                controlsVisible = chromeShown,
                autoplayNext = false,
                backgroundPlayback = false,
                onToggleChrome = toggleChrome,
                onEnded = {},
                onZoomedChange = {},
                panorama = sphere,
                scrim = scrim,
            )
            sphere != null -> PanoramaPhoto(uri, sphere, onTap = toggleChrome)
            else -> ZoomablePhoto(uri, rememberDeepZoomState(), onClick = toggleChrome)
        }
        AnimatedVisibility(chromeShown, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(chromeScrim(scrim, top = true))
                    .windowInsetsPadding(TOP_INSETS)
                    .padding(8.dp),
            ) {
                BackAction(onBack)
                Spacer(Modifier.weight(1f))
                if (panorama.available) PanoramaToggle(sphere != null, panorama.toggle)
            }
        }
    }
}

@Composable
private fun BackAction(onBack: () -> Unit) {
    IconAction(ViewerIcons.Back, onClick = onBack, label = stringResource(R.string.cloudipix_back), tint = GalleryTheme.colors.text)
}

/** 평면 보기와 파노라마 보기를 오갑니다. 파노라마로 보는 중이면 강조색으로 표시합니다. */
@Composable
private fun PanoramaToggle(on: Boolean, onToggle: () -> Unit) {
    val colors = GalleryTheme.colors
    IconAction(
        ViewerIcons.Panorama,
        onClick = onToggle,
        label = stringResource(R.string.action_panorama),
        tint = if (on) colors.accent else colors.text,
    )
}

// ---- 사진 확대 ----

/** 원본 픽셀 기준 최소 최대 배율입니다. 작은 사진도 이만큼은 확대할 수 있습니다. */
private const val MAX_ZOOM_OF_PIXELS = 2f

/** 화면에 맞춘 크기 기준 최대 배율입니다. 큰 사진은 화면 맞춤 크기의 이 배수까지 확대합니다. */
private const val MAX_ZOOM_OF_SCREEN = 6f

/** 확대 판정에 쓰는 여유입니다. 부동소수점 오차로 1을 조금 넘는 경우는 확대로 보지 않습니다. */
private const val ZOOM_EPSILON = 1.0001f

private val ZoomableState.isPhotoZoomed: Boolean
    get() = contentTransformation.scaleMetadata.userZoom > ZOOM_EPSILON

/** 두 번 누르면 화면을 가득 채우도록 확대하고, 이미 확대돼 있으면 원래대로 돌립니다. */
internal val FillScreenOnDoubleTap = DoubleClickToZoomListener { state, centroid ->
    val fill = state.screenFillScale() ?: return@DoubleClickToZoomListener
    if (state.isPhotoZoomed) state.resetZoom() else state.zoomTo(fill, centroid)
}

@Composable
internal fun ZoomablePhoto(model: Any, state: ZoomableImageState, onClick: () -> Unit) {
    ZoomableAsyncImage(
        model = model,
        contentDescription = null,
        state = state,
        modifier = Modifier.fillMaxSize(),
        onClick = { onClick() },
        onDoubleClick = FillScreenOnDoubleTap,
    )
}

/**
 * 사진이 화면을 빈틈없이 채우는 배율입니다. zoomTo가 쓰는 기준에 맞춰, 처음 맞춤 배율을 적용하기 전의
 * 사진 크기로 계산합니다. 크기를 아직 모르면 `null`입니다.
 */
@OptIn(ExperimentalTelephotoApi::class)
@Suppress("DEPRECATION")
private fun ZoomableState.screenFillScale(): Float? {
    val transformation = contentTransformation
    if (!transformation.isSpecified) return null
    val photo = transformation.contentSize
    val viewport = coordinateSystem.viewportSize
    if (photo.isEmpty() || viewport.isEmpty()) return null
    return ContentScale.Crop.computeScaleFactor(photo, viewport).scaleX
}

/**
 * 큰 사진을 깊게 확대할 수 있는 확대 상태입니다. 최대 배율은 원본 픽셀 기준, 화면 맞춤 기준, 화면 채움 배율 중
 * 가장 큰 값이며, 사진 크기를 알게 되면 다시 계산합니다.
 */
@Composable
private fun rememberDeepZoomState(): ZoomableImageState {
    var maxZoom by remember { mutableFloatStateOf(MAX_ZOOM_OF_PIXELS) }
    val zoomable = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoom))
    LaunchedEffect(zoomable) {
        snapshotFlow { zoomable.deepZoomLimit() }.collect { maxZoom = it }
    }
    return rememberZoomableImageState(zoomable)
}

private fun ZoomableState.deepZoomLimit(): Float {
    val transformation = contentTransformation
    val initial = if (transformation.isSpecified) transformation.scaleMetadata.initialScale else ScaleFactor.Unspecified
    val fitScale = if (initial != ScaleFactor.Unspecified) initial.scaleX else 0f
    return maxOf(MAX_ZOOM_OF_PIXELS, fitScale * MAX_ZOOM_OF_SCREEN, screenFillScale() ?: 0f)
}

// ---- 세로 스와이프 ----

/** 이만큼 세로로 끌었다 놓으면 뷰어를 닫습니다. */
private val SWIPE_THRESHOLD = 80.dp

/**
 * 한 손가락으로 세로로 끄는 동작을 가로챕니다. 자식이 터치를 받기 전 단계에서 보므로 사진 확대·페이지 넘기기와
 * 겹치지 않도록, 터치 여유를 넘긴 뒤 세로 이동이 가로 이동의 두 배 이상이고 [enabled]일 때만 끌기로 봅니다.
 * 끄는 동안 [onDrag]에 누른 지점부터의 세로 거리를, 손을 떼면 [onRelease]에 마지막 거리를 넘깁니다.
 * 두 번째 손가락이 닿으면 끌기를 끝내지 않고 그만둡니다.
 */
private fun Modifier.verticalSwipe(
    enabled: () -> Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
) = pointerInput(Unit) {
    awaitEachGesture {
        val pointer = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).id
        var moved = Offset.Zero
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer }
            val multiTouch = event.changes.count { it.pressed } > 1
            if (change == null || !change.pressed || multiTouch) break
            moved += change.positionChange()
            if (!dragging) {
                if (moved.getDistance() < viewConfiguration.touchSlop) continue
                val mostlyVertical = abs(moved.y) >= abs(moved.x) * 2
                if (!enabled() || !mostlyVertical) break
                dragging = true
            }
            change.consume()
            onDrag(moved.y)
        }
        if (dragging) onRelease(moved.y)
    }
}

// ---- 화면 구성 ----

/** 시스템 막대를 [visible]에 맞춰 보이거나 숨깁니다. 뷰어를 떠나면 항상 다시 보입니다. */
@Composable
private fun SystemBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val bars = WindowInsetsCompat.Type.systemBars()
    LaunchedEffect(insets, visible) {
        if (visible) insets.show(bars) else insets.hide(bars)
    }
    DisposableEffect(insets) {
        onDispose { insets.show(bars) }
    }
}

/** 재생하기 전 동영상 자리에 첫 프레임과 재생 아이콘을 보여 줍니다. 서버 동영상은 받아 둔 미리보기를 씁니다. */
@Composable
private fun VideoPoster(medium: Medium, onToggleChrome: () -> Unit) {
    val uri = medium.displayUri
    val poster: Any = if (uri.scheme == REMOTE_MEDIA_SCHEME) Thumbnail(uri) else uri
    Box(Modifier.fillMaxSize().clickable(onClick = onToggleChrome), contentAlignment = Alignment.Center) {
        AsyncImage(poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Icon(ViewerIcons.Play, contentDescription = null, tint = GalleryTheme.colors.text, modifier = Modifier.iconSize(40.dp))
    }
}

/** 상단 막대입니다. 파일 이름과 촬영 일시, 파노라마 전환, 정보 버튼을 둡니다. */
@Composable
private fun TopChrome(
    medium: Medium,
    dateTaken: Long,
    infoOpen: Boolean,
    panorama: Boolean,
    panoramaOn: Boolean,
    scrim: Float,
    onTogglePanorama: () -> Unit,
    onBack: () -> Unit,
    onToggleInfo: () -> Unit,
) {
    val colors = GalleryTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val taken = remember(dateTaken, locale) { takenLabel(dateTaken, locale) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(chromeScrim(scrim, top = true))
            .windowInsetsPadding(TOP_INSETS)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackAction(onBack)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(medium.name, style = GalleryTheme.type.meta, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(taken, style = GalleryTheme.type.meta, color = colors.text.copy(alpha = 0.62f))
        }
        if (panorama) PanoramaToggle(panoramaOn, onTogglePanorama)
        IconAction(
            ViewerIcons.Info,
            onClick = onToggleInfo,
            label = stringResource(R.string.details_title),
            tint = if (infoOpen) colors.accent else colors.text,
        )
    }
}

/** 상단 막대의 촬영 일시입니다. 한국어는 '2025년 3월 1일 · 09:30', 그 밖의 언어는 '1 March 2025 · 09:30' 형태입니다. */
private fun takenLabel(millis: Long, locale: Locale): String {
    val day = if (locale.language == Locale.KOREAN.language) "yyyy년 M월 d일" else "d MMMM yyyy"
    return SimpleDateFormat("$day · HH:mm", locale).format(Date(millis))
}

@Composable
private fun BottomChrome(scrim: Float, layout: BarLayout, favorite: Boolean, onAction: (BarAction) -> Unit) {
    val bar = Modifier
        .background(chromeScrim(scrim, top = false))
        .windowInsetsPadding(BOTTOM_INSETS)
        .padding(vertical = 12.dp)
    ActionBar(layout, onAction, bar, tint = GalleryTheme.colors.text, favoritesSelected = favorite)
}

private val DetailsPanelShape = RoundedCornerShape(6.dp)

/** 사진 위에 떠 있는 간단한 정보 패널입니다. 내용이 길면 패널 안에서 스크롤합니다. */
@Composable
private fun DetailsPanel(medium: Medium, details: MediaDetails, onClose: () -> Unit) {
    val colors = GalleryTheme.colors
    val faded = colors.text.copy(alpha = 0.5f)
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .background(colors.surface.copy(alpha = 0.9f), DetailsPanelShape)
            .border(1.dp, colors.text.copy(alpha = 0.14f), DetailsPanelShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.details_title), modifier = Modifier.weight(1f), style = GalleryTheme.type.section, color = faded)
            Icon(
                ViewerIcons.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = faded,
                modifier = Modifier.iconSize(16.dp).clickable(onClick = onClose),
            )
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            DetailRow(stringResource(R.string.details_size), formatSize(medium.size))
            val optional = listOf(
                R.string.details_dimensions to details.dimensions,
                R.string.details_camera to details.camera,
                R.string.details_exposure to details.exposure,
            )
            for ((label, value) in optional) {
                if (value != null) DetailRow(stringResource(label), value)
            }
            MediaDetailLocation(details, medium.name) { label, value -> DetailRow(stringResource(label), value, wrapAtCharacters = true) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, wrapAtCharacters: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = GalleryTheme.type.body, color = GalleryTheme.colors.text.copy(alpha = 0.55f))
        MediaDetailValue(value, GalleryTheme.type.meta, GalleryTheme.colors.text, Modifier.weight(1f), wrapAtCharacters)
    }
}
