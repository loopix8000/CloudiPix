package app.cloudipix.gallery.feature.editor

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.GallerySlider
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.t8rin.cropper.ImageCropper
import com.t8rin.cropper.model.AspectRatio
import com.t8rin.cropper.model.OutlineType
import com.t8rin.cropper.model.RectCropShape
import com.t8rin.cropper.rememberImageCropperState
import com.t8rin.cropper.settings.CropDefaults
import com.t8rin.cropper.settings.CropOutlineProperty
import kotlin.math.roundToInt

internal const val PHOTO_EDITOR_PREVIEW = "photo_editor_preview"

private enum class EditorPanel(@StringRes val label: Int, val icon: ImageVector) {
    Crop(R.string.editor_crop, GalleryIcons.Crop),
    Adjust(R.string.editor_adjust, GalleryIcons.Exposure),
    Filters(R.string.photo_editor_filters, GalleryIcons.Filter),
    Draw(R.string.photo_editor_draw, GalleryIcons.Pencil),
    Text(R.string.editor_text, GalleryIcons.Text),
}

private enum class FrameRatio(private val width: Int, private val height: Int) {
    Free(0, 0), Square(1, 1), FourFive(4, 5), ThreeFour(3, 4), TwoThree(2, 3), NineSixteen(9, 16);

    val canTranspose: Boolean get() = width != height

    fun aspectRatio(landscape: Boolean): Float? = when {
        this == Free -> null
        landscape -> height.toFloat() / width
        else -> width.toFloat() / height
    }

    fun label(landscape: Boolean) = if (landscape) "$height:$width" else "$width:$height"
}

