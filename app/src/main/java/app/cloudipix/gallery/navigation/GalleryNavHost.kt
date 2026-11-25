package app.cloudipix.gallery.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.media.fullFramePreview
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.feature.editor.PhotoEditorScreen
import app.cloudipix.gallery.feature.folders.FoldersScreen
import app.cloudipix.gallery.feature.media.MediaScreen
import app.cloudipix.gallery.feature.security.FolderAccessGate
import app.cloudipix.gallery.feature.settings.IgnoredFoldersScreen
import app.cloudipix.gallery.feature.settings.FolderSyncScreen
import app.cloudipix.gallery.feature.settings.CloudSyncSettingsScreen
import app.cloudipix.gallery.feature.settings.RemoteSourcesScreen
import app.cloudipix.gallery.feature.settings.RemoteServerDetailsScreen
import androidx.hilt.navigation.compose.hiltViewModel
import app.cloudipix.gallery.feature.settings.RemoteServerScreen
import app.cloudipix.gallery.feature.settings.RemoteFolderPickerScreen
import app.cloudipix.gallery.feature.settings.SettingsScreen
import app.cloudipix.gallery.feature.settings.ActionBarsScreen
import app.cloudipix.gallery.feature.viewer.ViewerPhotoPreview
import app.cloudipix.gallery.feature.viewer.ViewerScreen
import app.cloudipix.gallery.ui.theme.GalleryScreenTheme
import app.cloudipix.gallery.ui.theme.GallerySystemBarAppearance
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.isDark
import app.cloudipix.gallery.ui.AboutPage
import app.cloudipix.gallery.ui.SearchPage
import app.cloudipix.gallery.ui.TrashPage
import coil3.BitmapImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Serializable
object Folders

@Serializable
object Settings

@Serializable
object RemoteSources

@Serializable
data class RemoteServerDetails(val id: String)

@Serializable
data class RemoteServerSettings(val id: String? = null)

@Serializable
data class RemoteFolderPicker(val serverId: String)

@Serializable
data class FolderSyncOptions(val albumId: Long? = null, val serverId: String? = null)

@Serializable
object CloudSyncSettings

@Serializable
object Search

@Serializable
object IgnoredFolders

@Serializable
object ActionBars

@Serializable
object Trash

@Serializable
object About

@Serializable
data class Media(val bucketId: Long, val title: String, val chooseCover: Boolean = false)

@Serializable
data class Viewer(val bucketId: Long, val mediumId: Long, val previewUri: String? = null)

private data class ViewerOpening(val route: Viewer, val medium: Medium, val bitmap: BitmapImage? = null)

private data class ClosingPhoto(val id: Long, val originalOpacity: State<Float>)

@Serializable
data class Editor(val mediumId: Long? = null, val bucketId: Long = RECENT_BUCKET_ID, val inputUri: String? = null)

