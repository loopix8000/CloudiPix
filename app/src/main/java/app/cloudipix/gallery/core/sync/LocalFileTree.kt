package app.cloudipix.gallery.core.sync

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import app.cloudipix.gallery.core.media.documentPath
import app.cloudipix.gallery.core.media.storageRoots
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 모든 파일 접근 권한으로 기기 폴더를 직접 읽고 쓴다. 폴더 선택기가 고를 수 없는 Download 루트도 다룰 수 있다.
 * 문서 ID와 동작은 [LocalSyncTree]와 같게 맞춘다. 같은 이름이 있으면 덮어쓰지 않고, 폴더 삭제는 하위까지 지운다.
 */
internal class LocalFileTree(private val context: Context, tree: Uri) : LocalSyncStorage {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val roots = context.storageRoots()
    override val treeId: String = DocumentsContract.getTreeDocumentId(tree)
    private val childPrefix = if (treeId.endsWith(':')) treeId else "$treeId/"

    override fun storagePath(id: String): String? =
        if (id == treeId || id.startsWith(childPrefix)) documentPath(roots, id) else null

    private fun file(id: String): File = File(storagePath(id) ?: throw IOException("로컬 동기화 저장소가 연결되어 있지 않습니다."))

    override fun document(id: String): LocalDocument = read(id, file(id)) ?: throw IOException("로컬 항목을 찾을 수 없습니다.")

    override fun childDocument(parent: String, name: String): LocalDocument? {
        if (!validSyncName(name)) throw IOException("로컬 대상에서 요청한 이름을 사용할 수 없습니다.")
        val id = syncChildId(parent, name)
        return read(id, file(id))
    }

    private fun read(id: String, file: File): LocalDocument? {
        val stat = try { Os.lstat(file.path) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return null
            throw IOException(error)
        }
        val directory = OsConstants.S_ISDIR(stat.st_mode)
        if (!directory && !OsConstants.S_ISREG(stat.st_mode)) throw IOException("지원하지 않는 로컬 파일 형식입니다.")
        val modified = stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000
        return LocalDocument(id, file.name, directory, stat.st_size, modified, if (directory) Document.MIME_TYPE_DIR else mimeOf(file.name))
    }

    override fun list(id: String): List<LocalDocument> {
        val children = file(id).listFiles() ?: throw IOException("로컬 폴더를 읽을 수 없습니다.")
        return children.mapNotNull { child ->
            read(syncChildId(id, child.name), child)
        }
    }

    override fun ensureDirectory(parent: String, name: String): String {
        val existing = childDocument(parent, name)
        if (existing == null) return create(parent, name, directory = true)
        if (existing.directory && existing.name == name) return existing.id
        throw IOException("로컬 대상에 같은 이름이 있습니다.")
    }

    override fun copyAndHash(file: LocalDocument, output: OutputStream, checkActive: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val size = FileInputStream(file(file.id)).use { input ->
            val hashed = DigestOutputStream(output, hash)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                hashed.write(buffer, 0, count)
                total += count
            }
            total
        }
        if (size != file.size || document(file.id) != file) throw IOException("로컬 파일이 변경되었습니다.")
        return hash.digest().hex()
    }

    override fun rename(file: LocalDocument, name: String): String =
        renameChild(file.id.substringBeforeLast('/', file.id.substringBefore(':') + ":"), file.id, file.name, name)

    override fun renameChild(parent: String, id: String, from: String, name: String): String {
        if (!validSyncName(name)) throw IOException("로컬 대상에서 요청한 이름을 사용할 수 없습니다.")
        val source = file(id)
        val targetId = syncChildId(parent, name)
        val target = file(targetId)
        Files.move(source.toPath(), target.toPath())
        return targetId
    }

    override fun delete(id: String) {
        val target = file(id)
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) throw IOException("로컬 파일을 지울 수 없습니다.")
    }

    override fun removeEmptyDirectory(id: String) {
        // 끝의 /는 디렉터리만 허용한다. 빈 폴더만 제거하고 내용이 있으면 남긴다.
        try {
            Os.remove(file(id).path + "/")
        } catch (_: ErrnoException) {
        } catch (_: IOException) {
        }
    }

    override fun requireWritableVolume() {
        val root = storagePath(treeId)?.let(::File)
        if (root == null || storage?.getStorageVolume(root)?.state != Environment.MEDIA_MOUNTED) {
            throw IOException("로컬 동기화 저장소가 연결되어 있지 않습니다.")
        }
    }

    override suspend fun stage(parent: String, name: String, write: suspend (OutputStream) -> Unit): LocalDraft {
        val id = create(parent, name, directory = false)
        try {
            return FileOutputStream(file(id)).use { output ->
                write(output)
                output.flush()
                val stat = try { Os.fstat(output.fd) } catch (_: ErrnoException) { null }
                val stamp = stat?.takeIf { OsConstants.S_ISREG(it.st_mode) }?.let {
                    SyncStamp(it.st_size, it.st_mtim.tv_sec * 1000 + it.st_mtim.tv_nsec / 1_000_000)
                }
                LocalDraft(id, stamp)
            }
        } catch (error: Exception) {
            runCatching { delete(id) }
            throw error
        }
    }

    private fun create(parent: String, name: String, directory: Boolean): String {
        // 경로 구분자나 `..`이 든 이름으로 동기화 폴더 밖에 쓰지 않게 막는다.
        if (!validSyncName(name)) throw IOException("로컬 대상에서 요청한 이름을 사용할 수 없습니다.")
        val id = syncChildId(parent, name)
        val target = file(id)
        if (target.exists()) throw IOException("로컬 대상에 같은 이름이 있습니다.")
        val created = if (directory) target.mkdir() else target.createNewFile()
        if (!created) throw IOException("로컬 대상에 파일이나 폴더를 만들 수 없습니다.")
        return id
    }
}

private fun mimeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}
