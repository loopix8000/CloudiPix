package app.cloudipix.gallery.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.StorageRoot
import app.cloudipix.gallery.core.media.storageRoots
import app.cloudipix.gallery.core.model.validFolderName
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 모든 파일 접근 권한으로 기기 폴더를 둘러보고 하나를 고른다. 폴더 선택기와 달리 Download 루트도 고를 수 있다.
 * [excluded]와 그 하위 폴더는 목록에서 빼고 고를 수도 없다. 결과는 절대 경로다.
 */
@Composable
internal fun FolderBrowser(
    title: String,
    initialPath: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    excluded: String? = null,
    selectStorageRoot: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roots = remember(excluded) {
        context.storageRoots().filter { excluded == null || excluded.isWithin(it.path) }
    }
    var requestedPath by rememberSaveable {
        mutableStateOf(initialPath?.trimEnd('/')?.takeIf { path ->
            roots.any { path.isWithin(it.path) } && File(path).isDirectory
        })
    }
    var creating by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<OpenedFolder?>(null) }
    fun browse(path: String?) {
        requestedPath = path
        if (path == null) opened = null
    }
    val destination = requestedPath
    LaunchedEffect(destination) {
        if (destination == null || destination == opened?.path) return@LaunchedEffect
        val result = listFolders(destination, roots.any { it.path == destination }, excluded)
        if (requestedPath != destination) return@LaunchedEffect
        val folders = result.getOrNull()
        if (folders != null) {
            opened = OpenedFolder(destination, folders)
        } else {
            requestedPath = opened?.path
            Toast.makeText(context, R.string.folder_browser_read_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val path = opened?.path
    val root = path?.let { p -> roots.firstOrNull { p.isWithin(it.path) } }
    val folders = opened?.folders.orEmpty()
    val selectable = path != null && path == destination && (selectStorageRoot || path != root?.path) &&
        excluded?.let { path.isWithin(it) || it == File(path).parent } != true

    GallerySheet(title, onDismiss, skipPartiallyExpanded = true) {
        Text(
            path?.let { storagePathLabel(it) } ?: stringResource(R.string.sync_internal_storage),
            style = GalleryTheme.type.meta,
            color = GalleryTheme.colors.textMuted,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.screen).padding(bottom = 8.dp),
        )
        key(path) {
            LazyColumn(Modifier.height((LocalConfiguration.current.screenHeightDp * 0.55f).dp).fillMaxWidth()) {
                if (path == null) {
                    items(roots, key = StorageRoot::id) { storage ->
                        BrowserRow(storagePathLabel(storage.path), GalleryIcons.Folder) { browse(storage.path) }
                    }
                } else {
                    item {
                        BrowserRow(stringResource(R.string.folder_browser_up), GalleryIcons.Back) {
                            // 저장소 최상위에서는 저장소 목록으로 돌아간다.
                            browse(if (root == null || root.path == path) null else File(path).parent)
                        }
                    }
                    item {
                        BrowserRow(stringResource(R.string.files_new_folder), GalleryIcons.Add, path == destination) {
                            if (path == requestedPath) creating = true
                        }
                    }
                    if (folders.isEmpty()) item {
                        Text(
                            stringResource(R.string.folder_browser_empty),
                            style = GalleryTheme.type.meta,
                            color = GalleryTheme.colors.textFaint,
                            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = 12.dp),
                        )
                    }
                    items(folders, key = File::getPath) { folder ->
                        BrowserRow(folder.name, GalleryIcons.Folder) { browse(folder.path) }
                    }
                }
            }
        }
        DialogButtons(
            enabled = selectable,
            onDismiss = onDismiss,
            onConfirm = { if (path == requestedPath) path?.let(onPick) },
            confirmLabel = stringResource(R.string.folder_browser_select),
            modifier = Modifier.padding(horizontal = Spacing.screen).padding(bottom = Spacing.base),
        )
    }

    if (creating && path != null) TextDialog(
        title = stringResource(R.string.files_new_folder),
        initial = "",
        isValid = { validFolderName(it.trim()) },
        onConfirm = { name ->
            creating = false
            val folder = File(path, name.trim())
            scope.launch {
                val created = withContext(Dispatchers.IO) { folder.mkdir() }
                if (created) {
                    if (requestedPath == path) browse(folder.path)
                } else Toast.makeText(context, R.string.folder_browser_create_failed, Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { creating = false },
    )
}

private data class OpenedFolder(val path: String, val folders: List<File>)

private suspend fun listFolders(path: String, isRoot: Boolean, excluded: String?): Result<List<File>> = withContext(Dispatchers.IO) {
    runCatching {
        checkNotNull(File(path).listFiles())
            .filter { it.isDirectory && !it.name.startsWith('.') && !(isRoot && it.name == "Android") }
            .filterNot { folder -> excluded != null && folder.path.isWithin(excluded) }
            .sortedBy { it.name.lowercase() }
    }
}

private fun String.isWithin(parent: String): Boolean {
    val base = parent.trimEnd('/')
    return this == base || startsWith("$base/")
}

@Composable
private fun BrowserRow(title: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.iconSize(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = GalleryTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
