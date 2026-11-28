package app.cloudipix.gallery.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.Medium
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 입력이 멈춘 뒤 검색을 시작하기까지 기다리는 시간입니다. */
private const val TYPING_PAUSE_MS = 200L

private const val ACTIVE_KEY = "search_active"
private const val QUERY_KEY = "search_query"

/**
 * 폴더·앨범 화면의 제목 줄에서 여는 검색 상태입니다.
 * 화면 항목마다 하나씩 생기며, 사진을 보고 돌아오거나 화면이 다시 만들어져도 검색 중 상태와 검색어를 유지합니다.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    /** 제목 줄이 검색 입력란으로 바뀌어 있는지입니다. */
    val active: StateFlow<Boolean> = saved.getStateFlow(ACTIVE_KEY, false)
    val query: StateFlow<String> = saved.getStateFlow(QUERY_KEY, "")
    val pendingRemovals = repository.pendingRemovals

    /** 앨범 안에서 연 검색이면 그 앨범에 보이는 항목입니다. `null`이면 전체 보관함에서 찾습니다. */
    private val scope = MutableStateFlow<Flow<List<Medium>>?>(null)

    /**
     * 앞뒤 공백을 뺀 검색어로 찾고, 새 입력이 오면 이전 검색은 취소합니다.
     * 전체 검색은 입력이 잠시 멈춘 뒤 찾고, 앨범 안 검색은 이미 불러온 항목을 거르므로 바로 찾습니다.
     * 새 결과가 오기 전까지는 직전 결과를 그대로 두어 입력 중에 '결과 없음'이 깜빡이지 않게 합니다.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchMatches> = combine(query, scope, ::Pair)
        .debounce { (_, within) -> if (within == null) TYPING_PAUSE_MS else 0L }
        .flatMapLatest { (text, within) -> matching(text, within) }
        .stateIn(viewModelScope, SharingStarted.Lazily, SearchMatches.None)

    /**
     * 이 검색을 [items] 안으로 한정합니다. 앨범 화면이 처음 그려질 때 결과를 구독하기 전에 부릅니다.
     * 화면이 다시 그려져도 처음 정한 범위를 유지합니다.
     */
    fun searchWithin(items: Flow<List<Medium>>) {
        if (scope.value == null) scope.value = items
    }

    fun open() {
        saved[ACTIVE_KEY] = true
    }

    /** 검색을 닫으면 검색어도 비워 다음에 열 때 원래 화면부터 보이게 합니다. */
    fun close() {
        saved[QUERY_KEY] = ""
        saved[ACTIVE_KEY] = false
    }

    fun setQuery(value: String) {
        saved[QUERY_KEY] = value
    }

    private fun matching(text: String, within: Flow<List<Medium>>?): Flow<SearchMatches> {
        val term = text.trim()
        return when {
            term.isEmpty() -> flowOf(SearchMatches.None)
            // 전체 검색과 같이 파일 이름에 검색어가 들어 있으면 찾고, 앨범에 보이는 순서를 그대로 둡니다.
            within != null -> within.map { items ->
                SearchMatches(term, items.filter { it.name.contains(term, ignoreCase = true) })
            }.flowOn(Dispatchers.Default)
            else -> repository.search(term).map { SearchMatches(term, it) }
        }
    }
}

/** [term]으로 찾은 항목입니다. [term]이 비어 있으면 아직 찾은 적이 없다는 뜻입니다. */
data class SearchMatches(val term: String, val items: List<Medium>) {
    companion object {
        val None = SearchMatches("", emptyList())
    }
}
