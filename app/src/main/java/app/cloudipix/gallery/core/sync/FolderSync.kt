package app.cloudipix.gallery.core.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.cloudipix.gallery.core.database.FavoriteDao
import app.cloudipix.gallery.core.database.MediaIndexChanges
import app.cloudipix.gallery.core.database.MediumEntity
import app.cloudipix.gallery.core.media.MediaPaths
import app.cloudipix.gallery.core.media.documentPath
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.core.media.treeUriForPath
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.hasHiddenFolderSegment
import app.cloudipix.gallery.core.model.isVirtualFolder
import app.cloudipix.gallery.core.remote.CloudFolderBackup
import app.cloudipix.gallery.core.remote.CloudFolderBackupEntry
import app.cloudipix.gallery.core.remote.ConnectedFolder
import app.cloudipix.gallery.core.remote.RemoteEntry
import app.cloudipix.gallery.core.remote.RemoteFailure
import app.cloudipix.gallery.core.remote.RemoteFileChange
import app.cloudipix.gallery.core.remote.RemoteFolder
import app.cloudipix.gallery.core.remote.RemoteFolderSync
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.RemoteProtocol
import app.cloudipix.gallery.core.remote.RemoteRequestException
import app.cloudipix.gallery.core.remote.RemoteServer
import app.cloudipix.gallery.core.remote.RemoteTransport
import app.cloudipix.gallery.core.remote.folderBackup
import app.cloudipix.gallery.core.remote.isRemoteDescendantOf
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.remote.photoMime
import app.cloudipix.gallery.core.remote.relativePathFrom
import app.cloudipix.gallery.core.remote.remoteFavoriteKey
import app.cloudipix.gallery.core.remote.remoteId
import app.cloudipix.gallery.core.remote.remotePathLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val MIN_SYNC_INTERVAL = 15

internal enum class AlbumSyncState { Incomplete, Syncing, Complete }

internal data class SyncHistoryItem(val folderLabel: String, val entry: FolderSyncHistoryEntry)

internal data class AllFolderSyncHistory(
    val items: List<SyncHistoryItem>,
    val hasReadError: Boolean = false,
)

private data class CachedFolderHistory(
    val revision: Long?,
    val entries: List<FolderSyncHistoryEntry>,
    val failed: Boolean,
)

internal enum class SyncSetupIssue { Location, Overlap }
internal enum class SyncLocationMode { Default, SelectedFolder, ChildFolder }

internal data class FolderConnectionSettings(
    val serverName: String,
    val folder: RemoteFolder,
    val inheritsSync: Boolean,
    val filter: SyncFileFilter?,
    val inheritedFilter: SyncFileFilter,
)

internal data class FolderConnectionLocation(val uri: Uri, val mode: SyncLocationMode)

@Serializable
internal data class FolderSyncSettingsExport(
    val intervalMinutes: Int,
    val conflictPolicy: SyncConflictPolicy,
    val defaultLocalPath: String,
    val folders: List<SyncedFolderExport>,
    val cloudFolders: List<CloudFolderBackup>? = null,
    val fileFilters: SyncFileFilterSettings? = null,
    val scheduledSyncEnabled: Boolean? = null,
    val scheduledSyncUnmeteredOnly: Boolean? = null,
)

@Serializable
internal data class SyncedFolderExport(
    val serverId: String,
    val serverName: String,
    val remotePath: String,
    val localPath: String,
    val locationMode: SyncLocationMode,
    val keepLocalFiles: Boolean,
    val excludedLocalChild: String?,
    val excludedSubfolders: Set<String>,
    val localRootPath: String? = null,
)

internal data class SyncRestoreTarget(val serverId: String, val remotePath: String, val backup: SyncedFolderExport)

internal data class SyncRestoreAccess(
    val localPath: String,
    val initialUri: Uri?,
    val useAsDefault: Boolean,
    val folders: List<SyncRestoreTarget>,
)

internal data class SyncRestoreResult(val pending: List<SyncRestoreAccess>, val complete: Boolean)

internal class SyncSetupException(val issue: SyncSetupIssue) : IOException()

