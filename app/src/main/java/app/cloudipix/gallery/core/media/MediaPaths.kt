package app.cloudipix.gallery.core.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 한 번의 `IN (...)` 조건에 넣는 최대 ID 수입니다. 너무 긴 SQL을 만들지 않도록 나눠 조회합니다. */
private const val IN_CLAUSE_BATCH = 500

/**
 * MediaStore 항목의 실제 파일 경로를 찾습니다. 경로는 미디어 스캐너 갱신과 셸 경로 복사처럼
 * 파일 시스템 경로가 꼭 필요한 곳에서만 씁니다.
 */
@Singleton
@Suppress("DEPRECATION") // 경로가 필요한 곳에서는 MediaColumns.DATA 말고 다른 방법이 없습니다.
class MediaPaths @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 각 항목의 절대 경로입니다. 경로를 찾지 못한 항목은 빼고 입력 순서를 유지합니다. */
    suspend fun absolute(media: List<Medium>): List<String> = withContext(Dispatchers.IO) {
        val (videos, images) = media.partition { it.type == MediaType.Video }
        val videoPaths = dataPaths(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videos.map { it.id })
        val imagePaths = dataPaths(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, images.map { it.id })
        media.mapNotNull { medium -> (if (medium.type == MediaType.Video) videoPaths else imagePaths)[medium.id] }
    }

    /** 항목 하나의 절대 경로입니다. 권한이 없거나 경로가 비어 있으면 `null`입니다. */
    suspend fun absolute(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(0)?.takeIf { it.isNotBlank() }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** 기본 외부 저장소가 아닌 볼륨(SD 카드 등)에 있는 항목만 볼륨 이름과 함께 돌려줍니다. */
    internal suspend fun volumes(mediaIds: Collection<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME)
        val found = HashMap<Long, String>()
        try {
            for (batch in mediaIds.chunked(IN_CLAUSE_BATCH)) {
                context.contentResolver.query(collection, columns, idIn(MediaStore.MediaColumns._ID, batch), null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val volume = cursor.getString(1)
                        if (!volume.isNullOrEmpty() && volume != MediaStore.VOLUME_EXTERNAL) found[cursor.getLong(0)] = volume
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyMap()
        }
        found
    }

    /**
     * 폴더들에 든 파일의 상위 디렉터리 목록입니다. 다른 항목의 하위 디렉터리는 빼서
     * 미디어 스캐너가 같은 곳을 두 번 훑지 않게 합니다. 결과는 사전순입니다.
     * [bucketIds]가 `null`이면 모든 폴더입니다.
     */
    suspend fun folders(bucketIds: Set<Long>?): List<String> = withContext(Dispatchers.IO) {
        val all = foldersByBucket(bucketIds).values.flatMapTo(HashSet()) { it }
        all.filter { !it.hasAncestorIn(all) }.sorted()
    }

    /** 폴더별로 파일이 들어 있는 디렉터리 경로입니다. [bucketIds]가 `null`이면 모든 폴더입니다. */
    internal suspend fun foldersByBucket(bucketIds: Set<Long>?): Map<Long, Set<String>> = withContext(Dispatchers.IO) {
        val byBucket = LinkedHashMap<Long, MutableSet<String>>()
        if (bucketIds == null) {
            collectDirectories(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, byBucket)
            collectDirectories(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, byBucket)
        } else {
            for (batch in bucketIds.chunked(IN_CLAUSE_BATCH)) {
                val selection = idIn(MediaStore.MediaColumns.BUCKET_ID, batch)
                collectDirectories(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, byBucket)
                collectDirectories(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, byBucket)
            }
        }
        byBucket
    }

    private fun collectDirectories(collection: Uri, selection: String?, into: MutableMap<Long, MutableSet<String>>) {
        val columns = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.DATA)
        context.contentResolver.query(collection, columns, selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val directory = cursor.getString(1)?.substringBeforeLast('/', "")
                if (!directory.isNullOrEmpty()) into.getOrPut(cursor.getLong(0)) { LinkedHashSet() } += directory
            }
        }
    }

    private fun dataPaths(collection: Uri, ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        val paths = HashMap<Long, String>(ids.size)
        for (batch in ids.chunked(IN_CLAUSE_BATCH)) {
            context.contentResolver.query(collection, columns, idIn(MediaStore.MediaColumns._ID, batch), null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(1) ?: continue
                    paths[cursor.getLong(0)] = path
                }
            }
        }
        return paths
    }
}

private fun idIn(column: String, ids: Collection<Long>): String = ids.joinToString(",", prefix = "$column IN (", postfix = ")")

/** 집합 안에 이 경로의 상위 디렉터리가 있는지 경로를 한 단계씩 올라가며 확인합니다. */
private fun String.hasAncestorIn(directories: Set<String>): Boolean {
    var end = lastIndexOf('/')
    while (end > 0) {
        if (substring(0, end) in directories) return true
        end = lastIndexOf('/', end - 1)
    }
    return false
}
