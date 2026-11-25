package app.cloudipix.gallery.core.data

import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction

import app.cloudipix.gallery.core.database.FavoriteDao
import app.cloudipix.gallery.core.database.FolderCoverDao
import app.cloudipix.gallery.core.database.FolderRow
import app.cloudipix.gallery.core.database.GalleryDatabase
import app.cloudipix.gallery.core.database.IgnoredFolderDao
import app.cloudipix.gallery.core.database.IgnoredFolderEntity
import app.cloudipix.gallery.core.database.MediaDao
import app.cloudipix.gallery.core.database.MediaIndexChanges
import app.cloudipix.gallery.core.database.MediumEntity
import app.cloudipix.gallery.core.database.PinnedFolderDao
import app.cloudipix.gallery.core.database.PinnedFolderEntity
import app.cloudipix.gallery.core.media.LocalFolderFiles
import app.cloudipix.gallery.core.media.MediaPaths
import app.cloudipix.gallery.core.media.MediaStoreScanner
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.core.model.FAVORITES_BUCKET_ID
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderChange
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.FolderHideLocation
import app.cloudipix.gallery.core.model.FolderHideOption
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.RECENT_BUCKET_ID
import app.cloudipix.gallery.core.model.ancestorHideOptions
import app.cloudipix.gallery.core.model.isVirtualFolder
import app.cloudipix.gallery.core.model.mediaUri
import app.cloudipix.gallery.core.model.validFolderName
import app.cloudipix.gallery.core.remote.RemoteGallery
import app.cloudipix.gallery.core.remote.RemoteFiles
import app.cloudipix.gallery.core.remote.RemoteFileChange
import app.cloudipix.gallery.core.remote.remoteId
import app.cloudipix.gallery.core.remote.TrashChange
import app.cloudipix.gallery.core.remote.remoteFavoriteKey
import app.cloudipix.gallery.core.remote.isRemoteId
import app.cloudipix.gallery.core.security.GalleryLocks
import app.cloudipix.gallery.core.settings.SettingsRepository
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.core.sync.mediaDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val media: MediaDao,
    private val favorites: FavoriteDao,
    private val ignored: IgnoredFolderDao,
    private val pinned: PinnedFolderDao,
    private val scanner: MediaStoreScanner,
    private val paths: MediaPaths,
    private val writer: MediaWriter,
    private val remote: RemoteGallery,
    private val remoteFiles: RemoteFiles,
    private val covers: FolderCoverDao,
    private val locks: GalleryLocks,
    private val localFolders: LocalFolderFiles,
    private val database: GalleryDatabase,
    private val settings: SettingsRepository,
    private val folderSync: FolderSync,
) {

    private val indexSync = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val removing = MutableStateFlow(emptySet<Long>())
    val pendingRemovals = removing.asStateFlow()
    val changes = scanner.changes
    private val lockedFolders = locks.settings.map { it.folders }.distinctUntilChanged()

    private val linkedFolderMirrors = remote.linkedFolders
        .map { links -> links.mapNotNull { it.albumMirror() } }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mirrorBuckets = linkedFolderMirrors.flatMapLatest { mirrors ->
        if (mirrors.isEmpty()) return@flatMapLatest flowOf(emptyMap())
        val candidates = mirrors.mapTo(hashSetOf(), AlbumMirror::relativePath)
        media.allFolders().map { rows ->
            buildMap {
                for (row in rows) {
                    val path = row.relativePath.trimEnd('/')
                    if (path in candidates) put(row.bucketId, path)
                }
            }
        }.distinctUntilChanged().map { matching ->
            val ids = matching.keys
            val directories = if (ids.isEmpty()) emptyMap() else try {
                paths.foldersByBucket(ids)
            } catch (_: SecurityException) {
                emptyMap()
            }
            matchAlbumMirrors(mirrors, directories)
        }
    }.distinctUntilChanged()

    private val noAlbumMirrors = AlbumMirrors(emptyMap(), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val albumMirrors = mirrorBuckets.flatMapLatest { buckets ->
        if (buckets.isEmpty()) return@flatMapLatest flowOf(noAlbumMirrors)
        val groups = buckets.keys.chunked(500).map(media::observeMirrorFiles)
        val mirrorFiles = combine(groups) { it.flatMap { files -> files } }
        combine(mirrorFiles, lockedFolders, remote.folderFileFilters) { files, locked, filters ->
            AlbumMirrors(buckets, files.filterNot { it.bucketId in locked }, filters)
        }
    }.shareIn(repositoryScope, SharingStarted.WhileSubscribed(5_000, 0), replay = 1)

    private val visibleAlbums = remote.albums.combine(removing) { albums, ids ->
        if (ids.isEmpty()) albums else albums.mapNotNull { album ->
            if (album.photos.none { it.id in ids }) album
            else album.copy(photos = album.photos.filterNot { it.id in ids }).takeIf { it.photos.isNotEmpty() }
        }
    }
    private val albumSources = visibleAlbums.combine(albumMirrors, ::Pair)
    private val browsingAlbumSources = visibleAlbums.combine(
        albumMirrors.map<AlbumMirrors, AlbumMirrors?> { it }
            .onStart { emit(albumMirrors.replayCache.lastOrNull()) }
            .distinctUntilChanged(),
        ::Pair,
    )

    val trash: Flow<List<Medium>> = changes.onStart { emit(null) }.map {
        scanner.scan(trashed = true).map { it.toMedium(false) }.sortedByDescending(Medium::dateTaken)
    }.combine(remote.trash) { local, cloud -> (local + cloud).sortedByDescending(Medium::dateTaken) }
        .combine(lockedFolders) { items, locked -> items.filterNot { it.bucketId in locked } }
        .withoutRemoving()

    fun folders(filter: MediaFilter, latestCovers: Boolean = false, includeHidden: Boolean = false): Flow<List<Folder>> {
        val cloudFolders = combine(browsingAlbumSources, pinned.ids(), ignored.all(), covers.all()) { sources, pins, hidden, styles ->
            val (albums, resolvedMirrors) = sources
            val mirrors = resolvedMirrors ?: noAlbumMirrors
            val pinnedIds = pins.toHashSet()
            val hiddenIds = hidden.mapTo(mutableSetOf()) { it.bucketId }
            val excluded = if (includeHidden) emptySet() else hiddenIds
            val stylesById = styles.associateBy { it.bucketId }
            val cloud = albums.filterNot { it.id in excluded }.mapNotNull { stored ->
                val album = stored.matching(filter) ?: return@mapNotNull null
                val style = stylesById[album.id]
                val additions = mirrors.localAdditions(album.id, album, filter)
                val folder = album.asFolder(album.id in pinnedIds) { mirrors.coverUri(album.id, it) }
                // 최근 목록은 기기 항목 뒤에 서버 항목을 붙이고, 같은 촬영 시각이면 마지막 항목을 고른다.
                val latest = if (latestCovers) {
                    val photo = album.photos.asReversed().maxBy { it.taken ?: it.modified }
                    val local = additions.asReversed().maxByOrNull { it.dateTaken }
                    if (local != null && local.dateTaken > (photo.taken ?: photo.modified)) {
                        mediaUri(local.id, MediaType.entries[local.type])
                    } else mirrors.coverUri(album.id, photo)
                } else null
                folder.copy(
                    itemCount = folder.itemCount + additions.size,
                    size = folder.size + additions.sumOf { it.size },
                    automaticCover = latest,
                    manualCover = album.photos.firstOrNull { it.id == style?.photoId }?.let { mirrors.coverUri(album.id, it) }
                        ?: additions.firstOrNull { it.id == style?.photoId }?.toMedium(false)?.uri,
                )
            }
            BrowsingFolders(cloud, resolvedMirrors, pinnedIds, hiddenIds)
        }
        return cloudFolders.combine(media.folders(filter.types, includeHidden).onStart { emit(emptyList()) }) { cloud, rows ->
            // The remote index is safe to display before local mirror identities have been resolved.
            val folders = cloud.mirrors?.folders(
                rows.map { it.toFolder(it.bucketId in cloud.pinned) },
                cloud.folders, if (includeHidden) emptySet() else cloud.hidden, cloud.pinned, filter,
            ) ?: cloud.folders
            if (includeHidden) folders.map { if (it.bucketId in cloud.hidden) it.copy(isHidden = true) else it } else folders
        }.combine(lockedFolders) { folders, locked ->
            folders.map { if (it.bucketId in locked) it.copy(isLocked = true, automaticCover = null) else it }
        }
    }

    private data class BrowsingFolders(
        val folders: List<Folder>,
        val mirrors: AlbumMirrors?,
        val pinned: Set<Long>,
        val hidden: Set<Long>,
    )

    /** 갤러리에서 숨긴 폴더를 표지와 함께 보여 준다. 사진을 옮기거나 복사할 대상으로 고를 때 쓴다. */
    fun hiddenFolders(): Flow<List<Folder>> =
        combine(media.hiddenFolders(), albumSources, ignored.all(), lockedFolders) { rows, sources, hidden, locked ->
            val (albums, mirrors) = sources
            val hiddenIds = hidden.mapTo(hashSetOf()) { it.bucketId }
            val cloud = albums.filter { it.id in hiddenIds && it.photos.isNotEmpty() }.map { album ->
                album.asFolder(pinned = false) { mirrors.coverUri(album.id, it) }
            }
            (rows.map { it.toFolder(isPinned = false) } + cloud).map { if (it.bucketId in locked) it.copy(isLocked = true) else it }
        }

    suspend fun setFolderCover(bucketId: Long, photoId: Long?) = covers.setPhoto(bucketId, photoId)

    suspend fun localFolderPath(folder: Folder): String {
        check(!folder.isRemote && !folder.isRecent && !folder.isLocked)
        return localFolders.folderPath(folder)
    }

    suspend fun renameLocalFolder(folder: Folder, name: String): String {
        check(validFolderName(name))
        val path = localFolderPath(folder)
        return changeLocalFolder(folder.bucketId) { locked -> localFolders.rename(folder.bucketId, path, name, locked) }
    }

    suspend fun moveLocalFolder(folder: Folder, destination: String): String {
        val path = localFolderPath(folder)
        return changeLocalFolder(folder.bucketId) { locked -> localFolders.move(folder.bucketId, path, destination, locked) }
    }

    private suspend fun changeLocalFolder(bucketId: Long, change: (Set<Long>) -> FolderChange): String = indexSync.withLock {
        check(bucketId != RECENT_BUCKET_ID && !isRemoteId(bucketId))
        withContext(Dispatchers.IO + NonCancellable) {
            val updated = change(locks.settings.value.folders)
            try {
                updateFolderState(updated)
            } finally {
                refreshIndex(null)
            }
            updated.name
        }
    }

    private suspend fun updateFolderState(change: FolderChange) {
        database.withTransaction {
            val pins = pinned.ids().first().toHashSet()
            val hidden = ignored.all().first().associateBy(IgnoredFolderEntity::bucketId)
            val favoriteKeys = favorites.paths().first().toHashSet()
            for ((oldId, newId, name) in change.folders) {
                if (oldId != newId && oldId in pins) {
                    pinned.removeAll(listOf(oldId))
                    pinned.addAll(listOf(PinnedFolderEntity(newId)))
                }
                if (oldId in hidden) {
                    if (oldId != newId) ignored.remove(oldId)
                    ignored.add(IgnoredFolderEntity(newId, name))
                }
                val cover = covers.find(oldId) ?: continue
                val newPhoto = cover.photoId?.let { change.photos[it] }
                if (oldId != newId) covers.remove(oldId)
                if (newPhoto != null || oldId != newId) covers.upsert(cover.copy(bucketId = newId, photoId = newPhoto))
            }
            for ((source, target) in change.favorites) {
                if (source == target || source !in favoriteKeys) continue
                favorites.move(source, target)
            }
        }
        settings.remapFolders(change.folders.associate { it.oldId to it.newId })
    }

    private val deviceFolderEntries = media.allFolders().distinctUntilChanged().map { rows ->
        val volumes = paths.volumes(rows.map { it.mediaId })
        rows.map { FolderEntry(it.bucketId, it.bucketName, it.relativePath, it.itemCount, volumes[it.mediaId]) }
    }.shareIn(repositoryScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    val folderEntries: Flow<List<FolderEntry>> =
        combine(
            deviceFolderEntries, remote.transferFolders, ignored.all(), albumSources, linkedFolderMirrors,
        ) { local, cloud, hidden, sources, linkedMirrors ->
            val (albums, mirrors) = sources
            val indexed = albums.associateBy { it.id }
            val cloudIds = cloud.mapTo(hashSetOf(), FolderEntry::bucketId)
            val localPaths = linkedMirrors.associate { it.cloudId to it.absolutePath }
            val present = local.filterNot { mirrors.cloudByLocal[it.bucketId] in cloudIds } + cloud.map { entry ->
                val album = indexed[entry.bucketId]
                entry.copy(
                    itemCount = album?.photos.orEmpty().size + mirrors.localAdditions(entry.bucketId, album, MediaFilter.All).size,
                    syncLocalPath = localPaths[entry.bucketId]?.let(LocalStoragePath::absolute),
                )
            }
            val known = present.map(FolderEntry::bucketId).toHashSet()
            (present + hidden.filter { it.bucketId !in known }.map { FolderEntry(it.bucketId, it.name, "", 0) })
                .sortedBy { it.name.lowercase() }
        }

    suspend fun setIgnoredFolders(folders: List<FolderEntry>) =
        ignored.replaceAll(folders.map { IgnoredFolderEntity(it.bucketId, it.name) })

    suspend fun setPinned(bucketIds: List<Long>, pin: Boolean) =
        if (pin) pinned.addAll(bucketIds.map(::PinnedFolderEntity)) else pinned.removeAll(bucketIds)

    fun mediaIn(bucketId: Long, filter: MediaFilter): Flow<List<Medium>> = when {
        isVirtualFolder(bucketId) -> {
            val favoritesOnly = bucketId == FAVORITES_BUCKET_ID
            val local = if (favoritesOnly) {
                media.favoriteMedia(filter.types).map { items ->
                    items.map { it.medium.toMedium(isFavorite = true, favoriteOrder = it.addedOrder) }
                }
            } else media.recent(filter.types).withFavorites()
            combine(local, remoteMedia(filter, favoritesOnly), albumMirrors, lockedFolders, ignored.all()) { device, cloud, mirrors, locked, hidden ->
                mirrors.mergeMedia(device, cloud, locked + hidden.map { it.bucketId })
            }
        }
        isRemoteId(bucketId) -> combine(visibleAlbums, albumMirrors, favorites.paths()) { albums, mirrors, paths ->
            val album = albums.firstOrNull { it.id == bucketId }
            val marked = paths.toHashSet()
            val types = filter.types
            val cloud = album?.photos.orEmpty().filter { it.type.ordinal in types }.map { photo ->
                photo.asMedium(bucketId).copy(isFavorite = remoteFavoriteKey(photo.id) in marked)
            }
            val local = mirrors.filesIn(bucketId).filter { it.type in types }.map { it.toMedium(it.path in marked) }
            mirrors.mergeMedia(local, cloud)
        }
        else -> media.mediaIn(bucketId, filter.types).withFavorites()
    }.withoutRemoving()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun search(query: String): Flow<List<Medium>> = lockedFolders.flatMapLatest { locked ->
        combine(
            media.search(query, locked.toList()).withFavorites(),
            remoteMedia(MediaFilter.All),
            albumMirrors,
            ignored.all(),
        ) { local, cloud, mirrors, hidden ->
            val excluded = locked + hidden.map(IgnoredFolderEntity::bucketId)
            mirrors.mergeMedia(local, cloud.filter { it.name.contains(query, ignoreCase = true) }, excluded)
                .sortedByDescending(Medium::dateTaken).take(300)
        }
    }.withoutRemoving()

    private fun remoteMedia(filter: MediaFilter, favoritesOnly: Boolean = false): Flow<List<Medium>> =
        combine(remote.albums, ignored.all(), favorites.entries()) { albums, hidden, entries ->
            if (favoritesOnly && entries.isEmpty()) return@combine emptyList()
            val types = filter.types
            val hiddenIds = hidden.mapTo(mutableSetOf()) { it.bucketId }
            val orderByPath = entries.associate { it.path to it.addedOrder }
            albums.filter { it.id !in hiddenIds }
                .flatMap { album ->
                    album.photos.mapNotNull { photo ->
                        val order = orderByPath[remoteFavoriteKey(photo.id)]
                        if (photo.type.ordinal !in types || (favoritesOnly && order == null)) null else photo.asMedium(album.id).copy(
                            isFavorite = order != null,
                            favoriteOrder = order.takeIf { favoritesOnly },
                        )
                    }
                }
        }

    fun remotePath(medium: Medium): String? = medium.remoteUri?.let(remote::photoPath)

    fun remotePathLabel(medium: Medium): String? = medium.remoteUri?.let(remote::photoPathLabel)

    private fun Flow<List<MediumEntity>>.withFavorites(): Flow<List<Medium>> =
        combine(this, favorites.paths()) { items, favPaths ->
            val favs = favPaths.toHashSet()
            items.map { it.toMedium(it.path in favs) }
        }

    val ignoredFolders: Flow<List<IgnoredFolderEntity>> = ignored.all()

    internal suspend fun folderHideOptions(bucketIds: Set<Long>): List<FolderHideOption> =
        withContext(Dispatchers.Default) {
            val (cloud, local) = bucketIds.filterNot(::isVirtualFolder).partition(::isRemoteId)
            val parents = localFolderHideOptions(local.toSet())
            if (cloud.isEmpty()) parents else parents + remote.folderHideOptions(cloud.toSet())
        }

    private suspend fun localFolderHideOptions(bucketIds: Set<Long>): List<FolderHideOption> {
        if (bucketIds.isEmpty()) return emptyList()
        val rows = media.allFolders().first()
        val topFolders = rows.mapNotNullTo(hashSetOf()) { row ->
            val path = row.relativePath.trimEnd('/')
            if (row.bucketId in bucketIds && '/' in path) path.substringBefore('/') else null
        }
        if (topFolders.isEmpty()) return emptyList()
        val candidates = rows.filter { it.relativePath.substringBefore('/') in topFolders }
        val absoluteFolders = try {
            paths.foldersByBucket(candidates.mapTo(hashSetOf()) { it.bucketId })
        } catch (_: SecurityException) {
            return emptyList()
        }
        val mirrors = mirrorBuckets.first()
        val linked = if (mirrors.isEmpty()) emptyMap() else {
            remote.linkedFolders.first().associate { it.bucketId to it.folder.name }
        }
        val locations = candidates.mapNotNull { row ->
            val path = absoluteFolders[row.bucketId]?.singleOrNull() ?: return@mapNotNull null
            val suffix = "/${row.relativePath.trimEnd('/')}"
            if (!path.endsWith(suffix)) return@mapNotNull null
            val cloudId = mirrors[row.bucketId]?.takeIf { it in linked && row.bucketId !in bucketIds }
            val name = cloudId?.let(linked::get) ?: row.bucketName
            path.removeSuffix(suffix) to FolderHideLocation(cloudId ?: row.bucketId, name, path)
        }.groupBy({ it.first }, { it.second })
        val selectedVolumes = locations.filterValues { folders -> folders.any { it.bucketId in bucketIds } }
        return selectedVolumes.flatMap { (root, folders) ->
            ancestorHideOptions(folders, bucketIds, root).map { it.copy(localRoot = root) }
        }
    }

    internal suspend fun ignoreFolders(folders: Map<Long, String>) =
        ignored.addAll(folders.filterKeys { !isVirtualFolder(it) }.map { (id, name) -> IgnoredFolderEntity(id, name) })

    internal suspend fun unignoreFolders(bucketIds: Set<Long>) {
        database.withTransaction {
            bucketIds.chunked(500).forEach { ignored.removeAll(it) }
        }
    }

    suspend fun medium(id: Long): Medium? =
        if (isRemoteId(id)) remote.medium(id) else media.medium(id)?.toMedium(false)

    suspend fun locate(uri: Uri): Medium? {
        val id = scanner.mediaIdOf(uri) ?: return null
        return medium(id) ?: run {
            sync()
            medium(id)
        }
    }

    suspend fun mediaBucketOf(uri: Uri): Long? = scanner.mediaBucketOf(uri)

    suspend fun mediaForFolders(bucketIds: List<Long>): List<Medium> {
        val locked = locks.settings.value.folders
        val allowed = bucketIds.filterNot { it in locked }
        val mirrors = albumMirrors.first()
        val local = (allowed.filterNot(::isRemoteId).chunked(500).flatMap { media.mediaForFolders(it) } +
            allowed.filter(::isRemoteId).flatMap(mirrors::filesIn)).distinctBy(MediumEntity::id).map { it.toMedium(false) }
        val cloud = if (allowed.any(::isRemoteId)) remote.albums.first().filter { it.id in allowed }
            .flatMap { album -> album.photos.map { it.asMedium(album.id) } } else emptyList()
        return mirrors.mergeMedia(local, cloud, locked)
    }

    suspend fun sync(ids: Set<Long>? = null) = indexSync.withLock {
        if (ids == null) refreshIndex(null)
        else for (batch in ids.chunked(500)) refreshIndex(batch.toSet())
    }

    private suspend fun refreshIndex(ids: Set<Long>?) {
        val items = scanner.scan(ids = ids)
        database.withTransaction {
            val previous = if (ids == null) media.indexedMedia() else media.indexedMedia(ids)
            folderSync.onMediaIndexChanged(media.updateIndex(items, previous))
        }
    }

    // 시스템이 파일 ID를 생략한 경우에도 백그라운드에서는 동기화 위치의 색인만 갱신한다.
    internal suspend fun syncWatchedMedia(ids: Set<Long>?) {
        if (ids != null) return sync(ids)
        withContext(Dispatchers.IO) {
            indexSync.withLock {
                val current = mutableMapOf<Long, MediumEntity>()
                val previous = mutableMapOf<Long, MediumEntity>()
                val volumes = scanner.externalVolumes()
                for (root in folderSync.watchedLocations()) {
                    val volumeId = root.localId.substringBefore(':')
                    val volume = if (volumeId == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else volumeId.lowercase()
                    if (volume !in volumes) continue
                    val prefix = root.localId.substringAfter(':').trim('/').let { if (it.isEmpty()) "" else "$it/" }
                    val dirs = mutableMapOf<Pair<Long, String>, Boolean>()
                    fun includes(item: MediumEntity) = dirs.getOrPut(item.bucketId to item.relativePath) {
                        root.mediaDirectory(item) != null
                    }
                    scanner.scan(volume = volume, relativePath = prefix).filter(::includes).associateByTo(current, MediumEntity::id)
                    media.indexedUnder(prefix).filter(::includes).associateByTo(previous, MediumEntity::id)
                }
                // 감시 범위 밖으로 이동한 항목도 갤러리에는 새 위치로 남긴다.
                for (missing in (previous.keys - current.keys).chunked(500)) {
                    scanner.scan(ids = missing.toSet()).associateByTo(current, MediumEntity::id)
                }
                database.withTransaction {
                    folderSync.onMediaIndexChanged(media.updateIndex(current.values.toList(), previous.values.toList()))
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<List<Medium>>.withoutRemoving(): Flow<List<Medium>> =
        removing.flatMapLatest { ids ->
            // 숨김이 해제될 때도 최신 인덱스를 구독해 이전 목록이 잠깐 다시 나타나지 않게 한다.
            if (ids.isEmpty()) this else map { items -> items.filterNot { it.id in ids } }
        }

    suspend fun submitRemoval(
        items: List<Medium>,
        change: TrashChange,
        approveLocal: suspend (List<Medium>) -> Boolean,
        onFailure: () -> Unit,
    ): Boolean {
        require(change == TrashChange.Trash || change == TrashChange.Delete)
        val ids = items.mapTo(mutableSetOf(), Medium::id)
        while (true) {
            val active = removing.value
            if (ids.any { it in active }) return false
            if (removing.compareAndSet(active, active + ids)) break
        }
        // 승인 전 취소된 항목과 백그라운드로 넘긴 항목의 수명을 구분한다.
        val awaiting = ids.toMutableSet()
        try {
            for (batch in items.filterNot(Medium::isRemote).chunked(2000)) {
                if (!approveLocal(batch)) return false
                val approved = batch.mapTo(mutableSetOf(), Medium::id)
                awaiting.removeAll(approved)
                finishRemoval(approved, onFailure) {
                    indexSync.withLock {
                        database.withTransaction {
                            val removed = approved.chunked(500).flatMap { media.indexedMedia(it.toSet()) }
                            media.removeFromIndex(approved)
                            folderSync.onMediaIndexChanged(MediaIndexChanges(removed, emptyList()))
                        }
                    }
                }
            }
            val cloud = items.filter(Medium::isRemote)
            if (cloud.isNotEmpty()) {
                val queued = cloud.mapTo(mutableSetOf(), Medium::id)
                awaiting.removeAll(queued)
                finishRemoval(queued, onFailure) { changeRemoteTrash(cloud, change) }
            }
            return true
        } finally {
            removing.update { it - awaiting }
        }
    }

    private fun finishRemoval(ids: Set<Long>, onFailure: () -> Unit, work: suspend () -> Unit) {
        repositoryScope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onFailure() }
            } finally {
                removing.update { it - ids }
            }
        }
    }

    internal suspend fun applyRemoteChanges(changes: List<RemoteFileChange>) {
        if (changes.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            try { remote.applyFileChanges(changes) } finally { folderSync.remoteFilesChanged(changes) }
        }
    }

    suspend fun refresh(bucketIds: Set<Long>?): Unit = coroutineScope {
        if (bucketIds == null) launch { remote.refresh() }
        else bucketIds.filter(::isRemoteId).toSet().takeIf { it.isNotEmpty() }?.let { ids -> launch { remote.refresh(buckets = ids) } }
        val local = bucketIds?.filterNot(::isRemoteId)?.toSet()
        if (local == null || local.isNotEmpty()) {
            scanner.rescan(paths.folders(local))
            sync()
        }
    }

    internal suspend fun localSourcesToCopyForMove(sources: List<Medium>, folder: FolderEntry): List<Medium> {
        if (folder.volumeName == null || sources.isEmpty()) return sources
        val volume = requireNotNull(folder.volumeName)
        val volumes = paths.volumes(sources.map(Medium::id))
        return sources.filter { volumes.getValue(it.id) != volume }
    }

    /** [byPath]이면 모든 파일 접근 권한으로 파일 경로를 직접 옮기거나 복사한다. 수정 허락과 원본 삭제 확인이 필요 없다. */
    internal suspend fun transfer(
        items: List<Medium>, folder: FolderEntry, move: Boolean, crossVolumeIds: Set<Long>, byPath: Boolean = false,
    ): TransferResult {
        val cloudTarget = isRemoteId(folder.bucketId) && folder.volumeName == null
        val copiedFavorites = mutableSetOf<String>()
        val changes = mutableListOf<RemoteFileChange>()
        val imported = mutableSetOf<Long>()
        try {
            val completed = items.count { item ->
                when {
                    item.bucketId == folder.bucketId -> true
                    item.isRemote || cloudTarget -> {
                        val source = if (move && item.isFavorite) favoriteKey(item) else null
                        val key = remoteFiles.transfer(item, folder, move, { imported.add(it) }) { changes.add(it) }
                        if (source != null) {
                            if (item.isRemote) favorites.move(source, key) else {
                                favorites.copy(source, key)
                                copiedFavorites.add(source)
                            }
                        }
                        true
                    }
                    byPath && move && item.id in crossVolumeIds -> {
                        val source = if (item.isFavorite) favoriteKey(item) else null
                        val target = writer.moveAcrossByPath(item, folder.path, requireNotNull(folder.volumeName))
                            ?: return@count false
                        if (source != null) {
                            favorites.copy(source, target)
                            copiedFavorites.add(source)
                        }
                        true
                    }
                    byPath && move -> relocate(item, { oldPath ->
                        val name = paths.absolute(item.uri)?.substringAfterLast('/') ?: oldPath.substringAfterLast('/')
                        folder.path + name
                    }) { writer.moveByPath(item, folder.path) }
                    byPath -> writer.copyByPath(item, folder.path, requireNotNull(folder.volumeName))
                    item.id in crossVolumeIds -> {
                        val source = if (item.isFavorite) favoriteKey(item) else null
                        val target = writer.copyForMove(item, folder.path, requireNotNull(folder.volumeName)) ?: return@count false
                        if (source != null) {
                            favorites.copy(source, target)
                            copiedFavorites.add(source)
                        }
                        true
                    }
                    move -> relocate(item, { oldPath ->
                        val name = paths.absolute(item.uri)?.substringAfterLast('/') ?: oldPath.substringAfterLast('/')
                        folder.path + name
                    }) { writer.move(item, folder.path) }
                    else -> writer.copy(item, folder.path, requireNotNull(folder.volumeName))
                }
            }
            return TransferResult(completed, copiedFavorites)
        } finally {
            applyRemoteChanges(changes)
            if (imported.isNotEmpty()) sync(imported)
        }
    }

    internal suspend fun finishCopiedMove(sourceFavorites: Set<String>, sourceIds: Set<Long>) {
        sync(sourceIds)
        for (path in sourceFavorites) {
            if (!media.hasPath(path)) favorites.remove(path)
        }
    }

    suspend fun rename(medium: Medium, name: String): Boolean {
        if (!medium.isRemote) return relocate(medium, { it.substringBeforeLast('/', "") + "/" + name }) {
            if (hasAllFilesAccess()) writer.renameByPath(medium, name) else writer.rename(medium, name)
        }
        val change = remoteFiles.rename(medium, name)
        applyRemoteChanges(listOf(change))
        if (medium.isFavorite) {
            favorites.move(remoteFavoriteKey(medium.id), remoteFavoriteKey(remoteId(change.serverId, requireNotNull(change.after).path)))
        }
        return true
    }

    suspend fun changeRemoteTrash(items: List<Medium>, action: TrashChange) {
        val completed = mutableListOf<RemoteFileChange>()
        try {
            for (item in items) {
                completed += remoteFiles.changeTrash(item, action)
            }
        } finally {
            // 재조회가 실패해도 서버가 이미 처리한 원본이 오프라인 목록에 되살아나지 않게 한다.
            applyRemoteChanges(completed)
        }
    }

    suspend fun editMetadata(medium: Medium, edit: MetadataEdit): Boolean {
        if (medium.isRemote) {
            applyRemoteChanges(listOf(remoteFiles.editMetadata(medium, edit)))
            return true
        }
        if (!writer.writeMetadata(medium, edit)) return false
        scanner.rescan(paths.absolute(listOf(medium)))
        sync(setOf(medium.id))
        return true
    }

    private suspend fun relocate(medium: Medium, target: suspend (String) -> String, write: suspend () -> Boolean): Boolean {
        val path = media.pathOf(medium.id)
        if (!write()) return false
        if (medium.isFavorite && path != null) {
            favorites.move(path, target(path))
        }
        return true
    }

    suspend fun setFavorite(medium: Medium, favorite: Boolean) {
        val path = favoriteKey(medium) ?: return
        if (favorite) favorites.add(path) else favorites.remove(path)
    }

    private suspend fun favoriteKey(medium: Medium): String? =
        if (medium.isRemote) remoteFavoriteKey(medium.id) else media.pathOf(medium.id)
}

internal data class TransferResult(val completed: Int, val sourceFavorites: Set<String>)

private val MediumEntity.path: String get() = relativePath + name

private fun MediumEntity.toMedium(isFavorite: Boolean, favoriteOrder: Long? = null) = Medium(
    id = id,
    name = name,
    bucketId = bucketId,
    dateTaken = dateTaken,
    dateModified = dateModified,
    size = size,
    type = MediaType.entries[type],
    durationMs = durationMs,
    isFavorite = isFavorite,
    favoriteOrder = favoriteOrder,
)

private fun FolderRow.toFolder(isPinned: Boolean) = Folder(
    bucketId = bucketId,
    name = bucketName,
    itemCount = itemCount,
    size = size,
    dateTaken = dateTaken,
    dateModified = dateModified,
    coverId = coverId,
    coverType = coverType,
    isPinned = isPinned,
    manualCover = preferredPhotoId?.let { id ->
        preferredPhotoType?.let(MediaType.entries::getOrNull)?.takeUnless { it == MediaType.Video }?.let { mediaUri(id, it) }
    },
)
