package app.cloudipix.gallery.feature.folders

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.sync.AlbumSyncState

internal data class FolderBinding(
    val style: TileStyle, val settings: GallerySettings, val locked: Set<Long>, val selected: Set<Long>,
    val sync: Map<Long, AlbumSyncState>, val dragged: Long?, val width: Int,
)

private class FolderAdapter : RecyclerView.Adapter<FolderHolder>() {
    var folders: List<Folder> = emptyList()
    lateinit var binding: FolderBinding
    lateinit var click: (Folder) -> Unit
    lateinit var hold: (Folder) -> Unit

    init { setHasStableIds(true) }
    fun update(items: List<Folder>, config: FolderBinding) {
        if (folders === items && ::binding.isInitialized && binding == config) return
        folders = items
        binding = config
        notifyDataSetChanged()
    }
    override fun getItemCount() = folders.size
    override fun getItemId(position: Int) = folders[position].bucketId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderHolder {
        val holder = FolderHolder(FolderTileView(parent.context))
        holder.tile.setOnClickListener { holder.folder?.let(click) }
        holder.tile.setOnLongClickListener {
            val folder = holder.folder
            if (folder == null || folder.isRecent) false else { hold(folder); true }
        }
        return holder
    }
    override fun onBindViewHolder(holder: FolderHolder, position: Int) {
        val folder = folders[position]
        holder.folder = folder
        val id = folder.bucketId
        with(binding) {
            holder.tile.alpha = if (id == dragged) 0f else 1f
            holder.tile.bind(folder, settings, style, id in locked, sync[id], id in selected,
                selected.isNotEmpty() && !folder.isRecent, width)
        }
    }
    override fun onViewRecycled(holder: FolderHolder) {
        holder.tile.recycle()
        holder.folder = null
    }
}

private class FolderSpacing : RecyclerView.ItemDecoration() {
    var gap = 0
    var rowGap = 0
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val manager = parent.layoutManager as GridLayoutManager
        val position = parent.getChildAdapterPosition(view)
        if (position < 0) return
        val columns = manager.spanCount
        val column = position % columns
        val start = column * gap / columns
        val end = gap - (column + 1) * gap / columns
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        outRect.set(if (rtl) end else start, if (position >= columns) rowGap else 0, if (rtl) start else end, 0)
    }
}

@Composable
internal fun RecyclingFolderGrid(
    viewport: FolderViewport, folders: List<Folder>, binding: FolderBinding, columns: Int,
    gap: Int, rowGap: Int, top: Int, onClick: (Folder) -> Unit, onHold: (Folder) -> Unit, modifier: Modifier,
) {
    val adapter = remember { FolderAdapter() }
    val spacing = remember { FolderSpacing() }
    AndroidView(
        factory = { context -> FolderRecycler(context).apply {
            layoutManager = GridLayoutManager(context, columns)
            this.adapter = adapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            addItemDecoration(spacing)
            viewport.attach(this)
        } },
        modifier = modifier.pointerInput(viewport) { detectTapGestures { viewport.click(it) } }
            .scrollable(viewport.scroll, Orientation.Vertical, reverseDirection = true),
        onRelease = { rv -> viewport.detach(rv); rv.adapter = null },
        update = { rv ->
            adapter.click = onClick
            adapter.hold = onHold
            val layout = rv.layoutManager as GridLayoutManager
            if (layout.spanCount != columns || spacing.gap != gap || spacing.rowGap != rowGap) {
                layout.spanCount = columns
                spacing.gap = gap
                spacing.rowGap = rowGap
                rv.invalidateItemDecorations()
            }
            if (rv.paddingLeft != gap || rv.paddingTop != top) rv.setPadding(gap, top, gap, 0)
            adapter.update(folders, binding)
        },
    )
}
