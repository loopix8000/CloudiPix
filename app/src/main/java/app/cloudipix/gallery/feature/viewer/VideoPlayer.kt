package app.cloudipix.gallery.feature.viewer

import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import app.cloudipix.gallery.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import app.cloudipix.gallery.core.media.Panorama
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.core.remote.RemoteVideoEntryPoint
import dagger.hilt.android.EntryPointAccessors
import app.cloudipix.gallery.ui.component.GalleryDropdownMenu
import app.cloudipix.gallery.ui.component.MenuEntry
import app.cloudipix.gallery.ui.component.iconSize
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

private val SPEEDS = List(8) { 0.25f * (it + 1) }
internal val VIDEO_CONTROLS_BOTTOM = 96.dp
internal val VIDEO_CONTROLS_HEIGHT = 28.dp

enum class VideoFit(@StringRes val labelRes: Int) {
    Fit(R.string.fit_fit),
    Fill(R.string.fit_fill),
    Stretch(R.string.fit_stretch),
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    uri: Uri,
    title: String,
    controlsVisible: Boolean,
    autoplayNext: Boolean,
    backgroundPlayback: Boolean,
    onToggleChrome: () -> Unit,
    onEnded: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    panorama: Panorama? = null,
    controlsBottom: Dp = VIDEO_CONTROLS_BOTTOM,
    scrim: Float = 0f,
) {
    val context = LocalContext.current
    val colors = GalleryTheme.colors
    val light = colors.background.luminance() > 0.5f
    val player = remember(uri) {
        ExoPlayer.Builder(context).apply {
            if (uri.scheme == REMOTE_MEDIA_SCHEME) {
                // 서버 동영상은 전체를 받지 않고 재생 위치에 필요한 구간만 읽는다.
                val videos = EntryPointAccessors.fromApplication(context, RemoteVideoEntryPoint::class.java).remoteVideos()
                setMediaSourceFactory(DefaultMediaSourceFactory(videos.dataSourceFactory()))
            }
        }.build()
    }
    val currentAutoplayNext by rememberUpdatedState(autoplayNext)
    val currentOnEnded by rememberUpdatedState(onEnded)
    var playing by rememberSaveable(uri) { mutableStateOf(true) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var position by rememberSaveable(uri) { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var fit by rememberSaveable { mutableStateOf(VideoFit.Fit) }
    val zoom = rememberZoomableState()

    LaunchedEffect(zoom) {
        snapshotFlow { (zoom.zoomFraction ?: 0f) > 0.01f }.collect(onZoomedChange)
    }

    DisposableEffect(player) {
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        )
        player.seekTo(position)
        player.setPlaybackSpeed(speed)
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.playWhenReady = playing
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && currentAutoplayNext) currentOnEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, autoplayNext) {
        player.repeatMode = if (autoplayNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!backgroundPlayback) player.pause()
    }

    if (backgroundPlayback) PlaybackSessionEffect(player)

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (panorama != null) {
            PanoramaVideo(player, panorama, onTap = onToggleChrome)
        } else BoxWithConstraints(
            modifier = Modifier.fillMaxSize().clipToBounds().zoomable(zoom, onClick = { onToggleChrome() }),
            contentAlignment = Alignment.Center,
        ) {
            val containerRatio = maxWidth / maxHeight
            val wide = ratio > containerRatio
            val surface = when (fit) {
                VideoFit.Stretch -> Modifier.fillMaxSize()
                VideoFit.Fit -> if (wide) Modifier.size(maxWidth, maxWidth / ratio)
                else Modifier.size(maxHeight * ratio, maxHeight)
                VideoFit.Fill -> if (wide) Modifier.size(maxHeight * ratio, maxHeight)
                else Modifier.size(maxWidth, maxWidth / ratio)
            }
            PlayerSurface(player = player, modifier = surface)
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(colors.background.copy(alpha = if (light) 0.85f else 0.60f), CircleShape)
                    .clickable { if (playing) player.pause() else player.play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) ViewerIcons.Pause else ViewerIcons.Play,
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.iconSize(26.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = controlsBottom),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background.copy(alpha = if (light) maxOf(scrim, 0.86f) else scrim))
                    // 가로 화면에서 측면 내비게이션 바와 카메라 컷아웃을 피한다.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(formatDuration(position), style = GalleryTheme.type.meta, color = colors.text)
                Scrubber(
                    progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.weight(1f),
                ) { fraction ->
                    if (duration > 0) {
                        val target = (fraction.coerceIn(0f, 1f) * duration).toLong()
                        player.seekTo(target)
                        position = target
                    }
                }
                Text(formatDuration(duration), style = GalleryTheme.type.meta, color = colors.text)

                SpeedPicker(speed) {
                    speed = it
                    player.setPlaybackSpeed(it)
                }
                if (panorama == null) {
                    Pill(stringResource(fit.labelRes)) { fit = VideoFit.entries[(fit.ordinal + 1) % VideoFit.entries.size] }
                }
                Icon(
                    if (muted) ViewerIcons.VolumeOff else ViewerIcons.VolumeOn,
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier
                        .iconSize(18.dp)
                        .clickable {
                            muted = !muted
                            player.volume = if (muted) 0f else 1f
                        },
                )
            }
        }
    }
}

@Composable
private fun Scrubber(progress: Float, modifier: Modifier = Modifier, onSeek: (Float) -> Unit) {
    val accent = GalleryTheme.colors.accent
    Box(
        modifier = modifier
            .height(VIDEO_CONTROLS_HEIGHT)
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek(change.position.x / size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
            .background(GalleryTheme.colors.text.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
        )
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Box(Modifier.fillMaxWidth(progress), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(accent))
        }
    }
}

@Composable
private fun SpeedPicker(speed: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(speed.speedLabel()) { open = true }
        GalleryDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(6.dp),
        ) {
            SPEEDS.forEach { option ->
                MenuEntry(option.speedLabel(), value = if (option == speed) "\u2713" else null) {
                    onSelect(option)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = GalleryTheme.type.meta,
        color = GalleryTheme.colors.text,
        modifier = Modifier
            .background(GalleryTheme.colors.surface.copy(alpha = 0.55f), RoundedCornerShape(ControlRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

private fun Float.speedLabel() = "${toString().trimEnd('0').trimEnd('.')}×"
