package app.cloudipix.gallery.core.sync

import android.os.SystemClock
import app.cloudipix.gallery.core.model.hasHiddenFolderSegment
import app.cloudipix.gallery.core.remote.RemoteEntry
import app.cloudipix.gallery.core.remote.RemoteFileChange
import app.cloudipix.gallery.core.remote.RemoteFailure
import app.cloudipix.gallery.core.remote.RemoteRequestException
import app.cloudipix.gallery.core.remote.RemoteReadStats
import app.cloudipix.gallery.core.remote.RemoteFolderSync
import app.cloudipix.gallery.core.remote.RemoteServer
import app.cloudipix.gallery.core.remote.RemoteTransport
import app.cloudipix.gallery.core.remote.RemoteDirectoryListings
import app.cloudipix.gallery.core.remote.SYNC_FILE_PREFIX
import app.cloudipix.gallery.core.remote.photoMime
import app.cloudipix.gallery.core.remote.restoredName
import app.cloudipix.gallery.core.remote.trashFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

private const val MAX_CONCURRENT_DOWNLOADS = 8
private const val CHECKPOINT_INTERVAL_MS = 30_000L
// 한 실행에서 원본을 읽어 내용을 비교하는 폴더 수. 목록 비교 자리와 따로 센다.
private const val MAX_CONTENT_COMPARISONS = 2
// 부분 동기화에서 한 폴더의 바뀐 이름이 이보다 많으면 이름마다 묻지 않고 폴더 목록을 한 번 읽는다.
private const val MAX_NAMED_REMOTE_LOOKUPS = 8

@Serializable
// [fileId]는 서버 쪽 기록에만 채운다. 서버에서 옮긴 파일을 알아볼 때 쓰며 기기 쪽 기록에서는 항상 null이다.
internal data class SyncStamp(val size: Long, val modified: Long, val etag: String? = null, val fileId: Long? = null)

@Serializable
internal data class SyncedFile(val hash: String, val local: SyncStamp, val remote: SyncStamp? = null) {
    fun matchesBoth(localNow: SyncStamp?, remoteNow: SyncStamp?): Boolean =
        local.modified > 0 && local == localNow && remote != null && remoteNow != null && remote.size == remoteNow.size &&
            (hashForRemote(remoteNow) != null || (remote.modified > 0 && remote.modified == remoteNow.modified &&
                (remote.etag == null || remote.etag == remoteNow.etag)))

    fun hashForRemote(stamp: SyncStamp?): String? {
        // 강한 ETag만 서버 파일의 내용이 그대로임을 보장한다.
        val tag = stamp?.etag ?: return null
        val saved = remote ?: return null
        return hash.takeIf { tag.length >= 2 && tag.first() == '"' && tag.last() == '"' &&
            saved.etag == tag && saved.size == stamp.size }
    }
}

internal fun SyncedFile?.canQueueDownload(localNow: SyncStamp?, remoteNow: SyncStamp): Boolean {
    if (this == null) return localNow == null
    return remote != null && localNow != null && localNow.modified > 0 && local == localNow &&
        !matchesBoth(localNow, remoteNow) && hashForRemote(remoteNow) == null
}

internal enum class SyncOutcome { Success, Failed, Retry }

internal enum class SyncFileAction { Forget, Record, Upload, Download, DeleteLocal, DeleteRemote, PreserveVersions, Conflict }

internal fun isSyncContentConflict(localHash: String?, remoteHash: String?, previousHash: String?): Boolean =
    localHash != null && remoteHash != null && localHash != remoteHash && localHash != previousHash && remoteHash != previousHash

internal fun syncFileAction(
    localHash: String?,
    remoteHash: String?,
    previousHash: String?,
    policy: SyncConflictPolicy,
    localModified: Long = 0L,
    remoteModified: Long = 0L,
): SyncFileAction = when {
    localHash == remoteHash -> if (localHash == null) SyncFileAction.Forget else SyncFileAction.Record
    remoteHash == previousHash -> if (localHash == null) SyncFileAction.DeleteRemote else SyncFileAction.Upload
    localHash == previousHash -> if (remoteHash == null) SyncFileAction.DeleteLocal else SyncFileAction.Download
    // 삭제와 수정이 겹친 경우에는 양쪽에 파일이 있는 충돌 처리 옵션을 적용하지 않는다.
    localHash == null || remoteHash == null -> SyncFileAction.Conflict
    else -> when (policy) {
        SyncConflictPolicy.KeepBoth -> SyncFileAction.Conflict
        SyncConflictPolicy.PreferLocal -> SyncFileAction.Upload
        SyncConflictPolicy.PreferRemote -> SyncFileAction.Download
        SyncConflictPolicy.KeepBothVersions -> SyncFileAction.PreserveVersions
        SyncConflictPolicy.PreferNewest -> {
            // 서버의 수정 시각은 초 단위일 수 있으므로 같은 초의 파일은 충돌 상태로 둔다.
            val localSeconds = localModified / 1_000L
            val remoteSeconds = remoteModified / 1_000L
            when {
                localModified <= 0L || remoteModified <= 0L -> SyncFileAction.Conflict
                localSeconds > remoteSeconds -> SyncFileAction.Upload
                localSeconds < remoteSeconds -> SyncFileAction.Download
                else -> SyncFileAction.Conflict
            }
        }
    }
}

internal data class FolderSyncProgress(
    val syncId: String,
    val relativePath: String = "",
    val entryNumber: Int = 0,
    val directoryEntries: Int = 0,
    val counts: SyncFileCounts = SyncFileCounts(),
    val conflicts: Int = 0,
    val conflictPolicy: SyncConflictPolicy,
    val resolvedConflicts: SyncConflictCounts = SyncConflictCounts(),
    // 연결된 하위 폴더별 집계와 탐색을 마친 하위 폴더. 키는 동기화 루트 기준 상대 경로다.
    val subfolders: Map<String, SyncSubfolderResult> = emptyMap(),
    val finishedSubfolders: Set<String> = emptySet(),
    val selectedSubfolders: Set<String>? = null,
    val comparingFolders: Set<String> = emptySet(),
) {
    fun includes(subfolder: String?) = subfolder == null || selectedSubfolders?.contains(subfolder) != false

    fun phaseOf(subfolder: String): SyncSubfolderPhase = when {
        subfolder in finishedSubfolders -> SyncSubfolderPhase.Finished
        relativePath.isWithinSubfolder(subfolder) || comparingFolders.any { it.isWithinSubfolder(subfolder) } -> SyncSubfolderPhase.Running
        else -> SyncSubfolderPhase.Waiting
    }

    // 하위 폴더 행에는 그 폴더 안의 진행 위치와 집계만 보여준다.
    fun forSubfolder(subfolder: String): FolderSyncProgress {
        val inside = subfolder !in finishedSubfolders && relativePath.startsWith("$subfolder/", ignoreCase = true)
        val own = subfolders[subfolder] ?: SyncSubfolderResult()
        return copy(
            relativePath = if (inside) relativePath.substring(subfolder.length + 1) else "",
            entryNumber = if (inside) entryNumber else 0,
            directoryEntries = if (inside) directoryEntries else 0,
            counts = own.counts,
            conflicts = own.conflicts,
            resolvedConflicts = own.resolvedConflicts,
            subfolders = emptyMap(),
            finishedSubfolders = emptySet(),
            comparingFolders = comparingFolders.filterTo(mutableSetOf()) { it.isWithinSubfolder(subfolder) },
        )
    }
}

internal enum class SyncSubfolderPhase { Waiting, Running, Finished }

internal fun String.isWithinSubfolder(subfolder: String) =
    equals(subfolder, ignoreCase = true) || startsWith("$subfolder/", ignoreCase = true)

private fun SyncSubfolderResult.counted(change: SyncFileCounts.() -> SyncFileCounts) = copy(counts = counts.change())
private fun SyncSubfolderResult.plusUnchanged(count: Int = 1) = counted { copy(unchanged = unchanged + count) }
private fun SyncSubfolderResult.plusUploaded() = counted { copy(uploaded = uploaded + 1) }
private fun SyncSubfolderResult.plusDownloaded() = counted { copy(downloaded = downloaded + 1) }
private fun SyncSubfolderResult.plusDeleted() = counted { copy(deleted = deleted + 1) }
private fun SyncSubfolderResult.plusConflict() = copy(conflicts = conflicts + 1)
private fun SyncSubfolderResult.plusResolved(added: SyncConflictCounts) = copy(resolvedConflicts = with(resolvedConflicts) {
    SyncConflictCounts(device + added.device, server + added.server, both + added.both)
})