@Composable
fun PhotoEditorScreen(onBack: () -> Unit, viewModel: PhotoEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GalleryTheme.colors
    val context = LocalContext.current
    val cropper = rememberImageCropperState()
    var panel by rememberSaveable { mutableStateOf<EditorPanel?>(EditorPanel.Crop) }
    var ratio by rememberSaveable { mutableStateOf(FrameRatio.Free) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var adjustment by rememberSaveable { mutableStateOf(Adjustment.Brightness) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var pen by rememberSaveable { mutableStateOf(AnnotationInk.White) }
    var penWeight by rememberSaveable { mutableStateOf(AnnotationWeight.Medium) }
    var selectedTextId by rememberSaveable { mutableStateOf<Long?>(null) }
    var textTarget by remember { mutableStateOf<Offset?>(null) }
    var editTextId by remember { mutableStateOf<Long?>(null) }
    val selectedText = state.edit.annotations.filterIsInstance<PhotoText>().firstOrNull { it.id == selectedTextId }
    val cropping = panel == EditorPanel.Crop
    val frameReady = state.original != null && !state.previewIsDraft && state.previewEdit == state.edit.withoutAnnotations()
    val renderedEdit = state.previewEdit.copy(annotations = state.edit.annotations)
    val labelSize = with(LocalDensity.current) { GalleryTheme.type.label.fontSize.toDp() }
    val controlsHeight = Spacing.hitTarget + 6.dp + maxOf(Spacing.hitTarget, labelSize * 1.5f + 20.dp)

    fun addText(at: Offset) {
        editTextId = null
        textTarget = at
    }

    fun hasCropChanges() = ratio != FrameRatio.Free || cropper.canUndo

    fun applyFrame(transform: (PhotoEdit) -> PhotoEdit = { it }) {
        cropper.endTransformation()
        val hasCrop = hasCropChanges()
        viewModel.beginGesture()
        viewModel.change { edit ->
            val rect = if (hasCrop && !state.previewIsDraft && state.previewEdit == edit.withoutAnnotations()) {
                cropper.normalizedCropRect()
            } else null
            transform(rect?.let(edit::cropped) ?: edit)
        }
    }

    fun showPanel(next: EditorPanel?) {
        comparing = false
        if (panel == EditorPanel.Crop) applyFrame()
        viewModel.finishGesture()
        if (next == EditorPanel.Crop) {
            ratio = FrameRatio.Free
            landscape = false
            cropper.reset()
        }
        panel = next
        if (next == EditorPanel.Filters) viewModel.loadThumbnails()
    }

    fun cancelCrop() {
        if (state.saving) return
        panel = null
        viewModel.cancelGesture()
    }

    fun closeEditor() {
        if (state.saving) return
        if (cropping) cropper.endTransformation()
        if (state.edit != PhotoEdit() || (cropping && hasCropChanges())) {
            confirmClose = true
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::closeEditor)
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorAction(GalleryIcons.Close, R.string.action_close, ::closeEditor, enabled = !state.saving)
            Spacer(Modifier.weight(1f))
            val canCompare = !cropping && !state.saving && state.edit != PhotoEdit()
            IconToggleButton(checked = comparing, onCheckedChange = { comparing = it }, enabled = canCompare,
                modifier = Modifier.size(44.dp)) {
                Icon(GalleryIcons.ComparePhotos, stringResource(R.string.photo_editor_compare), Modifier.size(20.dp),
                    tint = when {
                        !canCompare -> colors.textMuted.copy(alpha = .42f)
                        comparing -> colors.accent
                        else -> colors.text
                    })
            }
            EditorAction(GalleryIcons.Undo, R.string.action_undo, { comparing = false; viewModel.undo() },
                enabled = state.canUndo && !cropping && !state.saving)
            EditorAction(GalleryIcons.Redo, R.string.action_redo, { comparing = false; viewModel.redo() },
                enabled = state.canRedo && !cropping && !state.saving)
            TextButton(
                enabled = state.original != null && !state.saving && (!cropping || frameReady),
                onClick = {
                    if (cropping) showPanel(null)
                    viewModel.save()
                },
            ) {
                Text(stringResource(R.string.action_save), color = if (state.original != null && !state.saving)
                    colors.accent else colors.textMuted, style = GalleryTheme.type.body)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111114))
                .testTag(PHOTO_EDITOR_PREVIEW).padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = if (comparing) state.original else state.preview
            if (bitmap != null) {
                val description = stringResource(R.string.photo_editor_preview)
                if (cropping) {
                    val cropBitmap = if (renderedEdit.annotations.isEmpty()) bitmap else key(bitmap, renderedEdit) {
                        produceState<Bitmap?>(null) {
                            value = withContext(Dispatchers.Default) { renderedEdit.annotate(bitmap) }
                        }.value
                    }
                    if (cropBitmap == null) {
                        CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
                        return@Box
                    }
                    val image = remember(cropBitmap) { cropBitmap.asImageBitmap() }
                    val aspectRatio = ratio.aspectRatio(landscape)
                    val cropProperties = remember(aspectRatio) {
                        CropDefaults.properties(
                            cropOutlineProperty = CropOutlineProperty(OutlineType.Rect, RectCropShape(0, "Rectangle")),
                            aspectRatio = aspectRatio?.let { AspectRatio(it) } ?: AspectRatio.Original,
                            fixedAspectRatio = aspectRatio != null,
                            overlayRatio = 1f,
                        )
                    }
                    ImageCropper(
                        modifier = Modifier.fillMaxSize(),
                        imageBitmap = image,
                        contentDescription = description,
                        cropProperties = cropProperties,
                        state = cropper,
                        isOverlayDraggable = frameReady,
                        onCropStart = {},
                        onZoomChange = {},
                        onCropSuccess = {},
                    )
                } else {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    val annotate = !comparing && !state.saving && frameReady
                    if (!comparing && (renderedEdit.annotations.isNotEmpty() || panel == EditorPanel.Draw || panel == EditorPanel.Text)) {
                        PhotoAnnotationLayer(
                            edit = renderedEdit,
                            photoSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                            drawing = annotate && panel == EditorPanel.Draw,
                            editingText = annotate && panel == EditorPanel.Text,
                            pen = pen,
                            penWidth = penWeight.stroke,
                            selectedTextId = selectedTextId,
                            onStroke = { stroke -> viewModel.change { it.addAnnotation(stroke) } },
                            onSelectText = { selectedTextId = it },
                            onMoveText = { label -> viewModel.change { it.replaceText(label.id, label) } },
                            onPlaceText = ::addText,
                        )
                    }
                }
            }
            if (bitmap == null || state.saving) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
            }
        }

        if (panel != null) Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(controlsHeight),
            contentAlignment = Alignment.Center,
        ) {
            when (panel) {
                EditorPanel.Crop -> CropControls(
                    ratio = ratio,
                    landscape = landscape,
                    ready = frameReady,
                    onRatio = {
                        if (ratio != it) {
                            cropper.reset()
                            ratio = it
                        }
                    },
                    onLandscapeChange = {
                        if (ratio.canTranspose) cropper.reset()
                        landscape = it
                    },
                    onRotate = { clockwise ->
                        applyFrame { it.rotated(clockwise) }
                        ratio = FrameRatio.Free
                    },
                    onMirror = {
                        applyFrame(PhotoEdit::mirror)
                        ratio = FrameRatio.Free
                    },
                    onCancel = ::cancelCrop,
                    onDone = { showPanel(null) },
                )
                EditorPanel.Adjust -> AdjustControls(
                    adjustment = adjustment,
                    value = state.edit.adjustments[adjustment] ?: 0,
                    onSelect = { viewModel.finishGesture(); adjustment = it },
                    onChange = { value ->
                        comparing = false
                        viewModel.adjustPreview(adjustment, value)
                    },
                    onFinish = viewModel::finishGesture,
                )
                EditorPanel.Filters -> FilterControls(state.thumbnails, state.edit.filter) { filter ->
                    comparing = false
                    viewModel.change { it.copy(filter = filter) }
                }
                EditorPanel.Draw -> DrawControls(pen, penWeight, { pen = it }, { penWeight = it })
                EditorPanel.Text -> TextControls(
                    selected = selectedText != null,
                    onAdd = {
                        comparing = false
                        addText(state.edit.sourcePoint(Offset(0.5f, 0.5f)))
                    },
                    onEdit = {
                        selectedText?.let {
                            comparing = false
                            editTextId = it.id
                            textTarget = it.center
                        }
                    },
                    onDelete = {
                        selectedText?.let { label -> viewModel.change { it.replaceText(label.id, null) } }
                        selectedTextId = null
                    },
                )
                null -> Unit
            }
        }

        HorizontalDivider(color = colors.hairline)
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp)) {
            EditorPanel.entries.forEach { tool ->
                val selected = panel == tool
                val color = if (selected) colors.accent else colors.textMuted
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(
                        selected = selected, enabled = state.original != null && !state.saving,
                        role = Role.Tab,
                    ) {
                        showPanel(tool.takeUnless { selected })
                    },
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tool.icon, null, Modifier.size(20.dp), tint = color)
                    Text(stringResource(tool.label), style = GalleryTheme.type.label, color = color, maxLines = 1)
                }
            }
        }
    }

    textTarget?.let { target ->
        EditPhotoTextDialog(
            initial = selectedText?.takeIf { it.id == editTextId },
            onConfirm = { text, color, size ->
                val id = editTextId ?: SystemClock.elapsedRealtimeNanos()
                val textLabel = PhotoText(id, text, target, color, size)
                val label = state.preview?.let { bitmap ->
                    val projection = AnnotationProjection(renderedEdit, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
                    projection.placeLabel(textLabel, projection.imagePoint(target))
                } ?: textLabel
                viewModel.change { if (editTextId == null) it.addAnnotation(label) else it.replaceText(id, label) }
                selectedTextId = id
                textTarget = null
            },
            onDismiss = { textTarget = null },
        )
    }

    if (confirmClose) {
        DialogCard(stringResource(R.string.editor_save_changes), onDismiss = { confirmClose = false }) {
            DialogButtons(enabled = true, onDismiss = { confirmClose = false }, onConfirm = onBack,
                confirmLabel = stringResource(R.string.editor_discard_changes))
        }
    }
}

