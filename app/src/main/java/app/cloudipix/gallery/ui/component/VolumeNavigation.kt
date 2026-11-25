package app.cloudipix.gallery.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.cloudipix.gallery.GalleryActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val VOLUME_HOLD_DELAY_MS = 350L
private const val VOLUME_REPEAT_INTERVAL_MS = 170L
private const val SCROLL_VIEWPORTS_PER_SECOND = 1.8f
private const val MAX_SCROLL_FRAME_NANOS = 50_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000f

internal val VolumeStepAnimation = tween<Float>(durationMillis = 140)

@Composable
internal fun VolumeNavigation(
    enabled: Boolean,
    onHold: (suspend (Int) -> Unit)? = null,
    onStep: (Int) -> Unit,
) {
    val activity = LocalActivity.current as? GalleryActivity ?: return
    if (!enabled) return
    val scope = rememberCoroutineScope()
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnHold by rememberUpdatedState(onHold)
    LifecycleResumeEffect(key1 = activity) {
        val handler: (Int) -> Job = { step ->
            currentOnStep(step)
            scope.launch {
                delay(VOLUME_HOLD_DELAY_MS)
                val hold = currentOnHold
                if (hold != null) {
                    hold(step)
                } else {
                    while (true) {
                        currentOnStep(step)
                        delay(VOLUME_REPEAT_INTERVAL_MS)
                    }
                }
            }
        }
        activity.onVolumeNavigationPress = handler
        onPauseOrDispose {
            if (activity.onVolumeNavigationPress === handler) activity.onVolumeNavigationPress = null
        }
    }
}

@Composable
internal fun VolumeGridScroll(state: LazyGridState, enabled: Boolean) {
    VolumeGridScroll(state, { state.layoutInfo.viewportSize.height }, state.canScrollBackward, state.canScrollForward, enabled)
}

@Composable
internal fun VolumeGridScroll(state: ScrollableState, height: () -> Int, backward: Boolean, forward: Boolean, enabled: Boolean) {
    key(state) {
        val scope = rememberCoroutineScope()
        VolumeNavigation(
            enabled = enabled && (backward || forward),
            onHold = { step -> state.scrollWhileHeld(step, height()) },
        ) { step ->
            if (if (step < 0) backward else forward) {
                scope.launch {
                    val distance = height() / 3f
                    state.animateScrollBy(distance * step, VolumeStepAnimation)
                }
            }
        }
    }
}

private suspend fun ScrollableState.scrollWhileHeld(step: Int, height: Int) {
    val speed = height * SCROLL_VIEWPORTS_PER_SECOND * step
    if (speed == 0f) return
    scroll {
        var previousFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            // 프레임이 밀렸을 때 누적 이동량을 한꺼번에 적용하지 않는다.
            val elapsed = (frame - previousFrame).coerceAtMost(MAX_SCROLL_FRAME_NANOS)
            if (elapsed > 0 && scrollBy(speed * (elapsed / NANOS_PER_SECOND)) == 0f) break
            previousFrame = frame
        }
    }
}
