package app.cloudipix.gallery.core.settings

import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.model.isVirtualFolder
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.remote.remoteId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
internal data class TabFolderLocation(
    val path: String,
    val serverId: String? = null,
    val volumeName: String? = null,
)

internal data class TabFolderRestore(val settings: GallerySettings, val complete: Boolean = true)

internal class FolderTabBackup @Inject constructor(
    private val media: MediaRepository,
    private val remote: RemoteGallery,
) {
    suspend fun locations(settings: GallerySettings): Map<Long, TabFolderLocation> = withContext(Dispatchers.Default) {
        val (cloudIds, deviceIds) = settings.tabFolderIds().partition(::isRemoteId)
        buildMap {
            if (deviceIds.isNotEmpty()) {
                val ids = deviceIds.toHashSet()
                for (folder in media.deviceFoldersForBackup()) {
                    if (folder.bucketId in ids) put(folder.bucketId, TabFolderLocation(folder.path, volumeName = folder.volumeName))
                }
            }
            if (cloudIds.isNotEmpty()) {
                val ids = cloudIds.toHashSet()
                for (folder in remote.linkedFolders.first()) {
                    if (folder.bucketId in ids) put(folder.bucketId, TabFolderLocation(folder.folder.path, folder.serverId))
                }
            }
        }
    }

    suspend fun restore(backup: SettingsExport, serverIds: Map<String, String>): TabFolderRestore =
        withContext(Dispatchers.Default) {
            val settings = backup.settings
            val ids = settings.tabFolderIds()
            if (ids.isEmpty()) return@withContext TabFolderRestore(settings)
            val locations = backup.tabFolderLocations.filterKeys { it in ids }
            val deviceFolders = if (locations.values.any { it.serverId == null }) {
                media.deviceFoldersForBackup().groupBy { it.path }
            } else emptyMap()
            val resolved = mutableMapOf<Long, Long>()
            for ((id, location) in locations) {
                val server = location.serverId
                if (server == null) {
                    val candidates = deviceFolders[location.path].orEmpty()
                    val folder = candidates.firstOrNull { it.bucketId == id }
                        ?: candidates.singleOrNull { it.volumeName == location.volumeName }
                        ?: candidates.singleOrNull()
                    if (folder != null) resolved[id] = folder.bucketId
                } else {
                    val current = serverIds[server] ?: continue
                    resolved[id] = remoteId(current, location.path)
                }
            }
            restoreLegacyCloudIds(backup, ids.filterTo(mutableSetOf()) { isRemoteId(it) && it !in locations }, serverIds, resolved)
            TabFolderRestore(settings.remapTabFolders(resolved), complete = locations.keys.all { it in resolved })
        }

    private suspend fun restoreLegacyCloudIds(
        backup: SettingsExport,
        missing: MutableSet<Long>,
        serverIds: Map<String, String>,
        resolved: MutableMap<Long, Long>,
    ) {
        val changed = serverIds.filter { (previous, current) -> previous != current }
        if (missing.isEmpty() || changed.isEmpty()) return
        // 예전 백업에서도 동기화 설정에 경로가 남아 있으면 같은 폴더 ID를 다시 만들 수 있다.
        for (server in backup.sync?.cloudFolders.orEmpty()) {
            val current = changed[server.serverId] ?: continue
            if (server.accountKey == null) continue
            for (folder in server.folders) {
                val id = remoteId(server.serverId, folder.path)
                if (missing.remove(id)) resolved[id] = remoteId(current, folder.path)
            }
        }
        if (missing.isEmpty()) return
        val previousIds = changed.entries.groupBy({ it.value }, { it.key })
        for (folder in remote.linkedFolders.first()) {
            for (serverId in previousIds[folder.serverId].orEmpty()) {
                val id = remoteId(serverId, folder.folder.path)
                if (missing.remove(id)) resolved[id] = folder.bucketId
            }
            if (missing.isEmpty()) return
        }
    }
}

internal fun GallerySettings.tabFolderIds(): Set<Long> = buildSet {
    for (tab in folderTabs) tab.folderIds.filterNotTo(this, ::isVirtualFolder)
}

private fun GallerySettings.remapTabFolders(ids: Map<Long, Long>): GallerySettings {
    if (ids.none { (saved, current) -> saved != current }) return this
    return copy(
        folderTabs = folderTabs.map { tab -> tab.copy(folderIds = tab.folderIds.mapTo(mutableSetOf()) { ids[it] ?: it }) },
        folderOrder = folderOrder.map { ids[it] ?: it },
        folderMediaSettings = folderMediaSettings.mapKeys { (id, _) -> ids[id] ?: id },
    )
}
