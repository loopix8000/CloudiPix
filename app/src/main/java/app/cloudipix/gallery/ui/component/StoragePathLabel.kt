package app.cloudipix.gallery.ui.component

import android.os.storage.StorageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.remote.isRemoteId

@Composable
internal fun storagePathLabel(absolutePath: String): String =
    storagePathLabel(LocalStoragePath.absolute(absolutePath))

@Composable
internal fun storagePathLabel(location: LocalStoragePath): String {
    val context = LocalContext.current
    val removableName = location.volumeName?.takeIf { location.isRemovable }?.let { volume ->
        context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { volume.equals(it.uuid, ignoreCase = true) }?.getDescription(context)
            ?.takeIf { it.isNotBlank() }
    } ?: stringResource(R.string.storage_sd_card)
    return location.label(stringResource(R.string.sync_internal_storage), removableName)
}

@Composable
internal fun folderPathLabel(folder: FolderEntry, preferSyncLocalPath: Boolean = false): String = when {
    preferSyncLocalPath && folder.syncLocalPath != null -> storagePathLabel(folder.syncLocalPath)
    isRemoteId(folder.bucketId) -> folder.path
    else -> storagePathLabel(LocalStoragePath.relative(folder.path, folder.volumeName))
}
