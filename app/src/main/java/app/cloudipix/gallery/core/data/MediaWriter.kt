package app.cloudipix.gallery.core.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.core.media.acceptsMedia
import app.cloudipix.gallery.core.media.storageRoots
import app.cloudipix.gallery.core.media.vacantFile
import app.cloudipix.gallery.core.media.write
import app.cloudipix.gallery.core.model.MediaType
import androidx.exifinterface.media.ExifInterface
import app.cloudipix.gallery.core.model.Medium
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    fun trashRequest(media: List<Medium>, trashed: Boolean): IntentSender =
        MediaStore.createTrashRequest(resolver, media.map(Medium::uri), trashed).intentSender

    fun deleteRequest(media: List<Medium>): IntentSender =
        MediaStore.createDeleteRequest(resolver, media.map(Medium::uri)).intentSender

    fun writeRequest(media: List<Medium>): IntentSender =
        MediaStore.createWriteRequest(resolver, media.map(Medium::uri)).intentSender

    suspend fun generations(items: List<Medium>): Map<Long, Long> = withContext(Dispatchers.IO) {
        val versions = mutableMapOf<Long, Long>()
        for (batch in items.chunked(500)) {
            val ids = batch.joinToString(",") { it.id.toString() }
            resolver.query(MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.GENERATION_MODIFIED),
                "_id IN ($ids)", null, null)?.use {
                while (it.moveToNext()) versions[it.getLong(0)] = it.getLong(1)
            }
        }
        if (versions.size != items.size) throw IOException("기기 원본을 확인할 수 없습니다.")
        versions
    }

    suspend fun deleteMovedSources(items: List<Medium>, generations: Map<Long, Long>): List<Medium> =
        withContext(Dispatchers.IO) {
            items.filter { item ->
                val version = generations.getValue(item.id)
                try {
                    resolver.delete(item.uri, "${MediaStore.MediaColumns.GENERATION_MODIFIED} = ?", arrayOf(version.toString())) == 0
                } catch (_: SecurityException) {
                    // 다른 앱의 사진 등 직접 삭제할 수 없는 항목만 시스템 승인 대상으로 남긴다.
                    true
                }
            }
        }

    internal suspend fun saveEdit(sourceUri: Uri, sourceName: String, file: File, mime: String): Int? =
        withContext(Dispatchers.IO) {
            val pictures = "${Environment.DIRECTORY_PICTURES}/"
            val folder = if (sourceUri.authority == MediaStore.AUTHORITY) {
                runCatching {
                    resolver.query(sourceUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME),
                        null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
                    }
                }.getOrNull()
            } else null
            val name = "${sourceName.substringBeforeLast('.', sourceName)}_edited_${System.currentTimeMillis()}.${file.extension}"
            val write = { output: OutputStream -> file.inputStream().use { it.copyTo(output) }; Unit }
            val directory = folder?.first ?: pictures
            val volume = folder?.second ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            if (insert(MediaType.Image, name, mime, directory, volume, write = write) != null) {
                if (directory == pictures) app.cloudipix.gallery.R.string.editor_saved_to_pictures
                else app.cloudipix.gallery.R.string.editor_saved
            } else if ((directory != pictures || volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) &&
                insert(MediaType.Image, name, mime, pictures, write = write) != null) {
                app.cloudipix.gallery.R.string.editor_saved_to_pictures
            } else null
        }

    suspend fun writeMetadata(medium: Medium, edit: MetadataEdit): Boolean = withContext(Dispatchers.IO) {
        val draft = File.createTempFile("metadata", null, context.cacheDir)
        runCatching {
            resolver.openInputStream(medium.uri)?.use { input -> draft.outputStream().use { input.copyTo(it) } }
                ?: error("Unable to read ${medium.uri}.")
            ExifInterface(draft).run {
                write(edit)
                saveAttributes()
            }
            resolver.openOutputStream(medium.uri, "wt")?.use { output -> draft.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to write ${medium.uri}.")
        }.also { draft.delete() }.isSuccess
    }

    suspend fun copy(source: Medium, folder: String, volume: String): Boolean = withContext(Dispatchers.IO) {
        val mime = resolver.getType(source.uri) ?: return@withContext false
        insert(source.type, source.name, mime, folder, volume) { output ->
            resolver.openInputStream(source.uri)?.use { it.copyTo(output) } ?: error("Unable to read ${source.uri}.")
        } != null
    }

    suspend fun move(medium: Medium, folder: String): Boolean =
        update(medium, MediaStore.MediaColumns.RELATIVE_PATH, folder)

    // 아래 경로 함수는 모든 파일 접근 권한이 있을 때 쓴다. 수정 허락 없이 Download 같은 위치에도 옮길 수 있다.

    /** 같은 저장소 안에서 옮긴다. 이름 변경이라 MediaStore ID와 수정 시각이 그대로 유지된다. */
    internal suspend fun moveByPath(medium: Medium, folder: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val source = sourceFile(medium) ?: return@runCatching false
            val directory = File(source.root, folder)
            check(directory.isDirectory || directory.mkdirs())
            Files.move(source.file.toPath(), vacantFile(directory, source.file.name).toPath())
            true
        }.getOrDefault(false)
    }

    /** 다른 저장소로 복사해 내용을 확인한 뒤 원본을 지운다. 수정 시각은 원본을 따른다. 결과는 새 상대 경로다. */
    internal suspend fun moveAcrossByPath(medium: Medium, folder: String, volume: String): String? = withContext(Dispatchers.IO) {
        val source = sourceFile(medium) ?: return@withContext null
        val original = stat(source.file) ?: return@withContext null
        val target = copyFileTo(source.file, folder, volume, original) ?: return@withContext null
        if (stat(source.file)?.matches(original) != true || !source.file.delete()) {
            // 원본을 지우지 못하면 복사본도 지워 같은 사진이 두 곳에 남지 않게 한다.
            target.delete()
            scan(target.path)
            return@withContext null
        }
        scan(source.file.path, target.path)
        folder + target.name
    }

    internal suspend fun copyByPath(medium: Medium, folder: String, volume: String): Boolean = withContext(Dispatchers.IO) {
        val source = sourceFile(medium) ?: return@withContext false
        val target = copyFileTo(source.file, folder, volume) ?: return@withContext false
        scan(target.path)
        true
    }

    internal suspend fun renameByPath(medium: Medium, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val source = sourceFile(medium) ?: return@runCatching false
            val target = File(source.file.parentFile, name)
            Files.move(source.file.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }

    private class SourceFile(val file: File, val root: String)

    @Suppress("DEPRECATION")
    private fun sourceFile(medium: Medium): SourceFile? {
        val columns = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.RELATIVE_PATH)
        val (path, relativePath) = resolver.query(medium.uri, columns, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        } ?: return null
        if (path == null || relativePath == null) return null
        val file = File(path)
        val suffix = relativePath + file.name
        return if (path.endsWith(suffix)) SourceFile(file, path.removeSuffix(suffix)) else null
    }

    private fun copyFileTo(source: File, folder: String, volume: String, original: StructStat? = null): File? {
        val root = context.storageRoots().firstOrNull { it.volumeName == volume } ?: return null
        val directory = File(root.directory, folder)
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = try { reserveFile(directory, source.name) } catch (_: IOException) { return null }
        return try {
            FileInputStream(source).use { input ->
                if (original == null) {
                    target.outputStream().use { input.copyTo(it) }
                } else {
                    if (!Os.fstat(input.fd).matches(original)) throw IOException("원본 파일이 변경되었습니다.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = target.outputStream().use { DigestInputStream(input, digest).copyTo(it) }
                    if (size != original.st_size || !target.inputStream().use { it.sha256() }.contentEquals(digest.digest())) {
                        throw IOException("대상 파일이 원본과 다릅니다.")
                    }
                    val modified = original.st_mtim.tv_sec * 1000 + original.st_mtim.tv_nsec / 1_000_000
                    if (!target.setLastModified(modified)) throw IOException("원본의 수정 시각을 보존할 수 없습니다.")
                }
            }
            target
        } catch (_: IOException) {
            target.delete()
            null
        } catch (_: ErrnoException) {
            target.delete()
            null
        }
    }

    private fun reserveFile(directory: File, name: String): File {
        while (true) {
            val target = vacantFile(directory, name)
            if (target.createNewFile()) return target
        }
    }

    private fun stat(file: File): StructStat? = try { Os.stat(file.path) } catch (_: ErrnoException) { null }

    private fun StructStat.matches(other: StructStat): Boolean =
        st_dev == other.st_dev && st_ino == other.st_ino && st_size == other.st_size &&
            st_mtim.tv_sec == other.st_mtim.tv_sec && st_mtim.tv_nsec == other.st_mtim.tv_nsec &&
            st_ctim.tv_sec == other.st_ctim.tv_sec && st_ctim.tv_nsec == other.st_ctim.tv_nsec

    private fun scan(vararg paths: String) {
        val scanned = CountDownLatch(paths.size)
        MediaScannerConnection.scanFile(context, arrayOf(*paths), null) { _, _ -> scanned.countDown() }
        scanned.await(10, TimeUnit.SECONDS)
    }

    internal suspend fun copyForMove(source: Medium, folder: String, volume: String): String? = withContext(Dispatchers.IO) {
        val original = MediaStore.setRequireOriginal(source.uri)
        val mime = resolver.getType(source.uri) ?: return@withContext null
        val collection = mediaCollection(source.type, volume)
        val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "relative_path = ? AND _display_name = ?", arrayOf(folder, source.name), null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
        if (existing != null) {
            val sourceHash = resolver.openInputStream(original)?.use { it.sha256() } ?: return@withContext null
            val targetHash = resolver.openInputStream(MediaStore.setRequireOriginal(existing))?.use { it.sha256() }
            return@withContext if (targetHash != null && sourceHash.contentEquals(targetHash)) folder + source.name else null
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val uri = insert(source.type, source.name, mime, folder, volume, modified = source.dateModified, verify = { target ->
            val targetHash = resolver.openInputStream(target)?.use { it.sha256() }
            if (targetHash == null || !targetHash.contentEquals(digest.digest())) {
                throw IOException("대상 파일이 원본과 다릅니다.")
            }
        }) { output ->
            val input = resolver.openInputStream(original) ?: throw IOException("원본 파일을 읽을 수 없습니다.")
            val size = DigestInputStream(input, digest).use { it.copyTo(output) }
            if (source.size > 0 && size != source.size) throw IOException("원본 파일 크기가 바뀌었습니다.")
        } ?: return@withContext null
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: source.name
        folder + name
    }

    internal fun importPhoto(
        source: Medium, file: File, mime: String, folder: String, volume: String, acceptIdentical: Boolean,
    ): Uri {
        if (file.length() == 0L || (source.size > 0 && file.length() != source.size)) throw IOException()
        if (acceptIdentical) {
            val collection = mediaCollection(source.type, volume)
            val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "relative_path = ? AND _display_name = ?", arrayOf(folder, source.name), null)?.use {
                if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null
            }
            if (existing != null) {
                val hash = resolver.openInputStream(existing)?.use { it.sha256() } ?: throw IOException()
                if (file.inputStream().use { it.sha256() }.contentEquals(hash)) return existing
                throw IOException("같은 이름의 다른 사진이 있습니다.")
            }
        }
        val verify: (Uri) -> Unit = { uri ->
            if (acceptIdentical) {
                val stored = resolver.openInputStream(uri)?.use { it.sha256() } ?: throw IOException()
                if (!file.inputStream().use { it.sha256() }.contentEquals(stored)) throw IOException("저장된 사진이 원본과 다릅니다.")
            }
        }
        return insert(source.type, source.name, mime, folder, volume, verify = verify) { output ->
            file.inputStream().use { if (it.copyTo(output) != file.length()) throw IOException() }
        } ?: throw IOException("기기에 사진을 저장할 수 없습니다.")
    }

    suspend fun rename(medium: Medium, name: String): Boolean =
        update(medium, MediaStore.MediaColumns.DISPLAY_NAME, name)

    private suspend fun update(medium: Medium, column: String, value: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.update(medium.uri, ContentValues().apply { put(column, value) }, null, null) > 0
        }.getOrDefault(false)
    }

    private fun insert(type: MediaType, name: String, mime: String, folder: String,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY, modified: Long? = null,
        verify: (Uri) -> Unit = {}, write: (OutputStream) -> Unit): Uri? {
        // 사진·동영상 컬렉션이 받지 않는 위치(Download, 앱 폴더 등)는 모든 파일 접근 권한으로 경로에 직접 쓴다.
        if (!folder.acceptsMedia(type)) {
            return if (hasAllFilesAccess()) insertByPath(name, folder, volume, modified, verify, write) else null
        }
        val collection = mediaCollection(type, volume)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use(write) ?: error("Unable to open $uri.")
            verify(uri)
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("저장한 사진을 공개할 수 없습니다.")
            }
        }.onFailure {
            resolver.delete(uri, null, null)
            return null
        }
        if (modified != null && modified > 0) restoreModified(uri, modified)
        return uri
    }

    private fun insertByPath(name: String, folder: String, volume: String, modified: Long?,
        verify: (Uri) -> Unit, write: (OutputStream) -> Unit): Uri? {
        val root = context.storageRoots().firstOrNull { it.volumeName == volume } ?: return null
        val directory = File(root.directory, folder)
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = try { reserveFile(directory, name) } catch (_: IOException) { return null }
        return try {
            target.outputStream().use(write)
            if (modified != null && modified > 0) target.setLastModified(modified)
            val uri = scanForUri(target.path) ?: throw IOException("저장한 사진을 등록할 수 없습니다.")
            verify(uri)
            uri
        } catch (_: Exception) {
            target.delete()
            scan(target.path)
            null
        }
    }

    private fun scanForUri(path: String): Uri? {
        var result: Uri? = null
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
            result = uri
            scanned.countDown()
        }
        scanned.await(10, TimeUnit.SECONDS)
        return result
    }

    // 새로 만든 파일은 이 앱 소유라 경로로 수정 시각을 되돌릴 수 있다. 되돌리지 못해도 복사 자체는 유지한다.
    @Suppress("DEPRECATION")
    private fun restoreModified(uri: Uri, millis: Long) {
        val path = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return
        if (!runCatching { File(path).setLastModified(millis) }.getOrDefault(false)) return
        scan(path)
    }
}

private fun mediaCollection(type: MediaType, volume: String): Uri =
    if (type == MediaType.Video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)

private fun InputStream.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) return digest.digest()
        digest.update(buffer, 0, count)
    }
}

