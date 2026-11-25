package app.cloudipix.gallery.core.sync

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.cloudipix.gallery.core.media.EXTERNAL_STORAGE_DOCUMENTS
import app.cloudipix.gallery.core.media.documentPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

internal data class LocalDocument(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val mimeType: String? = null,
)

internal data class LocalDraft(val id: String, val stamp: SyncStamp?)

/**
 * 동기화가 기기 폴더를 읽고 쓰는 방법. 문서 ID는 두 구현 모두 `primary:경로` 형식이다.
 * 모든 파일 접근 권한이 있으면 [LocalFileTree], 없으면 폴더 선택기 권한으로 [LocalSyncTree]를 쓴다.
 */
internal interface LocalSyncStorage : LocalCopyStorage {
    fun document(id: String): LocalDocument
    fun childDocument(parent: String, name: String): LocalDocument?
    fun ensureDirectory(parent: String, name: String): String
    fun copyAndHash(file: LocalDocument, output: OutputStream, checkActive: () -> Unit): String
    fun rename(file: LocalDocument, name: String): String
    fun renameChild(parent: String, id: String, from: String, name: String): String
    fun requireWritableVolume()
    suspend fun stage(parent: String, name: String, write: suspend (OutputStream) -> Unit): LocalDraft
    fun storagePath(id: String): String?

    override suspend fun fingerprint(file: LocalDocument): String {
        val caller = currentCoroutineContext()
        return copyAndHash(file, DiscardOutput) { caller.ensureActive() }
    }
}

internal object DiscardOutput : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

internal class LocalSyncTree(private val context: Context, private val tree: Uri) : LocalSyncStorage {
    private val resolver = context.contentResolver
    private val storage = context.getSystemService(StorageManager::class.java)
    override val treeId: String = DocumentsContract.getTreeDocumentId(tree)
    private val pathIds = tree.authority == EXTERNAL_STORAGE

    override fun document(id: String): LocalDocument = readDocument(id) ?: throw IOException("로컬 항목을 찾을 수 없습니다.")

    override fun childDocument(parent: String, name: String): LocalDocument? = try {
        // 외부 저장소 문서 ID로 목적지만 확인해 파일마다 전체 폴더를 다시 열거하지 않는다.
        readDocument(syncChildId(parent, name))
    } catch (_: FileNotFoundException) {
        null
    } catch (error: SecurityException) {
        missingChild(parent, name, error)
    } catch (error: IllegalArgumentException) {
        missingChild(parent, name, error)
    }

    private fun missingChild(parent: String, name: String, cause: RuntimeException): LocalDocument? {
        val path = storagePath(syncChildId(parent, name)) ?: throw IOException(cause)
        requireWritableVolume()
        try { Os.stat(path) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return null
            throw IOException(error)
        }
        throw IOException(cause)
    }

