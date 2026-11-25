package app.cloudipix.gallery.core.media

import android.content.Context
import android.provider.MediaStore
import app.cloudipix.gallery.core.model.Folder
import app.cloudipix.gallery.core.model.FolderChange
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.RelocatedFolder
import app.cloudipix.gallery.core.model.mediaUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 모든 파일 접근 권한으로 기기 폴더의 이름을 바꾸거나 위치를 옮긴다.
 * 폴더 안의 사진이 아닌 파일까지 함께 옮겨지고, 같은 저장소 안에서만 옮길 수 있다.
 */
class LocalFolderFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: MediaStoreScanner,
) {
    private val resolver = context.contentResolver

    /** 폴더의 절대 경로. 대표 사진의 위치에서 구한다. */
    @Suppress("DEPRECATION")
    suspend fun folderPath(folder: Folder): String = withContext(Dispatchers.IO) {
        val uri = mediaUri(folder.coverId, MediaType.entries[folder.coverType])
        val columns = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        val path = resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val data = cursor.getString(0) ?: return@use null
            val relativePath = cursor.getString(1)?.takeIf(String::isNotEmpty) ?: return@use null
            // 저장소 루트 + 상대 경로로 다시 조합해 파일 이름이 경로 일부와 겹쳐도 폴더를 정확히 얻는다.
            val suffix = relativePath + cursor.getString(2)
            if (data.endsWith(suffix)) data.removeSuffix(suffix) + relativePath.trimEnd('/') else null
        }
        checkNotNull(path)
    }

    internal fun rename(bucketId: Long, path: String, name: String, locked: Set<Long>): FolderChange =
        changeFolder(bucketId, path, locked) { folder ->
            val target = File(folder.parentFile, name)
            val (root, relative) = locate(target.path)
            check(!target.exists() && scanner.scanFolder(root.volumeName, "$relative/").isEmpty())
            check(folder.renameTo(target))
            target
        }

    internal fun move(bucketId: Long, path: String, destination: String, locked: Set<Long>): FolderChange =
        changeFolder(bucketId, path, locked) { folder ->
            val parent = File(destination.trimEnd('/'))
            val target = File(parent, folder.name)
            check(parent.isDirectory && rootOf(parent.path) == locate(folder.path).first)
            check(parent.path != folder.parent && !parent.path.isWithin(folder.path) && !target.exists())
            check(folder.renameTo(target))
            target
        }

    private fun changeFolder(bucketId: Long, path: String, locked: Set<Long>, change: (File) -> File): FolderChange {
        val folder = File(path.trimEnd('/'))
        val (root, relative) = locate(folder.path)
        val prefix = "$relative/"
        val before = scanner.scanFolder(root.volumeName, prefix)
        check(before.any { it.relativePath == prefix && it.bucketId == bucketId } && before.none { it.bucketId in locked })
        val moved = change(folder)
        val newPrefix = locate(moved.path).second + "/"
        val after = scanner.scanFolder(root.volumeName, newPrefix)
        val byPath = after.associateBy { it.relativePath + it.name }
        val folders = mutableMapOf<Long, RelocatedFolder>()
        val photos = mutableMapOf<Long, Long>()
        val favorites = mutableMapOf<String, String>()
        for (item in before) {
            val newFile = newPrefix + item.relativePath.removePrefix(prefix) + item.name
            favorites[item.relativePath + item.name] = newFile
            val replacement = byPath[newFile] ?: continue
            folders[item.bucketId] = RelocatedFolder(item.bucketId, replacement.bucketId, replacement.bucketName)
            photos[item.id] = replacement.id
        }
        return FolderChange(moved.name, folders.values.toList(), photos, favorites)
    }

    private fun rootOf(path: String): StorageRoot = checkNotNull(context.storageRoots().firstOrNull { path.isWithin(it.path) })

    /** 폴더가 속한 저장소와 저장소 기준 상대 경로. 저장소 최상위 자체는 폴더로 다루지 않는다. */
    private fun locate(path: String): Pair<StorageRoot, String> {
        val root = rootOf(path)
        val relative = path.trimEnd('/').removePrefix(root.path).trim('/')
        check(relative.isNotEmpty())
        return root to relative
    }
}

private fun String.isWithin(parent: String): Boolean {
    val base = parent.trimEnd('/')
    val self = trimEnd('/')
    return self == base || self.startsWith("$base/")
}