private const val PHOTO_OPEN_MS = 170
private const val PHOTO_MAX_OPEN_MS = 250
private const val PHOTO_CLOSE_MS = 190
private const val PHOTO_MAX_TRAVEL_MS = 40
private const val PHOTO_CLOSE_HANDOFF_MS = 50
private const val SETTINGS_THEME_TRANSITION_MS = 180
// 열 때는 점차 감속하고 닫을 때는 반대로 가속한다.
private val photoOpenEasing = CubicBezierEasing(0.30f, 0.54f, 0.52f, 1f)
private val photoCloseEasing = CubicBezierEasing(0.55f, 0f, 0.75f, 0.35f)
private val photoBackdropEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
@OptIn(ExperimentalSharedTransitionApi::class)
private fun photoBoundsTransform(opening: Boolean, windowShortSide: Int) = BoundsTransform { from, to ->
    val travel = (to.center - from.center).getDistance()
    val longSide = maxOf(from.size.maxDimension, to.size.maxDimension).coerceAtLeast(1f)
    val extraMillis = (PHOTO_MAX_TRAVEL_MS * (travel / longSide).coerceIn(0f, 1f)).roundToInt()
    if (opening) {
        // 이동과 확대를 합쳐 가장 멀리 가는 모서리를 기준으로 빠른 전환에만 시간을 더한다.
        val dx = maxOf(abs(to.left - from.left), abs(to.right - from.right))
        val dy = maxOf(abs(to.top - from.top), abs(to.bottom - from.bottom))
        val edgeMillis = if (windowShortSide > 0) {
            (PHOTO_OPEN_MS * hypot(dx, dy) / windowShortSide).roundToInt()
        } else 0
        tween(edgeMillis.coerceIn(PHOTO_OPEN_MS + extraMillis, PHOTO_MAX_OPEN_MS), easing = photoOpenEasing)
    } else {
        tween(PHOTO_CLOSE_MS + extraMillis, easing = photoCloseEasing)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryNavHost(
    start: Any = Folders,
    onExit: () -> Unit = {},
    requestLocalAccess: (() -> Unit)? = null,
    settings: GallerySettings = GallerySettings(),
    openAlbum: (Media) -> Unit,
) {
    val navController = rememberNavController()
    val settingsState = rememberUpdatedState(settings)
    val currentSettings by settingsState
    val currentEntry by navController.currentBackStackEntryAsState()
    val settingsMode = settings.settingsTheme.mode
    val galleryIsDark = settings.themeMode.isDark()
    val settingsIsDark = settingsMode?.isDark()
    val animateSettingsThemeChange = settingsIsDark != null && (
        settingsIsDark != galleryIsDark || (settingsMode == ThemeMode.Black) != (settings.themeMode == ThemeMode.Black)
    )
    val openSettings = { navController.navigate(Settings) }
    val openSearch = { navController.navigate(Search) }
    val back: () -> Unit = { if (navController.previousBackStackEntry == null) onExit() else navController.popBackStack() }
    var sharedPhotoId by rememberSaveable { mutableStateOf<Long?>(null) }
    val closingPhoto = remember { mutableStateOf<ClosingPhoto?>(null) }
    var instantExitEntryId by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember(currentEntry) { mutableStateOf<ViewerOpening?>(null) }
    val context = LocalContext.current
    var openingPhoto by remember { mutableStateOf<ViewerOpening?>(null) }
    LaunchedEffect(currentEntry) {
        if (currentEntry?.destination?.hasRoute<Viewer>() != true) openingPhoto = null
    }
    LaunchedEffect(pendingPhoto) {
        val request = pendingPhoto ?: return@LaunchedEffect
        val source = navController.currentBackStackEntry
        val uri = request.medium.displayUri
        val preview = async {
            if (uri.scheme == "content") fullFramePreview(context, uri, cacheOnly = request.medium.localCopyUri != null) else null
        }
        // 선택한 칸의 시작 위치가 배치된 다음 프레임에 뷰어를 연다.
        withFrameNanos { }
        val ready = preview.await()
        withContext(Dispatchers.Main.immediate) {
            if (pendingPhoto != request) return@withContext
            if (navController.currentBackStackEntry == source) {
                openingPhoto = request.copy(bitmap = ready)
                navController.navigate(request.route)
            }
            pendingPhoto = null
        }
    }
    val openViewer: (Long, Medium) -> Unit = { bucketId, medium ->
        if (medium.type == MediaType.Video) {
            navController.navigate(Viewer(bucketId, medium.id))
        } else {
            sharedPhotoId = medium.id
            pendingPhoto = ViewerOpening(Viewer(bucketId, medium.id, medium.displayUri.toString()), medium)
        }
    }

    var sceneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val gridBounds = remember { mutableStateOf(Rect.Zero) }
    val gridModifier = Modifier.onGloballyPositioned { grid ->
        sceneCoordinates?.takeIf { it.isAttached }?.let { gridBounds.value = it.localBoundingBoxOf(grid) }
    }
    val visibleEntries by navController.visibleEntries.collectAsState()
    val folderState = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        // 폴더는 별도 앨범 액티비티를 연다. 사진 전환의 예측 배치에 폴더 그리드까지 참여시키지 않는다.
        visibleEntries.lastOrNull { it.destination.hasRoute<Folders>() }?.let { entry ->
            entry.LocalOwnersProvider(folderState) {
                FoldersScreen(
                    onOpenFolder = { openAlbum(Media(it.bucketId, it.name)) },
                    onChooseCover = { openAlbum(Media(it.bucketId, it.name, chooseCover = true)) },
                    onOpenSearch = { openSearch() },
                    onOpenSettings = { openSettings() },
                    onOpenTrash = { navController.navigate(Trash) },
                    requestLocalAccess = requestLocalAccess,
                )
            }
        }
        SharedTransitionLayout(Modifier.fillMaxSize().onGloballyPositioned { sceneCoordinates = it }) {
            val sharedScene = this
            val graph: NavGraphBuilder.() -> Unit = {
                composable<Folders> { }
                composable<Media> { entry ->
                    val visibility = this
                    val bucketId = entry.toRoute<Media>().bucketId
                    FolderAccessGate(bucketId, onBack = back) {
                        MediaScreen(
                            onBack = back,
                            onOpenMedium = { openViewer(bucketId, it) },
                            onOpenSearch = { openSearch() },
                            onOpenSettings = { openSettings() },
                            onOpenSync = { navController.navigate(FolderSyncOptions(albumId = bucketId)) },
                            photoTransition = { id ->
                                if (id == sharedPhotoId) {
                                    sharedScene.sharedPhoto(id, visibility, inGrid = true, closingPhoto = closingPhoto) { gridBounds.value }
                                } else Modifier
                            },
                            gridModifier = gridModifier,
                            initialSettings = currentSettings,
                        )
                    }
                }
                composable<Viewer> { entry ->
                    val removed = entry.id == instantExitEntryId
                    val visibility = this
                    val route = entry.toRoute<Viewer>()
                    GalleryScreenTheme(ThemeMode.Black) {
                        FolderAccessGate(route.bucketId, onBack = back) {
                            val opening = openingPhoto?.takeIf { it.route == route }
                            val chromeOverlay = if (opening != null && !removed) {
                                sharedScene.viewerChromeOverlay(visibility)
                            } else Modifier
                            ViewerScreen(
                                onBack = { photoId ->
                                    if (navController.currentBackStackEntry == entry) {
                                        sharedPhotoId = photoId
                                        back()
                                    }
                                },
                                onMediaRemoved = {
                                    if (navController.currentBackStackEntry == entry) {
                                        instantExitEntryId = entry.id
                                        sharedPhotoId = null
                                        back()
                                    }
                                },
                                onEdit = { navController.navigate(Editor(it.id, it.bucketId)) },
                                initialMedium = opening?.medium,
                                initialSettings = currentSettings,
                                initialPreview = route.previewUri?.let { ViewerPhotoPreview(route.mediumId, Uri.parse(it)) },
                                initialBitmap = opening?.bitmap,
                                chromeOverlay = chromeOverlay,
                                photoTransition = { id ->
                                    if (removed) Modifier else {
                                        SideEffect { sharedPhotoId = id }
                                        sharedScene.sharedPhoto(id, visibility, inGrid = false, closingPhoto = closingPhoto) { gridBounds.value }
                                    }
                                },
                                entering = visibility.transition.currentState == EnterExitState.PreEnter,
                                leaving = !removed && visibility.transition.targetState == EnterExitState.PostExit,
                                photoInMotion = !removed && sharedScene.isTransitionActive,
                            )
                        }
                    }
                }
                composable<Editor> { entry ->
                    val bucketId = entry.toRoute<Editor>().bucketId.takeUnless { it == RECENT_BUCKET_ID }
                    FolderAccessGate(bucketId, onBack = back) { PhotoEditorScreen(onBack = back) }
                }
                composable<Search> {
                    val visibility = this
                    SearchPage(
                        onBack = navController::popBackStack,
                        onOpenMedium = { openViewer(it.bucketId, it) },
                        photoTransition = { id ->
                            if (id == sharedPhotoId) {
                                sharedScene.sharedPhoto(id, visibility, inGrid = true, closingPhoto = closingPhoto) { gridBounds.value }
                            } else Modifier
                        },
                        gridModifier = gridModifier,
                    )
                }
                settingsComposable<Settings>(settingsState) {
                    SettingsScreen(
                        onBack = navController::popBackStack,
                        onOpenIgnored = { navController.navigate(IgnoredFolders) },
                        onOpenActionBars = { navController.navigate(ActionBars) },
                        onOpenAbout = { navController.navigate(About) },
                        onOpenRemoteSources = { navController.navigate(RemoteSources) },
                    )
                }
                settingsComposable<RemoteSources>(settingsState) {
                    RemoteSourcesScreen(
                        onBack = navController::popBackStack,
                        onAddServer = { navController.navigate(RemoteServerSettings()) },
                        onOpenServer = { id -> navController.navigate(RemoteServerDetails(id)) },
                        onManageLinkedFolders = { navController.navigate(FolderSyncOptions()) },
                        onSyncSettings = { navController.navigate(CloudSyncSettings) },
                    )
                }
                settingsComposable<RemoteServerDetails>(settingsState) { entry ->
                    val id = entry.toRoute<RemoteServerDetails>().id
                    val parent = remember(entry) { navController.getBackStackEntry(RemoteSources) }
                    RemoteServerDetailsScreen(
                        id = id,
                        onBack = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
                        onEdit = { navController.navigate(RemoteServerSettings(id)) },
                        onChooseFolders = { navController.navigate(RemoteFolderPicker(id)) },
                        onFolderLocations = { navController.navigate(FolderSyncOptions(serverId = id)) },
                        viewModel = hiltViewModel(parent),
                    )
                }
                settingsComposable<RemoteServerSettings>(settingsState) { entry ->
                    val editing = entry.toRoute<RemoteServerSettings>().id
                    RemoteServerScreen(id = editing, onBack = navController::popBackStack, onConnected = { id ->
                        if (editing == id) navController.popBackStack()
                        else {
                            navController.navigate(RemoteServerDetails(id)) { popUpTo<RemoteSources>() }
                            navController.navigate(RemoteFolderPicker(id))
                        }
                    })
                }
                settingsComposable<RemoteFolderPicker>(settingsState) {
                    RemoteFolderPickerScreen(onBack = navController::popBackStack)
                }
                settingsComposable<FolderSyncOptions>(settingsState) {
                    FolderSyncScreen(
                        onBack = navController::popBackStack,
                        onOpenFolderSync = { albumId -> navController.navigate(FolderSyncOptions(albumId = albumId)) },
                    )
                }
                settingsComposable<CloudSyncSettings>(settingsState) {
                    CloudSyncSettingsScreen(onBack = navController::popBackStack)
                }
                settingsComposable<About>(settingsState) {
                    AboutPage(onBack = navController::popBackStack)
                }
                settingsComposable<ActionBars>(settingsState) {
                    ActionBarsScreen(onBack = navController::popBackStack)
                }
                composable<Trash> {
                    TrashPage(onBack = navController::popBackStack)
                }
                settingsComposable<IgnoredFolders>(settingsState) {
                    IgnoredFoldersScreen(onBack = navController::popBackStack)
                }
            }
            val destination = currentEntry?.destination
            val viewingMedia = destination?.hasRoute<Viewer>() ?: (start is Viewer)
            val activeTheme = when {
                viewingMedia -> ThemeMode.Black
                destination?.isSettingsDestination() == true -> settingsMode ?: settings.themeMode
                else -> settings.themeMode
            }
            GallerySystemBarAppearance(activeTheme)
            NavHost(
                navController = navController,
                startDestination = start,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (animateSettingsThemeChange && crossesSettings()) fadeIn(tween(SETTINGS_THEME_TRANSITION_MS))
                    else photoEnter()
                },
                exitTransition = {
                    if (animateSettingsThemeChange && crossesSettings()) ExitTransition.KeepUntilTransitionsFinished
                    else photoExit()
                },
                popEnterTransition = { photoEnter() },
                popExitTransition = {
                    when {
                        initialState.id == instantExitEntryId -> ExitTransition.KeepUntilTransitionsFinished
                        animateSettingsThemeChange && crossesSettings() -> fadeOut(tween(SETTINGS_THEME_TRANSITION_MS))
                        else -> photoExit()
                    }
                },
                builder = graph,
            )
        }
    }
}

