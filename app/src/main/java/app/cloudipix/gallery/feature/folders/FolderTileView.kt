package app.cloudipix.gallery.feature.folders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.withSave
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.cloudFolderGlyph
import app.cloudipix.gallery.core.media.CoverBlur
import app.cloudipix.gallery.core.media.Thumbnail
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.remote.REMOTE_MEDIA_SCHEME
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.sync.AlbumSyncState
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryTheme
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale

internal data class TileStyle(
    val titleFace: Typeface, val metaFace: Typeface, val titleSize: Float, val metaSize: Float,
    val surface: Int, val text: Int, val muted: Int, val accent: Int, val onAccent: Int, val radius: Float,
    val insetX: Int, val insetY: Int, val linesGap: Int, val captionGap: Int, val border: Int, val selectionInset: Int,
)

@Composable
internal fun folderTileStyle(compact: Boolean): TileStyle {
    val title = if (compact) GalleryTheme.type.meta else GalleryTheme.type.label
    val meta = GalleryTheme.type.meta
    val resolver = LocalFontFamilyResolver.current
    val colors = GalleryTheme.colors
    return with(LocalDensity.current) {
        TileStyle(
            resolver.resolve(title.fontFamily, title.fontWeight ?: FontWeight.Normal).value as Typeface,
            resolver.resolve(meta.fontFamily, meta.fontWeight ?: FontWeight.Normal).value as Typeface,
            title.fontSize.toPx(), meta.fontSize.toPx(), colors.surface.toArgb(), colors.text.toArgb(),
            colors.textMuted.toArgb(), colors.accent.toArgb(), colors.onAccent.toArgb(), GalleryTheme.corners.radius.toPx(),
            (if (compact) 4.dp else 6.dp).roundToPx(), (if (compact) 3.dp else 5.dp).roundToPx(),
            2.dp.roundToPx(), 8.dp.roundToPx(), 2.dp.roundToPx(), 3.dp.roundToPx(),
        )
    }
}

