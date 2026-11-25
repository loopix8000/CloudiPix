package app.cloudipix.gallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.ui.component.CheckBox
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.folderPathLabel
import app.cloudipix.gallery.ui.formatCount
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 갤러리에서 숨길 폴더를 고르는 화면의 상태입니다. [edited]는 저장하기 전 사용자가 고친 선택이며,
 * 아직 고치지 않았으면 `null`이라 저장된 [ignored]를 그대로 보여 줍니다.
 */
data class IgnoredFoldersUiState(
    val folders: List<FolderEntry> = emptyList(),
    val ignored: Set<Long> = emptySet(),
    val edited: Set<Long>? = null,
) {
    val checked: Set<Long> get() = edited ?: ignored

    /** 저장할 변경이 있는지입니다. 고쳤다가 원래대로 돌리면 변경이 없습니다. */
    val dirty: Boolean get() = edited != null && edited != ignored
}

@HiltViewModel
class IgnoredFoldersViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val draft = MutableStateFlow<Set<Long>?>(null)
    private val saved = repository.ignoredFolders.map { folders -> folders.mapTo(HashSet()) { it.bucketId } }

    val state: StateFlow<IgnoredFoldersUiState> =
        combine(repository.folderEntries, saved, draft) { folders, ignored, edited -> IgnoredFoldersUiState(folders, ignored, edited) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IgnoredFoldersUiState())

    /**
     * 폴더 선택을 뒤집습니다. 화면 상태가 갱신되기 전에 연달아 눌러도 앞의 선택이 사라지지 않도록
     * 초안 값을 기준으로 원자적으로 고칩니다.
     */
    fun toggle(bucketId: Long) {
        draft.update { current ->
            val base = current ?: state.value.ignored
            if (bucketId in base) base - bucketId else base + bucketId
        }
    }

    fun save() = viewModelScope.launch {
        val snapshot = state.first()
        repository.setIgnoredFolders(snapshot.folders.filter { it.bucketId in snapshot.checked })
        draft.value = null
    }
}

private val SaveButtonShape = RoundedCornerShape(ControlRadius)

@Composable
fun IgnoredFoldersScreen(onBack: () -> Unit, viewModel: IgnoredFoldersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = GalleryTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = Spacing.screen, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(GalleryIcons.Back, onClick = onBack)
            Text(stringResource(R.string.ignored_title), style = GalleryTheme.type.title, color = colors.text)
            Spacer(Modifier.weight(1f))
            SaveButton(enabled = state.dirty, onClick = viewModel::save)
        }

        Text(
            stringResource(R.string.ignored_hint, state.checked.size),
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.base),
            style = GalleryTheme.type.meta,
            color = colors.textMuted,
        )

        val checked = state.checked
        LazyColumn {
            items(state.folders, key = { it.bucketId }) { folder ->
                FolderRow(folder, hidden = folder.bucketId in checked) { viewModel.toggle(folder.bucketId) }
            }
        }
    }
}

/** 바꾼 내용이 있을 때만 강조색으로 바뀌며 눌리는 저장 버튼입니다. */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Text(
        stringResource(R.string.action_save),
        modifier = Modifier
            .clip(SaveButtonShape)
            .background(if (enabled) colors.accent else colors.textFaint)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = GalleryTheme.type.label,
        color = colors.onAccent,
    )
}

/** 체크 상자, 폴더 이름과 경로, 항목 수를 한 줄에 보여 줍니다. 숨긴 폴더는 이름을 흐리게 씁니다. */
@Composable
private fun FolderRow(folder: FolderEntry, hidden: Boolean, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CheckBox(hidden)
        Column(Modifier.weight(1f)) {
            Text(
                folder.name,
                color = if (hidden) colors.textMuted else colors.text,
                style = GalleryTheme.type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.path.isNotEmpty()) {
                Text(
                    folderPathLabel(folder),
                    color = colors.textFaint,
                    style = GalleryTheme.type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(formatCount(folder.itemCount), style = GalleryTheme.type.meta, color = colors.textMuted)
    }
}