@Composable
private fun CropControls(
    ratio: FrameRatio,
    landscape: Boolean,
    ready: Boolean,
    onRatio: (FrameRatio) -> Unit,
    onLandscapeChange: (Boolean) -> Unit,
    onRotate: (Boolean) -> Unit,
    onMirror: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = GalleryTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FrameRatio.entries.forEach { item ->
                    val label = if (item == FrameRatio.Free) stringResource(R.string.photo_editor_free_crop)
                        else item.label(landscape)
                    EditorPill(label, ratio == item, ready, Modifier.widthIn(min = Spacing.hitTarget),
                        horizontalPadding = 6.dp) { onRatio(item) }
                }
            }
            IconToggleButton(checked = landscape, onCheckedChange = onLandscapeChange, enabled = ready,
                modifier = Modifier.size(Spacing.hitTarget)) {
                Icon(
                    GalleryIcons.Rectangle,
                    stringResource(R.string.photo_editor_landscape_ratios),
                    Modifier.size(22.dp).rotate(if (landscape) 0f else 90f),
                    tint = when {
                        !ready -> colors.textMuted.copy(alpha = .42f)
                        landscape -> colors.accent
                        else -> colors.text
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorAction(GalleryIcons.Close, R.string.action_cancel, onCancel)
            Spacer(Modifier.weight(1f))
            EditorAction(GalleryIcons.RotateLeft, R.string.action_rotate_left, { onRotate(false) }, enabled = ready)
            EditorAction(GalleryIcons.Mirror, R.string.action_mirror, onMirror, enabled = ready)
            EditorAction(GalleryIcons.RotateRight, R.string.action_rotate_right, { onRotate(true) }, enabled = ready)
            Spacer(Modifier.weight(1f))
            EditorAction(GalleryIcons.Check, R.string.action_done, onDone, enabled = ready, selected = true)
        }
    }
}