internal class FolderTileView(context: Context) : ViewGroup(context) {
    private val image = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val title = label()
    private val details = label()
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(details)
    }
    private var badges: FolderBadgesState? = null
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var overlay = false
    private var style: TileStyle? = null
    private var selectedInset = 0
    private var uri: Uri? = null
    private var pixels = 0
    private var blurred = false
    private var cropped = true
    private var request: Disposable? = null
    private val shade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0, 0, 0x6e000000)).apply { setColors(intArrayOf(0, 0, 0x6e000000), floatArrayOf(0f, .48f, 1f)) }
    private val surface = GradientDrawable()
    private val selection = GradientDrawable().apply { setColor(0) }

    init {
        addView(image)
        addView(caption)
        image.background = surface
        image.clipToOutline = true
        isFocusable = true
        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun label() = TextView(context).apply {
        includeFontPadding = false
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(folder: Folder, settings: GallerySettings, theme: TileStyle, locked: Boolean,
             sync: AlbumSyncState?, selected: Boolean, selecting: Boolean, width: Int) {
        val onPhoto = settings.detailsPlacement == DetailsPlacement.Overlay
        if (style != theme || overlay != onPhoto) {
            style = theme
            overlay = onPhoto
            surface.setColor(theme.surface)
            surface.cornerRadius = theme.radius
            shade.cornerRadius = theme.radius
            selection.cornerRadius = theme.radius
            selection.setStroke(theme.border, theme.accent)
            title.typeface = theme.titleFace
            details.typeface = theme.metaFace
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.titleSize)
            details.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.metaSize)
            title.setTextColor(if (overlay) 0xffededEF.toInt() else theme.text)
            details.setTextColor(if (overlay) 0xffd8d8db.toInt() else theme.muted)
            title.maxLines = 1
            requestLayout()
        }
        val name = folder.name.takeIf { settings.showFolderNames }
        val count = if (settings.showFolderCount) formatCount(folder.itemCount) else null
        val bytes = if (settings.showFolderSize) formatSize(folder.size) else null
        val info = if (count != null && bytes != null) "$count · $bytes" else count ?: bytes
        title.text = name
        details.text = info
        title.visibility = if (name == null) GONE else VISIBLE
        details.visibility = if (info == null) GONE else VISIBLE
        details.maxLines = if (count != null && bytes != null) 2 else 1
        details.setPadding(0, if (name != null && info != null) theme.linesGap else 0, 0, 0)
        val hasCaption = name != null || info != null
        caption.visibility = if (hasCaption) VISIBLE else GONE
        image.foreground = if (overlay && hasCaption) shade else null
        isSelected = selected
        foreground = if (selected) selection else null
        val inset = if (selected) theme.selectionInset else 0
        if (inset != selectedInset) { selectedInset = inset; requestLayout() }
        val cloudBadge = settings.showCloudFolderIcons && (folder.isRemote || sync != null) &&
            (!settings.showCloudFolderIconsOnlyWhileSyncing || sync == AlbumSyncState.Syncing)
        val cloud = if (cloudBadge) cloudFolderGlyph(sync) else null
        badges = if (folder.isFavorites || locked || folder.isPinned || folder.isHidden || selecting || cloudBadge)
            FolderBadgesState(
                favorite = folder.isFavorites,
                empty = folder.itemCount == 0,
                locked = locked,
                pinned = folder.isPinned,
                hidden = folder.isHidden,
                selecting = selecting,
                selected = selected,
                cloud = cloud?.first,
            )
        else null
        contentDescription = listOfNotNull(
            folder.name,
            info,
            if (locked) context.getString(R.string.folder_locked) else null,
            if (folder.isPinned) context.getString(R.string.pinned) else null,
            if (folder.isHidden) context.getString(R.string.ignored_title) else null,
            cloud?.second?.let(context::getString),
        ).joinToString(", ")
        invalidate()
        bindImage(folder.coverUri.takeUnless { folder.isFavorites && folder.itemCount == 0 }, width, locked, settings.cropThumbnails)
    }

    private fun bindImage(next: Uri?, size: Int, locked: Boolean, crop: Boolean) {
        if (uri == next && size == pixels && blurred == locked && cropped == crop) return
        val previous = uri
        val placeholder = if (next?.scheme == REMOTE_MEDIA_SCHEME && previous != null && previous.authority == next.authority &&
            previous.pathSegments.firstOrNull() == next.pathSegments.firstOrNull() && blurred == locked)
            image.drawable?.asImage() else null
        clearImage()
        uri = next
        pixels = size
        blurred = locked
        cropped = crop
        if (next == null) return
        image.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val builder = ImageRequest.Builder(context).data(Thumbnail(next)).target(image)
            .size(size, size).scale(if (crop) Scale.FILL else Scale.FIT).precision(Precision.EXACT).crossfade(false)
        if (locked) builder.transformations(CoverBlur) else builder.placeholderMemoryCacheKey(next.toString())
        if (placeholder != null) builder.placeholder(placeholder).error(placeholder)
        request = SingletonImageLoader.get(context).enqueue(builder.build())
    }

    private fun clearImage() {
        request?.dispose()
        request = null
        uri = null
        image.setImageDrawable(null)
    }

    fun recycle() {
        clearImage()
        badges = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val side = (width - selectedInset * 2).coerceAtLeast(0)
        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        image.measure(exact, exact)
        val horizontal = if (overlay) style?.insetX ?: 0 else 0
        caption.measure(MeasureSpec.makeMeasureSpec((side - 2 * horizontal).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val extra = if (overlay || caption.visibility == GONE) 0 else caption.measuredHeight + (style?.captionGap ?: 0)
        setMeasuredDimension(width, width + extra)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val end = width - selectedInset
        image.layout(selectedInset, selectedInset, end, end)
        val x = selectedInset + if (overlay) style?.insetX ?: 0 else 0
        val y = if (overlay) end - (style?.insetY ?: 0) - caption.measuredHeight else end + (style?.captionGap ?: 0)
        caption.layout(x, y, x + caption.measuredWidth, y + caption.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val state = badges ?: return
        val theme = style ?: return
        val density = resources.displayMetrics.density
        canvas.withSave {
            translate(image.left.toFloat(), image.top.toFloat())
            scale(density, density)
            drawFolderBadges(state, brush, image.width / density, layoutDirection == LAYOUT_DIRECTION_RTL,
                theme.accent, theme.onAccent)
        }
    }
}

internal class FolderHolder(val tile: FolderTileView) : RecyclerView.ViewHolder(tile) {
    var folder: Folder? = null
}
