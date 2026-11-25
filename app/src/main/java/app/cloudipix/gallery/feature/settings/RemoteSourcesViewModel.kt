package app.cloudipix.gallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cloudipix.gallery.core.cache.CacheUsage
import app.cloudipix.gallery.core.remote.RemoteFailure
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.RemoteImageCache
import app.cloudipix.gallery.core.remote.RemoteProtocol
import app.cloudipix.gallery.core.remote.RemoteRequestException
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.SyncFileFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteSourcesViewModel @Inject constructor(
    private val gallery: RemoteGallery,
    private val cache: RemoteImageCache,
    private val folderSync: FolderSync,
) : ViewModel() {
    val servers = gallery.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, 0), null)
    internal val fileFilters = gallery.fileFilters

    private val pending = MutableStateFlow(false)
    val busy = pending.asStateFlow()
    private val problem = MutableStateFlow<RemoteFailure?>(null)
    val error = problem.asStateFlow()
    private val usage = MutableStateFlow<CacheUsage?>(null)
    internal val cacheUsage = usage.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                usage.value = cache.usage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                problem.value = RemoteFailure.Storage
            }
        }
    }

    fun connect(
        id: String?,
        name: String,
        protocol: RemoteProtocol,
        address: String,
        username: String,
        password: String,
        onConnected: (String) -> Unit,
    ) = runAction {
        onConnected(folderSync.saveServer(name, protocol, address, username, password, id))
    }

    fun refresh(id: String) = runAction {
        gallery.refresh(id)
        usage.value = cache.usage()
    }

    fun remove(id: String, onRemoved: () -> Unit) = runAction {
        if (folderSync.removeServer(id)) onRemoved() else problem.value = RemoteFailure.Storage
    }

    fun clearCache() = runAction {
        usage.value = cache.clear()
    }

    fun clearError() { problem.value = null }

    internal fun setGlobalFileFilter(filter: SyncFileFilter) = runAction { folderSync.setGlobalFileFilter(filter) }

    fun setCacheLimit(limitMiB: Long, onSaved: () -> Unit) = runAction {
        usage.value = cache.resize(limitMiB)
        onSaved()
    }

    private fun runAction(action: suspend () -> Unit) {
        if (pending.value) return
        pending.value = true
        problem.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteRequestException) {
                problem.value = error.failure
            } catch (_: Exception) {
                problem.value = RemoteFailure.Storage
            } finally {
                pending.value = false
            }
        }
    }
}
