package app.cloudipix.gallery.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.data.MediaWriter
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.remote.RemoteFiles
import app.cloudipix.gallery.navigation.Editor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

internal data class PhotoEditorState(
    val original: Bitmap? = null,
    val preview: Bitmap? = null,
    val edit: PhotoEdit = PhotoEdit(),
    val previewEdit: PhotoEdit = PhotoEdit(),
    val adjusting: Boolean = false,
    val previewIsDraft: Boolean = false,
    val thumbnails: Map<PhotoFilter, Bitmap> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val finished: Boolean = false,
    @StringRes val notice: Int? = null,
)

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val remoteFiles: RemoteFiles,
    private val writer: MediaWriter,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val route = savedState.toRoute<Editor>()
    private var medium: Medium? = null
    private val undo = ArrayDeque<PhotoEdit>()
    private val redo = ArrayDeque<PhotoEdit>()
    private var gestureFrom: PhotoEdit? = null
    private var loadingThumbnails = false
    private val _state = MutableStateFlow(PhotoEditorState())
    internal val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val photo = try {
                medium = route.mediumId?.let { repository.medium(it) }
                loadImage(preview = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(notice = R.string.photo_editor_open_failed, finished = true) }
                return@launch
            }
            _state.update { it.copy(original = photo, preview = photo) }
            var draftSource: Bitmap? = null
            state.map { it.edit.withoutAnnotations() to it.adjusting }.distinctUntilChanged().conflate().collect { (edit, adjusting) ->
                val displayed = state.value
                if (edit == displayed.previewEdit && adjusting == displayed.previewIsDraft) return@collect
                try {
                    val preview = withContext(Dispatchers.Default) {
                        val source = if (adjusting) {
                            draftSource ?: photo.scaledForEditor(720).also { draftSource = it }
                        } else photo
                        edit.render(context, source)
                    }
                    _state.update { it.copy(preview = preview, previewEdit = edit, previewIsDraft = adjusting) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(notice = R.string.photo_editor_preview_failed) }
                }
            }
        }
    }

    private suspend fun loadImage(preview: Boolean): Bitmap = withContext(Dispatchers.IO) {
        val photo = medium
        if (photo != null) remoteFiles.withReadableUri(photo) { decodeEditorImage(context, it, preview) }
        else decodeEditorImage(context, Uri.parse(requireNotNull(route.inputUri)), preview)
    }

    internal fun change(transform: (PhotoEdit) -> PhotoEdit) {
        val before = state.value.edit
        val after = transform(before)
        if (before == after) return
        if (gestureFrom == null) record(before)
        setEdit(after)
    }

    internal fun beginGesture() {
        if (gestureFrom == null) gestureFrom = state.value.edit
    }

    internal fun adjustPreview(adjustment: Adjustment, value: Int) {
        val before = state.value.edit
        val after = before.adjusted(adjustment, value)
        if (before == after) return
        beginGesture()
        setEdit(after, adjusting = true)
    }

    internal fun finishGesture() {
        val before = gestureFrom ?: return
        gestureFrom = null
        if (before != state.value.edit) record(before)
        setEdit(state.value.edit)
    }

    internal fun cancelGesture() {
        val before = gestureFrom ?: return
        gestureFrom = null
        setEdit(before)
    }

    internal fun undo() {
        finishGesture()
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(state.value.edit)
        setEdit(previous)
    }

    internal fun redo() {
        finishGesture()
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(state.value.edit)
        setEdit(next)
    }

    private fun record(before: PhotoEdit) {
        undo.addLast(before)
        if (undo.size > 32) undo.removeFirst()
        redo.clear()
    }

    private fun setEdit(edit: PhotoEdit, adjusting: Boolean = false) {
        _state.update { it.copy(edit = edit, adjusting = adjusting, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    }

    internal fun loadThumbnails() {
        val photo = state.value.original ?: return
        if (loadingThumbnails || state.value.thumbnails.isNotEmpty()) return
        loadingThumbnails = true
        viewModelScope.launch {
            try {
                val thumbnails = withContext(Dispatchers.Default) {
                    val thumbnail = photo.scaledForEditor(150)
                    PhotoFilter.entries.associateWith { PhotoEdit(filter = it).render(context, thumbnail) }
                }
                _state.update { it.copy(thumbnails = thumbnails) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(notice = R.string.photo_editor_preview_failed) }
            } finally {
                loadingThumbnails = false
            }
        }
    }

    internal fun consumeNotice() = _state.update { it.copy(notice = null) }

    internal fun save() {
        if (state.value.saving || state.value.original == null) return
        finishGesture()
        val edit = state.value.edit
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val notice = saveRenderedPhoto(edit)
                _state.update { it.copy(saving = false, finished = true, notice = notice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, notice = R.string.editor_save_failed) }
            } catch (_: OutOfMemoryError) {
                _state.update { it.copy(saving = false, notice = R.string.editor_save_failed) }
            }
        }
    }

    private suspend fun saveRenderedPhoto(edit: PhotoEdit): Int = withContext(Dispatchers.IO) {
        val original = loadImage(preview = false)
        val alpha = original.hasAlpha()
        val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (alpha) "image/png" else "image/jpeg"
        val file = File.createTempFile("edit-", if (alpha) ".png" else ".jpg", context.cacheDir)
        try {
            val result = withContext(Dispatchers.Default) {
                val rendered = edit.render(context, original, releaseOriginal = true)
                var annotated: Bitmap? = null
                try {
                    edit.annotate(rendered, reuse = true).also { annotated = it }
                } finally {
                    if (annotated !== rendered) rendered.recycle()
                }
            }
            try {
                file.outputStream().use { if (!result.compress(format, 96, it)) throw IOException() }
            } finally {
                result.recycle()
            }
            val photo = medium
            if (photo?.isRemote == true) {
                repository.applyRemoteChanges(listOf(remoteFiles.saveEdit(photo, file, mime)))
                R.string.editor_saved
            } else {
                val source = photo?.uri ?: Uri.parse(requireNotNull(route.inputUri))
                writer.saveEdit(source, photo?.name ?: displayName(source), file, mime) ?: throw IOException()
            }
        } finally {
            if (!original.isRecycled) original.recycle()
            file.delete()
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Photo"
}

private fun Bitmap.scaledForEditor(maxEdge: Int): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxEdge) return this
    val scale = maxEdge.toFloat() / longestEdge
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1), true)
}
