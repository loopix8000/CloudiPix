package app.cloudipix.gallery.core.remote

import android.net.Uri
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.MediaFilter
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.model.withLeadingSlash
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

const val REMOTE_MEDIA_SCHEME = "gallery-remote"
internal const val SYNC_FILE_PREFIX = "cloudify-sync-"

@Serializable
enum class RemoteProtocol { WebDav, Ftp }

@Serializable
internal class RemoteServer(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val address: String,
    val username: String,
    val password: String,
    val folders: List<RemoteFolder>? = null,
    val discoverAll: Boolean = folders == null,
    val legacySyncImported: Boolean = false,
) {
    val hasLinkedFolders: Boolean get() = discoverAll || folders.orEmpty().any(RemoteFolder::linked)
    val folderLinks: RemoteFolderLinks get() = RemoteFolderLinks(folders.orEmpty(), discoverAll)

    fun withFolders(
        folders: List<RemoteFolder>, discoverAll: Boolean = this.discoverAll, legacySyncImported: Boolean = this.legacySyncImported,
    ) = RemoteServer(id, name, protocol, address, username, password, folders, discoverAll, legacySyncImported)

    fun withFolder(folder: RemoteFolder): RemoteServer {
        val saved = folders.orEmpty()
        return withFolders(if (saved.any { it.path == folder.path }) {
            saved.map { if (it.path == folder.path) folder else it }
        } else saved + folder)
    }

    fun connectedFolders(destinations: List<RemoteFolder>): List<ConnectedFolder> {
        val roots = destinations.filter { it.sync != null }.sortedByDescending { it.path.length }
        return destinations.map { folder ->
            val parent = if (folder.sync == null) roots.firstOrNull { folder.path.isRemoteDescendantOf(it.path) } else null
            ConnectedFolder(id, name, folder, parent)
        }
    }
}

data class RemoteServerStatus(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val address: String,
    val username: String,
    val refreshing: Boolean = false,
    val unavailable: Boolean = false,
    val albumCount: Int = 0,
    val folderCount: Int? = null,
)

@Serializable
data class RemoteFolder(
    val path: String,
    val name: String,
    val sync: RemoteFolderSync? = null,
    // 자동 검색 연결에서 해제한 폴더는 다음 검색에도 제외한다.
    val linked: Boolean = true,
    // 이 값이 없는 기존 연결은 선택한 폴더의 범위를 그대로 유지한다.
    val includeSubfolders: Boolean = false,
) {
    val displayPath: String get() = remotePathLabel(path)
}

internal fun remotePathLabel(path: String): String {
    val decodedPath = if (!path.startsWith('/') && "://" in path) Uri.parse(path).path.orEmpty() else path
    return decodedPath.withLeadingSlash()
}

internal fun String.isRemoteDescendantOf(parent: String): Boolean =
    this != parent && startsWith(parent.trimEnd('/') + "/")

internal fun RemoteFolder.relativePathFrom(parent: RemoteFolder): String? {
    if (!path.trimEnd('/').isRemoteDescendantOf(parent.path.trimEnd('/'))) return null
    val prefix = parent.displayPath.trimEnd('/') + "/"
    return displayPath.trimEnd('/').takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
}

internal class RemoteFolderLinks(folders: List<RemoteFolder>, private val discoverAll: Boolean = false) {
    val byPath = folders.associateBy(RemoteFolder::path)
    val selected = folders.filter(RemoteFolder::linked)
    private val recursive = selected.filter(RemoteFolder::includeSubfolders)

    fun includes(path: String): Boolean = byPath[path]?.linked ?: scansSubfolders(path)

    fun scansSubfolders(path: String): Boolean = discoverAll || recursive.any {
        path == it.path || path.isRemoteDescendantOf(it.path)
    }
}

internal data class RemoteFolderSelection(
    val root: RemoteFolder,
    val links: RemoteFolderLinks,
    val discoverAll: Boolean,
)

@Serializable
data class RemoteFolderSync(
    val id: String,
    val treeUri: String,
    val localId: String,
    val localLabel: String,
    val directChild: Boolean = false,
    val useTreeRoot: Boolean = false,
    val excludedLocalChild: String? = null,
    val preserveExistingFiles: Boolean = false,
    val excludedSubfolders: Set<String> = emptySet(),
) {
    val keepLocalFiles: Boolean get() = useTreeRoot || preserveExistingFiles

    internal fun excludesSubfolder(path: String): Boolean {
        if (excludedSubfolders.isEmpty()) return false
        val relative = path.lowercase(Locale.ROOT)
        return excludedSubfolders.any {
            val excluded = it.lowercase(Locale.ROOT)
            relative == excluded || relative.startsWith("$excluded/")
        }
    }
}

