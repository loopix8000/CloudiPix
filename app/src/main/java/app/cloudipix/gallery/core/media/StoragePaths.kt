package app.cloudipix.gallery.core.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.cloudipix.gallery.core.model.MediaType
import java.io.File
import java.util.Locale

internal const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"

/** 모든 파일 접근 권한이 있으면 기기 폴더 작업(사진·폴더 이동, 동기화)을 파일 경로로 처리한다. */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/** 연결된 저장소 하나. [id]는 외부 저장소 문서 ID의 앞부분(`primary` 또는 UUID), [path]는 끝 `/`가 없는 절대 경로다. */
internal data class StorageRoot(val id: String, val path: String) {
    val isPrimary: Boolean get() = id == "primary"
    val volumeName: String get() = if (isPrimary) MediaStore.VOLUME_EXTERNAL_PRIMARY else id.lowercase(Locale.ROOT)
    val directory: File get() = File(path)
}

internal fun Context.storageRoots(): List<StorageRoot> =
    getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { volume ->
        val directory = volume.directory ?: return@mapNotNull null
        val id = if (volume.isPrimary) "primary" else volume.uuid ?: return@mapNotNull null
        StorageRoot(id, directory.absolutePath.trimEnd('/'))
    }

/** `primary:Download/X` 같은 문서 ID를 절대 경로로 바꾼다. 연결되지 않은 저장소면 null. */
internal fun documentPath(roots: List<StorageRoot>, id: String): String? {
    if (':' !in id) return null
    val volume = id.substringBefore(':')
    val relative = id.substringAfter(':').trim('/')
    if (relative.split('/').any { it == "." || it == ".." }) return null
    val root = roots.firstOrNull { if (volume == "primary") it.isPrimary else volume.equals(it.id, ignoreCase = true) }
        ?: return null
    return if (relative.isEmpty()) root.path else "${root.path}/$relative"
}

/** 절대 경로를 외부 저장소 문서 ID로 바꾼다. 연결된 저장소 밖의 경로면 null. */
internal fun pathDocumentId(roots: List<StorageRoot>, path: String): String? {
    val clean = path.trimEnd('/')
    if (clean.split('/').any { it == "." || it == ".." }) return null
    for (root in roots) {
        val relative = when {
            clean == root.path -> ""
            clean.startsWith(root.path + "/") -> clean.removePrefix(root.path + "/")
            else -> continue
        }
        return "${root.id}:$relative"
    }
    return null
}

internal fun Context.documentPath(id: String): String? = documentPath(storageRoots(), id)

internal fun Context.pathDocumentId(path: String): String? = pathDocumentId(storageRoots(), path)

/** 동기화 설정에 저장하는 위치 식별자. 폴더 선택기 권한이 없어도 같은 형식을 쓴다. */
internal fun Context.treeUriForPath(path: String): Uri? =
    pathDocumentId(path)?.let { DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_DOCUMENTS, it) }

/** 같은 이름이 있으면 `이름 (1).확장자`처럼 비어 있는 이름을 고른다. */
internal fun vacantFile(directory: File, name: String): File {
    val stem = name.substringBeforeLast('.', name)
    val extension = name.removePrefix(stem)
    return generateSequence(0) { it + 1 }
        .map { File(directory, if (it == 0) name else "$stem ($it)$extension") }
        .first { !it.exists() }
}

/**
 * MediaStore의 사진·동영상 컬렉션이 [type] 파일을 둘 수 있는 상대 경로(`Pictures/X/`)인지 확인한다.
 * 나머지 위치(Download, 앱 폴더 등)는 모든 파일 접근 권한으로 경로에 직접 써야 한다.
 */
internal fun String.acceptsMedia(type: MediaType): Boolean =
    trim('/').substringBefore('/') in if (type == MediaType.Video) VIDEO_FOLDERS else IMAGE_FOLDERS

// Environment.DIRECTORY_* 값과 같다. 상수가 아닌 필드라 순수 함수에서는 값을 직접 쓴다.
private val IMAGE_FOLDERS = setOf("DCIM", "Pictures")
private val VIDEO_FOLDERS = setOf("DCIM", "Movies", "Pictures")