internal fun IOException.syncOutcome() = when ((this as? RemoteRequestException)?.failure) {
    RemoteFailure.Address, RemoteFailure.Credentials -> SyncOutcome.Failed
    else -> SyncOutcome.Retry
}

internal class FolderSyncRun(
    private val remotePath: String,
    private val location: RemoteFolderSync,
    private val local: LocalSyncStorage,
    private val server: RemoteServer,
    private val transport: RemoteTransport,
    private val listings: RemoteDirectoryListings,
    private val files: SyncFileIndex,
    private val conflictPolicy: SyncConflictPolicy,
    private val filterForFolder: (String) -> SyncFileFilter,
    private val checkpoint: () -> Unit,
    private val onPublished: suspend (String, RemoteEntry) -> Unit,
    private val subfolders: List<String> = emptyList(),
    private val manualSubfolder: String? = null,
    private val scanStarted: Long,
    private val comparisons: FolderComparisonQueue.Root,
    private val transfers: Mutex,
    private val onProgress: (FolderSyncProgress) -> Unit,
) {
    private data class PendingDownload(val key: String, val remote: RemoteEntry, val local: LocalDocument?, val hash: String?)
    private class StagedDownload(val id: String, val parent: String, val name: String, val hash: String, val stamp: SyncStamp) {
        var installed = false
    }
    private class ReceivedFile(val record: SyncedFile, val changed: Boolean)
    private class PendingDeletion(val path: String, val local: LocalDocument?, val remote: RemoteEntry?)
    private data class PendingComparison(
        val key: String, val local: LocalDocument, val remote: RemoteEntry, val previous: SyncedFile?,
    )
    private data class ContentComparison(val localHash: String, val remoteHash: String?)
    // [remoteEtag]는 상위 폴더 목록에서 본 이 폴더의 ETag다. 저장된 목록과 같으면 서버에 다시 묻지 않는다.
    private data class Directory(
        val relative: String, val localId: String?, val remotePath: String?, val depth: Int, val remoteEtag: String? = null,
    )
    // 이동 감지 후보. 한쪽에만 새로 생긴 파일과, 기록은 있는데 한쪽에서만 사라진 파일이다.
    private class LocalOnly(val path: String, val document: LocalDocument)
    private class RemoteOnly(val path: String, val entry: RemoteEntry)
    private class LocalGone(val path: String, val record: SyncedFile, val remote: RemoteEntry)
    private class RemoteGone(val path: String, val record: SyncedFile, val document: LocalDocument)
    private class ServerMove(val source: LocalGone, val hash: String)
    private sealed interface DirectoryComparisonEvent {
        data class Discovered(val directories: List<Directory>) : DirectoryComparisonEvent
        // 목록 비교를 마치고 비교 자리를 내줬다. 내용 비교는 이어서 진행한다.
        object Listed : DirectoryComparisonEvent
        data class Finished(val directory: Directory, val result: Result<Comparison>, val listed: Boolean) : DirectoryComparisonEvent
    }
    private data class Comparison(
        val locals: Map<String, List<LocalDocument>>, val remotes: Map<String, List<RemoteEntry>>,
        val localBackups: Map<String, LocalDocument>, val remoteBackups: Map<String, RemoteEntry>,
        val localParts: List<LocalDocument>, val remoteParts: List<RemoteEntry>,
        val knownNames: List<String>, val names: Set<String>, val downloads: List<PendingDownload>,
        val unchanged: Int, val pendingContents: List<PendingComparison>,
        val contents: Map<String, Result<ContentComparison>> = emptyMap(),
    )

    private val prepared = mutableMapOf<String, Result<Comparison>>()
    private val contentSlots = Semaphore(MAX_CONTENT_COMPARISONS)
    private val updatedStamps = mutableMapOf<String, SyncStamp>()
    private var transferring = false
    var transferWaitMs = 0L
        private set
    var peakComparisons = 0
        private set

    private suspend fun beginTransfer() {
        if (!transferring) {
            val start = SystemClock.elapsedRealtime()
            transfers.lock()
            transferring = true
            transferWaitMs = SystemClock.elapsedRealtime() - start
        }
    }

    private fun applyComparedStamps() {
        for ((path, stamp) in updatedStamps) files[path]?.let { files[path] = it.copy(remote = stamp) }
        updatedStamps.clear()
    }

    private var lastCheckpoint = System.currentTimeMillis()
    var progress = FolderSyncProgress(location.id, conflictPolicy = conflictPolicy,
        selectedSubfolders = manualSubfolder?.let { subfolders.toSet() })
        private set(value) {
            field = value
            onProgress(value)
        }
    var outcome = SyncOutcome.Success
        private set
    var changed = false
        private set
    var remoteChanged = false
        private set
    private val observed = mutableSetOf<String>()
    private val preservedPaths = mutableSetOf<String>()
    private val subfolderKeys = subfolders.mapTo(hashSetOf(), ::syncKey)
    // 다시 확인하지 못한 폴더의 접두사. 모두 '/'로 끝난다.
    private val unscanned = hashSetOf<String>()
    // 충돌로 건너뛴 항목의 소문자 접두사. 모두 '/'로 끝나며 그 아래 기록을 실행이 끝날 때 남긴다.
    private val conflicted = hashSetOf<String>()
    private val remotePrefix = "$SYNC_FILE_PREFIX${location.id}-"
    private val localPrefix = ".$remotePrefix"
    private var selectedChildren: Map<String, Set<String>>? = null
    // 서버가 파일 ID를 알려 주면(Nextcloud·ownCloud) 새 파일을 올릴 때 체크섬을 함께 보낸다.
    @Volatile private var reportsFileIds = false
    private val newLocalFiles = mutableListOf<LocalOnly>()
    private val newRemoteFiles = mutableListOf<RemoteOnly>()
    private val goneLocally = mutableListOf<LocalGone>()
    private val goneRemotely = mutableListOf<RemoteGone>()
    // 키는 옮긴 뒤의 경로다. 원래 경로는 movedAway에 두어 삭제하거나 다시 받지 않게 한다.
    private val serverMoves = mutableMapOf<String, ServerMove>()
    private val deviceMoves = mutableMapOf<String, RemoteGone>()
    private val movedAway = hashSetOf<String>()
    val remoteEdits = mutableListOf<RemoteFileChange>()
    val readStats = RemoteReadStats()
    var localListMs = 0L
        private set
    var remoteListMs = 0L
        private set
    var listingWallMs = 0L
        private set
    private var readers = 0
    private var readingSince = 0L

    // 전체 합계와 함께 경로가 속한 연결된 하위 폴더의 집계도 갱신한다.
    private fun tally(path: String, current: FolderSyncProgress = progress, change: SyncSubfolderResult.() -> SyncSubfolderResult) {
        val total = SyncSubfolderResult(current.counts, current.conflicts, current.resolvedConflicts).change()
        val owners = subfolders.filter(path::isWithinSubfolder)
        progress = current.copy(
            counts = total.counts,
            conflicts = total.conflicts,
            resolvedConflicts = total.resolvedConflicts,
            subfolders = if (owners.isEmpty()) current.subfolders else
                current.subfolders + owners.associateWith { (current.subfolders[it] ?: SyncSubfolderResult()).change() },
        )
    }

    suspend fun run(paths: Set<String>? = null) {
        if (!local.document(location.localId).directory || !transport.isFolderUnderRoot(server, remotePath)) {
            throw IOException("동기화 폴더를 확인할 수 없습니다.")
        }
        selectedChildren = paths?.let(::childrenForChanges)
        selectedChildren?.let { selected ->
            progress = progress.copy(selectedSubfolders = subfolders.filterTo(mutableSetOf()) { syncKey("$it/") in selected })
        }
        try {
            prepareTree(Directory("", location.localId, remotePath, 0))
            applyComparedStamps()
            checkpointIfDue()
            planMoves()
            walk("", location.localId, remotePath, { location.localId }, { remotePath }, depth = 0)
        } finally {
            if (transferring) transfers.unlock()
        }
        // 다시 포함할 때 양쪽 파일의 변경 여부를 판단할 수 있도록 기존 기록을 남긴다.
        val considered = paths ?: files.keys
        val forgotten = considered.filter {
            (manualSubfolder == null || it.isWithinSubfolder(manualSubfolder)) &&
                it !in observed && !it.hasAncestorIn(unscanned) && !isConflicted(it) &&
                !it.hasHiddenFolderSegment() && !location.excludesSubfolder(it)
        }
        forgotten.forEach(files::remove)
        progress = progress.copy(relativePath = "", entryNumber = 0, directoryEntries = 0)
    }

    private suspend fun readFolderLists(
        localDirectory: String?, remoteDirectory: String?, knownEtag: String? = null, probe: Boolean = false,
    ): Pair<List<LocalDocument>, List<RemoteEntry>> {
        synchronized(this) {
            if (readers++ == 0) readingSince = SystemClock.elapsedRealtime()
        }
        try {
            return coroutineScope {
                val remoteRead = remoteDirectory?.let { parent ->
                    async {
                        val begin = SystemClock.elapsedRealtime()
                        try {
                            listings.read(server, parent, requireComplete = true, scanStarted = scanStarted,
                                knownEtag = knownEtag, probeEtag = probe).entries
                        } finally {
                            synchronized(this@FolderSyncRun) { remoteListMs += SystemClock.elapsedRealtime() - begin }
                        }
                    }
                }
                val locals = localDirectory?.let { parent ->
                    val begin = SystemClock.elapsedRealtime()
                    try {
                        local.list(parent)
                    } finally {
                        synchronized(this@FolderSyncRun) { localListMs += SystemClock.elapsedRealtime() - begin }
                    }
                }.orEmpty()
                locals to remoteRead?.await().orEmpty()
            }
        } finally {
            synchronized(this) {
                if (--readers == 0) listingWallMs += SystemClock.elapsedRealtime() - readingSince
            }
        }
    }

    private suspend fun prepareTree(root: Directory) = coroutineScope {
        val pending = ArrayDeque<Directory>()
        pending.add(root)
        val events = Channel<DirectoryComparisonEvent>(Channel.UNLIMITED)
        // 목록을 읽는 폴더만 비교 자리 수로 제한한다. 내용 비교 중인 폴더가 다음 폴더의 목록 읽기를 막지 않는다.
        var listing = 0
        var running = 0
        while (pending.isNotEmpty() || running > 0) {
            while (pending.isNotEmpty() && listing < FolderComparisonQueue.CAPACITY) {
                val directory = pending.removeFirst()
                listing++
                running++
                launch {
                    var listed = false
                    val result = try {
                        val plan = comparisons.compare {
                            compareDirectory(directory) { children ->
                                if (children.isNotEmpty()) events.send(DirectoryComparisonEvent.Discovered(children))
                            }
                        }
                        listed = true
                        events.send(DirectoryComparisonEvent.Listed)
                        Result.success(withContents(directory, plan))
                    } catch (error: IOException) {
                        Result.failure(error)
                    } catch (error: SecurityException) {
                        Result.failure(error)
                    }
                    events.send(DirectoryComparisonEvent.Finished(directory, result, listed))
                }
            }
            when (val event = events.receive()) {
                is DirectoryComparisonEvent.Discovered -> event.directories.forEach(pending::addLast)
                DirectoryComparisonEvent.Listed -> listing--
                is DirectoryComparisonEvent.Finished -> {
                    running--
                    if (!event.listed) listing--
                    prepared[event.directory.relative] = event.result
                }
            }
        }
    }

    private suspend fun compareDirectory(directory: Directory, onChildren: suspend (List<Directory>) -> Unit = {}): Comparison =
        markComparing(directory, countPeak = true) { compareEntries(directory, onChildren) }

    // 원본을 읽는 내용 비교는 목록 비교 자리를 내준 뒤 실행 안의 별도 한도에서 진행한다.
    private suspend fun withContents(directory: Directory, plan: Comparison): Comparison {
        if (plan.pendingContents.isEmpty()) return plan
        return contentSlots.withPermit {
            markComparing(directory, countPeak = false) { plan.copy(contents = compareContents(plan.pendingContents)) }
        }
    }

    private suspend fun <T> markComparing(directory: Directory, countPeak: Boolean, block: suspend () -> T): T {
        val path = directory.relative.trimEnd('/')
        synchronized(this) {
            val comparing = progress.comparingFolders + path
            if (countPeak) peakComparisons = maxOf(peakComparisons, comparing.size)
            progress = progress.copy(comparingFolders = comparing)
        }
        try {
            return block()
        } finally {
            synchronized(this) { progress = progress.copy(comparingFolders = progress.comparingFolders - path) }
        }
    }

    // 바뀐 이름이 적으면 이름마다 묻고, 많으면 폴더 목록을 한 번 읽어 고른다. 목록은 같은 폴더의 다음 조회와 공유된다.
    private suspend fun selectedRemoteEntries(relative: String, parent: String, names: Set<String>, etag: String?): List<RemoteEntry> {
        if (names.size <= MAX_NAMED_REMOTE_LOOKUPS) return names.mapNotNull { name ->
            transport.entryAt(server, parent, name) ?: if (relative + name in files)
                transport.entryAt(server, parent, backupName(name, remotePrefix)) else null
        }
        val keys = names.mapTo(hashSetOf(), ::syncKey)
        val backups = names.filter { relative + it in files }.mapTo(hashSetOf()) { backupName(it, remotePrefix) }
        return readFolderLists(null, parent, etag).second.filter { syncKey(it.name) in keys || it.name in backups }
    }

    private suspend fun compareEntries(directory: Directory, onChildren: suspend (List<Directory>) -> Unit): Comparison {
        val (relative, localDirectory, remoteDirectory, depth) = directory
        if (depth > 64) throw IOException("동기화 폴더의 깊이가 너무 큽니다.")
        val parentKey = syncKey(relative)
        val selectedNames = selectedChildren?.get(parentKey)
        val selection = selectedNames?.mapTo(hashSetOf(), ::syncKey)
        val (localList, remoteList) = if (selectedNames == null) {
            // 루트는 폴더 ETag만 먼저 확인해, 아무것도 바뀌지 않은 트리는 요청 한 번으로 끝낸다.
            readFolderLists(localDirectory, remoteDirectory, directory.remoteEtag, probe = depth == 0)
        } else {
            val locals = localDirectory?.let { parent ->
                selectedNames.mapNotNull { name ->
                    local.childDocument(parent, name) ?: if (relative + name in files)
                        local.childDocument(parent, backupName(name, localPrefix)) else null
                }
            }.orEmpty()
            val remotes = remoteDirectory?.let { parent ->
                selectedRemoteEntries(relative, parent, selectedNames, directory.remoteEtag)
            }.orEmpty()
            locals to remotes
        }
        if (!reportsFileIds && remoteList.any { it.fileId != null }) reportsFileIds = true
        val nextChild = manualSubfolder?.takeUnless { relative.trimEnd('/').isWithinSubfolder(it) }
            ?.removePrefix(relative)?.substringBefore('/')
        val cleanParts = selection == null && nextChild == null
        val localParts = if (cleanParts) localList.filter { !it.directory && it.name.startsWith(localPrefix) && it.name.endsWith(".part") } else emptyList()
        val remoteParts = if (cleanParts) remoteList.filter { !it.directory && !it.unsupported && it.name.startsWith(remotePrefix) && it.name.endsWith(".sgpart") } else emptyList()
        // 저장 위치를 상위로 바로잡을 때 기존 복사본이 서버의 새 하위 폴더로 올라가지 않게 한다.
        val excludedKey = location.excludedLocalChild?.takeIf { depth == 0 }?.let(::syncKey)
        val fileFilter = filterForFolder(relative)
        fun included(name: String): Boolean {
            val key = syncKey(name)
            return isVisibleSyncName(name) && key != excludedKey && (selection == null || key in selection) &&
                (nextChild == null || nextChild.equals(name, ignoreCase = true))
        }
        val localEntries = localList.filter { included(it.name) }.groupByTo(linkedMapOf()) { syncKey(it.name) }
        val remoteEntries = remoteList.filter { included(it.name) }.groupByTo(linkedMapOf()) { syncKey(it.name) }
        val localBackups = localList.filter { it.name.startsWith(localPrefix) && it.name.endsWith(".backup") }
            .associateBy(LocalDocument::name)
        val remoteBackups = remoteList.filter { it.name.startsWith(remotePrefix) && it.name.endsWith(".backup") }
            .associateBy(RemoteEntry::name)
        val knownNames = if (localBackups.isEmpty() && remoteBackups.isEmpty()) emptyList() else
            files.keys.filter { it.startsWith(relative) }.map { it.removePrefix(relative) }
                .filter { '/' !in it && included(it) }
        val names = LinkedHashSet(localEntries.keys)
        names.addAll(remoteEntries.keys)
        knownNames.mapTo(names, ::syncKey)
        val directoryEntries = names.size
        val newDownloads = mutableListOf<PendingDownload>()
        val updates = mutableListOf<PendingDownload>()
        val pendingComparisons = mutableListOf<PendingComparison>()
        var unchanged = 0
        val stable = ArrayList<String>(256)
        val stamps = mutableMapOf<String, SyncStamp>()
        val children = mutableListOf<Directory>()
        val folder = relative.trimEnd('/')
        fun reportUnchanged() {
            if (stable.isEmpty()) return
            unchanged += stable.size
            synchronized(this) {
                observed.addAll(stable)
                updatedStamps.putAll(stamps)
                val position = progress.copy(relativePath = folder, entryNumber = unchanged, directoryEntries = directoryEntries)
                tally(folder, position) { plusUnchanged(stable.size) }
            }
            stable.clear()
            stamps.clear()
        }
        val remaining = names.iterator()
        while (remaining.hasNext()) {
            currentCoroutineContext().ensureActive()
            val key = remaining.next()
            val locals = localEntries[key].orEmpty()
            val remotes = remoteEntries[key].orEmpty()
            val existing = locals.singleOrNull()
            val remote = remotes.singleOrNull()
            if (existing?.directory == true || remote?.directory == true) {
                val name = existing?.name ?: requireNotNull(remote).name
                val path = relative + name
                if (locals.size <= 1 && remotes.size <= 1 && (existing == null || existing.directory) &&
                    (remote == null || (remote.directory && !remote.unsupported && remote.name == name)) &&
                    (nextChild == null || name == nextChild) && validSyncName(name) &&
                    !location.excludesSubfolder(path) && path !in files &&
                    selectedChildren?.containsKey(syncKey("$path/")) != false &&
                    (localBackups.isEmpty() || backupName(name, localPrefix) !in localBackups) &&
                    (remoteBackups.isEmpty() || backupName(name, remotePrefix) !in remoteBackups)
                ) children.add(Directory("$path/", existing?.id, remote?.path, depth + 1, remote?.etag))
                continue
            }
            if (nextChild != null) continue
            if (remote == null) {
                if (existing != null && locals.size == 1 && remotes.isEmpty()) {
                    noteLocalOnly(relative + existing.name, existing, fileFilter, localBackups, remoteBackups)
                }
                continue
            }
            val entry = remote
            val path = relative + entry.name
            if (entry.unsupported || !validSyncName(entry.name) || location.excludesSubfolder(path) ||
                selectedChildren?.containsKey(syncKey("$path/")) == true ||
                (localBackups.isNotEmpty() && backupName(entry.name, localPrefix) in localBackups) ||
                (remoteBackups.isNotEmpty() && backupName(entry.name, remotePrefix) in remoteBackups)
            ) continue

            val previous = files[path]
            if (locals.size > 1 || (existing != null && existing.name != entry.name)) continue
            if (!fileFilter.allows(entry.name, existing?.mimeType, entry.mimeType)) continue
            val localStamp = existing?.syncStamp()
            val remoteStamp = entry.syncStamp()
            // 서버 체크섬이 기록한 해시와 같으면 ETag가 바뀌었어도 내용은 그대로다.
            val sameByChecksum = previous != null && localStamp != null && previous.local.modified > 0 &&
                previous.local == localStamp && entry.serverSha256 == previous.hash
            if (previous?.matchesBoth(localStamp, remoteStamp) == true || sameByChecksum) {
                if (syncKey(path) in subfolderKeys) continue
                if (previous.remote != remoteStamp) stamps[path] = remoteStamp
                stable.add(path)
                localEntries.remove(key)
                remoteEntries.remove(key)
                remaining.remove()
                if (stable.size == 256) reportUnchanged()
            } else if (previous.canQueueDownload(localStamp, remoteStamp)) {
                val job = PendingDownload(key, entry, existing, previous?.hash)
                if (existing == null) {
                    newDownloads.add(job)
                    synchronized(this) { newRemoteFiles.add(RemoteOnly(path, entry)) }
                } else updates.add(job)
            } else if (existing != null) {
                pendingComparisons.add(PendingComparison(key, existing, entry, previous))
            } else if (previous != null && previous.remoteUnchanged(entry)) {
                synchronized(this) { goneLocally.add(LocalGone(path, previous, entry)) }
            }
        }
        reportUnchanged()
        // 먼저 하위 폴더를 예약해 현재 폴더의 내용 비교가 끝나기 전에도 시작할 수 있게 한다.
        onChildren(children)
        return Comparison(localEntries, remoteEntries, localBackups, remoteBackups, localParts, remoteParts,
            knownNames, names, newDownloads + updates, unchanged, pendingComparisons)
    }

    // 서버에 없는 기기 파일을 이동 후보로 모은다. 실제 처리는 적용 단계의 같은 조건에서만 일어난다.
    private fun noteLocalOnly(path: String, document: LocalDocument, filter: SyncFileFilter,
        localBackups: Map<String, LocalDocument>, remoteBackups: Map<String, RemoteEntry>) {
        val name = document.name
        if (document.directory || !validSyncName(name) || location.excludesSubfolder(path) ||
            selectedChildren?.containsKey(syncKey("$path/")) == true || !filter.allows(name, document.mimeType, null) ||
            (localBackups.isNotEmpty() && backupName(name, localPrefix) in localBackups) ||
            (remoteBackups.isNotEmpty() && backupName(name, remotePrefix) in remoteBackups)
        ) return
        val previous = files[path]
        synchronized(this) {
            if (previous == null) newLocalFiles.add(LocalOnly(path, document))
            else if (previous.local.modified > 0 && previous.local == document.syncStamp()) goneRemotely.add(RemoteGone(path, previous, document))
        }
    }

    // 한쪽에서 사라진 기록과 내용이 같은 새 파일을 짝지어, 지우고 다시 보내는 대신 반대쪽에서도 옮긴다.
    // 내용이 같으면 실제로 옮긴 것이 아니어도 결과가 같으므로, 각 파일을 한 번씩만 짝짓는다.
    private suspend fun planMoves() {
        if (goneRemotely.isNotEmpty() && newRemoteFiles.isNotEmpty()) {
            val byFileId = goneRemotely.filter { it.record.remote?.fileId != null }.groupBy { it.record.remote?.fileId }
            val byHash = goneRemotely.groupBy { it.record.hash }
            val used = hashSetOf<String>()
            for (target in newRemoteFiles) {
                val entry = target.entry
                // 파일 ID가 같아도 옮기면서 내용이 바뀌었을 수 있어 크기와 수정 시각까지 확인한다.
                val sameFile = entry.fileId?.let { id -> byFileId[id]?.singleOrNull() }?.takeIf { source ->
                    val saved = source.record.remote
                    saved != null && saved.size == entry.size && saved.modified > 0 && saved.modified == entry.modified &&
                        !entry.modifiedTimeIsApproximate && source.path !in used
                }
                val source = sameFile ?: entry.serverSha256?.let { hash ->
                    byHash[hash]?.firstOrNull { it.path !in used && it.record.local.size == entry.size }
                } ?: continue
                used.add(source.path)
                deviceMoves[target.path] = source
                movedAway.add(source.path)
            }
        }
        if (goneLocally.isNotEmpty() && newLocalFiles.isNotEmpty()) {
            val bySize = goneLocally.groupBy { it.record.local.size }
            val used = hashSetOf<String>()
            for (target in newLocalFiles) {
                currentCoroutineContext().ensureActive()
                val candidates = bySize[target.document.size]?.filter { it.path !in used }
                if (candidates.isNullOrEmpty()) continue
                // 크기가 같은 후보가 있을 때만 새 파일의 내용을 읽는다.
                val hash = try {
                    local.fingerprint(target.document)
                } catch (_: IOException) {
                    continue
                } catch (_: SecurityException) {
                    continue
                }
                val source = candidates.firstOrNull { it.record.hash == hash } ?: continue
                used.add(source.path)
                serverMoves[target.path] = ServerMove(source, hash)
                movedAway.add(source.path)
            }
        }
    }

    private suspend fun compareContents(pending: List<PendingComparison>): Map<String, Result<ContentComparison>> = buildMap {
        for ((key, left, right, previous) in pending) {
            currentCoroutineContext().ensureActive()
            val result = try {
                val serverSha256 = right.serverSha256
                val serverSha1 = if (serverSha256 == null) right.serverSha1 else null
                val (localHash, localSha1) = if (serverSha1 == null) local.fingerprint(left) to null else local.fingerprintWithSha1(left)
                // 서버가 저장한 체크섬이 있으면 내려받지 않고 비교한다. SHA-1은 같은지 확인하는 데만 쓴다.
                // 다운로드에 쓰일 파일은 적용 단계에서 임시 파일로 받아 다시 읽는 일을 피한다.
                val remoteHash = previous?.hashForRemote(right.syncStamp()) ?: serverSha256
                    ?: localHash.takeIf { localSha1 != null && localSha1 == serverSha1 }
                    ?: if (mayStageRemote(localHash, previous)) null else transport.digest(server, right.path).hex()
                Result.success(ContentComparison(localHash, remoteHash))
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: SecurityException) {
                Result.failure(error)
            }
            put(key, result)
        }
    }

    private suspend fun walk(relative: String, localDirectory: String?, remoteDirectory: String?,
        makeLocal: () -> String, makeRemote: suspend () -> String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (selectedChildren?.containsKey(syncKey(relative)) == false) return
        // 백업 복구 후에야 진입할 수 있는 폴더만 여기서 처음 비교한다.
        val plan = prepared.remove(relative)?.getOrThrow()
            ?: Directory(relative, localDirectory, remoteDirectory, depth).let { directory ->
                withContents(directory, comparisons.compare { compareDirectory(directory).also { applyComparedStamps() } })
            }
        val localEntries = plan.locals
        val remoteEntries = plan.remotes
        val localBackups = plan.localBackups
        val remoteBackups = plan.remoteBackups
        val knownNames = plan.knownNames
        val names = plan.names
        // 기기에서 옮겨 처리할 새 서버 파일은 내려받지 않고 아래에서 하나씩 처리한다.
        val downloads = if (deviceMoves.isEmpty()) plan.downloads
            else plan.downloads.filterNot { relative + it.remote.name in deviceMoves }
        val unchanged = plan.unchanged
        val directoryEntries = names.size + unchanged
        val fileFilter = filterForFolder(relative)
        if (manualSubfolder != null && !relative.trimEnd('/').isWithinSubfolder(manualSubfolder)) {
            val name = manualSubfolder.removePrefix(relative).substringBefore('/')
            val path = relative + name
            val locals = localEntries[syncKey(name)].orEmpty()
            val remotes = remoteEntries[syncKey(name)].orEmpty()
            if (locals.size > 1 || remotes.size > 1 || locals.any { !it.directory || it.name != name } ||
                remotes.any { !it.directory || it.unsupported || it.name != name } || path in files) {
                conflict(relative, name)
            } else if (locals.isNotEmpty() || remotes.isNotEmpty()) {
                walk("$path/", locals.firstOrNull()?.id, remotes.firstOrNull()?.path,
                    { local.ensureDirectory(localDirectory ?: makeLocal(), name) },
                    { transport.createFolder(server, remoteDirectory ?: makeRemote(), name).also { remoteChanged = true } }, depth + 1)
            }
            return
        }
        if (plan.localParts.isNotEmpty() || plan.remoteParts.isNotEmpty()) {
            beginTransfer()
            plan.localParts.forEach { runCatching { local.delete(it.id) } }
            plan.remoteParts.forEach { runCatching { transport.deleteFile(server, it.path, it.etag) } }
        }
        progress = progress.copy(relativePath = relative.trimEnd('/'), entryNumber = unchanged, directoryEntries = directoryEntries)
        if (downloads.isNotEmpty()) beginTransfer()
        var writableLocal = localDirectory
        var writableRemote = remoteDirectory
        val ensureLocal = { writableLocal ?: makeLocal().also { writableLocal = it } }
        val ensureRemote: suspend () -> String = { writableRemote ?: makeRemote().also { writableRemote = it } }

        val received = if (downloads.isEmpty()) emptySet()
        else receivePending(relative, ensureLocal, downloads, directoryEntries, unchanged)
        val deletions = mutableListOf<PendingDeletion>()

        var entryNumber = unchanged + received.size
        for (key in names) {
            currentCoroutineContext().ensureActive()
            if (key in received) continue
            entryNumber++
            val locals = localEntries[key].orEmpty()
            val remotes = remoteEntries[key].orEmpty()
            val sameKey = locals.map(LocalDocument::name) + remotes.map(RemoteEntry::name)
            val name = sameKey.firstOrNull() ?: knownNames.first { syncKey(it) == key }
            val path = relative + name
            if (location.excludesSubfolder(path)) continue
            val localFile = locals.firstOrNull { !it.directory }
            val remoteFile = remotes.firstOrNull { !it.directory }
            if ((localFile != null || remoteFile != null || sameKey.isEmpty()) &&
                !fileFilter.allows(name, localFile?.mimeType, remoteFile?.mimeType)
            ) {
                // 양쪽을 같은 기준으로 건너뛰어 필터에 걸린 파일을 삭제로 해석하지 않는다.
                observed.add(path)
                if (locals.any(LocalDocument::directory) || remotes.any(RemoteEntry::directory)) unscanned.add("$path/")
                continue
            }
            // 충돌 복사본을 이번 실행에서 기록한 경우 이전 목록으로 다시 처리하지 않는다.
            if (path in preservedPaths) continue
            progress = progress.copy(relativePath = path, entryNumber = entryNumber, directoryEntries = directoryEntries)
            if (sameKey.any { it != name } || locals.size > 1 || remotes.size > 1 ||
                remotes.any(RemoteEntry::unsupported) || !validSyncName(name)
            ) {
                conflict(relative, key)
                continue
            }

            try {
                val localBackup = if (localBackups.isEmpty()) null else localBackups[backupName(name, localPrefix)]
                val remoteBackup = if (remoteBackups.isEmpty()) null else remoteBackups[backupName(name, remotePrefix)]
                if (localBackup != null || remoteBackup != null) beginTransfer()
                val left = recoverLocal(locals.singleOrNull(), localBackup, path, name)
                val right = recoverRemote(remotes.singleOrNull(), remoteBackup, remoteDirectory, path, name)
                when {
                    left != null && right != null && left.directory != right.directory -> conflict(relative, key)
                    left?.directory == true || right?.directory == true -> {
                        if (path in files) {
                            conflict(relative, key)
                        } else {
                            walk("$path/", left?.id, right?.path,
                                { local.ensureDirectory(ensureLocal(), name) },
                                { transport.createFolder(server, ensureRemote(), name).also { remoteChanged = true } }, depth + 1)
                        }
                    }
                    else -> {
                        if (selectedChildren?.containsKey(syncKey("$path/")) == true) {
                            conflict(relative, key)
                            continue
                        }
                        observed.add(path)
                        val compared = plan.contents[key]?.getOrThrow()
                        reconcile(path, name, left, right, writableLocal, writableRemote, ensureLocal, ensureRemote, deletions, compared)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                recordFailure(path, error.syncOutcome())
            } catch (_: SecurityException) {
                recordFailure(path, SyncOutcome.Failed)
            }
        }
        removeDeleted(relative, writableLocal, writableRemote, deletions)
        // 전송 없이 내용 비교로 확정한 기록도 폴더를 마칠 때마다 주기적으로 저장한다.
        checkpointIfDue()
        subfolders.firstOrNull { relative.trimEnd('/').equals(it, ignoreCase = true) }?.let { finished ->
            progress = progress.copy(finishedSubfolders = progress.finishedSubfolders + finished)
        }
    }

    // 내려받으면서 얻은 파일 정보로 완료 기록을 남긴다.
    private suspend fun receivePending(
        relative: String,
        ensureLocal: () -> String,
        downloads: List<PendingDownload>,
        directoryEntries: Int,
        alreadyChecked: Int,
    ): Set<String> {
        val parent = try {
            ensureLocal()
        } catch (_: IOException) {
            return emptySet()
        } catch (_: SecurityException) {
            return emptySet()
        }
        val pending = downloads.withIndex().iterator()
        val processed = mutableSetOf<String>()
        val missingTimes = mutableMapOf<String, Long>()
        val state = Any()
        coroutineScope {
            repeat(minOf(downloads.size, MAX_CONCURRENT_DOWNLOADS)) {
                launch {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val next = synchronized(state) {
                            if (!pending.hasNext()) null else pending.next().also { (index, download) ->
                                progress = progress.copy(relativePath = relative + download.remote.name,
                                    entryNumber = alreadyChecked + index + 1, directoryEntries = directoryEntries)
                            }
                        } ?: break
                        val job = next.value
                        val path = relative + job.remote.name
                        try {
                            val file = receiveFile(parent, path, job)
                            synchronized(state) {
                                observed.add(path)
                                processed.add(job.key)
                                files[path] = file.record
                                if (file.record.local.modified <= 0) missingTimes[job.remote.name] = System.currentTimeMillis()
                                if (file.changed) countDownload(path) else tally(path) { plusUnchanged() }
                                checkpointIfDue()
                            }
                        } catch (error: IOException) {
                            synchronized(state) {
                                processed.add(job.key)
                                recordFailure(path, error.syncOutcome())
                            }
                        } catch (_: SecurityException) {
                            synchronized(state) {
                                processed.add(job.key)
                                recordFailure(path, SyncOutcome.Failed)
                            }
                        }
                    }
                }
            }
        }
        // 수정 시각을 제공하지 않은 파일만 폴더가 끝날 때 한 번 보완한다.
        if (missingTimes.isNotEmpty()) {
            for (document in local.list(parent)) {
                val finished = missingTimes[document.name] ?: continue
                val path = relative + document.name
                val record = files[path] ?: continue
                if (!document.directory && document.size == record.local.size && document.modified in 1L..finished) {
                    files[path] = record.copy(local = document.syncStamp())
                }
            }
        }
        return processed
    }

    // 새 파일은 임시 파일로 받은 뒤 이름만 바꾼다. 기존 파일을 바꿀 때만 목록을 읽은 뒤 바뀌지 않았는지 확인한다.
    private suspend fun receiveFile(parent: String, path: String, job: PendingDownload): ReceivedFile {
        val draft = stageDownload(parent, job.remote)
        try {
            val existing = job.local
            if (existing != null) {
                if (local.childDocument(parent, job.remote.name) != existing) throw IOException("로컬 파일이 변경되었습니다.")
                if (draft.hash == job.hash) {
                    onPublished(path, job.remote)
                    return ReceivedFile(SyncedFile(draft.hash, existing.syncStamp(), job.remote.syncStamp()), changed = false)
                }
            }
            installDownload(draft, job.remote.name, existing)
            return ReceivedFile(SyncedFile(draft.hash, draft.stamp, job.remote.syncStamp()), changed = true)
        } finally {
            discardDownload(draft)
        }
    }

    private fun checkpointIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastCheckpoint < CHECKPOINT_INTERVAL_MS) return
        checkpoint()
        lastCheckpoint = now
    }

    private fun recordFailure(path: String, failure: SyncOutcome) {
        outcome = maxOf(outcome, failure)
        observed.add(path)
        unscanned.add("$path/")
    }

    // 충돌한 항목과 그 아래 기록은 지우지 않도록 접두사만 남기고, 실행이 끝날 때 한 번에 확인한다.
    private fun conflict(relative: String, name: String) {
        tally(relative + name) { plusConflict() }
        conflicted.add(syncKey(relative + name) + "/")
    }

    private fun isConflicted(path: String): Boolean {
        if (conflicted.isEmpty()) return false
        val key = syncKey(path)
        return "$key/" in conflicted || key.hasAncestorIn(conflicted)
    }

    private suspend fun reconcile(
        path: String,
        name: String,
        left: LocalDocument?,
        right: RemoteEntry?,
        localParent: String?,
        remoteParent: String?,
        ensureLocal: () -> String,
        ensureRemote: suspend () -> String,
        deletions: MutableList<PendingDeletion>,
        compared: ContentComparison?,
    ) {
        // 반대쪽에서도 옮기기로 한 원래 위치는 지우거나 다시 받지 않는다. 옮기지 못하면 다음 실행에서 처리한다.
        if (path in movedAway) return
        val previous = files[path]
        if (previous != null && left == null && right != null) {
            // 삭제와 원격 수정이 겹쳤는지는 휴지통 이동 직전에 해당 파일로 판단한다.
            deletions.add(PendingDeletion(path, null, right))
            return
        }
        // 동기화 이력이 있는 파일의 로컬 부재는 삭제일 수 있으므로 새 파일만 바로 받는다.
        if (previous == null && left == null && right != null) {
            beginTransfer()
            val move = deviceMoves.remove(path)
            if (move != null && moveOnDevice(path, name, right, move, ensureLocal)) return
            receive(path, ensureLocal(), right, null, null, null)
            return
        }
        // 서버에 없는 새 로컬 파일은 올리면서 한 번만 해시를 계산한다.
        if (previous == null && right == null && left != null) {
            beginTransfer()
            val move = serverMoves.remove(path)
            if (move != null && moveOnServer(path, name, left, move, ensureRemote)) return
            upload(path, ensureRemote(), name, left, move?.hash, null, null)
            return
        }
        val localStamp = left?.syncStamp()
        val remoteStamp = right?.syncStamp()
        // 양쪽 목록 정보가 그대로면 원본을 열지 않는다. 덮어쓰기와 삭제는 아래에서 내용을 검증한다.
        if (previous?.matchesBoth(localStamp, remoteStamp) == true) {
            if (previous.remote != remoteStamp) files[path] = previous.copy(remote = remoteStamp)
            tally(path) { plusUnchanged() }
            return
        }
        val localHash = compared?.localHash ?: left?.let { local.fingerprint(it) }
        val knownRemoteHash = compared?.remoteHash ?: previous?.hashForRemote(remoteStamp) ?: right?.serverSha256
        val draft = if (knownRemoteHash == null && left != null && right != null && mayStageRemote(localHash, previous)) {
            beginTransfer()
            stageDownload(requireNotNull(localParent), right)
        } else null
        try {
            val remoteHash = knownRemoteHash ?: draft?.hash ?: right?.let { transport.digest(server, it.path).hex() }
            applyFileAction(path, name, left, right, localParent, remoteParent, ensureLocal, ensureRemote,
                previous, localHash, remoteHash, draft, deletions)
        } finally {
            draft?.let(::discardDownload)
        }
    }

    // 서버에서 옮긴 파일을 기기에서도 옮긴다. 원래 파일이 그대로일 때만 옮기고, 실패하면 평소처럼 내려받는다.
    private suspend fun moveOnDevice(path: String, name: String, entry: RemoteEntry, move: RemoteGone, ensureLocal: () -> String): Boolean {
        try {
            val parent = ensureLocal()
            if (local.document(move.document.id) == move.document) {
                val moved = local.document(local.move(move.document, parent, name))
                files.remove(move.path)
                files[path] = SyncedFile(move.record.hash, moved.syncStamp(), entry.syncStamp())
                observed.add(move.path)
                countDownload(path)
                checkpointIfDue()
                return true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        // 아직 처리하지 않은 원래 위치는 평소대로 처리한다.
        movedAway.remove(move.path)
        return false
    }

    // 기기에서 옮긴 파일을 서버에서도 옮긴다. 서버 파일이 기록과 같을 때만 옮기고, 실패하면 평소처럼 올린다.
    private suspend fun moveOnServer(path: String, name: String, file: LocalDocument, move: ServerMove,
        ensureRemote: suspend () -> String): Boolean {
        val source = move.source
        try {
            val parent = ensureRemote()
            // 강한 ETag가 있으면 그 사이 바뀐 서버 파일은 옮기지 않는다.
            val moved = transport.moveEntry(server, source.remote, parent, name)
            files.remove(source.path)
            files[path] = SyncedFile(move.hash, file.syncStamp(), moved.syncStamp())
            observed.add(source.path)
            tally(path) { plusUploaded() }
            changed = true
            remoteChanged = true
            if (selectedChildren != null) {
                remoteEdits += RemoteFileChange(server.id, transport.parent(server, source.remote.path), source.remote.path, null)
                remoteEdits += RemoteFileChange(server.id, parent, moved.path, moved)
            }
            onPublished(path, moved)
            checkpointIfDue()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        movedAway.remove(source.path)
        return false
    }

    private fun mayStageRemote(localHash: String?, previous: SyncedFile?): Boolean =
        localHash == previous?.hash || conflictPolicy == SyncConflictPolicy.PreferRemote ||
            conflictPolicy == SyncConflictPolicy.PreferNewest || conflictPolicy == SyncConflictPolicy.KeepBothVersions

    private suspend fun applyFileAction(
        path: String, name: String, left: LocalDocument?, right: RemoteEntry?, localParent: String?, remoteParent: String?,
        ensureLocal: () -> String, ensureRemote: suspend () -> String, previous: SyncedFile?,
        localHash: String?, remoteHash: String?, draft: StagedDownload?, deletions: MutableList<PendingDeletion>,
    ) {
        val action = syncFileAction(
            localHash, remoteHash, previous?.hash, conflictPolicy,
            localModified = left?.modified ?: 0L,
            remoteModified = right?.takeUnless { it.modifiedTimeIsApproximate }?.modified ?: 0L,
        )
        if (action == SyncFileAction.Upload || action == SyncFileAction.Download || action == SyncFileAction.PreserveVersions) {
            beginTransfer()
        }
        when (action) {
            SyncFileAction.Forget -> files.remove(path)
            SyncFileAction.Record -> {
                if (left != null && right != null) onPublished(path, right)
                files[path] = SyncedFile(requireNotNull(localHash), requireNotNull(left).syncStamp(), right?.syncStamp())
                tally(path) { plusUnchanged() }
                checkpointIfDue()
            }
            SyncFileAction.Upload -> upload(path, ensureRemote(), name, requireNotNull(left), requireNotNull(localHash), right, remoteHash)
            SyncFileAction.Download -> receive(path, ensureLocal(), requireNotNull(right), left, localHash, remoteHash, draft)
            SyncFileAction.PreserveVersions -> {
                val parent = requireNotNull(remoteParent)
                val entry = requireNotNull(right)
                val hash = requireNotNull(remoteHash)
                preserveRemoteVersion(path, requireNotNull(localParent), parent, entry, hash, draft)
                upload(path, parent, name, requireNotNull(left), requireNotNull(localHash), entry, hash)
            }
            SyncFileAction.DeleteRemote -> deletions.add(PendingDeletion(path, null, requireNotNull(right)))
            SyncFileAction.DeleteLocal -> deletions.add(PendingDeletion(path, requireNotNull(left), null))
            SyncFileAction.Conflict -> tally(path) { plusConflict() }
        }
        if (isSyncContentConflict(localHash, remoteHash, previous?.hash)) {
            val added = when (action) {
                SyncFileAction.Upload -> SyncConflictCounts(device = 1)
                SyncFileAction.Download -> SyncConflictCounts(server = 1)
                SyncFileAction.PreserveVersions -> SyncConflictCounts(both = 1)
                else -> null
            }
            if (added != null) tally(path) { plusResolved(added) }
        }
    }

    private suspend fun receive(path: String, parent: String, entry: RemoteEntry, existing: LocalDocument?,
        previous: String?, expectedHash: String?, staged: StagedDownload? = null) {
        val (file, hash) = downloadToLocal(parent, entry.name, entry, existing, previous, expectedHash, staged)
        recordDownload(path, entry, file, hash)
    }

    private fun recordDownload(path: String, entry: RemoteEntry, file: LocalDocument, hash: String) {
        files[path] = SyncedFile(hash, file.syncStamp(), entry.syncStamp())
        countDownload(path)
        checkpointIfDue()
    }

    private fun countDownload(path: String) {
        tally(path) { plusDownloaded() }
        changed = true
    }

    private suspend fun downloadToLocal(parent: String, name: String, entry: RemoteEntry, existing: LocalDocument?,
        previous: String?, expectedHash: String?, staged: StagedDownload? = null): Pair<LocalDocument, String> {
        val draft = staged ?: stageDownload(parent, entry)
        try {
            if (expectedHash != null && draft.hash != expectedHash) throw IOException("서버 파일이 변경되었습니다.")
            if (local.childDocument(parent, name) != existing || (existing != null && local.fingerprint(existing) != previous)) {
                throw IOException("로컬 파일이 변경되었습니다.")
            }
            return local.document(installDownload(draft, name, existing)) to draft.hash
        } finally {
            if (staged == null) discardDownload(draft)
        }
    }

    private suspend fun stageDownload(parent: String, entry: RemoteEntry): StagedDownload {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        val draftName = "$localPrefix${UUID.randomUUID()}.part"
        val draft = local.stage(parent, draftName) { output ->
            val limit = entry.size.takeIf { it > 0 } ?: Long.MAX_VALUE
            received = transport.download(server, entry.path, DigestOutputStream(output, digest), limit, allowEmpty = true, etag = entry.etag)
        }
        // 응답 길이는 전송 중에 확인하므로 받은 크기만 목록의 크기와 비교한다.
        if (entry.size > 0 && received != entry.size) {
            runCatching { local.delete(draft.id) }
            throw IOException("서버 파일이 변경되었거나 전송이 중단되었습니다.")
        }
        val stamp = draft.stamp?.takeIf { it.size == received && it.modified > 0 } ?: SyncStamp(received, 0)
        return StagedDownload(draft.id, parent, draftName, digest.digest().hex(), stamp)
    }

    private suspend fun installDownload(draft: StagedDownload, name: String, existing: LocalDocument?): String {
        currentCoroutineContext().ensureActive()
        val backup = existing?.let {
            val backupName = backupName(name, localPrefix)
            local.renameChild(draft.parent, it.id, it.name, backupName) to backupName
        }
        try {
            val installed = local.renameChild(draft.parent, draft.id, draft.name, name)
            draft.installed = true
            backup?.let { (id, _) -> runCatching { local.delete(id) } }
            return installed
        } catch (error: Exception) {
            backup?.let { (id, from) -> runCatching { local.renameChild(draft.parent, id, from, name) } }
            throw error
        }
    }

    private fun discardDownload(draft: StagedDownload) {
        if (!draft.installed) runCatching { local.delete(draft.id) }
    }

    private suspend fun preserveRemoteVersion(path: String, localParent: String, remoteParent: String, entry: RemoteEntry,
        hash: String, staged: StagedDownload?) {
        val relative = path.removeSuffix(entry.name)
        var index = 0
        while (true) {
            val name = conflictCopyName(entry.name, hash, ++index)
            val pathForCopy = relative + name
            val localCopy = local.childDocument(localParent, name)
            val remoteCopy = transport.entryAt(server, remoteParent, name)
            if (localCopy != null && (localCopy.directory || localCopy.name != name) ||
                remoteCopy != null && (remoteCopy.directory || remoteCopy.unsupported || remoteCopy.name != name)) continue
            if (localCopy != null && local.fingerprint(localCopy) != hash) continue
            if (remoteCopy != null && transport.digest(server, remoteCopy.path).hex() != hash) continue

            // 두 위치에 복사본이 있는 것을 확인한 뒤에만 원래 서버 파일을 교체할 수 있다.
            val copy = localCopy ?: downloadToLocal(localParent, name, entry, null, null, hash, staged).first.also { countDownload(pathForCopy) }
            if (remoteCopy == null) {
                upload(pathForCopy, remoteParent, name, copy, hash, null, null)
            } else {
                files[pathForCopy] = SyncedFile(hash, SyncStamp(copy.size, copy.modified),
                    SyncStamp(remoteCopy.size, remoteCopy.modified, remoteCopy.etag))
            }
            observed.add(pathForCopy)
            preservedPaths.add(pathForCopy)
            return
        }
    }

    private suspend fun upload(path: String, parent: String, name: String, file: LocalDocument, knownHash: String?,
        existing: RemoteEntry?, previous: String?) {
        // 체크섬을 저장하는 서버에는 새 파일의 해시를 먼저 계산해 함께 보낸다. 다른 기기가 내려받지 않고 비교할 수 있다.
        val hash = knownHash ?: if (reportsFileIds && existing == null) local.fingerprint(file) else null
        val caller = currentCoroutineContext()
        val mime = photoMime(name)
        val uploaded = transport.uploadForSync(server, parent, name, file.size, existing, mime,
            backupName(name, remotePrefix), previous?.decodeHex(), hash?.decodeHex()) { output ->
            val digest = local.copyAndHash(file, output) { caller.ensureActive() }
            if (hash != null && digest != hash) throw IOException("로컬 파일이 변경되었습니다.")
            digest
        }
        files[path] = SyncedFile(uploaded.hash, file.syncStamp(), uploaded.version?.let { SyncStamp(file.size, it.modified, it.etag) })
        checkpointIfDue()
        tally(path) { plusUploaded() }
        changed = true
        remoteChanged = true
        val entry = RemoteEntry(transport.child(server, parent, name), name, false, uploaded.version?.modified ?: 0L,
            file.size, uploaded.version?.etag, mimeType = mime)
        if (selectedChildren != null) remoteEdits += RemoteFileChange(server.id, parent, entry.path, entry)
        onPublished(path, entry)
    }

    // 삭제는 폴더별로 모아 양쪽 목록을 한 번씩만 다시 읽는다. 그 사이 다시 생기거나 바뀐 파일은 지우지 않는다.
    private suspend fun removeDeleted(
        relative: String,
        localDirectory: String?,
        remoteDirectory: String?,
        deletions: List<PendingDeletion>,
    ) {
        if (deletions.isEmpty()) return
        beginTransfer()
        val (localNow, remoteNow) = try {
            local.requireWritableVolume()
            val byName = selectedChildren != null
            val localEntries = localEntriesNow(relative, localDirectory, if (byName) deletions.map { it.path.substringAfterLast('/') } else null)
            val localDeletes = deletions.filter { it.local != null }
            val remoteEntries = if (localDeletes.isEmpty()) emptySet() else {
                if (byName) transport.requireDirectory(server, remoteDirectory ?: remotePath)
                remoteNamesNow(relative, remoteDirectory, if (byName) localDeletes.map { it.path.substringAfterLast('/') } else null)
            }
            localEntries to remoteEntries
        } catch (error: IOException) {
            deletions.forEach { recordFailure(it.path, error.syncOutcome()) }
            return
        } catch (_: SecurityException) {
            deletions.forEach { recordFailure(it.path, SyncOutcome.Failed) }
            return
        }
        for (deletion in deletions) {
            currentCoroutineContext().ensureActive()
            val key = syncKey(deletion.path.substringAfterLast('/'))
            try {
                val remote = deletion.remote
                if (remote != null) {
                    if (localNow == null || key in localNow) throw IOException("로컬 파일이 다시 생겼습니다.")
                    // 서버 파일은 휴지통 이름으로 옮기며, 강한 ETag가 있으면 그 사이 바뀐 파일은 옮기지 않는다.
                    val parent = transport.parent(server, remote.path)
                    val saved = files[deletion.path] ?: throw IOException("삭제할 파일의 동기화 기록이 없습니다.")
                    // 서버 체크섬이나 목록 정보로 그대로임을 알 수 있으면 내려받아 확인하지 않는다.
                    val serverHash = remote.serverSha256
                    val changedOnServer = if (serverHash != null) serverHash != saved.hash
                        else !saved.remoteUnchanged(remote) && transport.digest(server, remote.path).hex() != saved.hash
                    if (changedOnServer) {
                        tally(deletion.path) { plusConflict() }
                        continue
                    }
                    val name = trashFileName(remote.name)
                    val trash = transport.child(server, parent, name)
                    transport.renameFile(server, remote.path, trash, remote.etag)
                    if (selectedChildren != null) remoteEdits += RemoteFileChange(server.id, parent, remote.path, remote.copy(path = trash, name = name))
                    remoteChanged = true
                } else {
                    val file = requireNotNull(deletion.local)
                    if (remoteNow == null || key in remoteNow) throw IOException("서버 파일이 다시 생겼습니다.")
                    if (localNow?.get(key) != file) throw IOException("로컬 파일이 변경되었습니다.")
                    local.delete(file.id)
                }
                files.remove(deletion.path)
                tally(deletion.path) { plusDeleted() }
                changed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                recordFailure(deletion.path, error.syncOutcome())
            } catch (_: SecurityException) {
                recordFailure(deletion.path, SyncOutcome.Failed)
            }
        }
    }

    // 폴더가 목록을 읽은 뒤 사라졌다면 루트부터 따라가 부재를 확인한다. 경로를 확인할 수 없으면 null을 돌려준다.
    private fun localEntriesNow(relative: String, directory: String?, names: List<String>?): Map<String, LocalDocument>? {
        var id = directory
        if (id == null) {
            var current = location.localId
            for (name in relative.trimEnd('/').split('/')) {
                val entry = local.childDocument(current, name) ?: return emptyMap()
                if (!entry.directory) return null
                current = entry.id
            }
            id = current
        }
        val parent = id ?: return null
        return (names?.mapNotNull { local.childDocument(parent, it) } ?: local.list(parent)).associateBy { syncKey(it.name) }
    }

    private suspend fun remoteNamesNow(relative: String, directory: String?, names: List<String>?): Set<String>? {
        var path = directory
        if (path == null) {
            var current = remotePath
            for (name in relative.trimEnd('/').split('/')) {
                val entry = transport.entryAt(server, current, name) ?: return emptySet()
                if (!entry.directory) return null
                current = entry.path
            }
            path = current
        }
        val parent = path ?: return null
        return (names?.mapNotNull { transport.entryAt(server, parent, it) } ?: transport.listBeforeDeletion(server, parent))
            .mapTo(hashSetOf()) { syncKey(it.name) }
    }

    private suspend fun recoverLocal(file: LocalDocument?, backup: LocalDocument?, path: String, name: String): LocalDocument? {
        if (backup == null || backup.directory) return file
        if (file == null) return local.document(local.rename(backup, name))
        if (!file.directory && local.fingerprint(file) == local.fingerprint(backup)) {
            local.delete(backup.id)
        } else {
            local.rename(backup, recoveredName(name))
            tally(path) { plusConflict() }
        }
        changed = true
        return file
    }

    private suspend fun recoverRemote(file: RemoteEntry?, backup: RemoteEntry?, parent: String?, path: String, name: String): RemoteEntry? {
        if (backup == null || backup.directory || parent == null) return file
        if (backup.unsupported) throw IOException("서버의 백업 파일 형식을 확인할 수 없습니다.")
        if (file == null) {
            val path = transport.child(server, parent, name)
            transport.renameFile(server, backup.path, path, backup.etag)
            remoteChanged = true
            return transport.entryAt(server, parent, name)?.takeIf { it.path == path }
                ?: throw IOException("서버의 원본 파일을 복구할 수 없습니다.")
        }
        if (!file.directory && transport.digest(server, file.path).contentEquals(transport.digest(server, backup.path))) {
            transport.deleteFile(server, backup.path, backup.etag)
        } else {
            transport.renameFile(server, backup.path, transport.child(server, parent, recoveredName(name)), backup.etag)
            tally(path) { plusConflict() }
        }
        remoteChanged = true
        return file
    }

    private fun backupName(name: String, prefix: String) = "$prefix${name.sha256()}.backup"

    private fun recoveredName(name: String): String {
        val stem = name.substringBeforeLast('.').take(32)
        val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..16 }
        return "$stem-recovered-${UUID.randomUUID()}" + (extension?.let { ".$it" } ?: "")
    }

}

// 같은 폴더의 변경은 한 목록으로 처리하고 관계없는 하위 폴더에는 들어가지 않는다.
internal fun childrenForChanges(paths: Set<String>): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    for (path in paths) {
        var parent = ""
        for (name in path.split('/')) {
            val key = syncKey(name)
            result.getOrPut(parent) { mutableSetOf() }.add(name)
            parent += "$key/"
        }
    }
    return result
}

