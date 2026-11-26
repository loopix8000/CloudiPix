package app.cloudipix.gallery.core.remote

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.cloudipix.gallery.core.database.SyncIndexDatabase
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.FolderHideLocation
import app.cloudipix.gallery.core.model.FolderHideOption
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.ancestorHideOptions
import app.cloudipix.gallery.core.sync.FolderSyncStore
import app.cloudipix.gallery.core.sync.SyncFileFilter
import app.cloudipix.gallery.core.sync.filterForFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteGallery @Inject internal constructor(
    @ApplicationContext context: Context,
    private val transport: RemoteTransport,
    private val store: FolderSyncStore,
    database: SyncIndexDatabase,
) {
    private val directory = File(context.noBackupFilesDir, "remote-gallery")
    private val connections = AtomicFile(File(directory, "connections"))
    internal val listings = RemoteDirectoryListings(context.cacheDir, database, transport)
    private val settingsLock = Mutex()
    private val refreshLock = Mutex()
    private val folderReadTimes by lazy { context.getSharedPreferences("remote-folder-reads", Context.MODE_PRIVATE) }
    private val dirtyIndexes = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedIndexesRead = CompletableDeferred<Unit>()
    private val registered = MutableStateFlow<List<RemoteServer>>(emptyList())
    private val snapshots = MutableStateFlow<Map<String, RemoteSnapshot>>(emptyMap())
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private val failures = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var loaded = false

    init {
        scope.launch {
            try {
                loadConnections()
            } finally {
                cachedIndexesRead.complete(Unit)
            }
        }
    }

    private suspend fun loadConnections(): Boolean = withContext(Dispatchers.IO) {
        settingsLock.withLock {
            if (loaded) return@withLock true
            val servers = try {
                Json.decodeFromString<List<RemoteServer>>(decrypt(connections.readFully()))
            } catch (_: FileNotFoundException) {
                if (connections.baseFile.exists()) return@withLock false
                emptyList()
            } catch (_: Exception) {
                return@withLock false
            }
            snapshots.value = servers.associate { server ->
                server.id to RemoteSnapshot(readIndex(server))
            }
            registered.value = servers
            loaded = true
            cachedIndexesRead.complete(Unit)
            true
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readIndex(server: RemoteServer): List<RemoteAlbum> {
        val albums = linkedMapOf<Long, RemoteAlbum>()
        val isVisible = albumVisibility(server)
        try {
            index(server.id).openRead().use { input ->
                for (album in Json.decodeToSequence<RemoteAlbum>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                    currentCoroutineContext().ensureActive()
                    if (isVisible(album)) albums[album.id] = album
                }
            }
        } catch (_: FileNotFoundException) {
            // 첫 조회로 인덱스가 만들어지기 전이다.
        } catch (_: IOException) {
            dirtyIndexes += server.id
        } catch (_: SerializationException) {
            dirtyIndexes += server.id
        }
        return albums.values.toList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> collectAlbumUpdates(
        initial: List<RemoteAlbum>,
        publish: suspend (List<RemoteAlbum>) -> Unit,
        read: suspend (suspend (RemoteAlbum) -> Unit) -> T,
    ): T = coroutineScope {
        val updates = Channel<RemoteAlbum>(Channel.BUFFERED)
        val publisher = launch {
            val albums by lazy(LazyThreadSafetyMode.NONE) { initial.associateByTo(linkedMapOf(), RemoteAlbum::id) }
            var nextPublishAt = 0L
            for (album in updates) {
                albums[album.id] = album
                // 첫 결과는 바로 내보내고 후속 결과는 짧게 모아 화면에서 반복 변환하는 비용을 줄인다.
                while (true) {
                    val remaining = nextPublishAt - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    val update = select<RemoteAlbum?> {
                        onTimeout(remaining) { null }
                        updates.onReceiveCatching { it.getOrNull() }
                    } ?: break
                    albums[update.id] = update
                }
                publish(albums.values.toList())
                nextPublishAt = SystemClock.elapsedRealtime() + ALBUM_UPDATE_INTERVAL_MS
            }
        }
        try {
            read(updates::send)
        } finally {
            updates.close()
            publisher.join()
        }
    }

    private suspend fun requireConnections() {
        if (!loadConnections()) throw RemoteRequestException(RemoteFailure.Storage)
    }

    internal val fileFilters = store.fileFilters
    // 저장된 색인을 모두 읽은 뒤 구독해 디스크 로딩 중의 빈 목록을 내보내지 않는다.
    private val visibleAlbumsByServer = combine(registered, snapshots, fileFilters) { servers, cached, settings ->
        servers.associate { server ->
            val overrides = settings.overridesFor(server.id)
            server.id to cached[server.id]?.albums.orEmpty().mapNotNull { album ->
                val path = album.folderPath(server)?.let(::remotePathLabel).orEmpty()
                album.withFileFilter(overrides.filterForFolder(path, settings.global))
            }
        }
    }.onStart { cachedIndexesRead.await() }.flowOn(Dispatchers.Default).distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000, 0), replay = 1)

    internal val albums: Flow<List<RemoteAlbum>> = visibleAlbumsByServer.map { it.values.flatten() }

    internal suspend fun indexedFavoritePhotos(ids: Set<Long>): Map<Long, RemotePhoto> {
        if (ids.isEmpty()) return emptyMap()
        val photos = mutableMapOf<Long, RemotePhoto>()
        for (snapshot in snapshots.value.values) {
            for (album in snapshot.albums) {
                currentCoroutineContext().ensureActive()
                for (photo in album.photos) {
                    if (photo.id !in ids) continue
                    photos[photo.id] = photo
                    if (photos.size == ids.size) return photos
                }
            }
        }
        return photos
    }

    internal val trash = combine(registered, snapshots) { servers, cached ->
        servers.flatMap { server -> cached[server.id]?.albums.orEmpty().flatMap { album ->
            album.trash.map { it.asMedium(album.id) }
        } }
    }

    private fun destinations(server: RemoteServer, indexed: List<RemoteAlbum>): List<RemoteFolder> {
        val links = server.folderLinks
        val root = if (server.discoverAll) listOf(RemoteFolder(transport.root(server), server.name)) else emptyList()
        return (links.selected + root + indexed.mapNotNull { album ->
            album.folderPath(server)?.let { RemoteFolder(it, album.name) }
        }).filter { links.includes(it.path) && transport.isVisibleFolder(server, it.path) }.distinctBy(RemoteFolder::path)
    }

    private fun RemoteAlbum.folderPath(server: RemoteServer): String? =
        directory ?: (photos.firstOrNull() ?: trash.firstOrNull())?.let { transport.parent(server, it.path) }

    private fun RemoteAlbum.withFileFilter(filter: SyncFileFilter): RemoteAlbum? {
        if (photos.isEmpty()) return null
        if (filter.isUnrestricted) return this
        val allowed = photos.filter { filter.allows(it.name, null, it.mimeType) }
        return when {
            allowed.isEmpty() -> null
            allowed.size == photos.size -> this
            else -> copy(photos = allowed)
        }
    }

    internal val transferFolders = combine(registered, snapshots, visibleAlbumsByServer) { servers, cached, visible ->
        servers.flatMap { server ->
            val indexed = cached[server.id]?.albums.orEmpty()
            val counts = visible[server.id].orEmpty().associate { it.id to it.photos.size }
            destinations(server, indexed).map { folder ->
                val id = remoteId(server.id, folder.path)
                FolderEntry(id, folder.name, "${server.name} · ${folder.displayPath}", counts[id] ?: 0)
            }
        }
    }

    internal val linkedFolders = combine(registered, snapshots) { servers, cached ->
        servers.flatMap { server ->
            server.connectedFolders(destinations(server, cached[server.id]?.albums.orEmpty()))
        }
    }.onStart { cachedIndexesRead.await() }

    internal val folderFileFilters = combine(linkedFolders, fileFilters) { folders, settings ->
        buildMap {
            for ((serverId, links) in folders.groupBy(ConnectedFolder::serverId)) {
                val overrides = settings.overridesFor(serverId)
                for (link in links) {
                    val filter = overrides.filterForFolder(link.folder.displayPath, settings.global)
                    if (!filter.isUnrestricted) put(link.bucketId, filter)
                }
            }
        }
    }.distinctUntilChanged()

    internal suspend fun folderHideOptions(bucketIds: Set<Long>): List<FolderHideOption> {
        val foldersByServer = linkedFolders.first().groupBy(ConnectedFolder::serverId)
        return registered.value.flatMap { server ->
            val folders = foldersByServer[server.id].orEmpty()
            if (folders.none { it.bucketId in bucketIds }) return@flatMap emptyList()
            val locations = folders.map { entry ->
                FolderHideLocation(entry.bucketId, entry.folder.name, entry.folder.path.trimEnd('/'))
            }
            ancestorHideOptions(locations, bucketIds, transport.root(server).trimEnd('/'), includeRoot = true, source = server.name) { path ->
                if (server.protocol == RemoteProtocol.WebDav) Uri.decode(path) else path
            }
        }
    }

    internal suspend fun connectionsForSync(): List<RemoteServer> {
        requireConnections()
        return registered.value
    }

    internal fun observeConnectionsForSync(): Flow<List<RemoteServer>> =
        registered.onStart { requireConnections() }

    internal suspend fun linkedFolder(serverId: String, path: String): Pair<RemoteServer, RemoteFolder> {
        requireConnections()
        val server = server(serverId)
        return server to requireLinkedFolder(server, path)
    }

    internal suspend fun destination(bucketId: Long): Pair<RemoteServer, String> {
        requireConnections()
        for (server in registered.value) {
            val folder = destinations(server, snapshots.value[server.id]?.albums.orEmpty())
                .firstOrNull { remoteId(server.id, it.path) == bucketId }
            if (folder != null && transport.isFolderUnderRoot(server, folder.path)) return server to folder.path
        }
        throw RemoteRequestException(RemoteFailure.Address)
    }

    internal suspend fun createFolder(parentId: Long, name: String): FolderEntry = withContext(Dispatchers.IO) {
        if (name.startsWith('.')) throw RemoteRequestException(RemoteFailure.Address)
        val (connection, parent) = destination(parentId)
        val path = transport.createFolder(connection, parent, name)
        refreshLock.withLock {
            settingsLock.withLock {
                val current = server(connection.id)
                val selected = current.folders.orEmpty()
                val links = current.folderLinks
                if (!links.includes(parent)) throw RemoteRequestException(RemoteFailure.Address)
                if (!links.includes(path)) {
                    saveUpdatedConnection(current.withFolders(selected.filterNot { it.path == path } + RemoteFolder(path, name)))
                }
                val snapshot = snapshots.value[connection.id] ?: RemoteSnapshot(emptyList())
                val id = remoteId(connection.id, path)
                if (snapshot.albums.none { it.id == id }) {
                    val albums = snapshot.albums + RemoteAlbum(id, name, emptyList(), directory = path)
                    writeIndex(connection.id, albums)
                    snapshots.update { it + (connection.id to RemoteSnapshot(albums, snapshot.unavailable)) }
                }
            }
        }
        FolderEntry(remoteId(connection.id, path), name, "${connection.name} · ${RemoteFolder(path, name).displayPath}", 0)
    }

    internal suspend fun medium(id: Long): Medium? {
        val indexWasLoaded = loaded
        val cached = indexedMedium(id)
        if (cached != null || indexWasLoaded || !loadConnections()) return cached
        return indexedMedium(id)
    }

    private suspend fun indexedMedium(id: Long): Medium? {
        val settings = fileFilters.first()
        for (server in registered.value) {
            val snapshot = snapshots.value[server.id] ?: continue
            val photo = snapshot.photos[id] ?: continue
            val album = snapshot.albums.firstOrNull { album -> album.photos.any { it.id == id } } ?: continue
            val path = album.folderPath(server)?.let(::remotePathLabel).orEmpty()
            val filter = settings.overridesFor(server.id).filterForFolder(path, settings.global)
            if (filter.allows(photo.name, null, photo.mimeType)) return photo.asMedium(album.id)
        }
        return null
    }

    val servers: Flow<List<RemoteServerStatus>> = combine(
        registered, visibleAlbumsByServer, refreshing, failures,
    ) { list, cached, busy, unavailable ->
        list.map { server ->
            RemoteServerStatus(
                server.id, server.name, server.protocol, server.address, server.username,
                refreshing = server.id in busy,
                unavailable = server.id in unavailable,
                albumCount = cached[server.id]?.size ?: 0,
                folderCount = server.folders.orEmpty().count { it.linked && transport.isVisibleFolder(server, it.path) }
                    .takeUnless { server.discoverAll },
            )
        }
    }.onStart { scope.launch { loadConnections() } }

    internal suspend fun save(
        name: String,
        protocol: RemoteProtocol,
        address: String,
        username: String,
        password: String,
        editing: String? = null,
    ): String = withContext(Dispatchers.IO) {
        requireConnections()
        val previous = registered.value.firstOrNull { it.id == editing }
        if (editing != null && previous == null) throw RemoteRequestException(RemoteFailure.Storage)
        val normalized = transport.normalizedAddress(protocol, address)
        val sameSource = previous?.takeIf {
            it.protocol == protocol && it.address == normalized && it.username == username.trim()
        }
        val remote = RemoteServer(
            id = sameSource?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { normalized.substringAfter("://").substringBefore('/') },
            protocol = protocol,
            address = normalized,
            username = username.trim(),
            password = password.ifEmpty { sameSource?.password.orEmpty() },
            folders = if (sameSource != null) sameSource.folders else emptyList(),
            discoverAll = sameSource?.discoverAll ?: false,
            legacySyncImported = sameSource?.legacySyncImported ?: false,
        )
        list(remote, transport.root(remote))
        val saved = settingsLock.withLock {
            val current = registered.value
            if (editing != null && current.none { it === previous }) return@withLock false
            val proposed = current.filterNot { it.id == editing } + remote
            if (!saveConnections(proposed)) return@withLock false
            registered.value = proposed
            if (editing != null && editing != remote.id) removeIndex(editing)
            true
        }
        if (!saved) throw RemoteRequestException(RemoteFailure.Storage)
        if (remote.discoverAll || !remote.folders.isNullOrEmpty()) scope.launch { refresh(remote.id) }
        remote.id
    }

    internal suspend fun folderSelection(id: String): RemoteFolderSelection = withContext(Dispatchers.IO) {
        requireConnections()
        val server = server(id)
        RemoteFolderSelection(RemoteFolder(transport.root(server), server.name), server.folderLinks, server.discoverAll)
    }

    internal suspend fun restoreFolderSelections(backups: List<CloudFolderBackup>): CloudFolderRestore = withContext(Dispatchers.IO) {
        requireConnections()
        val serverIds = mutableMapOf<String, String>()
        val changed = mutableSetOf<String>()
        var complete = true
        settingsLock.withLock {
            for (backup in backups) {
                val previous = backup.findServer(registered.value, allowLegacySingleMatch = backups.size == 1)
                if (previous == null || previous.id in serverIds.values) {
                    complete = false
                    continue
                }
                serverIds[backup.serverId] = previous.id
                val folders = previous.folders.orEmpty().associateByTo(linkedMapOf(), RemoteFolder::path)
                for (entry in backup.folders) {
                    val path = transport.restoreFolderPath(previous, entry.path, legacy = backup.accountKey == null)
                    if (path == null) {
                        complete = false
                        continue
                    }
                    if (!transport.isVisibleFolder(previous, path)) continue
                    val existing = folders[path]
                    if (!entry.linked && existing?.linked == true) continue
                    val folder = existing ?: RemoteFolder(path, entry.name)
                    folders[path] = folder.copy(
                        linked = entry.linked,
                        includeSubfolders = if (existing != null && backup.accountKey == null) {
                            existing.includeSubfolders
                        } else entry.includeSubfolders,
                    )
                }
                val restored = folders.values.toList()
                val discoverAll = previous.discoverAll || backup.discoverAll
                if (restored != previous.folders.orEmpty() || discoverAll != previous.discoverAll) {
                    saveFolderSelection(previous.withFolders(restored, discoverAll))
                    changed += previous.id
                }
            }
        }
        changed.forEach { id -> scope.launch { refresh(id) } }
        CloudFolderRestore(serverIds, complete)
    }

    internal suspend fun foldersIn(id: String, path: String): List<RemoteFolder> = withContext(Dispatchers.IO) {
        requireConnections()
        val server = server(id)
        if (!transport.isFolderUnderRoot(server, path) || !transport.isVisibleFolder(server, path)) {
            throw RemoteRequestException(RemoteFailure.Address)
        }
        list(server, path).filter { it.directory && !it.name.startsWith('.') }.map { RemoteFolder(it.path, it.name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RemoteFolder::name))
    }

    internal suspend fun saveFolderSelection(previous: RemoteServer, updated: RemoteServer, refreshPaths: Set<String>) {
        val indexChanged = settingsLock.withLock {
            if (server(previous.id) !== previous) throw RemoteRequestException(RemoteFailure.Storage)
            if (previous.folders.orEmpty() == updated.folders.orEmpty()) return@withLock false
            val changed = !previous.matchesIndex(updated)
            if (changed) saveFolderSelection(updated) else saveUpdatedConnection(updated)
            changed
        }
        if (indexChanged) scope.launch {
            while (true) {
                val before = registered.value.firstOrNull { it.id == previous.id } ?: break
                refresh(previous.id, roots = refreshPaths, directories = refreshPaths)
                val after = registered.value.firstOrNull { it.id == previous.id } ?: break
                if (before.matchesIndex(after)) break
            }
        }
    }

    private fun server(id: String): RemoteServer = registered.value.firstOrNull { it.id == id }
        ?: throw RemoteRequestException(RemoteFailure.Storage)

    internal suspend fun unlinkFolder(id: String, path: String) = withContext(Dispatchers.IO) {
        requireConnections()
        settingsLock.withLock {
            val server = server(id)
            val folder = requireLinkedFolder(server, path)
            val remaining = server.folders.orEmpty().filterNot { it.path == path }
            val links = RemoteFolderLinks(remaining, server.discoverAll)
            val excluded = remaining.filter { !it.linked && links.scansSubfolders(it.path) }
            val folders = links.selected + excluded + listOfNotNull(
                folder.copy(sync = null, linked = false, includeSubfolders = false).takeIf { links.scansSubfolders(path) },
            )
            saveFolderSelection(server.withFolders(folders))
        }
    }

    internal suspend fun setFolderSync(id: String, path: String, sync: RemoteFolderSync?) = withContext(Dispatchers.IO) {
        requireConnections()
        settingsLock.withLock {
            val server = server(id)
            val folder = requireLinkedFolder(server, path)
            if (folder.sync == sync) return@withLock
            saveUpdatedConnection(server.withFolder(folder.copy(sync = sync)))
        }
    }

    internal suspend fun importFolderSync(legacy: Map<String, List<RemoteFolder>>) = withContext(Dispatchers.IO) {
        requireConnections()
        settingsLock.withLock {
            val current = registered.value
            val updated = current.map { server ->
                if (server.legacySyncImported) return@map server
                val imported = legacy[server.id].orEmpty().filter { transport.isFolderUnderRoot(server, it.path) }
                if (imported.isEmpty()) return@map server
                val folders = server.folders.orEmpty().associateByTo(linkedMapOf(), RemoteFolder::path)
                for (folder in imported) {
                    val saved = folders[folder.path]
                    if (saved == null) folders[folder.path] = folder
                    else if (saved.linked && saved.sync == null) folders[folder.path] = saved.copy(sync = folder.sync)
                }
                // 기록 정리를 재시도하더라도 사용자가 끄거나 해제한 설정을 다시 가져오지 않는다.
                server.withFolders(folders.values.toList(), legacySyncImported = true)
            }
            if (updated != current) {
                if (!saveConnections(updated)) throw RemoteRequestException(RemoteFailure.Storage)
                registered.value = updated
            }
        }
    }

    private fun requireLinkedFolder(server: RemoteServer, path: String): RemoteFolder =
        destinations(server, snapshots.value[server.id]?.albums.orEmpty()).firstOrNull {
            it.path == path && transport.isFolderUnderRoot(server, path)
        } ?: throw RemoteRequestException(RemoteFailure.Address)

    private fun saveUpdatedConnection(server: RemoteServer) {
        val proposed = registered.value.map { if (it.id == server.id) server else it }
        if (!saveConnections(proposed)) throw RemoteRequestException(RemoteFailure.Storage)
        registered.value = proposed
    }

    private fun saveFolderSelection(server: RemoteServer) {
        val proposed = registered.value.map { if (it.id == server.id) server else it }
        if (!saveConnections(proposed)) throw RemoteRequestException(RemoteFailure.Storage)
        snapshots.update { cached ->
            val snapshot = cached[server.id] ?: return@update cached
            cached + (server.id to snapshot.matching(server))
        }
        if (!server.hasLinkedFolders) failures.update { it - server.id }
        registered.value = proposed
    }

    private suspend fun list(server: RemoteServer, path: String): List<RemoteEntry> = try {
        transport.list(server, path)
    } catch (error: RemoteRequestException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw RemoteRequestException(RemoteFailure.Connection)
    }

    internal suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        requireConnections()
        settingsLock.withLock {
            val proposed = registered.value.filterNot { it.id == id }
            if (!saveConnections(proposed)) return@withLock false
            registered.value = proposed
            removeIndex(id)
            true
        }
    }

    internal suspend fun refreshVisibleFolders(buckets: Set<Long>, force: Boolean = false) {
        if (buckets.isEmpty()) return
        val visibility = VisibleFolderRead(currentCoroutineContext()[Job], System.currentTimeMillis(), force)
        // 화면을 떠나면 다음 폴더부터 건너뛴다. 이미 시작한 조회 결과는 폴더를 열 때도 이어 쓴다.
        scope.async { refreshIndex(buckets = buckets, visibility = visibility) }.await()
    }

    internal suspend fun applyFileChanges(changes: List<RemoteFileChange>) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            settingsLock.withLock {
                for ((serverId, sources) in changes.groupBy { it.serverId }) {
                    val snapshot = snapshots.value[serverId] ?: RemoteSnapshot(emptyList())
                    val server = registered.value.firstOrNull { it.id == serverId } ?: continue
                    val albums = snapshot.albums.associateByTo(linkedMapOf(), RemoteAlbum::id)
                    val links = server.folderLinks
                    for ((directory, edits) in sources.groupBy { it.directory }) {
                        if (!links.includes(directory)) continue
                        val id = remoteId(serverId, directory)
                        val album = albums[id] ?: RemoteAlbum(id, links.byPath[directory]?.name
                            ?: remotePathLabel(directory).trimEnd('/').substringAfterLast('/'), emptyList(), directory = directory)
                        val photos = album.photos.associateByTo(linkedMapOf(), RemotePhoto::path)
                        val trash = album.trash.associateByTo(linkedMapOf(), RemotePhoto::path)
                        for (edit in edits) {
                            val old = edit.before?.let { photos.remove(it) ?: trash.remove(it) }
                            val entry = edit.after ?: continue
                            val original = restoredName(entry.name)
                            val display = original ?: entry.name
                            val type = entry.copy(name = display).type ?: continue
                            val photo = RemotePhoto(serverId, remoteId(serverId, entry.path), display, entry.path,
                                entry.modified, entry.size, type, entry.etag ?: "${entry.modified}:${entry.size}",
                                entry.preview, entry.mimeType, UUID.randomUUID().toString(), edit.taken ?: old?.taken,
                                old?.takeIf { it.path == entry.path }?.staleLocalCopyModified)
                            (if (original == null) photos else trash)[entry.path] = photo
                        }
                        albums[id] = album.copy(photos = photos.values.toList(), trash = trash.values.toList(), listingToken = null)
                    }
                    val updated = albums.values.toList()
                    snapshots.update { it + (serverId to RemoteSnapshot(updated, snapshot.unavailable)) }
                    writeIndex(serverId, updated)
                }
            }
        }
    }

    suspend fun refresh(serverId: String? = null, buckets: Set<Long>? = null, roots: Set<String>? = null,
        directories: Set<String>? = null) = refreshIndex(serverId, buckets, roots, directories)

    private suspend fun refreshIndex(serverId: String? = null, buckets: Set<Long>? = null, roots: Set<String>? = null,
        directories: Set<String>? = null, visibility: VisibleFolderRead? = null) = withContext(Dispatchers.IO) {
        if (visibility?.active == false) return@withContext
        if (!loadConnections()) return@withContext
        refreshLock.withLock {
            if (visibility?.active == false) return@withLock
            val requestedBuckets = if (visibility != null && buckets != null) {
                buckets.filterTo(hashSetOf()) { visibility.needsRead(folderReadTimes.getLong(it.toString(), 0L)) }
                    .takeIf { it.isNotEmpty() } ?: return@withLock
            } else buckets
            val shareSince = if (serverId == null && buckets == null && roots == null && directories == null)
                System.nanoTime() else null
            val servers = registered.value.filter {
                (serverId == null || it.id == serverId) && it.hasLinkedFolders
            }
            for (server in servers) {
                if (visibility?.active == false) break
                val previous = snapshots.value[server.id]
                val affected = if (directories != null) {
                    val links = server.folderLinks
                    directories.filter(links::includes).map { path ->
                        links.byPath[path] ?: RemoteFolder(path, remotePathLabel(path).trimEnd('/').substringAfterLast('/'))
                    }
                } else if (requestedBuckets == null && roots == null) null else {
                    val indexed = previous?.albums.orEmpty()
                    val candidates = if (visibility != null && requestedBuckets != null) indexed.filter { it.id in requestedBuckets } else indexed
                    destinations(server, candidates).filter { folder ->
                        requestedBuckets?.contains(remoteId(server.id, folder.path)) == true || roots.orEmpty().any { root ->
                            folder.path == root || (!server.discoverAll && folder.path.isRemoteDescendantOf(root))
                        }
                    }
                }
                if (affected != null && affected.isEmpty()) continue
                refreshing.update { it + server.id }
                try {
                    val checkedFolders = mutableMapOf<Long, Long>()
                    val updated = collectAlbumUpdates(previous?.albums.orEmpty(), { albums ->
                        settingsLock.withLock publish@{
                            if (registered.value.none { it.matchesIndex(server) }) return@publish
                            dirtyIndexes += server.id
                            snapshots.update { it + (server.id to RemoteSnapshot(albums, previous?.unavailable ?: false)) }
                        }
                    }) { emit -> scan(server, affected, roots, shareSince, previous, visibility, checkedFolders, emit) }
                    settingsLock.withLock persist@{
                        if (registered.value.none { it.matchesIndex(server) }) return@persist
                        snapshots.update { if (it[server.id] === updated) it else it + (server.id to updated) }
                        if (previous == null || previous.albums != updated.albums || server.id in dirtyIndexes) {
                            writeIndex(server.id, updated.albums)
                        }
                        failures.update { if (updated.unavailable) it + server.id else it - server.id }
                        if (checkedFolders.isNotEmpty()) {
                            val edit = folderReadTimes.edit()
                            checkedFolders.forEach { (id, time) -> edit.putLong(id.toString(), time) }
                            edit.apply()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    settingsLock.withLock {
                        if (registered.value.any { it.matchesIndex(server) }) failures.update { it + server.id }
                    }
                } finally {
                    refreshing.update { it - server.id }
                }
            }
        }
    }

    internal fun source(uri: Uri): Pair<RemoteServer, RemotePhoto>? = indexedSource(uri)?.takeIf { (_, photo) ->
        (photo.cacheVersion ?: photo.revision) == uri.pathSegments.getOrNull(1)
    }

    private fun indexedSource(uri: Uri): Pair<RemoteServer, RemotePhoto>? {
        val server = registered.value.firstOrNull { it.id == uri.authority } ?: return null
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return null
        val photo = snapshots.value[server.id]?.photos?.get(id) ?: return null
        return server to photo
    }

    internal suspend fun refreshOpenedPhoto(uri: Uri, localCopyModified: Long?): Unit = withContext(Dispatchers.IO) {
        if (!loaded && !loadConnections()) return@withContext
        val (server, photo) = indexedSource(uri) ?: return@withContext
        val folder = transport.parent(server, photo.path)
        if (!transport.isFolderUnderRoot(server, folder)) return@withContext
        val fileName = photo.path.substringAfterLast('/').let { if (server.protocol == RemoteProtocol.WebDav) Uri.decode(it) else it }
        val entry = try {
            transport.entryAt(server, folder, fileName)
        } catch (_: IOException) {
            null
        }?.takeIf { it.path == photo.path && !it.directory && !it.unsupported } ?: return@withContext
        val revision = if (entry.etag == null && entry.modified == 0L && entry.size == photo.size) photo.revision
            else entry.indexRevision(System.currentTimeMillis())
        val changed = !photo.matches(entry, revision)
        val staleCopy = localCopyModified?.takeIf { it != photo.staleLocalCopyModified &&
            (changed || hasOlderLocalCopy(server, folder, entry)) }
        if (!changed && staleCopy == null) return@withContext

        refreshLock.withLock {
            settingsLock.withLock update@{
                if (registered.value.none { it.matchesIndex(server) }) return@update
                val snapshot = snapshots.value[server.id] ?: return@update
                val latest = snapshot.photos[photo.id] ?: return@update
                if (latest != photo && !latest.matches(entry, revision)) return@update
                val albumId = remoteId(server.id, folder)
                val albumPosition = snapshot.albums.indexOfFirst { it.id == albumId }
                if (albumPosition < 0) return@update
                val album = snapshot.albums[albumPosition]
                val updated = latest.copy(modified = entry.modified, size = entry.size, revision = revision,
                    preview = entry.preview, mimeType = entry.mimeType,
                    cacheVersion = if (latest.matches(entry, revision)) latest.cacheVersion else UUID.randomUUID().toString(),
                    taken = latest.taken.takeIf { latest.revision == revision },
                    staleLocalCopyModified = staleCopy ?: latest.staleLocalCopyModified)
                if (updated == latest) return@update
                val albums = snapshot.albums.toMutableList()
                albums[albumPosition] = if (restoredName(fileName) != null) {
                    album.copy(trash = album.trash.map { if (it.id == photo.id) updated else it })
                } else {
                    album.copy(photos = album.photos.map { if (it.id == photo.id) updated else it })
                }
                snapshots.update { it + (server.id to RemoteSnapshot(albums, snapshot.unavailable)) }
                try {
                    writeIndex(server.id, albums)
                } catch (_: IOException) {
                    // 메모리 색인은 계속 사용하고 다음 폴더 갱신에서 저장을 다시 시도한다.
                }
            }
        }
    }

    private fun hasOlderLocalCopy(server: RemoteServer, path: String, entry: RemoteEntry): Boolean {
        val root = server.folders.orEmpty().filter { it.sync != null && (path == it.path || path.isRemoteDescendantOf(it.path)) }
            .maxByOrNull { it.path.length } ?: return false
        val sync = root.sync ?: return false
        val relative = if (path == root.path) entry.name else {
            val directory = RemoteFolder(path, "").relativePathFrom(root) ?: return false
            "$directory/${entry.name}"
        }
        val saved = try {
            store.readFiles(sync.id, paths = setOf(relative))[relative]?.remote
        } catch (_: IOException) {
            null
        } ?: return false
        return saved.size != entry.size || (saved.etag != null && entry.etag != null && saved.etag != entry.etag) ||
            (saved.modified > 0L && entry.modified > 0L && saved.modified != entry.modified)
    }

    internal fun photoPath(uri: Uri): String? = source(uri)?.let { (server, photo) ->
        if (server.protocol == RemoteProtocol.Ftp) Uri.parse(server.address).buildUpon().path(photo.path).build().toString()
        else photo.path
    }

    internal fun photoPathLabel(uri: Uri): String? = source(uri)?.let { (server, photo) ->
        "${server.name} · ${remotePathLabel(photo.path)}"
    }

    private suspend fun scan(server: RemoteServer, affected: List<RemoteFolder>?, roots: Set<String>?, shareSince: Long?,
        previous: RemoteSnapshot?, visibility: VisibleFolderRead?, checkedFolders: MutableMap<Long, Long>,
        emit: suspend (RemoteAlbum) -> Unit): RemoteSnapshot {
        val root = transport.root(server)
        val links = server.folderLinks
        val initial = if (server.discoverAll) listOf(RemoteFolder(root, server.name)) else links.selected
        val recurse = affected == null || roots != null
        val recursiveRefreshPaths = if (roots != null) affected.orEmpty().mapNotNull { folder ->
            folder.path.takeIf { (it in roots || it in links.byPath) && links.scansSubfolders(it) }
        } else emptyList()
        val pending = ArrayDeque(affected?.filter { folder ->
            folder.path in links.byPath || recursiveRefreshPaths.none { folder.path.isRemoteDescendantOf(it) }
        } ?: initial)
        val visited = mutableSetOf<String>()
        val albums = linkedMapOf<Long, RemoteAlbum>()
        val cachedAlbums = previous?.albums.orEmpty().associateBy(RemoteAlbum::id)
        val cachedChildren by lazy {
            cachedAlbums.values.mapNotNull { it.folderPath(server)?.takeUnless { path -> path == root } }
                .groupBy { transport.parent(server, it.trimEnd('/')) }
        }
        if (visibility != null) {
            albums.putAll(cachedAlbums)
        } else if (affected != null) {
            val replaced = affected.mapTo(mutableSetOf()) { remoteId(server.id, it.path) }
            cachedAlbums.filterTo(albums) { (id, album) ->
                id !in replaced && album.folderPath(server)?.let { path ->
                    recursiveRefreshPaths.none { path.isRemoteDescendantOf(it) }
                } != false
            }
        }
        val failedTrees = mutableListOf<String>()
        var unavailable = false
        val scanVersion = System.currentTimeMillis()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (visibility?.active == false) break
            val (path, name) = pending.removeFirst()
            if (!transport.isVisibleFolder(server, path) || !visited.add(path)) continue
            val albumId = remoteId(server.id, path)
            val walkChildren = recurse && links.scansSubfolders(path)
            try {
                listings.read(server, path, requireComplete = false, scanStarted = shareSince) { listing ->
                    val stored = cachedAlbums[albumId]
                    val missingEntries = if (listing.complete) false else {
                        val listed = listing.entries.associateBy(RemoteEntry::path)
                        stored?.isMissingFrom(listed) == true ||
                            (walkChildren && cachedChildren[path].orEmpty().any { listed[it]?.directory != true })
                    }
                    val confirmed = if (missingEntries) try {
                        transport.listBeforeDeletion(server, path)
                    } catch (_: IOException) {
                        null
                    } else null
                    val entries = confirmed ?: listing.entries
                    val complete = listing.complete || confirmed != null
                    if (walkChildren) {
                        entries.filter { it.directory && !it.name.startsWith('.') }.forEach { pending.add(RemoteFolder(it.path, it.name)) }
                        if (missingEntries && !complete) failedTrees += path
                    }
                    if (!links.includes(path)) return@read
                    if (!missingEntries || complete) checkedFolders[albumId] = System.currentTimeMillis()
                    else unavailable = true
                    // 예전 형식 기준으로 만든 앨범은 목록이 바뀌지 않았어도 전체 항목을 다시 읽는다. 항목별 정보는 이어 쓴다.
                    val cached = stored?.takeIf { it.mediaVersion == ALBUM_MEDIA_VERSION }
                    val revisionsDeferred = cached?.revisionsDeferred == true
                    // 목록 토큰은 유지하고, 보류한 수정은 전체 항목의 메타데이터를 비교할 때 반영한다.
                    val canReuseToken = visibility != null || !revisionsDeferred
                    if (canReuseToken && cached != null && listing.token != null && cached.listingToken == listing.token && cached.name == name) {
                        albums[albumId] = cached
                        return@read
                    }
                    val photos = mutableListOf<RemotePhoto>()
                    val trash = mutableListOf<RemotePhoto>()
                    val delta = listing.changes?.takeIf {
                        canReuseToken && listing.changesFrom != null && listing.changesFrom == cached?.listingToken
                    }
                    var deferred = revisionsDeferred && (visibility != null || !complete)
                    val previousByPath = stored?.let { (it.photos + it.trash).associateBy(RemotePhoto::path) }.orEmpty()
                    for (entry in delta?.entries ?: entries) {
                        if (entry.directory || entry.unsupported || entry.name.endsWith(".sgpart") ||
                            (entry.name.startsWith(SYNC_FILE_PREFIX) && entry.name.endsWith(".backup"))
                        ) continue
                        val originalName = restoredName(entry.name)
                        val type = (originalName?.let { entry.copy(name = it) } ?: entry).type
                        val revision = entry.indexRevision(scanVersion)
                        val previous = previousByPath[entry.path]
                        val nameOnScreen = originalName ?: entry.name
                        val destination = if (originalName == null) photos else trash
                        if (previous != null) {
                            val unchanged = previous.matches(entry, revision) && previous.name == nameOnScreen && previous.type == type
                            if (visibility != null || unchanged) {
                                deferred = deferred || !unchanged
                                destination += previous
                                continue
                            }
                        }
                        if (type == null) continue
                        val id = previous?.id ?: remoteId(server.id, entry.path)
                        val versionMatch = previous?.takeIf { it.revision == revision }
                        val cacheVersion = versionMatch?.cacheVersion
                        val captureTime = versionMatch?.taken
                        destination += RemotePhoto(server.id, id, nameOnScreen, entry.path,
                            entry.modified, entry.size, type, revision, entry.preview, entry.mimeType, cacheVersion, captureTime,
                            previous?.staleLocalCopyModified)
                    }
                    if (stored != null && (delta != null || !complete)) {
                        val replaced = (photos + trash).mapTo(hashSetOf(), RemotePhoto::path)
                        delta?.let { changes ->
                            changes.removed.mapTo(replaced) { transport.child(server, path, it) }
                            changes.entries.mapTo(replaced) { it.path.trimEnd('/') }
                        }
                        stored.photos.filterTo(photos) { it.path !in replaced }
                        stored.trash.filterTo(trash) { it.path !in replaced }
                    }
                    val album = RemoteAlbum(albumId, if (path == root) server.name else name, photos.distinctBy { it.id },
                        trash.distinctBy { it.id }, path, listing.token, ALBUM_MEDIA_VERSION, deferred)
                    albums[albumId] = album
                    if ((album.photos != stored?.photos || album.trash != stored?.trash || album.name != stored?.name) &&
                        (album.photos.isNotEmpty() || stored?.photos?.isNotEmpty() == true ||
                            album.trash.isNotEmpty() || stored?.trash?.isNotEmpty() == true)) {
                        emit(album)
                    }
                }
            } catch (error: IOException) {
                if (server.discoverAll) throw error
                unavailable = true
                if (links.includes(path)) cachedAlbums[albumId]?.let { albums[albumId] = it }
                if (walkChildren) failedTrees += path
            }
        }
        if (visibility != null && visited.isEmpty() && previous != null) return previous
        if (failedTrees.isNotEmpty()) {
            for ((id, album) in cachedAlbums) {
                val path = album.folderPath(server) ?: continue
                if (path !in visited && links.includes(path) && failedTrees.any { path.isRemoteDescendantOf(it) }) {
                    albums.putIfAbsent(id, album)
                }
            }
        }
        // 기존 앨범의 순서를 유지해 정렬 기준이 같은 폴더가 갱신 완료 시 다시 움직이지 않게 한다.
        val result = cachedAlbums.keys.mapNotNull(albums::remove) + albums.values
        return previous?.takeIf { it.unavailable == unavailable && it.albums == result } ?: RemoteSnapshot(result, unavailable)
    }

    private fun albumVisibility(server: RemoteServer): (RemoteAlbum) -> Boolean {
        val links = server.folderLinks
        val selected = links.selected.mapTo(hashSetOf()) { remoteId(server.id, it.path) }
        val excluded = links.byPath.values.filterNot(RemoteFolder::linked).mapTo(hashSetOf()) { remoteId(server.id, it.path) }
        return { album ->
            val path = album.folderPath(server)
            album.id !in excluded && (path == null || transport.isVisibleFolder(server, path)) &&
                (server.discoverAll || album.id in selected || path?.let(links::includes) == true)
        }
    }

    private fun RemoteSnapshot.matching(server: RemoteServer): RemoteSnapshot {
        val matching = albums.filter(albumVisibility(server))
        return if (matching.size == albums.size) this else RemoteSnapshot(matching, unavailable)
    }

    private fun removeIndex(id: String) {
        listings.removeServer(id)
        index(id).delete()
        dirtyIndexes.remove(id)
        snapshots.update { it - id }
        failures.update { it - id }
    }

    private fun index(id: String) = AtomicFile(File(directory, "$id.json"))

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeIndex(id: String, albums: List<RemoteAlbum>) {
        dirtyIndexes += id
        val file = index(id)
        val stream = file.startWrite()
        try {
            Json.encodeToStream(albums, stream)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        dirtyIndexes.remove(id)
    }

    private fun saveConnections(servers: List<RemoteServer>): Boolean = runCatching {
        connections.writeBytes(encrypt(Json.encodeToString(servers)))
    }.isSuccess

    private fun encrypt(text: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(text.encodeToByteArray())
    }

    private fun decrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}

private fun RemoteServer.matchesIndex(other: RemoteServer): Boolean {
    if (id != other.id || name != other.name || password != other.password || discoverAll != other.discoverAll) return false
    fun RemoteServer.indexFolders() = folders.orEmpty().filter { !discoverAll || !it.linked }.map { it.copy(sync = null) }
    return indexFolders() == other.indexFolders()
}

private class RemoteSnapshot(val albums: List<RemoteAlbum>, val unavailable: Boolean = false) {
    val photos by lazy {
        buildMap {
            for (album in albums) {
                for (photo in album.photos) put(photo.id, photo)
                for (photo in album.trash) put(photo.id, photo)
            }
        }
    }
}

private class VisibleFolderRead(private val caller: Job?, private val requestedAt: Long, private val force: Boolean) {
    val active: Boolean get() = caller?.isActive != false

    fun needsRead(lastRead: Long): Boolean {
        val age = System.currentTimeMillis() - lastRead
        return age < 0 || if (force) lastRead < requestedAt else age >= VISIBLE_FOLDER_REFRESH_MS
    }
}

private fun RemoteAlbum.isMissingFrom(entries: Map<String, RemoteEntry>): Boolean {
    fun missing(photo: RemotePhoto) = entries[photo.path]?.let { it.directory || it.unsupported } != false
    return photos.any(::missing) || trash.any(::missing)
}

private fun RemoteEntry.indexRevision(fallback: Long): String = etag ?: "${modified.takeIf { it > 0 } ?: fallback}:$size"

private fun RemotePhoto.matches(entry: RemoteEntry, revision: String): Boolean = this.revision == revision &&
    modified == entry.modified && size == entry.size && preview == entry.preview && mimeType == entry.mimeType

internal fun AtomicFile.writeBytes(bytes: ByteArray) {
    val stream = startWrite()
    try {
        stream.write(bytes)
        finishWrite(stream)
    } catch (error: Exception) {
        failWrite(stream)
        throw error
    }
}

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "remote_gallery_connections"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_SIZE = 12
private const val ALBUM_UPDATE_INTERVAL_MS = 150L
private const val VISIBLE_FOLDER_REFRESH_MS = 5 * 60_000L