internal data class ConnectedFolder(
    val serverId: String,
    val serverName: String,
    val folder: RemoteFolder,
    val inheritedFrom: RemoteFolder? = null,
) {
    val bucketId: Long = remoteId(serverId, folder.path)
    val inheritedRelativePath: String? get() = inheritedFrom?.let(folder::relativePathFrom)
    val excludedFromSync: Boolean get() =
        inheritedRelativePath?.let { inheritedFrom?.sync?.excludesSubfolder(it) } == true
    val excludedByAncestor: Boolean get() =
        inheritedRelativePath?.substringBeforeLast('/', "")
            ?.let { inheritedFrom?.sync?.excludesSubfolder(it) } == true
    val effectiveSync: RemoteFolderSync? get() = folder.sync ?: inheritedFrom?.sync?.takeUnless { excludedFromSync }
    val configuredLocalPath: String? get() {
        folder.sync?.let { return it.localLabel }
        val relative = inheritedRelativePath ?: return null
        return inheritedFrom?.sync?.let { "${it.localLabel.trimEnd('/')}/$relative" }
    }
}

enum class RemoteFailure { Address, Credentials, Connection, Storage }

@Serializable
internal data class RemoteEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val modified: Long = 0L,
    val size: Long = 0L,
    val etag: String? = null,
    val preview: String? = null,
    val mimeType: String? = null,
    val unsupported: Boolean = false,
    val modifiedTimeIsApproximate: Boolean = false,
) {
    val type: MediaType? get() {
        if (directory) return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mimeType == "image/gif" || extension == "gif" -> MediaType.Gif
            extension in PHOTO_EXTENSIONS -> MediaType.Image
            // 동영상은 앱에서 재생할 수 있는 형식만 확장자로 고른다.
            extension in VIDEO_EXTENSIONS -> MediaType.Video
            mimeType?.startsWith("image/") == true -> MediaType.Image
            else -> null
        }
    }
}

@Serializable
internal data class RemoteAlbum(
    val id: Long,
    val name: String,
    val photos: List<RemotePhoto>,
    val trash: List<RemotePhoto> = emptyList(),
    val directory: String? = null,
    val listingToken: String? = null,
    // 목록에서 인식하는 파일 형식이 바뀌면 올려서 예전 목록을 다시 읽게 한다.
    val mediaVersion: Int = 0,
) {
    /** [filter]에 맞는 항목만 남긴다. 남는 항목이 없으면 null이다. */
    fun matching(filter: MediaFilter): RemoteAlbum? {
        if (filter == MediaFilter.All) return this
        val types = filter.types
        val shown = photos.filter { it.type.ordinal in types }
        return when {
            shown.isEmpty() -> null
            shown.size == photos.size -> this
            else -> copy(photos = shown)
        }
    }

    fun asFolder(pinned: Boolean, coverUri: (RemotePhoto) -> Uri): Folder {
        val cover = photos.maxBy(RemotePhoto::modified)
        return Folder(
            bucketId = id,
            name = name,
            itemCount = photos.size,
            size = photos.sumOf(RemotePhoto::size),
            dateTaken = cover.modified,
            dateModified = cover.modified,
            coverId = cover.id,
            coverType = cover.type.ordinal,
            isPinned = pinned,
            remoteCover = coverUri(cover),
        )
    }
}

private val trashName = Regex("""^sg-trash-([0-9a-f-]{36})--(.+)\.sgtrash$""")

internal fun restoredName(name: String): String? = trashName.matchEntire(name)?.groupValues?.get(2)
internal fun trashFileName(name: String) = "sg-trash-${java.util.UUID.randomUUID()}--$name.sgtrash"

internal data class RemoteFileChange(
    val serverId: String,
    val directory: String,
    val before: String?,
    val after: RemoteEntry?,
    val taken: Long? = null,
)

@Serializable
internal data class RemotePhoto(
    val serverId: String,
    val id: Long,
    val name: String,
    val path: String,
    val modified: Long,
    val size: Long,
    val type: MediaType,
    val revision: String,
    val preview: String?,
    val mimeType: String? = null,
    val cacheVersion: String? = null,
    val taken: Long? = null,
) {
    val uri: Uri get() = Uri.Builder()
        .scheme(REMOTE_MEDIA_SCHEME)
        .authority(serverId)
        .appendPath(id.toString())
        .appendPath(cacheVersion ?: revision)
        .build()

    fun asMedium(bucketId: Long) = Medium(
        id = id,
        name = name,
        bucketId = bucketId,
        dateTaken = taken ?: modified,
        dateModified = modified,
        size = size,
        type = type,
        durationMs = 0L,
        isFavorite = false,
        remoteUri = uri,
    )
}

// 로컬 MediaStore 폴더에 쓰이는 32비트 ID와 최근 항목 예약값을 피한다.
internal fun remoteId(serverId: String, path: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest("$serverId\u0000$path".toByteArray())
    return (ByteBuffer.wrap(digest).long and 0x3fff_ffff_ffff_ffffL).coerceAtLeast(1) or Long.MIN_VALUE
}

fun isRemoteId(id: Long): Boolean = id in (Long.MIN_VALUE + 1)..(-0x4000_0000_0000_0001L)

internal val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "jpe", "png", "webp", "bmp", "heif", "heic", "avif", "dng", "tiff", "tif")
internal val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm")
internal const val ALBUM_MEDIA_VERSION = 1