    private fun readDocument(id: String): LocalDocument? {
        return resolver.query(uri(id), columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) || cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR))
                throw IOException("로컬 파일 정보를 읽을 수 없습니다.")
            if (!cursor.moveToFirst()) return null
            val mimeType = cursor.getString(2)
            LocalDocument(cursor.getString(0), cursor.getString(1), mimeType == Document.MIME_TYPE_DIR,
                cursor.getLong(3), cursor.getLong(4), mimeType)
        } ?: throw IOException("로컬 파일 정보를 읽을 수 없습니다.")
    }

    override fun list(id: String): List<LocalDocument> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(children, columns, null, null, null)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)
            ) throw IOException("로컬 폴더 목록을 완전히 읽을 수 없습니다.")
            buildList {
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(2)
                    add(LocalDocument(cursor.getString(0), cursor.getString(1), mimeType == Document.MIME_TYPE_DIR,
                        cursor.getLong(3), cursor.getLong(4), mimeType))
                }
            }
        } ?: throw IOException("로컬 폴더를 읽을 수 없습니다.")
    }

    override fun ensureDirectory(parent: String, name: String): String {
        val existing = childDocument(parent, name)
        if (existing == null) return create(parent, Document.MIME_TYPE_DIR, name)
        if (existing.directory && existing.name == name) return existing.id
        throw IOException("로컬 대상에 같은 이름이 있습니다.")
    }

    override fun copyAndHash(file: LocalDocument, output: OutputStream, checkActive: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val stream = resolver.openInputStream(uri(file.id)) ?: throw IOException("로컬 파일을 읽을 수 없습니다.")
        val size = stream.use { input ->
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

    override fun rename(file: LocalDocument, name: String): String = renameDocument(file, name).id

    fun renameDocument(file: LocalDocument, name: String): LocalDocument {
        val renamed = DocumentsContract.renameDocument(resolver, uri(file.id), name)
            ?: throw IOException("로컬 파일 이름을 변경할 수 없습니다.")
        val document = document(DocumentsContract.getDocumentId(renamed))
        if (document.name != name) {
            DocumentsContract.renameDocument(resolver, renamed, file.name)
            throw IOException("로컬 대상에 같은 이름이 있습니다.")
        }
        return document
    }

    override fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, uri(id))) throw IOException("로컬 파일을 지울 수 없습니다.")
    }

    override fun removeEmptyDirectory(id: String) {
        // 끝의 /는 디렉터리만 허용한다. SAF의 재귀 삭제를 피하고 빈 폴더만 제거한다.
        storagePath(id)?.let { path ->
            try {
                Os.remove("$path/")
            } catch (_: ErrnoException) {
                // 직접 접근이 제한된 저장소에서는 빈 폴더를 남긴다.
            }
        }
    }

    override fun requireWritableVolume() {
        val root = storagePath(treeId)?.let(::File)
        if (root == null || storage?.getStorageVolume(root)?.state != Environment.MEDIA_MOUNTED) {
            throw IOException("로컬 동기화 저장소가 연결되어 있지 않습니다.")
        }
    }

    override suspend fun stage(parent: String, name: String, write: suspend (OutputStream) -> Unit): LocalDraft {
        val id = create(parent, "application/octet-stream", name)
        try {
            val fd = resolver.openFileDescriptor(uri(id), "wt") ?: throw IOException("로컬 파일을 저장할 수 없습니다.")
            return ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                write(output)
                // 이미 쓰고 있는 파일에서 크기와 수정 시각을 얻는다. 이름 변경은 같은 폴더 안에서 수행한다.
                val stat = try { Os.fstat(fd.fileDescriptor) } catch (_: ErrnoException) { null }
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

    override fun storagePath(id: String): String? =
        if (tree.authority != EXTERNAL_STORAGE) null else context.documentPath(id)

    // 외부 저장소의 문서 ID는 경로이므로 ID만으로 요청한 이름인지 알 수 있다. 다른 공급자만 다시 조회한다.
    private fun hasName(parent: String, id: String, name: String) =
        if (pathIds) id == syncChildId(parent, name) else document(id).name == name

    // 같은 폴더 안에서 이름만 바꾸고 새 문서 ID를 돌려준다.
    override fun renameChild(parent: String, id: String, from: String, name: String): String {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, uri(id), name)
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw IOException("로컬 파일 이름을 변경할 수 없습니다.")
        val renamedId = DocumentsContract.getDocumentId(renamed)
        if (!hasName(parent, renamedId, name)) {
            runCatching { DocumentsContract.renameDocument(resolver, renamed, from) }
            throw IOException("로컬 대상에 같은 이름이 있습니다.")
        }
        return renamedId
    }

    private fun create(parent: String, mime: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, uri(parent), mime, name)
            ?: throw IOException("로컬 대상에 파일이나 폴더를 만들 수 없습니다.")
        val id = DocumentsContract.getDocumentId(created)
        if (!hasName(parent, id, name)) {
            delete(id)
            throw IOException("로컬 대상에서 요청한 이름을 사용할 수 없습니다.")
        }
        return id
    }

    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private companion object {
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}

internal const val EXTERNAL_STORAGE = EXTERNAL_STORAGE_DOCUMENTS
internal fun syncChildId(parent: String, name: String) = if (parent.endsWith(':')) parent + name else "${parent.trimEnd('/')}/$name"
internal fun syncKey(value: String) = value.lowercase(Locale.ROOT)
internal fun ByteArray.hex(): String = toByteString().hex()
internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).hex()

private fun invalidNameCharacter(char: Char): Boolean = char.isISOControl() || char in "/\\\"*:<>?|"

internal fun localSyncName(name: String): String =
    name.map { if (invalidNameCharacter(it)) '_' else it }.joinToString("").trim().trim('.').ifBlank { "_" }

internal fun validSyncName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.none(::invalidNameCharacter)
