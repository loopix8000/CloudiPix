package app.cloudipix.gallery.core.remote

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.cloudipix.gallery.core.data.MediaWriter
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.media.write
import app.cloudipix.gallery.core.model.FolderEntry
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class TrashChange { Trash, Restore, Delete }

@Singleton
class RemoteFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: RemoteGallery,
    private val transport: RemoteTransport,
    private val images: RemoteImages,
    private val writer: MediaWriter,
) {
    private val writes = Mutex()

    private fun source(medium: Medium) = gallery.source(medium.uri) ?: throw IOException("사진이 변경되었거나 연결이 해제되었습니다.")

    suspend fun <T> withReadableUri(medium: Medium, block: suspend (Uri) -> T): T =
        if (medium.isRemote) withTransferOriginal(medium) { block(Uri.fromFile(it)) } else block(medium.uri)

    suspend fun withCachedOriginalUri(uri: Uri, block: suspend (Uri) -> Unit): Boolean =
        images.withCachedOriginal(uri) { block(Uri.fromFile(it)) }

    private suspend fun <T> withTransferOriginal(medium: Medium, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        // 동영상 원본은 사진 원본 캐시를 밀어내지 않도록 한 번 쓰고 지우는 파일로 받는다.
        if (medium.type != MediaType.Video && medium.size <= MAX_ORIGINAL_BYTES) {
            return@withContext images.withOriginal(medium.uri, block)
        }
        val (server, photo) = source(medium)
        withDraft { file ->
            file.outputStream().use { transport.download(server, photo.path, it, photo.size.takeIf { n -> n > 0 } ?: Long.MAX_VALUE) }
            if (photo.size > 0 && file.length() != photo.size) throw IOException()
            block(file)
        }
    }

    suspend fun shareUri(medium: Medium): Uri {
        if (!medium.isRemote) return medium.uri
        return withTransferOriginal(medium) { original ->
            val directory = File(context.cacheDir, "shared-photos").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            val suffix = medium.name.substringAfterLast('.', "").filter(Char::isLetterOrDigit).take(12).ifEmpty {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(source(medium).second.mimeType) ?: "img"
            }
            val file = File.createTempFile("photo-", ".$suffix", directory)
            try {
                original.copyTo(file, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.sharedphotos", file, medium.name)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }

    internal suspend fun transfer(medium: Medium, folder: FolderEntry, move: Boolean, onImport: (Long) -> Unit,
        onChange: (RemoteFileChange) -> Unit): String = withContext(Dispatchers.IO) {
        writes.withLock {
            val from = if (medium.isRemote) source(medium) else null
            val to = if (isRemoteId(folder.bucketId)) gallery.destination(folder.bucketId) else null
            val sourceEntry = if (move && from != null) transport.currentFile(from.first, from.second) else null
            val target = if (to != null) {
                val (server, directory) = to
                val path = transport.child(server, directory, medium.name)
                val mime = when {
                    server.protocol == RemoteProtocol.Ftp -> null
                    from != null -> from.second.mimeType
                    else -> context.contentResolver.getType(medium.uri)
                }
                if (RemoteEntry(path, medium.name, false, mimeType = mime).type == null) {
                    throw IOException("대상 서버에서 사진이나 동영상으로 인식할 수 없는 형식입니다.")
                }
                if (move && from?.first?.id == server.id) {
                    val entry = transport.moveEntry(server, requireNotNull(sourceEntry), directory, medium.name)
                    onChange(RemoteFileChange(server.id, transport.parent(server, from.second.path), from.second.path, null))
                    onChange(RemoteFileChange(server.id, directory, null, entry, from.second.taken))
                } else {
                    if (from != null) {
                        withTransferOriginal(medium) { file ->
                            // 서로 다른 연결이 동일한 서버 파일을 가리킬 수 있어 새 대상을 게시한 경우에만 원본을 삭제한다.
                            val uploaded = transport.upload(server, directory, medium.name, file, mime = mime, verifyCopy = move)
                            onChange(RemoteFileChange(server.id, directory, null, uploaded, from.second.taken))
                            if (move) {
                                verifyBeforeRemoval(from, sourceEntry, file)
                                val entry = transport.currentFile(from.first, from.second)
                                transport.deleteFile(from.first, from.second.path, entry.etag)
                                onChange(RemoteFileChange(from.first.id, transport.parent(from.first, from.second.path), from.second.path, null))
                            }
                        }
                    } else withDraft { file ->
                        val input = context.contentResolver.openInputStream(medium.uri) ?: throw IOException()
                        input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
                        if (file.length() == 0L || (medium.size > 0 && file.length() != medium.size)) throw IOException()
                        val uploaded = transport.upload(server, directory, medium.name, file, acceptIdentical = move, mime = mime, verifyCopy = move)
                        onChange(RemoteFileChange(server.id, directory, null, uploaded, medium.dateTaken))
                    }
                }
                remoteFavoriteKey(remoteId(server.id, path))
            } else {
                val uri = withTransferOriginal(medium) { file ->
                    val original = requireNotNull(from)
                    val mime = original.second.mimeType?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
                        ?: photoMime(medium.name)
                    writer.importPhoto(medium, file, mime, folder.path, requireNotNull(folder.volumeName), acceptIdentical = move).also {
                        onImport(android.content.ContentUris.parseId(it))
                        if (move) verifyBeforeRemoval(original, sourceEntry, file)
                    }
                }
                // MediaStore는 이름이 겹치면 새 파일에 다른 이름을 붙일 수 있다.
                val name = context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: medium.name
                folder.path + name
            }
            if (move && from != null && to == null) {
                onChange(changeTrashLocked(medium, TrashChange.Trash))
            }
            target
        }
    }

    private suspend fun verifyBeforeRemoval(source: Pair<RemoteServer, RemotePhoto>, entry: RemoteEntry?, file: File) {
        // 강한 ETag가 없는 서버에서는 같은 크기와 수정 시각을 가진 변경도 구분한다.
        if (entry?.etag?.startsWith('"') != true && !transport.matches(source.first, source.second.path, file)) {
            throw IOException("전송 도중 원본이 변경되었습니다.")
        }
    }

    internal suspend fun rename(medium: Medium, name: String): RemoteFileChange = withContext(Dispatchers.IO) {
        writes.withLock {
            val (server, photo) = source(medium)
            movePhoto(server, photo, transport.currentFile(server, photo), name)
        }
    }

    internal suspend fun changeTrash(medium: Medium, action: TrashChange) = withContext(Dispatchers.IO) {
        writes.withLock { changeTrashLocked(medium, action) }
    }

    private suspend fun changeTrashLocked(medium: Medium, action: TrashChange): RemoteFileChange {
        val (server, photo) = source(medium)
        val entry = transport.currentFile(server, photo)
        val original = restoredName(entry.name)
        val name = when (action) {
            TrashChange.Trash -> {
                if (original != null) throw IOException()
                trashFileName(photo.name)
            }
            TrashChange.Restore -> original ?: throw IOException()
            TrashChange.Delete -> {
                if (original == null) throw IOException()
                transport.deleteFile(server, photo.path, entry.etag)
                return RemoteFileChange(server.id, transport.parent(server, photo.path), photo.path, null)
            }
        }
        return movePhoto(server, photo, entry, name)
    }

    private suspend fun movePhoto(server: RemoteServer, photo: RemotePhoto, entry: RemoteEntry, name: String): RemoteFileChange {
        val folder = transport.parent(server, photo.path)
        val moved = transport.moveEntry(server, entry, folder, name)
        return RemoteFileChange(server.id, folder, photo.path, moved, photo.taken)
    }

    internal suspend fun saveEdit(medium: Medium, file: File, mime: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            val (server, photo) = source(medium)
            val editedAt = System.currentTimeMillis()
            val name = "${photo.name.substringBeforeLast('.', photo.name)}_edited_$editedAt.${file.extension}"
            val folder = transport.parent(server, photo.path)
            val uploaded = transport.upload(server, folder, name, file, mime = mime)
            RemoteFileChange(server.id, folder, null, uploaded, editedAt.takeIf { uploaded.modified == 0L })
        }
    }

    internal suspend fun editMetadata(medium: Medium, edit: MetadataEdit) = withContext(Dispatchers.IO) {
        writes.withLock {
            val (server, photo) = source(medium)
            withTransferOriginal(medium) { original ->
                withDraft { draft ->
                    original.copyTo(draft, overwrite = true)
                    ExifInterface(draft).run { write(edit); saveAttributes() }
                    val folder = transport.parent(server, photo.path)
                    val entry = transport.upload(server, folder, photo.name, draft,
                        replace = transport.currentFile(server, photo), original = original, mime = photo.mimeType)
                    RemoteFileChange(server.id, folder, photo.path, entry, edit.taken)
                }
            }
        }
    }

    private suspend fun <T> withDraft(block: suspend (File) -> T): T {
        val file = File.createTempFile("cloud-write-", ".tmp", context.cacheDir)
        return try { block(file) } finally { file.delete() }
    }
}

internal fun remoteFavoriteKey(id: Long) = "gallery-remote:$id"

internal fun photoMime(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
