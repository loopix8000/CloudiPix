package app.cloudipix.gallery.ui.component

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/** 이 속도(dp/s)부터 끝을 흐리게 시작합니다. 손으로 천천히 끄는 정도에는 걸리지 않습니다. */
private const val BLUR_START_DP_PER_SECOND = 600f

/** 이 속도(dp/s)에서 가장 흐려집니다. */
private const val BLUR_FULL_DP_PER_SECOND = 2400f

/** 가장 빠를 때의 흐림 반지름입니다. */
private val MaxBlurRadius = 14.dp

/** 흐림이 걸리는 영역의 높이 비율입니다. 새 줄이 들어오는 끝에서 이만큼 안쪽까지 점점 선명해집니다. */
private const val EDGE_FRACTION = 0.3f

/** 표시 속도가 측정 속도를 따라가는 시간 상수(ns)입니다. 값이 클수록 흐림이 느리게 걸리고 느리게 풀립니다. */
private const val SMOOTHING_NANOS = 90_000_000f

/** 이 시간(ns) 동안 스크롤 이벤트가 없으면 멈춘 것으로 보고 흐림을 풀기 시작합니다. */
private const val STALL_NANOS = 50_000_000L

/** 이벤트 간격이 이보다 길면 첫 이벤트처럼 보고 속도를 재지 않습니다(ns). */
private const val MAX_EVENT_GAP_NANOS = 200_000_000L

/** 이보다 느린 표시 속도(px/s)는 0으로 봅니다. */
private const val REST_SPEED = 20f

/**
 * 격자를 빠르게 넘기는 동안 새 줄이 들어오는 쪽 끝을 흐리게 하는 효과의 상태입니다.
 * 중첩 스크롤로 매 이벤트의 속도를 재고, 화면에 쓰는 속도는 매 프레임 측정값을 부드럽게 따라갑니다.
 * 손을 멈추거나 관성이 끝나 이벤트가 끊기면 측정값을 0으로 보고 같은 방식으로 풀립니다.
 */
class ScrollEdgeBlurState internal constructor() : NestedScrollConnection {
    /** 화면에 반영하는 속도(px/s)입니다. 양수면 내용이 아래로 움직여 새 줄이 위에서 들어옵니다. */
    var velocity by mutableFloatStateOf(0f)
        private set

    /** 측정값이나 표시 속도가 남아 있어 매 프레임 갱신해야 하는지입니다. */
    internal var tracking by mutableStateOf(false)
        private set

    private var measured = 0f
    private var lastScrollNanos = 0L
    private var lastFrameNanos = 0L

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val now = System.nanoTime()
        val gap = now - lastScrollNanos
        lastScrollNanos = now
        // 처음이나 오랜만의 이벤트는 간격을 모르므로 속도를 재지 않습니다. 끝에 닿아 움직이지 못한 양은 속도에 넣지 않습니다.
        if (gap in 1..MAX_EVENT_GAP_NANOS) {
            measured = consumed.y * 1_000_000_000f / gap
            tracking = true
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        measured = 0f
        return Velocity.Zero
    }

    /** 표시 속도를 측정값 쪽으로 옮깁니다. 둘 다 0에 가까워지면 프레임 갱신을 멈춥니다. */
    internal fun onFrame() {
        val now = System.nanoTime()
        if (now - lastScrollNanos > STALL_NANOS) measured = 0f
        val dt = if (lastFrameNanos == 0L) 16_000_000L else (now - lastFrameNanos).coerceIn(1L, 100_000_000L)
        lastFrameNanos = now
        val next = velocity + (measured - velocity) * (1f - exp(-dt / SMOOTHING_NANOS))
        if (measured == 0f && abs(next) < REST_SPEED) {
            velocity = 0f
            lastFrameNanos = 0L
            tracking = false
        } else {
            velocity = next
        }
    }
}

/** [Modifier.scrollEdgeBlur]에 넘길 상태를 만들고, 스크롤 중에만 프레임마다 속도를 갱신합니다. */
@Composable
fun rememberScrollEdgeBlur(): ScrollEdgeBlurState {
    val state = remember { ScrollEdgeBlurState() }
    LaunchedEffect(state) {
        snapshotFlow { state.tracking }.collectLatest { tracking ->
            if (tracking) while (true) withFrameNanos { state.onFrame() }
        }
    }
    return state
}

/**
 * 빠르게 넘기는 동안 새 줄이 들어오는 쪽 끝을 흐리게 합니다. 내려가면 아래 끝, 올라가면 위 끝입니다.
 * 썸네일이 늦게 그려지는 순간이 흐림 속에 묻혀 덜 띄게 합니다. 느리게 끌 때는 아무것도 하지 않습니다.
 * 효과는 그리기 레이어에서만 바뀌므로 속도가 변해도 격자를 다시 구성하지 않습니다.
 */
fun Modifier.scrollEdgeBlur(state: ScrollEdgeBlurState): Modifier =
    nestedScroll(state).graphicsLayer { renderEffect = edgeBlurEffect(state.velocity) }

/** 지금 속도에 맞는 끝 흐림 효과입니다. 느리거나 RenderEffect가 없는 Android 12 미만이면 null입니다. */
private fun GraphicsLayerScope.edgeBlurEffect(velocity: Float): ComposeRenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || size.height <= 0f) return null
    val speedDp = abs(velocity) / density
    val strength = ((speedDp - BLUR_START_DP_PER_SECOND) / (BLUR_FULL_DP_PER_SECOND - BLUR_START_DP_PER_SECOND)).coerceIn(0f, 1f)
    if (strength == 0f) return null
    return edgeBlur(MaxBlurRadius.toPx() * strength, size, topEdge = velocity > 0f).asComposeRenderEffect()
}

/**
 * 한쪽 끝만 흐린 효과입니다. 전체를 흐린 그림을 끝에서 알파 1, [EDGE_FRACTION]만큼 안쪽에서 알파 0인
 * 세로 그라데이션으로 가린 뒤 원본 위에 덮습니다.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun edgeBlur(radius: Float, size: Size, topEdge: Boolean): RenderEffect {
    val edge = size.height * EDGE_FRACTION
    val (from, to) = if (topEdge) 0f to edge else size.height to size.height - edge
    val mask = RenderEffect.createShaderEffect(
        LinearGradient(0f, from, 0f, to, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP),
    )
    val blurred = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    val maskedBlur = RenderEffect.createBlendModeEffect(blurred, mask, BlendMode.DST_IN)
    return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), maskedBlur, BlendMode.SRC_OVER)
}
