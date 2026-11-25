package app.cloudipix.gallery.feature.folders

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.FolderTab
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.folderPathLabel
import app.cloudipix.gallery.ui.component.shadowed
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun FolderTabStrip(
    tabs: List<FolderTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = GalleryTheme.colors
    val inactiveColor = if (colors.background.luminance() < 0.5f) {
        lerp(colors.textMuted, colors.text, 0.5f)
    } else colors.textMuted
    val scroll = rememberLazyListState()
    LaunchedEffect(selected, tabs) {
        val layout = scroll.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == selected }
        if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset) {
            scroll.animateScrollToItem(selected)
        }
    }
    LazyRow(modifier.selectableGroup(), state = scroll, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
            val active = index == selected
            val style = if (tabs.size == 1) GalleryTheme.type.display else GalleryTheme.type.title
            Box(
                Modifier.widthIn(max = 180.dp).heightIn(min = GalleryTheme.hitTarget)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                // 선택 상태의 글자 너비를 확보해 탭 전환 시 옆 탭이 밀리지 않게 한다.
                Text(
                    tab.name,
                    modifier = Modifier.clearAndSetSemantics { }.drawWithContent { },
                    style = style,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tab.name,
                    style = style.shadowed(overlay),
                    fontSize = if (active) style.fontSize else style.fontSize * 0.85f,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) colors.text else inactiveColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun FolderTabDestinations(
    tabs: List<FolderTab>,
    sourceId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onManageTabs: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogCard(stringResource(R.string.tab_move), onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 8.dp)) {
            items(tabs.filterNot { it.id == sourceId }, key = { it.id }) { tab ->
                Text(tab.name, style = GalleryTheme.type.body, color = GalleryTheme.colors.text,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(tab.id) }.padding(vertical = 12.dp))
            }
            if (tabs.size == 1) item {
                Text(stringResource(R.string.tabs_manage), style = GalleryTheme.type.label, color = GalleryTheme.colors.accent,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onManageTabs).padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
internal fun FolderTabManager(tabs: List<FolderTab>, viewModel: FoldersViewModel, onDismiss: () -> Unit) {
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = GalleryTheme.colors
    val deleting = tabs.firstOrNull { it.id == deleteId }
    val editing = editId
    val backToList: () -> Unit = {
        if (!saving) {
            editId = null
            deleteId = null
        }
    }

    fun saveChanges(write: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                write()
                editId = null
                deleteId = null
            } catch (_: IOException) {
                Toast.makeText(context, R.string.metadata_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    when {
        editing != null -> {
            val isHome = editing == FolderTab.Home.id
            val defaultName = stringResource(R.string.tab_home)
            val folders = if (isHome) emptyList() else {
                val favorites = FolderEntry(FAVORITES_BUCKET_ID, stringResource(R.string.folder_favorites), "", 0)
                listOf(favorites) + viewModel.tabFolders.collectAsStateWithLifecycle().value
            }
            EditFolderTab(
                id = editing, tabs = tabs, folders = folders, saving = saving,
                onDismiss = backToList,
                onSave = { tab ->
                    saveChanges {
                        if (isHome) viewModel.renameHomeTab(tab.name.takeUnless { it == defaultName })
                        else viewModel.saveTab(tab)
                    }
                },
            )
        }
        deleting != null -> DialogCard(stringResource(R.string.tab_delete), onDismiss = backToList) {
            Text(stringResource(R.string.tab_delete_prompt, deleting.name), style = GalleryTheme.type.body,
                color = colors.text, modifier = Modifier.padding(top = 12.dp))
            DialogButtons(enabled = !saving, onDismiss = backToList, confirmLabel = stringResource(R.string.action_delete),
                onConfirm = { saveChanges { viewModel.deleteTab(deleting.id) } })
        }
        else -> DialogCard(stringResource(R.string.tabs_manage), onDismiss = { if (!saving) onDismiss() }) {
            FolderTabList(
                tabs = tabs,
                enabled = !saving,
                onEdit = { editId = it },
                onDelete = { deleteId = it },
                onMove = { id, offset -> saveChanges { viewModel.moveTab(id, offset) } },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 8.dp),
            )
            DialogButtons(enabled = !saving, onDismiss = { if (!saving) onDismiss() }, dismissLabel = stringResource(R.string.action_close),
                confirmLabel = stringResource(R.string.tab_add), onConfirm = { editId = UUID.randomUUID().toString() })
        }
    }
}

@Composable
private fun FolderTabList(
    tabs: List<FolderTab>,
    enabled: Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    modifier: Modifier,
) {
    val colors = GalleryTheme.colors
    val scroll = rememberScrollState()
    val rowHeight = GalleryTheme.hitTarget
    val rowPx = with(LocalDensity.current) { rowHeight.roundToPx().toFloat() }
    val moveTab by rememberUpdatedState(onMove)
    val tabIds = tabs.map { it.id }
    var draggedId by remember(tabIds) { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var grabY by remember { mutableFloatStateOf(0f) }
    var droppedOrder by remember(tabs) { mutableStateOf<List<FolderTab>?>(null) }
    val source = draggedId?.let(tabIds::indexOf) ?: -1
    fun targetIndex() = (source + dragOffset / rowPx).roundToInt().coerceIn(tabs.indices)
    val target = if (source >= 0) targetIndex() else -1
    val orderedTabs = remember(tabs, source, target, droppedOrder) {
        if (source >= 0 && target != source) {
            tabs.toMutableList().apply { add(target, removeAt(source)) }
        } else {
            droppedOrder ?: tabs
        }
    }

    LaunchedEffect(enabled) {
        if (enabled) droppedOrder = null
    }
    LaunchedEffect(draggedId, tabIds, rowPx) {
        if (source < 0) return@LaunchedEffect
        while (draggedId != null) {
            withFrameNanos { }
            val height = scroll.viewportSize.toFloat()
            val edge = minOf(rowPx, height / 2)
            if (edge <= 0f) continue
            // 목록 좌표를 보이는 영역의 좌표로 바꿔 가장자리까지의 거리를 계산한다.
            val y = source * rowPx + dragOffset + grabY - scroll.value
            val direction = when {
                y < edge -> (y - edge) / edge
                y > height - edge -> (y - height + edge) / edge
                else -> 0f
            }
            if (direction != 0f) {
                dragOffset += scroll.scrollBy(direction.coerceIn(-1f, 1f) * rowPx / 4)
            }
        }
    }

    Column(modifier.verticalScroll(scroll, enabled = draggedId == null)) {
        orderedTabs.forEachIndexed { index, tab ->
            key(tab.id) {
                val dragging = draggedId == tab.id
                val actionsEnabled = enabled && draggedId == null
                Row(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) {
                                val position = (source * rowPx + dragOffset).coerceIn(0f, tabs.lastIndex * rowPx)
                                position - index * rowPx
                            } else 0f
                        }
                        .then(if (dragging) Modifier.background(colors.surface) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tabs.size > 1) {
                        val up = stringResource(R.string.tab_move_up_named, tab.name)
                        val down = stringResource(R.string.tab_move_down_named, tab.name)
                        Box(
                            Modifier.size(rowHeight)
                                .semantics {
                                    contentDescription = tab.name
                                    customActions = buildList {
                                        if (actionsEnabled && index > 0) add(CustomAccessibilityAction(up) { moveTab(tab.id, -1); true })
                                        if (actionsEnabled && index < tabs.lastIndex) add(CustomAccessibilityAction(down) { moveTab(tab.id, 1); true })
                                    }
                                }
                                .pointerInput(tab.id, tabIds, rowPx, enabled) {
                                    if (!enabled) return@pointerInput
                                    val from = tabIds.indexOf(tab.id)
                                    detectVerticalDragGestures(
                                        onDragStart = { point ->
                                            dragOffset = 0f
                                            grabY = point.y
                                            draggedId = tab.id
                                        },
                                        onVerticalDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount
                                        },
                                        onDragCancel = { draggedId = null },
                                        onDragEnd = {
                                            val to = (from + dragOffset / rowPx).roundToInt().coerceIn(tabs.indices)
                                            draggedId = null
                                            if (to != from) {
                                                droppedOrder = tabs.toMutableList().apply { add(to, removeAt(from)) }
                                                moveTab(tab.id, to - from)
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(GalleryIcons.Grip, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(
                        Modifier.weight(1f).height(rowHeight)
                            .clickable(enabled = actionsEnabled, onClickLabel = stringResource(R.string.tab_edit)) { onEdit(tab.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.base),
                    ) {
                        Text(tab.name, style = GalleryTheme.type.body, color = colors.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(GalleryIcons.Edit, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    }
                    if (tab.id != FolderTab.Home.id) {
                        IconAction(GalleryIcons.Trash, onClick = { onDelete(tab.id) },
                            label = stringResource(R.string.tab_delete_named, tab.name), enabled = actionsEnabled)
                    } else if (tabs.size > 1) {
                        Spacer(Modifier.size(rowHeight))
                    }
                }
            }
        }
    }
}

private val folderIdSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

@Composable
private fun EditFolderTab(
    id: String,
    tabs: List<FolderTab>,
    folders: List<FolderEntry>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (FolderTab) -> Unit,
) {
    val tab = tabs.firstOrNull { it.id == id }
    val colors = GalleryTheme.colors
    var name by rememberSaveable(id) { mutableStateOf(tab?.name.orEmpty()) }
    var picked by rememberSaveable(id, stateSaver = folderIdSaver) { mutableStateOf(tab?.folderIds.orEmpty()) }
    val cleanName = name.trim()
    val duplicate = tabs.any { it.id != id && cleanName.equals(it.name, ignoreCase = true) }
    val nameLabel = stringResource(R.string.tab_name)

    DialogCard(stringResource(if (tab == null) R.string.tab_add else R.string.tab_edit), onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(nameLabel) },
                    textStyle = GalleryTheme.type.body,
                    shape = RoundedCornerShape(ControlRadius),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.controlOutline,
                        unfocusedBorderColor = colors.controlOutline,
                        focusedLabelColor = colors.text,
                        unfocusedLabelColor = colors.textMuted,
                        cursorColor = colors.accent,
                    ),
                )
                if (duplicate) {
                    Text(stringResource(R.string.tab_name_taken), style = GalleryTheme.type.body, color = colors.danger)
                }
                if (id != FolderTab.Home.id) {
                    Text(stringResource(R.string.section_folders), style = GalleryTheme.type.label, color = colors.textMuted,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                }
            }
            items(folders, key = { it.bucketId }) { folder ->
                val checked = folder.bucketId in picked
                Row(
                    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox) { on ->
                        picked = if (on) picked + folder.bucketId else picked - folder.bucketId
                    }.heightIn(min = GalleryTheme.hitTarget),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CheckBox(checked)
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = GalleryTheme.type.body, color = colors.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (folder.path.isNotEmpty()) {
                            Text(folderPathLabel(folder), style = GalleryTheme.type.meta, color = colors.textFaint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        DialogButtons(enabled = !saving && cleanName.isNotEmpty() && !duplicate, onDismiss = onDismiss,
            confirmLabel = stringResource(R.string.action_save), onConfirm = { onSave(FolderTab(id, cleanName, picked)) })
    }
}
