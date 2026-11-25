package app.cloudipix.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 액티비티가 테마를 적용하고 각 화면에 넘겨주는 전체 설정입니다.
 * 저장된 테마를 가능한 한 빨리 반영하도록 화면이 구독하기 전부터 설정을 읽기 시작합니다.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<GallerySettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = GallerySettings(),
    )
}
