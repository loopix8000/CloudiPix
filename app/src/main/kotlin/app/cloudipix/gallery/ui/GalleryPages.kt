package app.cloudipix.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.feature.files.FileAction
import app.cloudipix.gallery.feature.files.FileActions
import app.cloudipix.gallery.feature.search.SearchViewModel
import app.cloudipix.gallery.feature.trash.TrashViewModel
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.MediaThumbnail
import app.cloudipix.gallery.ui.component.VolumeGridScroll
import app.cloudipix.gallery.ui.component.galleryGridCells
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.formatDuration
import app.cloudipix.gallery.ui.formatSize

@Composable
internal fun PageFrame(color: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = color, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
    }
}

@Composable
internal fun PageHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(GalleryIcons.Back, stringResource(R.string.cloudipix_back)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium)
        }
        actions()
    }
    HorizontalDivider()
}

@Composable
internal fun SearchPage(
    onBack: () -> Unit,
    onOpenMedium: (Medium) -> Unit,
    photoTransition: @Composable (Long) -> Modifier,
    gridModifier: Modifier,
    model: SearchViewModel = hiltViewModel(),
) {
    val query by model.query.collectAsStateWithLifecycle()
    val results by model.results.collectAsStateWithLifecycle()
    val removing by model.pendingRemovals.collectAsStateWithLifecycle()
    val settings by model.gallerySettings.collectAsStateWithLifecycle()
    val visible = remember(results, removing) { results.filterNot { it.id in removing } }
    val focus = LocalFocusManager.current
    PageFrame {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GalleryIcons.Back, stringResource(R.string.cloudipix_back)) }
            OutlinedTextField(
                value = query,
                onValueChange = model::setQuery,
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                trailingIcon = if (query.isEmpty()) null else {
                    { IconButton(onClick = { model.setQuery("") }) { Icon(GalleryIcons.Close, stringResource(R.string.action_reset)) } }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (query.isNotBlank()) {
            Text(
                if (visible.isEmpty()) stringResource(R.string.search_no_matches)
                else stringResource(R.string.search_results, formatCount(visible.size)),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            MediaTiles(visible, settings, gridModifier, onSelect = { focus.clearFocus(); onOpenMedium(it) }, transition = photoTransition)
        }
    }
}

@Composable
internal fun TrashPage(onBack: () -> Unit, model: TrashViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf<FileAction?>(null) }
    val size = remember(state.items) { state.items.sumOf { it.size.coerceAtLeast(0L) } }
    val subtitle = stringResource(R.string.cloudipix_trash_summary, formatCount(state.items.size), formatSize(size))
    BackHandler(state.selecting && request == null) { model.clearSelection() }
    PageFrame {
        PageHeader(
            title = if (state.selecting) stringResource(R.string.selection_count, state.selected.size)
            else stringResource(R.string.trash_title),
            subtitle = subtitle,
            onBack = if (state.selecting) model::clearSelection else onBack,
        ) {
            if (state.items.isNotEmpty()) {
                TextButton(onClick = model::selectAll, enabled = state.selected.size < state.items.size) {
                    Text(stringResource(R.string.action_select_all))
                }
            }
        }
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.trash_empty)) }
        } else {
            Column(Modifier.fillMaxSize()) {
                MediaTiles(state.items, state.settings, Modifier.weight(1f).fillMaxWidth(), state.selected, onSelect = { model.toggle(it.id) })
                if (state.selecting) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { request = FileAction.Restore }) {
                            Icon(GalleryIcons.Undo, stringResource(R.string.action_restore))
                        }
                        IconButton(onClick = { request = FileAction.Delete }) {
                            Icon(GalleryIcons.Trash, stringResource(R.string.action_delete_forever))
                        }
                    }
                }
            }
        }
    }
    FileActions(request, model.selectedMedia(), onDone = { model.clearSelection(); request = null }, onDismiss = { request = null })
}

@Composable
private fun MediaTiles(
    items: List<Medium>,
    config: GallerySettings,
    modifier: Modifier,
    selection: Set<Long> = emptySet(),
    onSelect: (Medium) -> Unit,
    transition: @Composable (Long) -> Modifier = { Modifier },
) {
    val gap = config.thumbnailSpacing.gap
    val scroll = rememberLazyGridState()
    VolumeGridScroll(scroll, config.volumeButtonPaging)
    LazyVerticalGrid(
        columns = galleryGridCells(config.mediaColumns, gap),
        state = scroll,
        reverseLayout = config.scrollDirection == ScrollDirection.Up,
        modifier = modifier,
        contentPadding = PaddingValues(gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        items(items, key = Medium::id) { item ->
            val selected = item.id in selection
            Box(Modifier.clickable { onSelect(item) }.semantics { this.selected = selected }) {
                MediaThumbnail(item.displayUri, transition(item.id), config.cropThumbnails, description = item.name)
                if (selected) {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)))
                    Icon(GalleryIcons.Check, null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (config.showVideoDuration && item.type == MediaType.Video && item.durationMs > 0) {
                    Text(
                        formatDuration(item.durationMs),
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
