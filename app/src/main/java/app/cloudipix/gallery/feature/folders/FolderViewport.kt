package app.cloudipix.gallery.feature.folders

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.MotionEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class FolderRecycler(context: Context) : RecyclerView(context) {
    // 탭 전환, 줌, 다중 선택, 재정렬은 같은 Compose 포인터 스트림을 사용한다.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}

internal data class FolderHit(val id: Long, val origin: Offset, val width: Int)

internal class FolderViewport(private var saved: Parcelable? = null) {
    var view: FolderRecycler? by mutableStateOf(null)
        private set
    var canScrollBackward by mutableStateOf(false)
        private set
    var canScrollForward by mutableStateOf(false)
        private set
    private var consumed = 0
    private var remainder = 0f
    private val listener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            consumed += dy
            updateBounds(rv)
        }
    }
    private fun updateBounds(rv: RecyclerView) {
        canScrollBackward = rv.canScrollVertically(-1)
        canScrollForward = rv.canScrollVertically(1)
    }
    val scroll = ScrollableState { delta ->
        val rv = view
        if (rv == null || !rv.canScrollVertically(if (delta < 0) -1 else 1)) {
            remainder = 0f
            0f
        } else {
            val pixels = (delta + remainder).roundToInt()
            remainder += delta - pixels
            consumed = 0
            rv.scrollBy(0, pixels)
            if (consumed == pixels) delta else {
                remainder = 0f
                consumed.toFloat()
            }
        }
    }
    fun attach(rv: FolderRecycler) {
        view = rv
        rv.addOnScrollListener(listener)
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds(rv) }
        saved?.let { rv.layoutManager?.onRestoreInstanceState(it) }
        saved = null
    }
    fun detach(rv: FolderRecycler) {
        if (view !== rv) return
        saved = rv.layoutManager?.onSaveInstanceState()
        rv.removeOnScrollListener(listener)
        view = null
    }
    fun hit(point: Offset): FolderHit? {
        val rv = view ?: return null
        val child = rv.findChildViewUnder(point.x, point.y) ?: return null
        val id = (rv.getChildViewHolder(child) as FolderHolder).folder?.bucketId ?: return null
        return FolderHit(id, Offset(child.x, child.y), child.width)
    }
    fun click(point: Offset) {
        view?.findChildViewUnder(point.x, point.y)?.performClick()
    }
    companion object {
        val saver = Saver<FolderViewport, Bundle>(
            save = { Bundle().apply { putParcelable("position", it.view?.layoutManager?.onSaveInstanceState() ?: it.saved) } },
            restore = { @Suppress("DEPRECATION") FolderViewport(it.getParcelable("position")) },
        )
    }
}

@Composable
internal fun rememberFolderViewport() = rememberSaveable(saver = FolderViewport.saver) { FolderViewport() }
