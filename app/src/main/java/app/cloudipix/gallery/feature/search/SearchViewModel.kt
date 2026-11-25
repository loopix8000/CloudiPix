package app.cloudipix.gallery.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** 입력이 멈춘 뒤 검색을 시작하기까지 기다리는 시간입니다. */
private const val TYPING_PAUSE_MS = 200L

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val input = MutableStateFlow("")
    val query: StateFlow<String> = input.asStateFlow()
    val pendingRemovals = repository.pendingRemovals

    /** 입력이 잠시 멈추면 앞뒤 공백을 뺀 검색어로 찾고, 새 입력이 오면 이전 검색은 취소합니다. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<Medium>> = input
        .debounce(TYPING_PAUSE_MS)
        .flatMapLatest(::matching)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val gallerySettings: StateFlow<GallerySettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GallerySettings())

    fun setQuery(value: String) {
        input.value = value
    }

    private fun matching(text: String): Flow<List<Medium>> {
        val term = text.trim()
        return if (term.isEmpty()) flowOf(emptyList()) else repository.search(term)
    }
}
