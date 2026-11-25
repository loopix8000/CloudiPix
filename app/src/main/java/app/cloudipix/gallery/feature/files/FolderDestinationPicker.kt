package app.cloudipix.gallery.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.sync.AlbumSyncState
import app.cloudipix.gallery.feature.folders.FolderCard
import app.cloudipix.gallery.feature.security.LocalGalleryAccess
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.galleryGridCells
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

private class PickerAlbum(val folder: Folder, val destination: FolderEntry?) {
    val key: Long get() = destination?.bucketId ?: folder.bucketId
}

/**
 * 폴더 화면과 같은 썸네일 그리드로 사진을 옮기거나 복사할 폴더를 고른다.
 * 숨긴 폴더도 따로 모아 보여 주고, 대상이 될 수 없는 폴더는 흐리게 표시한다.
 */
@Composable
internal fun FolderDestinationPicker(
    title: String,
    subtitle: String,
    folders: PickerFolders?,
    destinations: List<FolderEntry>,
    syncStates: Map<Long, AlbumSyncState>,
    onPick: (FolderEntry) -> Unit,
    onBrowseDevice: () -> Unit,
    onNewFolder: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        PickerSystemBars()
        val colors = GalleryTheme.colors
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(GalleryIcons.Back, onClick = onDismiss, label = stringResource(R.string.action_close))
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title, style = GalleryTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = GalleryTheme.type.meta, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(GalleryIcons.Folder, onClick = onBrowseDevice, label = stringResource(R.string.sync_pick_location))
                if (onNewFolder != null) {
                    IconAction(GalleryIcons.Add, onClick = onNewFolder, label = stringResource(R.string.files_new_folder))
                }
            }
            if (folders != null) PickerGrid(folders, destinations, syncStates, onPick)
        }
    }
}

@Composable
private fun PickerGrid(
    folders: PickerFolders,
    destinations: List<FolderEntry>,
    syncStates: Map<Long, AlbumSyncState>,
    onPick: (FolderEntry) -> Unit,
) {
    val settings = folders.settings
    val access = LocalGalleryAccess.current
    val (shown, hidden) = remember(folders, destinations) {
        val byId = destinations.associateBy(FolderEntry::bucketId)
        val listed = (folders.shown + folders.hidden).mapTo(hashSetOf(), Folder::bucketId)
        // 앨범으로 보이지 않는 대상은 사진이 있을 때만 표지 없이 뒤에 붙인다. 빈 폴더는 새 폴더·저장 위치 선택으로 고른다.
        val extras = destinations.filter { it.bucketId !in listed && it.itemCount > 0 }.map { entry ->
            PickerAlbum(entry.asFolder(), entry)
        }
        val visible = folders.shown.filterNot(Folder::isVirtual).map { PickerAlbum(it, byId[it.bucketId]) } + extras
        visible to folders.hidden.map { PickerAlbum(it, byId[it.bucketId]) }
    }
    val gap = settings.thumbnailSpacing.gap
    val captioned = settings.showFolderNames || settings.showFolderCount || settings.showFolderSize
    val rowGap = if (captioned && settings.detailsPlacement == DetailsPlacement.Below) gap + 14.dp else gap

    @Composable
    fun Card(album: PickerAlbum) {
        val folder = album.folder
        val destination = album.destination
        FolderCard(
            folder = folder,
            settings = settings,
            locked = folder.isLocked && !access.hasFolderAccess(folder.bucketId),
            syncState = syncStates[folder.bucketId],
            modifier = if (destination != null) {
                Modifier.clickable { onPick(destination) }
            } else {
                Modifier.alpha(0.38f).semantics { disabled() }
            },
        )
    }

    LazyVerticalGrid(
        columns = galleryGridCells(settings.folderColumns, gap),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gap, end = gap, bottom = Spacing.base),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        items(shown, key = PickerAlbum::key) { Card(it) }
        if (hidden.isNotEmpty()) {
            item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.setting_ignored),
                    style = GalleryTheme.type.label,
                    color = GalleryTheme.colors.textMuted,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp).semantics { heading() },
                )
            }
            items(hidden, key = PickerAlbum::key) { Card(it) }
        }
    }
}

private fun FolderEntry.asFolder() = Folder(
    bucketId = bucketId,
    name = name,
    itemCount = itemCount,
    size = 0L,
    dateTaken = 0L,
    dateModified = 0L,
    coverId = 0L,
    coverType = 0,
)

@Composable
private fun PickerSystemBars() {
    val view = LocalView.current
    val light = GalleryTheme.colors.background.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
