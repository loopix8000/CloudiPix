package app.cloudipix.gallery.core.sync

import app.cloudipix.gallery.core.model.hasHiddenFolderSegment
import app.cloudipix.gallery.core.remote.RemoteFolderSync
import app.cloudipix.gallery.core.remote.SYNC_FILE_PREFIX
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal interface LocalCopyStorage {
    val treeId: String
    fun list(id: String): List<LocalDocument>
    suspend fun fingerprint(file: LocalDocument): String
    fun delete(id: String)
    fun removeEmptyDirectory(id: String)
}

internal suspend fun LocalCopyStorage.removeSyncedCopies(location: RemoteFolderSync, files: Map<String, SyncedFile>) {
    if (location.keepLocalFiles) return
    val prefix = if (treeId.endsWith(':')) treeId else treeId.trimEnd('/') + "/"
    val mirrorId = location.localId
    val parts = mirrorId.removePrefix(prefix).split('/')
    val expectedDepth = if (location.directChild) 1 else 2
    if (!mirrorId.startsWith(prefix) || parts.size != expectedDepth || !parts.all(::validSyncName)) {
        throw IOException("로컬 동기화 폴더 경로를 확인할 수 없습니다.")
    }
    // 경로를 먼저 검증해 `..`처럼 루트를 벗어나는 경로가 숨김 폴더로 취급되어 통과하지 않게 한다.
    if (mirrorId.substringAfter(':').hasHiddenFolderSegment()) return
    var parentId = treeId
    for (part in parts) {
        val childId = if (parentId.endsWith(':')) parentId + part else "${parentId.trimEnd('/')}/$part"
        if (list(parentId).none { it.directory && it.id == childId }) return
        parentId = childId
    }

    val parents = mutableSetOf<String>()
    for (path in files.keys) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && parents.add(parent)) parent = parent.substringBeforeLast('/', "")
    }
    val draftPrefix = Regex.escape(".${SYNC_FILE_PREFIX}${location.id}-")
    val draftName = Regex("${draftPrefix}[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.part")
    var failure: IOException? = null

    suspend fun clean(directory: String, relative: String, depth: Int): Boolean {
        var removedFiles = false
        for (entry in list(directory)) {
            currentCoroutineContext().ensureActive()
            if (!validSyncName(entry.name) || entry.id != "$directory/${entry.name}") continue
            val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            if (location.excludesSubfolder(path)) continue
            try {
                if (entry.directory) {
                    if (depth < 64 && !entry.name.startsWith('.') && clean(entry.id, path, depth + 1)) removedFiles = true
                    continue
                }
                if (!draftName.matches(entry.name)) {
                    val synced = files[path] ?: continue
                    if (entry.size != synced.local.size || fingerprint(entry) != synced.hash) continue
                }
                delete(entry.id)
                removedFiles = true
            } catch (error: IOException) {
                failure = error
            } catch (error: SecurityException) {
                failure = IOException("로컬 복사본을 정리할 권한이 없습니다.", error)
            }
        }
        if ((directory == mirrorId || removedFiles || relative in parents) && list(directory).isEmpty()) {
            removeEmptyDirectory(directory)
        }
        return removedFiles
    }

    clean(mirrorId, "", 0)
    failure?.let { throw it }
}
