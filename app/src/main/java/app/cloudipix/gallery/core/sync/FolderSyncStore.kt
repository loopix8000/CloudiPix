package app.cloudipix.gallery.core.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import app.cloudipix.gallery.core.database.SyncIndexDatabase
import app.cloudipix.gallery.core.database.ImportedSyncRoot
import app.cloudipix.gallery.core.database.SyncFileRow
import app.cloudipix.gallery.core.remote.RemoteFolder
import app.cloudipix.gallery.core.remote.RemoteFolderSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class SyncFileCounts(val unchanged: Int = 0, val uploaded: Int = 0, val downloaded: Int = 0, val deleted: Int = 0) {
    val completed: Int get() = unchanged + uploaded + downloaded + deleted
}

@Serializable
internal data class SyncConflictCounts(val device: Int = 0, val server: Int = 0, val both: Int = 0) {
    val total: Int get() = device + server + both
}

@Serializable
internal data class SyncSubfolderResult(
    val counts: SyncFileCounts = SyncFileCounts(),
    val conflicts: Int = 0,
    val resolvedConflicts: SyncConflictCounts = SyncConflictCounts(),
    val lastRun: Long? = null,
    val failed: Boolean? = null,
)

@Serializable
internal data class FolderSyncStatus(
    val lastRun: Long? = null,
    val conflicts: Int = 0,
    val failed: Boolean = false,
    val counts: SyncFileCounts? = null,
    val conflictPolicy: SyncConflictPolicy? = null,
    val resolvedConflicts: SyncConflictCounts = SyncConflictCounts(),
    // 연결된 하위 폴더별 집계. 키는 동기화 루트 기준 상대 경로다.
    val subfolders: Map<String, SyncSubfolderResult> = emptyMap(),
    val previousFullRun: Long? = null,
) {
    val lastFullRun: Long? get() = previousFullRun ?: lastRun
    val isComplete: Boolean get() = lastRun != null && !failed && conflicts == 0

    // 하위 폴더 집계가 없는 이전 결과는 상위 폴더의 합계를 대신 보여주지 않는다.
    fun forSubfolder(subfolder: String): FolderSyncStatus {
        val own = subfolders[subfolder]
        return copy(
            lastRun = own?.lastRun ?: if (own == null) lastFullRun else lastRun,
            conflicts = own?.conflicts ?: 0,
            failed = own?.failed ?: failed,
            counts = own?.counts,
            conflictPolicy = conflictPolicy?.takeIf { own != null },
            resolvedConflicts = own?.resolvedConflicts ?: SyncConflictCounts(),
            subfolders = emptyMap(),
        )
    }
}

@Serializable
internal data class FolderSyncHistoryEntry(
    val result: FolderSyncStatus,
    val automatic: Boolean? = null,
    val localLabel: String? = null,
    val durationMs: Long? = null,
)

@Serializable
private data class StoredSyncRuns(
    val latestSyncId: String? = null,
    val entries: List<FolderSyncHistoryEntry> = emptyList(),
    val rootStatus: FolderSyncStatus? = null,
)

private const val SYNC_HISTORY_LIMIT = 200

@Serializable
internal enum class SyncConflictPolicy { KeepBoth, PreferLocal, PreferRemote, PreferNewest, KeepBothVersions }

@Serializable
internal data class StoredSyncPreferences(
    val interval: Int = 60,
    // 초기 동기화 설정을 클라우드 연결 파일로 옮길 때 한 번 읽는다.
    @SerialName("folders") val legacyFolders: List<LegacySyncedFolder> = emptyList(),
    val defaultRoot: SyncRoot? = null,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.KeepBoth,
    val fileFilters: SyncFileFilterSettings = SyncFileFilterSettings(),
    val scheduledSyncEnabled: Boolean = true,
    // 켜면 데이터 요금이 드는 네트워크에서는 예약 실행의 파일 동기화를 건너뛴다.
    val scheduledSyncUnmeteredOnly: Boolean = false,
)

@Serializable
internal data class SyncRoot(val treeUri: String, val label: String)

