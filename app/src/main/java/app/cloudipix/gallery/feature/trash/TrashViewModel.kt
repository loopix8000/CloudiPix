package app.cloudipix.gallery.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class TrashUiState(
    val items: List<Medium> = emptyList(),
    /** 휴지통에 아직 남아 있는 항목 중 선택한 ID입니다. */
    val selected: Set<Long> = emptySet(),
    val settings: GallerySettings = GallerySettings(),
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    repository: MediaRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 사용자가 고른 ID입니다. 복원·삭제로 사라진 항목은 화면 상태를 만들 때 걸러냅니다. */
    private val picked = MutableStateFlow(emptySet<Long>())

    val state: StateFlow<TrashUiState> =
        combine(repository.trash, picked, settingsRepository.settings) { items, ids, settings ->
            val selected = if (ids.isEmpty()) ids else items.mapNotNullTo(HashSet()) { item -> item.id.takeIf { it in ids } }
            TrashUiState(items, selected, settings)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun toggle(id: Long) {
        picked.update { ids -> if (id in ids) ids - id else ids + id }
    }

    fun selectAll() {
        picked.value = state.value.items.mapTo(HashSet()) { it.id }
    }

    fun clearSelection() {
        picked.value = emptySet()
    }

    fun selectedMedia(): List<Medium> {
        val ids = picked.value
        return state.value.items.filter { it.id in ids }
    }
}
