package app.cloudipix.gallery.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.sync.AllFolderSyncHistory
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CloudSyncHistoryViewModel @Inject constructor(sync: FolderSync) : ViewModel() {
    internal val history = sync.allRunHistory()
        .stateIn<AllFolderSyncHistory?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
internal fun CloudSyncHistoryScreen(onBack: () -> Unit, viewModel: CloudSyncHistoryViewModel = hiltViewModel()) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val items = history?.items.orEmpty()
    val hasReadError = history?.hasReadError == true
    val showPartialError = hasReadError && items.isNotEmpty()
    val context = LocalContext.current
    val colors = GalleryTheme.colors

    LaunchedEffect(showPartialError) {
        if (showPartialError) Toast.makeText(context, R.string.sync_history_partial_error, Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ServerHeader(R.string.sync_all_history_title, onBack)
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
            if (items.isEmpty()) {
                item {
                    val message = when {
                        history == null -> R.string.sync_history_loading
                        hasReadError -> R.string.sync_history_error
                        else -> R.string.sync_history_empty
                    }
                    Column(Modifier.padding(vertical = 10.dp)) { SyncText(stringResource(message)) }
                }
            }
            items(items) { item ->
                HorizontalDivider(color = colors.hairline)
                SyncHistoryRow(item.entry, item.folderLabel)
            }
        }
    }
}
