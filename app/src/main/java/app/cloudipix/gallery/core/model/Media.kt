package app.cloudipix.gallery.core.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import app.cloudipix.gallery.core.remote.isRemoteId

enum class MediaType { Image, Video, Gif }

data class Medium(
    val id: Long,
    override val name: String,
    val bucketId: Long,
    override val dateTaken: Long,
    override val dateModified: Long,
    override val size: Long,
    val type: MediaType,
    val durationMs: Long,
    val isFavorite: Boolean,
    val remoteUri: Uri? = null,
    val localCopyUri: Uri? = null,
    override val favoriteOrder: Long? = null,
) : Sortable {
    override val typeRank: Int get() = type.ordinal
    val uri: Uri get() = remoteUri ?: mediaUri(id, type)
    val displayUri: Uri get() = localCopyUri ?: uri
    val isRemote: Boolean get() = remoteUri != null
}

@Immutable
data class Folder(
    val bucketId: Long,
    override val name: String,
    val itemCount: Int,
    override val size: Long,
    override val dateTaken: Long,
    override val dateModified: Long,
    val coverId: Long,
    val coverType: Int,
    val isPinned: Boolean = false,
    val remoteCover: Uri? = null,
    val manualCover: Uri? = null,
    val isLocked: Boolean = false,
    val automaticCover: Uri? = null,
    val isHidden: Boolean = false,
) : Sortable {
    override val typeRank: Int get() = coverType
    val isRecent: Boolean get() = bucketId == RECENT_BUCKET_ID
    val isFavorites: Boolean get() = bucketId == FAVORITES_BUCKET_ID
    val isVirtual: Boolean get() = isVirtualFolder(bucketId)
    val coverUri: Uri get() = manualCover ?: automaticCover ?: remoteCover ?: mediaUri(coverId, MediaType.entries[coverType])
    val isRemote: Boolean get() = !isVirtual && (remoteCover != null || isRemoteId(bucketId))
}

const val RECENT_BUCKET_ID = Long.MIN_VALUE
const val FAVORITES_BUCKET_ID = Long.MAX_VALUE

fun isVirtualFolder(bucketId: Long): Boolean = bucketId == RECENT_BUCKET_ID || bucketId == FAVORITES_BUCKET_ID

fun List<Folder>.recent(): Folder? = maxByOrNull(Folder::dateTaken)?.copy(
    bucketId = RECENT_BUCKET_ID,
    name = "",
    itemCount = sumOf(Folder::itemCount),
    size = sumOf(Folder::size),
    dateModified = maxOf(Folder::dateModified),
    isPinned = false,
    manualCover = null,
)

data class FolderEntry(
    val bucketId: Long,
    val name: String,
    val path: String,
    val itemCount: Int,
    val volumeName: String? = null,
    val syncLocalPath: LocalStoragePath? = null,
)

fun mediaUri(id: Long, type: MediaType): Uri = ContentUris.withAppendedId(
    if (type == MediaType.Video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    id,
)