@Singleton
class FolderSync @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val gallery: RemoteGallery,
    private val transport: RemoteTransport,
    private val paths: MediaPaths,
    private val store: FolderSyncStore,
    private val favorites: FavoriteDao,
) {
    private val scheduler = FolderSyncScheduler(context)
    private val operations = Mutex()
    private val transfers = Mutex()
    private val comparisons = FolderComparisonQueue()
    private val rootLocks = mutableMapOf<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val minutes = MutableStateFlow(StoredSyncPreferences().interval)
    internal val interval = minutes.asStateFlow()
    private val scheduled = MutableStateFlow(StoredSyncPreferences().scheduledSyncEnabled)
    internal val scheduledSyncEnabled = scheduled.asStateFlow()
    private val unmeteredOnly = MutableStateFlow(StoredSyncPreferences().scheduledSyncUnmeteredOnly)
    internal val scheduledSyncUnmeteredOnly = unmeteredOnly.asStateFlow()
    private val selectedConflictPolicy = MutableStateFlow(StoredSyncPreferences().conflictPolicy)
    internal val conflictPolicy = selectedConflictPolicy.asStateFlow()
    internal val fileFilters = store.fileFilters
    internal val builtInRoot = SyncRoot(
        DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE, "primary:$defaultRelativePath").toString(),
        File(Environment.getExternalStorageDirectory(), defaultRelativePath).absolutePath,
    )
    private val root = MutableStateFlow(builtInRoot)
    internal val defaultRoot = root.asStateFlow()
    private val status = MutableStateFlow<Map<String, FolderSyncStatus>>(emptyMap())
    internal val results = status.asStateFlow()
    private val active = MutableStateFlow<Map<String, FolderSyncProgress>>(emptyMap())
    internal val progress = active.asStateFlow()
    private val cleanupPending = MutableStateFlow(false)
    internal val localCleanupPending = cleanupPending.asStateFlow()
    private val currentRuns = mutableMapOf<String, Job>()
    internal val linkedFolders = gallery.linkedFolders.distinctUntilChanged()
    private var preferencesLoaded = false
    private var scheduleRestored = false
    private val mediaChangesLock = Mutex()
    private val pendingMedia = mutableMapOf<String, PendingMediaChanges>()
    private val retryMedia = mutableMapOf<String, Pair<String, PendingMediaChanges>>()

    internal suspend fun settingsForExport(): FolderSyncSettingsExport = withContext(Dispatchers.IO) {
        operations.withLock {
            loadPreferencesLocked()
            val servers = gallery.connectionsForSync()
            val serverIds = servers.mapTo(hashSetOf(), RemoteServer::id)
            val filters = store.preferences().fileFilters
            FolderSyncSettingsExport(
                intervalMinutes = interval.value,
                scheduledSyncEnabled = scheduled.value,
                scheduledSyncUnmeteredOnly = unmeteredOnly.value,
                conflictPolicy = conflictPolicy.value,
                defaultLocalPath = defaultRoot.value.label,
                folders = servers.syncTargets().map { (server, folder, sync) ->
                    SyncedFolderExport(
                        serverId = server.id,
                        serverName = server.name,
                        remotePath = folder.displayPath,
                        localPath = sync.localLabel,
                        locationMode = sync.locationMode,
                        keepLocalFiles = sync.keepLocalFiles,
                        excludedLocalChild = sync.excludedLocalChild,
                        excludedSubfolders = sync.excludedSubfolders,
                        localRootPath = localTreePath(Uri.parse(sync.treeUri)),
                    )
                },
                cloudFolders = servers.map(RemoteServer::folderBackup),
                fileFilters = filters.copy(folders = filters.folders.filter { it.serverId in serverIds }),
            )
        }
    }

    internal suspend fun restoreSettings(backup: FolderSyncSettingsExport): SyncRestoreResult = changeLinks {
        val cloudFolders = backup.cloudFolders ?: backup.folders.groupBy(SyncedFolderExport::serverId).map { (id, folders) ->
            val name = folders.first().serverName
            CloudFolderBackup(serverId = id, serverName = name, folders = folders.map { folder ->
                CloudFolderBackupEntry(
                    path = folder.remotePath,
                    name = folder.remotePath.trimEnd('/').substringAfterLast('/').ifEmpty { name },
                    includeSubfolders = true,
                )
            })
        }
        val cloud = gallery.restoreFolderSelections(cloudFolders)
        val treeGrants = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .mapNotNull { grant -> localTreePath(grant.uri)?.let { it to grant.uri } }
            .toMap()
        // 모든 파일 접근 권한이 있으면 폴더 선택기 없이 백업에 적힌 경로를 그대로 쓴다.
        val allFilesAccess = hasAllFilesAccess()
        val grantFor = { path: String -> treeGrants[path] ?: if (allFilesAccess) context.treeUriForPath(path) else null }
        val defaultPath = backup.defaultLocalPath.trimEnd('/')
        val restoredRoot = if (defaultPath == builtInRoot.label) {
            builtInRoot
        } else {
            grantFor(defaultPath)?.let { SyncRoot(it.toString(), defaultPath) }
        }
        val servers = gallery.connectionsForSync()
        val savedRoot = restoredRoot ?: root.value
        val saved = store.preferences()
        val restoredSchedule = backup.scheduledSyncEnabled ?: saved.scheduledSyncEnabled
        val restoredUnmetered = backup.scheduledSyncUnmeteredOnly ?: saved.scheduledSyncUnmeteredOnly
        val restoredFilters = backup.fileFilters?.let { imported ->
            val mapped = imported.folders.mapNotNull { filter ->
                cloud.serverIds[filter.serverId]?.let { filter.copy(serverId = it) }
            }
            imported.copy(folders = saved.fileFilters.folders.filterNot { it.serverId in cloud.serverIds.values } + mapped)
        } ?: saved.fileFilters
        store.savePreferences(saved.copy(
            interval = backup.intervalMinutes,
            scheduledSyncEnabled = restoredSchedule,
            scheduledSyncUnmeteredOnly = restoredUnmetered,
            conflictPolicy = backup.conflictPolicy,
            defaultRoot = savedRoot.takeUnless { it == builtInRoot },
            fileFilters = restoredFilters,
        ))
        root.value = savedRoot
        minutes.value = backup.intervalMinutes
        scheduled.value = restoredSchedule
        unmeteredOnly.value = restoredUnmetered
        selectedConflictPolicy.value = backup.conflictPolicy
        scheduler.schedule(backup.intervalMinutes, servers.any(RemoteServer::hasLinkedFolders), restoredSchedule)

        var complete = cloud.complete
        val foldersByRoot = linkedMapOf<String, MutableList<SyncRestoreTarget>>()
        if (restoredRoot == null) foldersByRoot[defaultPath] = mutableListOf()
        for (folder in backup.folders) {
            val server = servers.firstOrNull { it.id == cloud.serverIds[folder.serverId] }
            val remote = server?.folders.orEmpty().firstOrNull { it.linked && it.displayPath == folder.remotePath }
            if (server == null || remote == null || !transport.isVisibleFolder(server, remote.path)) {
                complete = false
                continue
            }
            val base = folder.localRootPath ?: folder.locationMode.backupRoot(folder.localPath)
            foldersByRoot.getOrPut(base.trimEnd('/')) { mutableListOf() }.add(SyncRestoreTarget(server.id, remote.path, folder))
        }
        val pending = buildList {
            for ((path, folders) in foldersByRoot) {
                val access = SyncRestoreAccess(path, context.treeUriForPath(path), path == defaultPath, folders)
                val grant = grantFor(path)
                val restored = grant?.let { uri ->
                    try { restoreAccessLocked(access, uri) } catch (_: IOException) { null } catch (_: SecurityException) { null }
                }
                if (restored == null) add(access)
                else if (!restored) complete = false
            }
        }
        SyncRestoreResult(pending, complete)
    }

    internal suspend fun restoreAccess(access: SyncRestoreAccess, uri: Uri): Boolean? = changeLinks {
        restoreAccessLocked(access, uri)
    }

    private fun localTreePath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE || !DocumentsContract.isTreeUri(uri)) return null
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        if (treeId.substringAfter(':').hasHiddenFolderSegment()) return null
        return context.documentPath(treeId)
    }

    // 모든 파일 접근 권한이 있으면 경로로 직접 다루고, 없으면 폴더 선택기로 받은 지속 권한을 쓴다.
    private fun hasTreeGrant(uri: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    private fun hasLocalAccess(uri: Uri) = hasAllFilesAccess() || hasTreeGrant(uri)

    private fun openLocal(uri: Uri): LocalSyncStorage =
        if (hasAllFilesAccess()) LocalFileTree(context, uri) else LocalSyncTree(context, uri)

    private fun takeTreeGrant(uri: Uri) {
        if (!hasAllFilesAccess()) context.contentResolver.takePersistableUriPermission(uri, TREE_GRANT)
    }

    private suspend fun restoreAccessLocked(access: SyncRestoreAccess, uri: Uri): Boolean? {
        val chosen = describeRoot(uri)
        if (chosen.label != access.localPath) return null
        val hadGrant = hasLocalAccess(uri)
        var complete = true
        try {
            if (!hadGrant) takeTreeGrant(uri)
            if (access.useAsDefault) {
                store.savePreferences(store.preferences().copy(defaultRoot = chosen.takeUnless { it == builtInRoot }))
                root.value = chosen
            }
            for (target in access.folders) {
                try {
                    val (server, folder) = gallery.linkedFolder(target.serverId, target.remotePath)
                    val location = prepareFolderLocation(server, folder, uri, target.backup.locationMode, target.backup)
                    gallery.setFolderSync(server.id, folder.path, location)
                } catch (_: IOException) {
                    complete = false
                } catch (_: SecurityException) {
                    complete = false
                }
            }
        } finally {
            if (!hadGrant) withContext(NonCancellable) {
                val tree = chosen.treeUri
                if (root.value.treeUri != tree && configured().none { it.sync.treeUri == tree } &&
                    store.pendingCleanup().none { it.treeUri == tree }
                ) release(tree)
            }
        }
        return complete
    }

    // operations 잠금 안에서만 호출한다. 저장에 실패한 이전 기록은 다음 진입 때 다시 옮긴다.
    private suspend fun loadPreferencesLocked() {
        if (preferencesLoaded) return
        val saved = store.preferences()
        root.value = saved.defaultRoot ?: builtInRoot
        if (saved.legacyFolders.isNotEmpty()) {
            gallery.importFolderSync(saved.legacyFolders.groupBy(LegacySyncedFolder::serverId)
                .mapValues { (_, folders) -> folders.map(LegacySyncedFolder::asFolder) })
        }
        val targets = configured()
        val legacyStatus = saved.legacyFolders.associate { it.id to it.status() }
        var retryHistory = false
        val restored = targets.mapNotNull { target ->
            val id = target.sync.id
            val result = try {
                store.loadStatus(target.folderId, target.sync, legacyStatus[id])
            } catch (_: IOException) {
                if (id in legacyStatus) retryHistory = true
                legacyStatus[id]
            }
            result?.let { id to it }
        }.toMap()
        status.update { restored + it }
        if (saved.legacyFolders.isNotEmpty()) {
            cleanupRemoved(saved.legacyFolders.map(LegacySyncedFolder::location), targets.map(SyncTarget::sync))
            if (!retryHistory) try {
                store.savePreferences(saved.copy(legacyFolders = emptyList()))
            } catch (_: IOException) {
                retryHistory = true
            }
        }
        minutes.value = saved.interval
        scheduled.value = saved.scheduledSyncEnabled
        unmeteredOnly.value = saved.scheduledSyncUnmeteredOnly
        selectedConflictPolicy.value = saved.conflictPolicy
        preferencesLoaded = !retryHistory
    }

    fun onAppOpened() {
        scope.launch {
            try {
                operations.withLock {
                    if (scheduleRestored) return@withLock
                    if (store.pendingCleanup().isNotEmpty()) {
                        cleanupPending.value = true
                        scheduler.enqueueCleanup()
                    }
                    loadPreferencesLocked()
                    scheduler.schedule(minutes.value, hasLinkedFolders(), scheduled.value)
                    updateMediaWatchLocked()
                    scheduleRestored = true
                }
            } catch (_: IOException) {
                // 동기화 설정을 읽을 수 없어도 갤러리는 열 수 있다.
            }
        }
    }

    internal fun foldersForAlbum(bucketId: Long): Flow<List<ConnectedFolder>> = linkedFolders.map { folders ->
        if (isVirtualFolder(bucketId)) return@map emptyList()
        if (isRemoteId(bucketId)) return@map folders.filter { it.bucketId == bucketId }
        val configured = folders.filter { it.folder.sync != null }
        if (configured.isEmpty()) return@map emptyList()
        val locations = localFolders(setOf(bucketId)).values.flatten()
        val pathsForBucket = locations.mapTo(hashSetOf()) { syncKey(it.trimEnd('/')) }
        val exact = folders.filter { link ->
            link.configuredLocalPath?.let { syncKey(it.trimEnd('/')) in pathsForBucket } == true
        }
        if (exact.isNotEmpty()) return@map exact
        configured.filter { connected ->
            connected.folder.sync?.let { location ->
                storagePath(location)?.let { root -> locations.any { containsSyncedLocalPath(location, root, it) } }
            } == true
        }
    }.distinctUntilChanged()

    internal fun albumSyncStates(localBuckets: Flow<Set<Long>>): Flow<Map<Long, AlbumSyncState>> = combine(
        albumSyncIds(localBuckets), progress.map { it.keys }.distinctUntilChanged(), results,
    ) { albums, activeIds, statuses ->
        albums.mapValues { (_, ids) ->
            when {
                ids.any(activeIds::contains) -> AlbumSyncState.Syncing
                ids.all { statuses[it]?.isComplete == true } -> AlbumSyncState.Complete
                else -> AlbumSyncState.Incomplete
            }
        }
    }.distinctUntilChanged()

    private fun albumSyncIds(localBuckets: Flow<Set<Long>>): Flow<Map<Long, List<String>>> = combine(
        linkedFolders, localBuckets,
    ) { folders, buckets ->
        val configured = folders.filter { it.folder.sync != null }
        if (configured.isEmpty()) return@combine emptyMap<Long, List<String>>()
        val localPaths = localFolders(buckets)
        buildList<Pair<Long, String>> {
            for (link in folders) link.effectiveSync?.let { add(link.bucketId to it.id) }
            for (link in configured) {
                val sync = link.folder.sync ?: continue
                val root = storagePath(sync) ?: continue
                localPaths.forEach { (id, locations) ->
                    if (locations.any { containsSyncedLocalPath(sync, root, it) }) add(id to sync.id)
                }
            }
        }.groupBy({ it.first }, { it.second })
    }.distinctUntilChanged()

    internal fun relativeDestination(link: ConnectedFolder, mode: SyncLocationMode): String =
        destinationParts(link.serverName, link.folder, mode).joinToString("/")

    internal suspend fun describeRoot(uri: Uri): SyncRoot = withContext(Dispatchers.IO) {
        val tree = writableTree(uri)
        if (tree.treeId.substringAfter(':').hasHiddenFolderSegment()) throw SyncSetupException(SyncSetupIssue.Location)
        SyncRoot(uri.toString(), tree.storagePath(tree.treeId) ?: throw SyncSetupException(SyncSetupIssue.Location))
    }

    internal suspend fun prepareRootAndCheckAccess(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val granted = hasLocalAccess(uri)
        if (uri.toString() == builtInRoot.treeUri) {
            val tree = writableTree(uri)
            val directory = File(builtInRoot.label)
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, tree.treeId)
                if (!granted || resolver.getType(document) != DocumentsContract.Document.MIME_TYPE_DIR) {
                    throw SyncSetupException(SyncSetupIssue.Location)
                }
            }
        }
        granted
    }

    internal suspend fun setDefaultRoot(uri: Uri?): Unit = withContext(Dispatchers.IO) {
        operations.withLock {
            loadPreferencesLocked()
            val old = root.value
            val retained = configured().map(SyncTarget::sync) + store.pendingCleanup()
            val chosen = uri?.let { describeRoot(it) }
            val saved = store.preferences()
            if (uri != null) takeTreeGrant(uri)
            try {
                store.savePreferences(saved.copy(defaultRoot = chosen))
            } catch (error: IOException) {
                if (chosen != null && chosen.treeUri != old.treeUri && retained.none { it.treeUri == chosen.treeUri }) release(chosen.treeUri)
                throw error
            }
            root.value = chosen ?: builtInRoot
            if (old.treeUri != root.value.treeUri && retained.none { it.treeUri == old.treeUri }) release(old.treeUri)
        }
    }

    internal suspend fun setLocation(bucketId: Long, uri: Uri, mode: SyncLocationMode) {
        val id = changeLinks {
            if (mode == SyncLocationMode.Default && uri.toString() != root.value.treeUri) throw SyncSetupException(SyncSetupIssue.Location)
            val link = linkedFolders.first().firstOrNull { it.bucketId == bucketId }
                ?: throw IOException("연결된 폴더를 찾을 수 없습니다.")
            if (link.inheritedFrom != null) throw SyncSetupException(SyncSetupIssue.Overlap)
            val (server, folder) = gallery.linkedFolder(link.serverId, link.folder.path)
            withLocationGrant(uri) {
                val location = prepareFolderLocation(server, folder, uri, mode)
                gallery.setFolderSync(server.id, folder.path, location)
                location.id
            }
        }
        scheduler.enqueue(id)
    }

    internal suspend fun stopSync(link: ConnectedFolder, deleteLocalCopies: Boolean) =
        changeLinks(cleanCopiesOf = { deleteLocalCopies && it.matches(link) }) {
            gallery.setFolderSync(link.serverId, link.folder.path, null)
        }

    internal suspend fun setSubfolderIncluded(bucketId: Long, included: Boolean) = changeLinks {
        val link = linkedFolders.first().firstOrNull { it.bucketId == bucketId }
            ?: throw IOException("연결된 폴더를 찾을 수 없습니다.")
        val parent = link.inheritedFrom ?: throw IOException("상위 동기화 폴더를 찾을 수 없습니다.")
        val sync = parent.sync ?: throw IOException("상위 동기화 설정을 찾을 수 없습니다.")
        val relative = link.inheritedRelativePath ?: throw IOException("하위 동기화 경로를 확인할 수 없습니다.")
        if (link.excludedByAncestor || included == !link.excludedFromSync) return@changeLinks
        val excluded = if (included) {
            sync.excludedSubfolders.filterNot { syncKey(it) == syncKey(relative) }.toSet()
        } else {
            sync.excludedSubfolders + relative
        }
        gallery.setFolderSync(link.serverId, parent.path, sync.copy(excludedSubfolders = excluded))
    }

    internal suspend fun unlink(link: ConnectedFolder, deleteLocalCopies: Boolean) =
        changeLinks(cleanCopiesOf = { deleteLocalCopies && it.matches(link) }) {
            gallery.unlinkFolder(link.serverId, link.folder.path)
        }

    internal suspend fun folderConnectionSettings(serverId: String, folder: RemoteFolder): FolderConnectionSettings =
        withContext(Dispatchers.IO) {
            operations.withLock {
                loadPreferencesLocked()
                val server = connectionForFolder(serverId, folder)
                val existing = server.folders.orEmpty().firstOrNull { it.path == folder.path && it.linked }
                val settings = store.preferences().fileFilters
                val path = folder.displayPath.trimEnd('/')
                val overrides = settings.overridesFor(serverId)
                val inherited = if (path.isEmpty()) settings.global else
                    overrides.filterForFolder(path.substringBeforeLast('/', ""), settings.global)
                FolderConnectionSettings(
                    server.name, existing ?: folder.copy(includeSubfolders = true),
                    existing?.sync == null && server.syncAncestor(folder) != null, overrides[path], inherited,
                )
            }
        }

    internal suspend fun saveFolderConnection(
        serverId: String,
        folder: RemoteFolder,
        includeSubfolders: Boolean,
        subfolderChanges: List<RemoteFolder>,
        enableSync: Boolean,
        location: FolderConnectionLocation?,
        filter: SyncFileFilter?,
    ) {
        val syncId = changeLinks {
            val server = connectionForFolder(serverId, folder)
            val existing = server.folders.orEmpty().firstOrNull { it.path == folder.path && it.linked }
            val selected = folder.copy(sync = existing?.sync, linked = true, includeSubfolders = includeSubfolders)
            val ancestor = if (existing?.sync == null) server.syncAncestor(folder) else null
            val prefix = ancestor?.let {
                folder.relativePathFrom(it) ?: throw RemoteRequestException(RemoteFailure.Address)
            }
            val subfolders = subfolderChanges.map { child ->
                if (!transport.isFolderUnderRoot(server, child.path) || !transport.isVisibleFolder(server, child.path)
                ) throw RemoteRequestException(RemoteFailure.Address)
                val relative = child.relativePathFrom(folder) ?: throw RemoteRequestException(RemoteFailure.Address)
                child to (if (prefix == null) relative else "$prefix/$relative")
            }
            val updated = server.folders.orEmpty().associateByTo(linkedMapOf(), RemoteFolder::path)
            for ((child, _) in subfolders) {
                val previous = updated[child.path]
                updated[child.path] = (previous ?: child).copy(linked = child.linked, sync = previous?.sync?.takeIf { child.linked })
            }
            val refreshPaths = subfolderChanges.filter(RemoteFolder::linked).mapTo(mutableSetOf(folder.path), RemoteFolder::path)
            fun RemoteFolderSync.applySubfolderChanges(includeSavedExclusions: Boolean): RemoteFolderSync {
                if (subfolders.isEmpty() && !includeSavedExclusions) return this
                val changes = subfolders.mapTo(hashSetOf()) { (_, relative) -> syncKey(relative) }
                val exclusions = excludedSubfolders.filterNotTo(linkedSetOf()) { syncKey(it) in changes }
                for ((child, relative) in subfolders) {
                    if (!child.linked) exclusions += relative
                }
                if (includeSavedExclusions) {
                    updated.values.asSequence().filterNot(RemoteFolder::linked)
                        .mapNotNull { it.relativePathFrom(folder) }.toCollection(exclusions)
                }
                return if (exclusions == excludedSubfolders) this else copy(excludedSubfolders = exclusions)
            }
            suspend fun save(sync: RemoteFolderSync?) = withContext(NonCancellable) {
                val saved = store.preferences()
                val filters = saved.fileFilters.withFolderFilter(serverId, folder.displayPath.trimEnd('/'), filter)
                if (filters != saved.fileFilters) store.savePreferences(saved.copy(fileFilters = filters))
                try {
                    updated[folder.path] = selected.copy(sync = sync?.applySubfolderChanges(existing?.sync == null))
                    ancestor?.let { parent ->
                        val syncFromParent = parent.sync ?: return@let
                        updated[parent.path] = parent.copy(sync = syncFromParent.applySubfolderChanges(false))
                    }
                    gallery.saveFolderSelection(server, server.withFolders(updated.values.toList()), refreshPaths)
                } catch (error: Exception) {
                    if (filters != saved.fileFilters) {
                        try {
                            store.savePreferences(saved)
                        } catch (rollbackError: IOException) {
                            error.addSuppressed(rollbackError)
                        }
                    }
                    throw error
                }
            }

            if (location != null) {
                if (ancestor != null) throw SyncSetupException(SyncSetupIssue.Overlap)
                if (location.mode == SyncLocationMode.Default && location.uri.toString() != root.value.treeUri) {
                    throw SyncSetupException(SyncSetupIssue.Location)
                }
                withLocationGrant(location.uri) {
                    val excludedPaths = subfolderChanges.filterNot(RemoteFolder::linked).mapTo(hashSetOf(), RemoteFolder::path)
                    val sync = prepareFolderLocation(server, selected, location.uri, location.mode, unlinkedPaths = excludedPaths)
                    save(sync)
                    sync.id
                }
            } else {
                val sync = if (enableSync && ancestor == null) {
                    existing?.sync ?: throw SyncSetupException(SyncSetupIssue.Location)
                } else null
                save(sync)
                null
            }
        }
        if (syncId != null) scheduler.enqueue(syncId)
    }

    private suspend fun connectionForFolder(serverId: String, folder: RemoteFolder): RemoteServer {
        val server = gallery.connectionsForSync().firstOrNull { it.id == serverId }
            ?: throw RemoteRequestException(RemoteFailure.Storage)
        if (!transport.isFolderUnderRoot(server, folder.path) || !transport.isVisibleFolder(server, folder.path)) {
            throw RemoteRequestException(RemoteFailure.Address)
        }
        return server
    }

    private fun RemoteServer.syncAncestor(folder: RemoteFolder): RemoteFolder? = folders.orEmpty().asSequence()
        .filter { it.linked && it.sync != null && folder.path.isRemoteDescendantOf(it.path) && transport.isVisibleFolder(this, it.path) }
        .maxByOrNull { it.path.length }

    internal suspend fun saveServer(
        name: String, protocol: RemoteProtocol, address: String, username: String, password: String, editing: String?,
    ): String = changeLinks(loadPreferences = false) {
        gallery.save(name, protocol, address, username, password, editing)
    }

    internal suspend fun removeServer(id: String): Boolean = changeLinks(loadPreferences = false, cleanCopiesOf = { it.server.id == id }) {
        gallery.remove(id)
    }

    internal suspend fun setInterval(value: Int) = withContext(Dispatchers.IO) {
        require(value >= MIN_SYNC_INTERVAL)
        operations.withLock {
            loadPreferencesLocked()
            store.savePreferences(store.preferences().copy(interval = value))
            minutes.value = value
            scheduler.schedule(value, hasLinkedFolders(), scheduled.value)
        }
    }

    internal suspend fun setScheduledSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        operations.withLock {
            loadPreferencesLocked()
            if (scheduled.value == enabled) return@withLock
            store.savePreferences(store.preferences().copy(scheduledSyncEnabled = enabled))
            scheduled.value = enabled
            scheduler.schedule(minutes.value, hasLinkedFolders(), enabled)
        }
    }

    internal suspend fun setScheduledSyncUnmeteredOnly(enabled: Boolean) = withContext(Dispatchers.IO) {
        operations.withLock {
            loadPreferencesLocked()
            if (unmeteredOnly.value == enabled) return@withLock
            store.savePreferences(store.preferences().copy(scheduledSyncUnmeteredOnly = enabled))
            unmeteredOnly.value = enabled
        }
    }

    // 작업 제약 대신 실행할 때 확인한다. 인터넷 연결이 확인되지 않은 내부망 Wi-Fi에서도 예약 동기화가 돌아야 하기 때문이다.
    private fun isMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    internal suspend fun setConflictPolicy(value: SyncConflictPolicy) = withContext(Dispatchers.IO) {
        operations.withLock {
            loadPreferencesLocked()
            if (selectedConflictPolicy.value == value) return@withLock
            store.savePreferences(store.preferences().copy(conflictPolicy = value))
            selectedConflictPolicy.value = value
        }
    }

    internal suspend fun setGlobalFileFilter(filter: SyncFileFilter) = updateFileFilters { it.copy(global = filter) }

    internal suspend fun setFolderFileFilter(serverId: String, folder: RemoteFolder, filter: SyncFileFilter?) = updateFileFilters { current ->
        connectionForFolder(serverId, folder)
        current.withFolderFilter(serverId, folder.displayPath.trimEnd('/'), filter)
    }

    private suspend fun updateFileFilters(update: suspend (SyncFileFilterSettings) -> SyncFileFilterSettings) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                loadPreferencesLocked()
                val saved = store.preferences()
                val filters = update(saved.fileFilters)
                if (saved.fileFilters == filters) return@withLock
                store.savePreferences(saved.copy(fileFilters = filters))
            }
        }

    internal suspend fun enqueue(id: String, subfolder: String? = null) {
        scheduler.enqueue(id, subfolder = subfolder)
    }

    internal suspend fun localMoveTarget(folder: FolderEntry, items: List<Medium>): FolderEntry? =
        withContext(Dispatchers.IO) {
            if (!isRemoteId(folder.bucketId) || items.isEmpty() || items.any(Medium::isRemote)) return@withContext null
            val link = gallery.linkedFolders.first().firstOrNull { it.bucketId == folder.bucketId } ?: return@withContext null
            val location = link.effectiveSync ?: return@withContext null
            val root = storagePath(location) ?: return@withContext null
            val relative = link.inheritedRelativePath
            val destination = if (relative.isNullOrEmpty()) root else "$root/$relative"
            if (!containsSyncedLocalPath(location, root, destination)) return@withContext null
            if (!hasLocalAccess(Uri.parse(location.treeUri))) return@withContext null
            val path = LocalStoragePath.absolute(destination)
            val volume = MediaStore.getExternalVolumeNames(context)
                .firstOrNull { it.equals(path.volumeName, ignoreCase = true) } ?: return@withContext null
            val directory = path.path.trim('/')
            val rootName = directory.substringBefore('/')
            if (rootName !in setOf("DCIM", "Pictures", "Download")) return@withContext null
            val filter = gallery.folderFileFilters.first()[folder.bucketId]
            val excludedChild = location.excludedLocalChild.takeIf { relative.isNullOrEmpty() }
            if (items.any { item ->
                val mime = photoMime(item.name)
                val entry = RemoteEntry("", item.name, directory = false, mimeType = mime)
                entry.type == null || !isVisibleSyncName(item.name) ||
                    item.name.equals(excludedChild, ignoreCase = true) || filter?.allows(item.name, mime, null) == false
            }) return@withContext null
            // 목록에 이미 있는 같은 이름의 파일은 기존 서버 이동의 충돌 검사로 처리한다.
            val names = gallery.albums.first().firstOrNull { it.id == folder.bucketId }?.photos.orEmpty()
                .mapTo(hashSetOf()) { syncKey(it.name) }
            if (items.any { syncKey(it.name) in names }) return@withContext null
            folder.copy(path = "$directory/", volumeName = volume)
        }

    internal suspend fun remoteFilesChanged(edits: List<RemoteFileChange>) {
        val targets = watchedLocationsWithFolders()
        for (target in targets) {
            val paths = mutableMapOf<String, Boolean>()
            val prefix = target.folder.displayPath.trimEnd('/') + "/"
            for (edit in edits) {
                if (target.server.id != edit.serverId) continue
                for (path in listOfNotNull(edit.before, edit.after?.path)) {
                    val full = remotePathLabel(path)
                    if (!full.startsWith(prefix)) continue
                    val relative = full.removePrefix(prefix)
                    if (relative.split('/').all { validSyncName(it) && isVisibleSyncName(it) } &&
                        !target.sync.excludesSubfolder(relative)) paths[relative] = path == edit.after?.path
                }
            }
            if (paths.isNotEmpty()) enqueueChanges(target.sync.id, mutableMapOf(), paths)
        }
    }

    internal suspend fun restoreMediaWatch() = withContext(Dispatchers.IO) {
        operations.withLock { updateMediaWatchLocked() }
    }

    private suspend fun updateMediaWatchLocked() {
        MediaChangeJob.setEnabled(context, watchedLocations().isNotEmpty())
    }

    private suspend fun watchedLocationsWithFolders() = configured().filter { it.isVisibleSyncTarget() }
    internal suspend fun watchedLocations() = watchedLocationsWithFolders().map(SyncTarget::sync)

    internal suspend fun onMediaIndexChanged(changes: MediaIndexChanges) = withContext(Dispatchers.IO) {
        if (changes.removed.isEmpty() && changes.changed.isEmpty()) return@withContext
        val targets = try {
            watchedLocations()
        } catch (error: IOException) {
            Log.w("FolderSync", "동기화 설정을 읽지 못해 변경 내용을 다음 동기화에서 반영합니다.", error)
            return@withContext
        }
        if (targets.isEmpty()) return@withContext
        val byRoot = mutableMapOf<String, MutableMap<String, SyncStamp?>>()
        val directories = mutableMapOf<Pair<Long, String>, List<Pair<String, String>>>()
        fun add(item: MediumEntity, stamp: SyncStamp?) {
            if (!isVisibleSyncName(item.name)) return
            val owners = directories.getOrPut(item.bucketId to item.relativePath) {
                targets.mapNotNull { root -> root.mediaDirectory(item)?.let { root.id to it } }
            }
            for ((id, prefix) in owners) {
                byRoot.getOrPut(id) { mutableMapOf() }[prefix + item.name] = stamp
            }
        }
        changes.removed.forEach { add(it, null) }
        changes.changed.forEach { add(it, SyncStamp(it.size, it.dateModified)) }
        for ((id, paths) in byRoot) enqueueChanges(id, paths)
    }

    private suspend fun enqueueChanges(id: String, paths: MutableMap<String, SyncStamp?>, remotePaths: Map<String, Boolean> = emptyMap()) = mediaChangesLock.withLock {
        val pending = pendingMedia[id]
        if (pending == null) {
            val token = UUID.randomUUID().toString()
            scheduler.enqueueChange(id, token)
            pendingMedia[id] = PendingMediaChanges(token, paths, remotePaths.toMutableMap())
        } else {
            pending.paths.putAll(paths)
            pending.remotePaths.putAll(remotePaths)
        }
        Unit
    }

    internal fun runHistory(folderId: Long): Flow<Result<List<FolderSyncHistoryEntry>>> = store.historyRevisions
        .map { it[folderId] }
        .distinctUntilChanged()
        .map { readHistory(folderId) }
        .flowOn(Dispatchers.IO)

    internal fun allRunHistory(): Flow<AllFolderSyncHistory> = flow {
        val existingIds = store.historyFolderIds()
        val cached = mutableMapOf<Long, CachedFolderHistory>()
        val folders = gallery.observeConnectionsForSync().map { servers ->
            buildMap {
                for (server in servers) {
                    for (folder in server.folders.orEmpty()) {
                        if (folder.linked) put(remoteId(server.id, folder.path), "${server.name} · ${folder.displayPath}")
                    }
                }
            }
        }.distinctUntilChanged()

        val history = combine(folders, store.historyRevisions) { labels, revisions ->
            cached.keys.retainAll(labels.keys)
            var hasReadError = false
            val items = buildList {
                for ((folderId, label) in labels) {
                    val revision = revisions[folderId]
                    if (existingIds != null && folderId !in existingIds && revision == null) continue
                    currentCoroutineContext().ensureActive()
                    val previous = cached[folderId]
                    val folder = if (previous != null && previous.revision == revision) previous else {
                        val runs = readHistory(folderId)
                        val entries = runs.getOrNull()?.map { entry ->
                            entry.copy(result = entry.result.copy(subfolders = emptyMap()))
                        }
                        CachedFolderHistory(revision, entries ?: previous?.entries.orEmpty(), runs.isFailure)
                            .also { cached[folderId] = it }
                    }
                    hasReadError = hasReadError || folder.failed
                    folder.entries.forEach { add(SyncHistoryItem(label, it)) }
                }
            }.sortedByDescending { it.entry.result.lastRun }
            AllFolderSyncHistory(items, hasReadError)
        }
        emitAll(history)
    }.catch { error ->
        if (error !is IOException) throw error
        emit(AllFolderSyncHistory(emptyList(), hasReadError = true))
    }.flowOn(Dispatchers.IO)

    private fun readHistory(folderId: Long): Result<List<FolderSyncHistoryEntry>> = try {
        Result.success(store.readRuns(folderId))
    } catch (error: IOException) {
        Result.failure(error)
    }

    private suspend fun <T> changeLinks(
        loadPreferences: Boolean = true, cleanCopiesOf: (SyncTarget) -> Boolean = { false }, change: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        operations.withLock {
            if (loadPreferences) loadPreferencesLocked()
            val previousServers = gallery.connectionsForSync()
            val wasScheduled = previousServers.any(RemoteServer::hasLinkedFolders)
            val targets = previousServers.syncTargets()
            val before = targets.map(SyncTarget::sync)
            val pending = unclaimedCleanup(before)
            val cleanup = targets.filter { it.isVisibleSyncTarget() && cleanCopiesOf(it) }
                .map(SyncTarget::sync).filterNot(RemoteFolderSync::keepLocalFiles)
            if (cleanup.isNotEmpty()) {
                // 연결 저장 직후 프로세스가 종료되어도 정리할 위치와 접근 권한을 잃지 않는다.
                store.savePendingCleanup((pending + cleanup).distinctBy(RemoteFolderSync::id))
                cleanupPending.value = true
                scheduler.enqueueCleanup()
            }
            try {
                change()
            } finally {
                withContext(NonCancellable) {
                    // 연결 저장을 기준으로 작업과 저장소 권한을 정리한다.
                    val servers = gallery.connectionsForSync()
                    val after = servers.syncTargets().map(SyncTarget::sync)
                    if (before.toSet() != after.toSet()) {
                        cleanupRemoved(before, after)
                        updateMediaWatchLocked()
                    } else if (cleanup.isNotEmpty()) {
                        unclaimedCleanup(after)
                    }
                    if (previousServers != servers) {
                        (previousServers.linkedFolderIds() - servers.linkedFolderIds()).forEach(store::removeRuns)
                    }
                    val shouldSchedule = servers.any(RemoteServer::hasLinkedFolders)
                    if (wasScheduled != shouldSchedule) {
                        val preferences = store.preferences()
                        scheduler.schedule(preferences.interval, shouldSchedule, preferences.scheduledSyncEnabled)
                    }
                }
            }
        }
    }

    private fun writableTree(uri: Uri): LocalSyncStorage {
        if (uri.authority != EXTERNAL_STORAGE || !DocumentsContract.isTreeUri(uri)) throw SyncSetupException(SyncSetupIssue.Location)
        val tree = openLocal(uri)
        tree.requireWritableVolume()
        return tree
    }

    private suspend fun <T> withLocationGrant(uri: Uri, save: suspend () -> T): T {
        val hadGrant = hasLocalAccess(uri)
        try {
            return save()
        } catch (error: Exception) {
            if (!hadGrant) withContext(NonCancellable) {
                val tree = uri.toString()
                if (root.value.treeUri != tree && configured().none { it.sync.treeUri == tree } &&
                    store.pendingCleanup().none { it.treeUri == tree }) release(tree)
            }
            throw error
        }
    }

    private suspend fun prepareFolderLocation(
        server: RemoteServer, folder: RemoteFolder, uri: Uri, mode: SyncLocationMode, restore: SyncedFolderExport? = null,
        unlinkedPaths: Set<String> = emptySet(),
    ): RemoteFolderSync {
        val otherTargets = configured().filterNot { it.sync.id == folder.sync?.id }
        val overlapsRemote = otherTargets.any {
            val willUnlink = server.id == it.server.id && it.folder.path in unlinkedPaths
            !willUnlink && sameEndpoint(server, it.server) && overlap(folder.path, it.folder.path)
        }
        if (overlapsRemote) throw SyncSetupException(SyncSetupIssue.Overlap)
        val others = otherTargets.map(SyncTarget::sync)
        val tree = writableTree(uri)
        val parts = if (restore == null) destinationParts(server.name, folder, mode) else {
            val base = tree.storagePath(tree.treeId) ?: throw SyncSetupException(SyncSetupIssue.Location)
            val path = restore.localPath.trimEnd('/')
            when {
                mode == SyncLocationMode.SelectedFolder && path == base -> emptyList()
                mode != SyncLocationMode.SelectedFolder && path.startsWith("$base/") -> {
                    path.removePrefix("$base/").split('/').also { segments ->
                        if (segments.any { !validSyncName(it) } ||
                            (mode == SyncLocationMode.ChildFolder && segments.size != 1)
                        ) throw SyncSetupException(SyncSetupIssue.Location)
                    }
                }
                else -> throw SyncSetupException(SyncSetupIssue.Location)
            }
        }
        val expected = parts.fold(tree.treeId, ::syncChildId)
        if (expected.substringAfter(':').hasHiddenFolderSegment()) throw SyncSetupException(SyncSetupIssue.Location)
        val previous = folder.sync
        val previousTreeId = previous?.takeIf { it.directChild }?.treeUri?.let {
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrNull()
        }
        val oldChild = legacySyncChild(previous, previousTreeId, expected)
        if (others.any { overlap(it.localId, expected) } ||
            (previous != null && oldChild == null && syncKey(previous.localId) != syncKey(expected) && overlap(previous.localId, expected))
        ) throw SyncSetupException(SyncSetupIssue.Overlap)

        if (restore == null) takeTreeGrant(uri)
        val existingCustomChild = mode == SyncLocationMode.ChildFolder && previous?.localId != expected &&
            tree.childDocument(tree.treeId, parts.single())?.directory == true
        var restoredExistingFolder = false
        val id = parts.foldIndexed(tree.treeId) { index, parent, name ->
            if (restore != null && index == parts.lastIndex && previous?.localId != expected) {
                restoredExistingFolder = tree.childDocument(parent, name)?.directory == true
            }
            tree.ensureDirectory(parent, name)
        }
        if (others.any { overlap(it.localId, id) }) throw SyncSetupException(SyncSetupIssue.Overlap)
        val label = tree.storagePath(id) ?: throw SyncSetupException(SyncSetupIssue.Location)
        val sameLocation = previous?.takeIf { it.localId == id }
        val syncId = sameLocation?.id ?: UUID.randomUUID().toString()
        val excludedChild = if (restore != null) restore.excludedLocalChild?.takeIf(::validSyncName)
        else (oldChild ?: sameLocation?.excludedLocalChild)?.takeIf { tree.childDocument(id, it)?.directory == true }
        return RemoteFolderSync(
            syncId, uri.toString(), id, label,
            directChild = mode == SyncLocationMode.ChildFolder,
            useTreeRoot = mode == SyncLocationMode.SelectedFolder,
            excludedLocalChild = excludedChild,
            preserveExistingFiles = mode != SyncLocationMode.SelectedFolder &&
                (existingCustomChild || oldChild != null || sameLocation?.keepLocalFiles == true ||
                    restore?.keepLocalFiles == true || restoredExistingFolder),
            excludedSubfolders = restore?.excludedSubfolders ?: previous?.excludedSubfolders.orEmpty(),
        )
    }

    private suspend fun cleanupRemoved(before: List<RemoteFolderSync>, after: List<RemoteFolderSync>) {
        val remaining = after.associateBy(RemoteFolderSync::id)
        val changed = before.filter { remaining[it.id] != it }.mapTo(mutableSetOf(), RemoteFolderSync::id)
        val stopping = changed.mapNotNull(currentRuns::get)
        stopping.forEach { it.cancel() }
        stopping.forEach { it.join() }
        val removed = changed - remaining.keys
        val pending = unclaimedCleanup(after)
        val pendingIds = pending.mapTo(mutableSetOf(), RemoteFolderSync::id)
        for (id in removed) {
            rootLocks.remove(id)
            mediaChangesLock.withLock {
                pendingMedia.remove(id)
                retryMedia.entries.removeAll { it.value.first == id }
            }
            scheduler.cancel(id)
            if (id !in pendingIds) store.remove(id)
        }
        if (removed.isNotEmpty()) status.update { it - removed }
        releaseUnused(before, after + pending)
    }

    private fun unclaimedCleanup(live: List<RemoteFolderSync>): List<RemoteFolderSync> {
        val (claimed, pending) = store.pendingCleanup().partition { old ->
            live.any { it.id == old.id || overlap(it.localId, old.localId) }
        }
        if (claimed.isNotEmpty()) {
            // 해제 저장이 실패했거나 같은 경로를 다시 연결한 경우, 이전 정리 요청은 폐기한다.
            claimed.filter { old -> live.none { it.id == old.id } }.forEach { store.remove(it.id) }
            releaseUnused(claimed, live + pending)
            store.savePendingCleanup(pending)
        }
        cleanupPending.value = pending.isNotEmpty()
        return pending
    }

    internal suspend fun cleanLocalCopies(): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            val live = configured().map(SyncTarget::sync)
            val pending = unclaimedCleanup(live).toMutableList()
            for (location in pending.toList()) {
                try {
                    val files = store.readFiles(location.id)
                    val uri = Uri.parse(location.treeUri)
                    if (uri.authority != EXTERNAL_STORAGE || !DocumentsContract.isTreeUri(uri)) {
                        throw IOException("로컬 동기화 저장 위치를 확인할 수 없습니다.")
                    }
                    if (files.isNotEmpty() || hasLocalAccess(uri)) {
                        val tree = openLocal(uri)
                        tree.requireWritableVolume()
                        tree.removeSyncedCopies(location, files)
                    }
                } catch (_: IOException) {
                    continue
                } catch (_: SecurityException) {
                    continue
                }
                store.remove(location.id)
                pending.remove(location)
                releaseUnused(listOf(location), live + pending)
                store.savePendingCleanup(pending)
            }
            cleanupPending.value = pending.isNotEmpty()
            pending.isEmpty()
        }
    }

    private fun releaseUnused(before: List<RemoteFolderSync>, retained: List<RemoteFolderSync>) {
        val trees = retained.mapTo(mutableSetOf(), RemoteFolderSync::treeUri)
        val unused = before.map(RemoteFolderSync::treeUri).distinct().filterNot(trees::contains)
        if (unused.isEmpty()) return
        // 작업자가 앱 화면보다 먼저 시작해도 기본 위치의 지속 권한을 보존한다.
        val defaultTree = try { (store.preferences().defaultRoot ?: builtInRoot).treeUri } catch (_: IOException) { return }
        unused.filterNot { it == defaultTree }.forEach(::release)
    }

    private fun release(tree: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), TREE_GRANT) }
    }

    internal suspend fun run(
        id: String?,
        refreshCloudFolders: Boolean,
        mediaToken: String?,
        subfolder: String?,
        beforeTransfer: suspend () -> Unit,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val ids = operations.withLock {
            loadPreferencesLocked()
            if (refreshCloudFolders && !scheduled.value) return@withContext SyncOutcome.Success
            // 데이터 요금이 드는 네트워크에서는 예약 실행의 파일 동기화를 건너뛰고 클라우드 폴더 목록만 갱신한다.
            if (refreshCloudFolders && unmeteredOnly.value && isMeteredNetwork()) return@withLock emptyList()
            configured().filter { it.isVisibleSyncTarget() && (id == null || it.sync.id == id) }.map { it.sync.id }
        }
        if (ids.isEmpty()) {
            if (refreshCloudFolders) gallery.refresh()
            return@withContext SyncOutcome.Success
        }
        // 대량 파일 전송이 끝날 때까지 일반 클라우드 앨범의 목록 갱신을 미루지 않는다.
        val cloudRefresh = if (refreshCloudFolders) launch { gallery.refresh() } else null
        val refresh = mutableMapOf<String, MutableSet<String>>()
        var outcome = SyncOutcome.Success
        val completed = coroutineScope {
            ids.distinct().map { id ->
                async {
                    try {
                        val lock = operations.withLock { rootLocks.getOrPut(id) { Mutex() } }
                        lock.withLock {
                            val result = runScheduledFolder(comparisons.root(id), refreshCloudFolders, mediaToken, subfolder, beforeTransfer)
                            result?.edits?.takeIf { it.isNotEmpty() }?.let { gallery.applyFileChanges(it) }
                            result
                        }
                    } catch (error: IOException) {
                        FolderRunResult(null, error.syncOutcome(), false, null)
                    } catch (_: SecurityException) {
                        FolderRunResult(null, SyncOutcome.Failed, false, null)
                    }
                }
            }.awaitAll()
        }
        for (result in completed.filterNotNull()) {
            outcome = maxOf(outcome, result.outcome)
            if (result.edits == null && (!refreshCloudFolders || result.remoteChanged)) {
                result.changedFolder?.let { folder -> refresh.getOrPut(folder.server.id) { mutableSetOf() }.add(folder.folder.path) }
            }
        }
        cloudRefresh?.join()
        refresh.forEach { (serverId, paths) -> gallery.refresh(serverId, roots = paths) }
        outcome
    }

    private suspend fun runScheduledFolder(
        ticket: FolderComparisonQueue.Root, periodic: Boolean, mediaToken: String?,
        subfolder: String?, beforeTransfer: suspend () -> Unit,
    ): FolderRunResult? = supervisorScope {
        val id = ticket.id
        val task = operations.withLock {
            if (periodic && !scheduled.value) return@supervisorScope null
            val linked = configured().firstOrNull { it.sync.id == id && it.isVisibleSyncTarget() } ?: return@supervisorScope null
            val child = subfolder?.let { path ->
                if (path.split('/').any { !validSyncName(it) || !isVisibleSyncName(it) } ||
                    linked.sync.excludedLocalChild.equals(path.substringBefore('/'), ignoreCase = true)) return@supervisorScope null
                linkedFolders.first().firstOrNull {
                    it.serverId == linked.server.id && it.inheritedFrom?.path == linked.folder.path &&
                        it.inheritedRelativePath == path && it.effectiveSync?.id == id && !it.excludedByAncestor
                } ?: return@supervisorScope null
            }
            val policy = selectedConflictPolicy.value
            val task = async {
                val startedAt = SystemClock.elapsedRealtime()
                val pending = if (mediaToken == null) null else mediaChangesLock.withLock {
                    retryMedia[mediaToken]?.second ?: pendingMedia[id]?.takeIf { it.token == mediaToken }?.also {
                        pendingMedia.remove(id)
                        retryMedia[mediaToken] = id to it
                    }
                }
                if (mediaToken != null && pending == null) return@async null
                val selected = pending?.let { it.paths.keys + it.remotePaths.keys }
                val files = selected?.let { store.readFiles(id, it) }
                val changes = files?.let { pending?.changesSince(it) }
                if (changes != null && changes.isEmpty()) {
                    mediaChangesLock.withLock { retryMedia.remove(mediaToken) }
                    return@async null
                }
                beforeTransfer()
                active.update { it + (id to FolderSyncProgress(id, conflictPolicy = policy, selectedSubfolders = subfolder?.let(::setOf))) }
                val result = runFolder(linked, policy, periodic || mediaToken != null, files, changes, startedAt, child, ticket)
                if (mediaToken != null && result.outcome != SyncOutcome.Retry) mediaChangesLock.withLock { retryMedia.remove(mediaToken) }
                result
            }
            currentRuns[id] = task
            task
        }
        try {
            task.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            withContext(NonCancellable) {
                operations.withLock {
                    if (currentRuns[id] === task) {
                        currentRuns.remove(id)
                        active.update { it - id }
                    }
                }
            }
        }
    }

    private suspend fun runFolder(target: SyncTarget, policy: SyncConflictPolicy, automatic: Boolean,
        indexed: SyncFileIndex?, paths: Set<String>?, startedAt: Long, child: ConnectedFolder?,
        ticket: FolderComparisonQueue.Root): FolderRunResult {
        val location = target.sync
        val subtree = child?.inheritedRelativePath
        val scanStarted = System.nanoTime()
        var run: FolderSyncRun? = null
        var outcome = SyncOutcome.Success
        try {
            val filters = store.fileFilters.first()
            val overrides = filters.overridesFor(target.server.id)
            val folderPath = target.folder.displayPath.trimEnd('/')
            val files = indexed ?: ticket.compare { store.readFiles(location.id, subfolder = subtree) }
            val local = openLocal(Uri.parse(location.treeUri))
            // 연결된 하위 폴더는 같은 실행 안에서 따로 집계해 각 폴더의 현황으로 보여준다.
            val subfolders = linkedFolders.first()
                .filter { it.serverId == target.server.id && it.inheritedFrom?.path == target.folder.path &&
                    !it.excludedFromSync && !it.excludedByAncestor }
                .mapNotNull(ConnectedFolder::inheritedRelativePath)
                .filter { subtree == null || it.isWithinSubfolder(subtree) }
            run = FolderSyncRun(target.folder.path, location, local, target.server, transport, gallery.listings, files, policy,
                filterForFolder = { relative -> overrides.filterForFolder("$folderPath/$relative", filters.global) },
                checkpoint = { store.saveFiles(location.id, files) },
                onPublished = { path, entry ->
                    if (entry.type != null) {
                        val localDirectory = location.localId.substringAfter(':').trim('/')
                        val localPath = if (localDirectory.isEmpty()) path else "$localDirectory/$path"
                        favorites.move(localPath, remoteFavoriteKey(remoteId(target.server.id, entry.path)))
                    }
                },
                subfolders = subfolders, manualSubfolder = subtree, scanStarted = scanStarted,
                comparisons = ticket, transfers = transfers) { progress ->
                active.update { it + (location.id to progress) }
            }
            try {
                if (paths == null) withContext(run.readStats) { run.run() } else run.run(paths)
            } finally {
                store.saveFiles(location.id, files)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            outcome = error.syncOutcome()
        } catch (_: SecurityException) {
            outcome = SyncOutcome.Failed
        }
        outcome = maxOf(outcome, run?.outcome ?: SyncOutcome.Success)
        val progress = run?.progress
        val now = System.currentTimeMillis()
        val previous = status.value[location.id]
        val subfolders = if (paths == null) progress?.subfolders.orEmpty() else {
            previous?.subfolders.orEmpty().mapValues { (_, result) -> result.copy(lastRun = result.lastRun ?: previous?.lastRun) } +
                progress?.subfolders.orEmpty()
        }
        val result = FolderSyncStatus(
            lastRun = now,
            previousFullRun = if (paths == null) null else previous?.lastFullRun,
            conflicts = progress?.conflicts ?: 0,
            failed = outcome != SyncOutcome.Success,
            counts = progress?.counts,
            conflictPolicy = policy,
            resolvedConflicts = progress?.resolvedConflicts ?: SyncConflictCounts(),
            subfolders = subfolders,
        )
        val partial = paths != null || subtree != null
        val rootStatus = if (!partial) result else {
            val root = previous ?: FolderSyncStatus()
            val covered = progress?.finishedSubfolders.orEmpty() + progress?.subfolders.orEmpty().keys + listOfNotNull(subtree)
            val updates = covered.associateWith { path ->
                if (path == subtree) SyncSubfolderResult(progress?.counts ?: SyncFileCounts(), result.conflicts,
                    result.resolvedConflicts, now, result.failed)
                else (progress?.subfolders?.get(path) ?: SyncSubfolderResult()).copy(lastRun = now, failed = result.failed)
            }
            root.copy(subfolders = root.subfolders + updates)
        }
        try {
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            val label = if (subtree == null) location.localLabel else "${location.localLabel.trimEnd('/')}/$subtree"
            if (paths == null && run != null) {
                val reads = run.readStats
                Log.i("FolderSync", "root=${location.id} outcome=$outcome totalMs=$durationMs " +
                    "localListMs=${run.localListMs} remoteListMs=${run.remoteListMs} listWallMs=${run.listingWallMs} " +
                    "peakComparisons=${run.peakComparisons} transferWaitMs=${run.transferWaitMs} " +
                    "otherMs=${durationMs - run.listingWallMs - run.transferWaitMs} listRequests=${reads.listingRequests} " +
                    "sharedLists=${reads.sharedListings} cachedLists=${reads.cachedListings} " +
                    "downloadedBytes=${reads.downloadedBytes.get()}")
            }
            store.saveResult(target.folderId, location.id, FolderSyncHistoryEntry(result, automatic, label, durationMs),
                rootStatus.takeIf { partial })
            status.update { it + (location.id to rootStatus) }
        } catch (_: IOException) {
            status.update { it + (location.id to rootStatus.copy(failed = true)) }
            outcome = SyncOutcome.Retry
        }
        val refreshTarget = child?.let { target.copy(folder = it.folder) } ?: target
        return FolderRunResult(refreshTarget.takeIf { run?.changed == true || run?.remoteChanged == true }, outcome,
            run?.remoteChanged == true, if (paths == null) null else run?.remoteEdits.orEmpty())
    }

    private suspend fun hasLinkedFolders() = gallery.connectionsForSync().any(RemoteServer::hasLinkedFolders)

    private suspend fun configured() = gallery.connectionsForSync().syncTargets()

    private fun SyncTarget.isVisibleSyncTarget(): Boolean =
        transport.isVisibleFolder(server, folder.path) && !sync.localId.substringAfter(':').hasHiddenFolderSegment()

    private fun List<RemoteServer>.syncTargets(): List<SyncTarget> = flatMap { server ->
        server.folders.orEmpty().filter(RemoteFolder::linked).mapNotNull { folder ->
            folder.sync?.let { SyncTarget(server, folder, it) }
        }
    }

    private fun List<RemoteServer>.linkedFolderIds(): Set<Long> = flatMapTo(mutableSetOf()) { server ->
        server.folders.orEmpty().filter(RemoteFolder::linked).map { remoteId(server.id, it.path) }
    }

    private fun storagePath(location: RemoteFolderSync) = context.documentPath(location.localId)

    private suspend fun localFolders(buckets: Set<Long>) = try {
        paths.foldersByBucket(buckets)
    } catch (_: SecurityException) {
        emptyMap()
    }

    private companion object {
        val defaultRelativePath = "${Environment.DIRECTORY_PICTURES}/CloudiPix"
        const val TREE_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

private class PendingMediaChanges(val token: String, val paths: MutableMap<String, SyncStamp?>, val remotePaths: MutableMap<String, Boolean>) {
    // 전송이 끝나면서 생긴 미디어 알림은 방금 확정한 기록과 메모리에서 비교한다.
    fun changesSince(files: Map<String, SyncedFile>) = paths.filter { (path, stamp) ->
        val saved = files[path]?.local
        if (stamp == null || saved == null) stamp != saved
        else saved.size != stamp.size || saved.modified / 1000 != stamp.modified / 1000
    }.keys + remotePaths.filter { (path, present) -> present || path in files }.keys
}

// 삭제된 사진도 색인에 있던 상대 경로와 버킷(절대 부모 경로의 해시)으로 저장소까지 구분한다.
internal fun RemoteFolderSync.mediaDirectory(item: MediumEntity): String? {
    val root = localId.substringAfter(':').trim('/')
    val directory = item.relativePath.trim('/')
    val child = when {
        directory.equals(root, ignoreCase = true) -> ""
        root.isEmpty() -> directory
        directory.startsWith("$root/", ignoreCase = true) -> directory.substring(root.length + 1)
        else -> return null
    }
    val absolute = if (child.isEmpty()) localLabel else "${localLabel.trimEnd('/')}/$child"
    if (syncKey(absolute).hashCode().toLong() != item.bucketId) return null
    if (child.hasHiddenFolderSegment() || excludesSubfolder(child) ||
        excludedLocalChild?.equals(child.substringBefore('/'), ignoreCase = true) == true) return null
    return if (child.isEmpty()) "" else "$child/"
}

private data class SyncTarget(val server: RemoteServer, val folder: RemoteFolder, val sync: RemoteFolderSync) {
    val folderId: Long get() = remoteId(server.id, folder.path)
    fun matches(link: ConnectedFolder) = server.id == link.serverId && folder.path == link.folder.path
}

internal val RemoteFolderSync.locationMode: SyncLocationMode
    get() = when {
        useTreeRoot -> SyncLocationMode.SelectedFolder
        directChild -> SyncLocationMode.ChildFolder
        else -> SyncLocationMode.Default
    }

private fun SyncLocationMode.backupRoot(localPath: String): String {
    val path = localPath.trimEnd('/')
    return when (this) {
        SyncLocationMode.Default -> path.substringBeforeLast('/').substringBeforeLast('/')
        SyncLocationMode.SelectedFolder -> path
        SyncLocationMode.ChildFolder -> path.substringBeforeLast('/')
    }
}

private data class FolderRunResult(val changedFolder: SyncTarget?, val outcome: SyncOutcome, val remoteChanged: Boolean,
    val edits: List<RemoteFileChange>?)

internal fun destinationParts(serverName: String, folder: RemoteFolder, mode: SyncLocationMode): List<String> {
    val name = localSyncName(folder.displayPath.trimEnd('/').substringAfterLast('/').ifBlank { serverName })
    return when (mode) {
        SyncLocationMode.Default -> listOf(localSyncName(serverName), name)
        SyncLocationMode.SelectedFolder -> emptyList()
        SyncLocationMode.ChildFolder -> listOf(name)
    }
}

internal fun legacySyncChild(previous: RemoteFolderSync?, previousTreeId: String?, destinationId: String): String? {
    if (previous?.directChild != true || previousTreeId != destinationId) return null
    val prefix = syncChildId(destinationId, "")
    if (!previous.localId.startsWith(prefix)) return null
    return previous.localId.removePrefix(prefix).takeIf(::validSyncName)
}

internal fun suggestedCustomLocationMode(path: String, remoteName: String): SyncLocationMode =
    if (syncKey(path.trimEnd('/').substringAfterLast('/')) == syncKey(remoteName)) SyncLocationMode.SelectedFolder
    else SyncLocationMode.ChildFolder

private fun containsSyncedLocalPath(location: RemoteFolderSync, root: String, path: String): Boolean {
    if (!containsPath(root, path) || location.excludedLocalChild?.let { containsPath("$root/$it", path) } == true) return false
    val relative = syncKey(path.trimEnd('/')).removePrefix(syncKey(root.trimEnd('/'))).trimStart('/')
    return !relative.hasHiddenFolderSegment() && !location.excludesSubfolder(relative)
}

private fun sameEndpoint(first: RemoteServer, second: RemoteServer): Boolean {
    if (first.id == second.id) return true
    val firstUrl = Uri.parse(first.address)
    val secondUrl = Uri.parse(second.address)
    return first.protocol == second.protocol && firstUrl.scheme == secondUrl.scheme &&
        firstUrl.host.equals(secondUrl.host, ignoreCase = true) && effectivePort(firstUrl) == effectivePort(secondUrl) &&
        first.username == second.username
}

private fun effectivePort(uri: Uri): Int = uri.port.takeIf { it != -1 } ?: when (uri.scheme) {
    "ftp" -> 21
    "https" -> 443
    else -> 80
}

private fun overlap(first: String, second: String): Boolean = containsPath(first, second) || containsPath(second, first)

private fun containsPath(parent: String, child: String): Boolean {
    val root = syncKey(parent.trimEnd('/'))
    val path = syncKey(child.trimEnd('/'))
    return root == path || path.startsWith("$root/")
}
