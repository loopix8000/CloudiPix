package app.cloudipix.gallery.feature.folders

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.validFolderName
import app.cloudipix.gallery.feature.permission.AllFilesAccessRequest
import app.cloudipix.gallery.feature.permission.DeviceFolderPicker
import app.cloudipix.gallery.ui.component.TextDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal data class FolderFileActions(val enabled: Boolean, val rename: (Folder) -> Unit, val move: (Folder) -> Unit)

private class MovingFolder(val folder: Folder, val path: String)

/** 기기 폴더의 이름 변경과 위치 이동. 모든 파일 접근 권한으로 폴더 안의 모든 파일을 함께 옮긴다. */
@Composable
internal fun rememberFolderFileActions(viewModel: FoldersViewModel): FolderFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var movingFolder by remember { mutableStateOf<MovingFolder?>(null) }
    var pendingAccess by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun perform(@StringRes failure: Int, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun finish(@StringRes message: Int, name: String) {
        viewModel.clearSelection()
        Toast.makeText(context, context.getString(message, name), Toast.LENGTH_SHORT).show()
    }

    fun withAccess(action: () -> Unit) {
        if (hasAllFilesAccess()) action() else pendingAccess = action
    }

    pendingAccess?.let { action ->
        AllFilesAccessRequest(
            onGranted = {
                pendingAccess = null
                action()
            },
            onDismiss = { pendingAccess = null },
        )
    }

    renameFolder?.let { folder ->
        TextDialog(
            title = stringResource(R.string.album_rename),
            initial = folder.name,
            isValid = { validFolderName(it.trim()) && it.trim() != folder.name },
            onDismiss = { renameFolder = null },
            onConfirm = { value ->
                renameFolder = null
                perform(R.string.album_rename_failed) {
                    finish(R.string.album_rename_done, viewModel.renameLocalFolder(folder, value.trim()))
                }
            },
        )
    }

    movingFolder?.let { moving ->
        DeviceFolderPicker(
            title = stringResource(R.string.album_move),
            initialPath = File(moving.path).parent,
            excluded = moving.path,
            onPick = { destination ->
                movingFolder = null
                perform(R.string.album_move_failed) {
                    finish(R.string.album_move_done, viewModel.moveLocalFolder(moving.folder, destination))
                }
            },
            onDismiss = { movingFolder = null },
        )
    }

    return FolderFileActions(
        enabled = !busy && pendingAccess == null && movingFolder == null,
        rename = { folder -> withAccess { renameFolder = folder } },
        move = { folder ->
            withAccess {
                perform(R.string.album_move_failed) { movingFolder = MovingFolder(folder, viewModel.localFolderPath(folder)) }
            }
        },
    )
}
