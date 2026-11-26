package app.cloudipix.gallery.core.data

import android.net.Uri
import app.cloudipix.gallery.core.database.MediumEntity
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.mediaUri
import app.cloudipix.gallery.core.remote.ConnectedFolder
import app.cloudipix.gallery.core.remote.RemoteAlbum
import app.cloudipix.gallery.core.remote.RemotePhoto
import app.cloudipix.gallery.core.sync.SyncFileFilter

internal data class AlbumMirror(val cloudId: Long, val relativePath: String, val absolutePath: String)

internal fun ConnectedFolder.albumMirror(): AlbumMirror? {
    val location = effectiveSync ?: return null
    val localPath = configuredLocalPath ?: return null
    val suffix = inheritedRelativePath?.let { "/$it" }.orEmpty()
    return AlbumMirror(bucketId, location.localId.substringAfter(':').trimEnd('/') + suffix, localPath.trimEnd('/'))
}

internal class AlbumMirrors(
    val cloudByLocal: Map<Long, Long>,
    files: List<MediumEntity>,
    private val filters: Map<Long, SyncFileFilter> = emptyMap(),
) {
    val localByCloud = cloudByLocal.entries.associate { (local, cloud) -> cloud to local }
    private val filesByCloud = files.groupBy { cloudByLocal.getValue(it.bucketId) }
    private val filesByName = filesByCloud.mapValues { (_, items) -> items.associateBy(MediumEntity::name) }
    private val visibleLocal = files.mapTo(hashSetOf(), MediumEntity::bucketId)
    private val filteredFiles = filesByCloud.filterKeys { it in filters }.mapValues { (id, items) ->
        items.filter { allowsLocal(id, it.name, MediaType.entries[it.type]) }
    }

    fun filesIn(cloudId: Long): List<MediumEntity> = filteredFiles[cloudId] ?: filesByCloud[cloudId].orEmpty()

    private fun allowsLocal(cloudId: Long, name: String, type: MediaType): Boolean =
        filters[cloudId]?.allows(name, if (type == MediaType.Video) "video/*" else "image/*", null) != false

    private fun matchingFile(cloudId: Long, name: String, size: Long, type: MediaType): MediumEntity? =
        filesByName[cloudId]?.get(name)?.takeIf { it.size == size && it.type == type.ordinal }

    fun coverUri(cloudId: Long, photo: RemotePhoto): Uri =
        matchingFile(cloudId, photo.name, photo.size, photo.type)?.takeUnless { it.dateModified == photo.staleLocalCopyModified }
            ?.let { mediaUri(it.id, photo.type) } ?: photo.uri

    fun mergeMedia(local: List<Medium>, cloud: List<Medium>, excluded: Set<Long> = emptySet()): List<Medium> {
        val visibleCloud = cloud.filterNot { it.bucketId in excluded }
        val names = visibleCloud.groupBy(Medium::bucketId).mapValues { (_, items) -> items.associateBy(Medium::name) }
        val copies = mutableMapOf<Long, Medium>()
        val device = local.mapNotNull { item ->
            val album = cloudByLocal[item.bucketId]
            if (item.bucketId in excluded || album in excluded) return@mapNotNull null
            if (album == null) return@mapNotNull item
            val counterpart = names[album]?.get(item.name) ?: return@mapNotNull item
                .takeIf { allowsLocal(album, it.name, it.type) }?.copy(bucketId = album)
            if (item.type == counterpart.type && item.size == counterpart.size) copies[counterpart.id] = item
            null
        }
        return device + visibleCloud.map { item ->
            val copy = copies[item.id]
            val indexed = if (copy == null) matchingFile(item.bucketId, item.name, item.size, item.type)
                ?.takeUnless { it.bucketId in excluded } else null
            when {
                copy != null -> item.copy(localCopyUri = copy.uri, localCopyModified = copy.dateModified)
                indexed != null -> item.copy(localCopyUri = mediaUri(indexed.id, item.type), localCopyModified = indexed.dateModified)
                else -> item
            }
        }
    }

    fun localAdditions(cloudId: Long, album: RemoteAlbum?, filter: MediaFilter): List<MediumEntity> {
        val local = filesIn(cloudId)
        if (local.isEmpty()) return emptyList()
        val cloudNames = album?.photos.orEmpty().mapTo(hashSetOf()) { it.name }
        val types = filter.types
        return local.filter { it.type in types && it.name !in cloudNames }
    }

    fun folders(
        local: List<Folder>, cloud: List<Folder>, hidden: Set<Long>, pins: Set<Long>, mediaFilter: MediaFilter = MediaFilter.All,
    ): List<Folder> {
        val represented = cloud.mapTo(hashSetOf(), Folder::bucketId)
        return local.mapNotNull { folder ->
            val id = cloudByLocal[folder.bucketId] ?: return@mapNotNull folder
            val pinned = folder.isPinned || id in pins
            when {
                id in represented || id in hidden -> null
                folder.bucketId !in visibleLocal -> folder
                id in filters -> folder.filteredMirror(id, pinned, mediaFilter)
                else -> folder.copy(bucketId = id, isPinned = pinned, localBucketId = folder.bucketId)
            }
        } + cloud.map { folder ->
            val local = localByCloud[folder.bucketId] ?: return@map folder
            folder.copy(localBucketId = local, isPinned = folder.isPinned || local in pins)
        }
    }

    private fun Folder.filteredMirror(cloudId: Long, pinned: Boolean, mediaFilter: MediaFilter): Folder? {
        val types = mediaFilter.types
        val items = filesIn(cloudId).filter { it.type in types }
        val cover = items.asReversed().maxByOrNull(MediumEntity::dateTaken) ?: return null
        return copy(
            bucketId = cloudId, isPinned = pinned, itemCount = items.size, size = items.sumOf(MediumEntity::size),
            localBucketId = bucketId,
            dateTaken = cover.dateTaken, dateModified = items.maxOf(MediumEntity::dateModified),
            coverId = cover.id, coverType = cover.type,
            manualCover = manualCover?.takeIf { uri -> items.any { mediaUri(it.id, MediaType.entries[it.type]) == uri } },
        )
    }
}

internal fun matchAlbumMirrors(mirrors: List<AlbumMirror>, directories: Map<Long, Set<String>>): Map<Long, Long> {
    val byPath = mirrors.groupBy(AlbumMirror::absolutePath)
    return buildMap {
        for ((bucket, paths) in directories) {
            val path = paths.singleOrNull() ?: continue
            val mirror = byPath[path.trimEnd('/')]?.singleOrNull() ?: continue
            put(bucket, mirror.cloudId)
        }
    }
}