// 경로의 상위 폴더 접두사('/'로 끝남) 중 하나가 [prefixes]에 있는지 확인한다. 접두사 목록 전체를 훑지 않는다.
internal fun String.hasAncestorIn(prefixes: Set<String>): Boolean {
    if (prefixes.isEmpty()) return false
    var slash = indexOf('/')
    while (slash >= 0) {
        if (substring(0, slash + 1) in prefixes) return true
        slash = indexOf('/', slash + 1)
    }
    return false
}

internal fun isVisibleSyncName(name: String) = !name.startsWith('.') && !name.endsWith(".sgpart") &&
    !(name.startsWith(SYNC_FILE_PREFIX) && name.endsWith(".backup")) && restoredName(name) == null

private fun LocalDocument.syncStamp() = SyncStamp(size, modified)
private fun RemoteEntry.syncStamp() = SyncStamp(size, modified, etag, fileId)

/**
 * 기록한 뒤 서버 파일이 바뀌지 않았는지 목록 정보로 확인한다. 강한 ETag나 서버 체크섬이 있으면 그 값으로,
 * 없으면 크기·정확한 수정 시각·ETag가 모두 같을 때 그대로로 본다. 휴지통 이동처럼 되돌릴 수 있는 작업에만 쓴다.
 */
private fun SyncedFile.remoteUnchanged(entry: RemoteEntry): Boolean {
    if (hashForRemote(entry.syncStamp()) != null) return true
    entry.serverSha256?.let { return it == hash }
    val saved = remote ?: return false
    return !entry.modifiedTimeIsApproximate && saved.size == entry.size && saved.modified > 0 &&
        saved.modified == entry.modified && saved.etag == entry.etag
}

// SHA-256과 함께 SHA-1을 한 번에 계산한다. 서버가 SHA-1 체크섬만 가진 파일과 내용이 같은지 볼 때 쓴다.
private suspend fun LocalSyncStorage.fingerprintWithSha1(file: LocalDocument): Pair<String, String> {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val caller = currentCoroutineContext()
    val sha256 = copyAndHash(file, DigestOutputStream(DiscardOutput, sha1)) { caller.ensureActive() }
    return sha256 to sha1.digest().hex()
}

internal fun conflictCopyName(name: String, hash: String, index: Int): String {
    val stem = name.substringBeforeLast('.').take(32).trimEnd { it.isHighSurrogate() }
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..16 }
    val fingerprint = "$name\u0000$hash".sha256().take(12)
    val suffix = if (index == 1) "" else "-$index"
    return "$stem-server-conflict-$fingerprint$suffix" + (extension?.let { ".$it" } ?: "")
}

private fun String.decodeHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