@Serializable
internal data class LegacySyncedFolder(
    val id: String,
    val serverId: String,
    val remotePath: String,
    val remoteName: String,
    val treeUri: String,
    val localId: String,
    val localLabel: String,
    val lastRun: Long? = null,
    val conflicts: Int = 0,
    val failed: Boolean = false,
) {
    fun location() = RemoteFolderSync(id, treeUri, localId, localLabel)
    fun asFolder() = RemoteFolder(remotePath, remoteName, location())
    fun status() = FolderSyncStatus(lastRun, conflicts, failed)
}

@Singleton
internal class FolderSyncStore @Inject constructor(@ApplicationContext context: Context, private val db: SyncIndexDatabase) {
    private val index get() = db.syncIndexDao()
    private val directory = File(context.noBackupFilesDir, "folder-sync")
    private val settings = AtomicFile(File(directory, "settings.json"))
    private val cleanup = AtomicFile(File(directory, "local-cleanup.json"))
    private val runRevisions = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val historyRevisions = runRevisions.asStateFlow()
    private val currentFileFilters = MutableStateFlow<SyncFileFilterSettings?>(null)
    val fileFilters = currentFileFilters.filterNotNull().onStart {
        if (currentFileFilters.value == null) try {
            preferences()
        } catch (_: IOException) {
            emit(SyncFileFilterSettings())
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun preferences(): StoredSyncPreferences = settings.readOr(StoredSyncPreferences())
        .also { currentFileFilters.value = it.fileFilters }

    @Synchronized
    fun savePreferences(value: StoredSyncPreferences) {
        settings.write(value)
        currentFileFilters.value = value.fileFilters
    }

    fun pendingCleanup(): List<RemoteFolderSync> = cleanup.readOr(emptyList())

    fun savePendingCleanup(locations: List<RemoteFolderSync>) = cleanup.write(locations)

    @Synchronized
    fun loadStatus(folderId: Long, location: RemoteFolderSync, legacy: FolderSyncStatus?): FolderSyncStatus? {
        val file = runs(folderId)
        val saved = file.readOr(StoredSyncRuns())
        if (saved.latestSyncId == location.id) return saved.rootStatus ?: saved.entries.firstOrNull()?.result
        val previous = status(location.id).readOr<FolderSyncStatus?>(null) ?: legacy
        if (previous?.lastRun != null) {
            file.write(saved.prepend(location.id, FolderSyncHistoryEntry(previous, localLabel = location.localLabel)))
            historyChanged(folderId)
        }
        return previous
    }

    @Synchronized
    fun saveResult(folderId: Long, syncId: String, entry: FolderSyncHistoryEntry, rootStatus: FolderSyncStatus? = null) {
        val file = runs(folderId)
        val saved = try {
            file.readOr(StoredSyncRuns())
        } catch (error: IOException) {
            if (error.cause !is IllegalArgumentException) throw error
            StoredSyncRuns()
        }
        file.write(saved.prepend(syncId, entry).copy(rootStatus = rootStatus))
        historyChanged(folderId)
    }

    @Synchronized
    fun readRuns(folderId: Long): List<FolderSyncHistoryEntry> = runs(folderId).readOr(StoredSyncRuns()).entries

    @Synchronized
    fun removeRuns(folderId: Long) {
        runs(folderId).delete()
        historyChanged(folderId)
    }

    fun historyFolderIds(): Set<Long>? = directory.list()?.mapNotNullTo(hashSetOf()) { name ->
        val base = name.removeSuffix(".bak")
        if (base.startsWith("runs-") && base.endsWith(".json")) {
            base.removePrefix("runs-").removeSuffix(".json").toLongOrNull()
        } else null
    }

    private fun historyChanged(folderId: Long) {
        runRevisions.update { it + (folderId to ((it[folderId] ?: 0L) + 1)) }
    }

    fun readFiles(id: String, paths: Set<String>? = null, subfolder: String? = null): SyncFileIndex = indexAccess {
        val legacy = fileIndex(id)
        if (legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists()) {
            db.runInTransaction {
                if (!index.imported(id)) {
                    val old = legacy.readOr(emptyMap<String, SyncedFile>())
                    old.entries.chunked(500).forEach { batch ->
                        index.putFiles(batch.mapNotNull { (path, file) -> file.row(id, path) })
                    }
                    index.markImported(ImportedSyncRoot(id))
                }
            }
            legacy.delete()
        }
        val rows = if (paths == null && subfolder == null) index.files(id) else {
            val keys = mutableSetOf<String>()
            for (path in paths ?: setOf(requireNotNull(subfolder))) {
                var key = syncKey(path)
                while (key.isNotEmpty() && keys.add(key)) key = key.substringBeforeLast('/', "")
            }
            val ancestorsAndFiles = keys.chunked(500).flatMap { index.files(id, it) }
            if (subfolder == null) ancestorsAndFiles else {
                // '/' 바로 다음 문자 '0'을 상한으로 써서 같은 접두사의 형제 폴더는 제외한다.
                val key = syncKey(subfolder)
                ancestorsAndFiles + index.filesInRange(id, "$key/", "${key}0")
            }
        }
        SyncFileIndex(rows.associateTo(mutableMapOf()) { it.path to SyncedFile(it.hash.hex(), it.local, it.remote) })
    }

    fun saveFiles(id: String, files: SyncFileIndex) = indexAccess {
        if (files.dirty.isNotEmpty()) {
            db.runInTransaction {
                for (batch in files.dirty.chunked(500)) {
                    // 해시 형식이 맞지 않는 기록은 이전 값이 남지 않도록 지운 것으로 처리한다.
                    val rows = batch.associateWith { path -> files[path]?.row(id, path) }
                    val removed = rows.filterValues { it == null }.keys.toList()
                    if (removed.isNotEmpty()) index.deleteFiles(id, removed)
                    index.putFiles(rows.values.filterNotNull())
                }
            }
            files.dirty.clear()
        }
    }

    fun remove(id: String) {
        fileIndex(id).delete()
        indexAccess {
            db.runInTransaction {
                index.clearFiles(id)
                index.forgetImport(id)
            }
        }
        status(id).delete()
    }

    private fun StoredSyncRuns.prepend(id: String, entry: FolderSyncHistoryEntry) =
        StoredSyncRuns(id, listOf(entry) + entries.take(SYNC_HISTORY_LIMIT - 1))

    private fun runs(folderId: Long) = AtomicFile(File(directory, "runs-$folderId.json"))
    private fun fileIndex(id: String) = AtomicFile(File(directory, "$id.json"))
    private fun status(id: String) = AtomicFile(File(directory, "status-$id.json"))
}

internal class SyncFileIndex(private val records: MutableMap<String, SyncedFile>) : Map<String, SyncedFile> by records {
    val dirty = mutableSetOf<String>()

    operator fun set(path: String, file: SyncedFile) {
        if (records.put(path, file) != file) dirty.add(path)
    }

    fun remove(path: String) {
        if (records.remove(path) != null) dirty.add(path)
    }
}

private fun SyncedFile.row(id: String, path: String) =
    hash.syncHashBytes()?.let { SyncFileRow(id, path, syncKey(path), it, local, remote) }

private inline fun <T> indexAccess(block: () -> T): T = try {
    block()
} catch (error: SQLiteException) {
    throw IOException("동기화 파일 기록에 접근할 수 없습니다.", error)
}

private inline fun <reified T> AtomicFile.readOr(default: T): T = try {
    Json.decodeFromString<T>(readFully().decodeToString())
} catch (error: FileNotFoundException) {
    if (baseFile.exists()) throw error
    default
} catch (error: IllegalArgumentException) {
    throw IOException("동기화 기록을 읽을 수 없습니다.", error)
}

private inline fun <reified T> AtomicFile.write(value: T) {
    val output = startWrite()
    try {
        output.write(Json.encodeToString(value).encodeToByteArray())
        finishWrite(output)
    } catch (error: Exception) {
        failWrite(output)
        throw error
    }
}