private inline fun <reified T : Any> NavGraphBuilder.settingsComposable(
    settings: State<GallerySettings>,
    crossinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val scope = this
        GalleryScreenTheme(settings.value.settingsTheme.mode) { scope.content(entry) }
    }
}

private fun NavDestination.isSettingsDestination(): Boolean =
    hasRoute<Settings>() || hasRoute<About>() || hasRoute<ActionBars>() || hasRoute<IgnoredFolders>() ||
        hasRoute<RemoteSources>() || hasRoute<RemoteServerDetails>() || hasRoute<RemoteServerSettings>() ||
        hasRoute<RemoteFolderPicker>() || hasRoute<FolderSyncOptions>() || hasRoute<CloudSyncSettings>()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.crossesSettings(): Boolean =
    initialState.destination.isSettingsDestination() != targetState.destination.isSettingsDestination()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.photoEnter(): EnterTransition =
    if (isPhotoTransition() && targetState.destination.hasRoute<Viewer>()) {
        // 사진이 크게 이동하는 경우에도 확대 도중 목록이 서서히 어두워지도록 한다.
        fadeIn(tween(PHOTO_MAX_OPEN_MS, easing = photoBackdropEasing))
    } else EnterTransition.None

private fun AnimatedContentTransitionScope<NavBackStackEntry>.photoExit(): ExitTransition =
    when {
        !isPhotoTransition() -> ExitTransition.None
        initialState.destination.hasRoute<Viewer>() -> fadeOut(tween(PHOTO_CLOSE_MS, easing = photoCloseEasing))
        else -> ExitTransition.KeepUntilTransitionsFinished
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isPhotoTransition(): Boolean = when {
    initialState.isPhotoGrid() && targetState.destination.hasRoute<Viewer>() -> targetState.toRoute<Viewer>().previewUri != null
    targetState.isPhotoGrid() && initialState.destination.hasRoute<Viewer>() -> initialState.toRoute<Viewer>().previewUri != null
    else -> false
}

private fun NavBackStackEntry.isPhotoGrid(): Boolean = destination.hasRoute<Media>() || destination.hasRoute<Search>()

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.viewerChromeOverlay(
    visibility: AnimatedVisibilityScope,
): Modifier {
    val transition = visibility.transition
    val opacity = transition.animateFloat(
        transitionSpec = { tween(PHOTO_OPEN_MS, easing = photoOpenEasing) },
        label = "사진 보기 조작 막대",
    ) { if (it == EnterExitState.PreEnter) 0f else 1f }
    val renderInOverlay = { isTransitionActive && transition.targetState == EnterExitState.Visible }
    // 확대 중인 사진 위에 조작 막대와 그라데이션을 함께 그린다.
    return Modifier.renderInSharedTransitionScopeOverlay(renderInOverlay, zIndexInOverlay = 2f)
        .graphicsLayer { alpha = if (renderInOverlay()) opacity.value else 1f }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.sharedPhoto(
    id: Long,
    visibility: AnimatedVisibilityScope,
    inGrid: Boolean,
    closingPhoto: MutableState<ClosingPhoto?>,
    gridBounds: () -> Rect,
): Modifier {
    val state = rememberSharedContentState(id)
    val targetVisible = visibility.transition.targetState == EnterExitState.Visible
    val opening = targetVisible != inGrid
    val baseMillis = if (opening) PHOTO_OPEN_MS else PHOTO_CLOSE_MS
    val easing = if (opening) photoOpenEasing else photoCloseEasing
    val windowSize = LocalWindowInfo.current.containerSize
    val shortSide = minOf(windowSize.width, windowSize.height)
    val boundsTransform = remember(opening, shortSide) { photoBoundsTransform(opening, shortSide) }

    val gridFraction = if (state.isMatchFound) {
        visibility.transition.animateFloat({ tween(baseMillis, easing = easing) }, label = "사진 모서리") {
            if ((it == EnterExitState.Visible) == inGrid) 1f else 0f
        }
    } else null
    val radius = GalleryTheme.corners.radius
    val clip = remember(gridFraction, gridBounds, radius) {
        object : SharedTransitionScope.OverlayClip {
            private val path = Path()

            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: Rect,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Path {
                // 목록에서 잘린 부분은 사진이 뷰어로 이동하는 만큼만 드러낸다.
                val viewport = gridBounds()
                val fraction = gridFraction?.value ?: 0f
                val visible = if (viewport.isEmpty) bounds else lerp(bounds, bounds.intersect(viewport), fraction)
                path.reset()
                if (!visible.isEmpty) {
                    path.addRoundRect(RoundRect(visible, CornerRadius(with(density) { radius.toPx() } * fraction)))
                }
                return path
            }
        }
    }
    if (!inGrid) {
        val opacity = if (state.isMatchFound) {
            visibility.transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.PostExit) {
                        // 칸까지 이동하는 시간을 확보한 뒤 원본을 서서히 지운다.
                        tween(
                            durationMillis = PHOTO_CLOSE_HANDOFF_MS,
                            delayMillis = PHOTO_CLOSE_MS + PHOTO_MAX_TRAVEL_MS,
                            easing = LinearEasing,
                        )
                    } else snap()
                },
                label = "닫는 사진 투명도",
            ) { if (it == EnterExitState.PostExit) 0f else 1f }
        } else null
        if (visibility.transition.targetState == EnterExitState.PostExit && opacity != null) {
            DisposableEffect(id, opacity) {
                val photo = ClosingPhoto(id, opacity)
                closingPhoto.value = photo
                onDispose {
                    if (closingPhoto.value === photo) closingPhoto.value = null
                }
            }
        }
        return Modifier.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = visibility,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            boundsTransform = boundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = 1f,
            clipInOverlayDuringTransition = clip,
        ).graphicsLayer {
            alpha = if (state.isMatchFound) opacity?.value ?: 1f else 1f
        }
    }
    return Modifier.sharedElement(
        sharedContentState = state,
        animatedVisibilityScope = visibility,
        boundsTransform = boundsTransform,
        clipInOverlayDuringTransition = clip,
    ).drawWithContent {
        // 원본이 흐려질 때 아래 썸네일을 불투명하게 그려 목록 배경이 비치는 것을 막는다.
        val originalOpacity = closingPhoto.value?.takeIf { it.id == id }?.originalOpacity?.value ?: 1f
        if (!state.isMatchFound || !isTransitionActive || (targetVisible && originalOpacity < 1f)) {
            drawContent()
        }
    }
}