@Composable
private fun AdjustControls(
    adjustment: Adjustment,
    value: Int,
    onSelect: (Adjustment) -> Unit,
    onChange: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = GalleryTheme.colors
    val label = stringResource(adjustment.label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GallerySlider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
                valueRange = -100f..100f,
                onValueChangeFinished = onFinish,
            )
            Text(if (value > 0) "+$value" else value.toString(),
                Modifier.padding(start = 10.dp).width(38.dp), style = GalleryTheme.type.label, color = colors.text)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Adjustment.entries.forEach { item ->
                EditorPill(stringResource(item.label), adjustment == item) { onSelect(item) }
            }
        }
    }
}

@Composable
private fun FilterControls(thumbnails: Map<PhotoFilter, Bitmap>, selected: PhotoFilter, onSelect: (PhotoFilter) -> Unit) {
    val colors = GalleryTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilter.entries.forEach { item ->
            val active = selected == item
            Column(
                Modifier.width(56.dp).selectable(selected = active, role = Role.RadioButton) { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(shape).background(colors.surface)
                    .border(1.dp, if (active) colors.accent else colors.hairline, shape)) {
                    thumbnails[item]?.let {
                        val thumb = remember(it) { it.asImageBitmap() }
                        Image(thumb, null, Modifier.fillMaxSize().padding(2.dp).clip(shape),
                            contentScale = ContentScale.Crop)
                    }
                }
                Text(stringResource(item.label), style = GalleryTheme.type.label,
                    color = if (active) colors.accent else colors.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun EditorPill(
    label: String, selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp, onClick: () -> Unit,
) {
    val colors = GalleryTheme.colors
    Text(
        label,
        modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.surface)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        style = GalleryTheme.type.label, textAlign = TextAlign.Center, color = if (selected) colors.text else colors.textMuted,
    )
}

@Composable
private fun EditorAction(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val colors = GalleryTheme.colors
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, stringResource(label), Modifier.size(20.dp), tint = when {
            !enabled -> colors.textMuted.copy(alpha = .42f)
            selected -> colors.accent
            else -> colors.text
        })
    }
}
