package app.cloudipix.gallery.core.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.cloudipix.gallery.core.model.MediaType
import app.cloudipix.gallery.core.model.Medium
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPaths @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun absolute(media: List<Medium>): List<String> = withContext(Dispatchers.IO) {
        val (videos, images) = media.partition { it.type == MediaType.Video }
        val imagePaths = pathsIn(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, images)
        val videoPaths = pathsIn(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videos)
        media.mapNotNull { (if (it.type == MediaType.Video) videoPaths else imagePaths)[it.id] }
    }

    @Suppress("DEPRECATION")
    suspend fun absolute(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    internal suspend fun volumes(mediaIds: Collection<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME)
        val volumes = mutableMapOf<Long, String>()
        try {
            for (batch in mediaIds.chunked(500)) {
                val ids = batch.joinToString(",")
                context.contentResolver.query(collection, columns, "_id IN ($ids)", null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val volume = cursor.getString(1)
                        if (!volume.isNullOrEmpty() && volume != MediaStore.VOLUME_EXTERNAL) {
                            volumes[cursor.getLong(0)] = volume
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyMap()
        }
        volumes
    }

    suspend fun folders(bucketIds: Set<Long>?): List<String> = withContext(Dispatchers.IO) {
        val found = foldersByBucket(bucketIds).values.flatten().toSortedSet()
        found.filter { folder -> found.none { it != folder && folder.startsWith("$it/") } }
    }

    internal suspend fun foldersByBucket(bucketIds: Set<Long>?): Map<Long, Set<String>> = withContext(Dispatchers.IO) {
        val folders = mutableMapOf<Long, MutableSet<String>>()
        if (bucketIds?.isEmpty() != true) {
            collectFolders(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketIds, folders)
            collectFolders(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketIds, folders)
        }
        folders
    }

    @Suppress("DEPRECATION")
    private fun collectFolders(collection: Uri, bucketIds: Set<Long>?, folders: MutableMap<Long, MutableSet<String>>) {
        val selection = bucketIds?.let { "${MediaStore.MediaColumns.BUCKET_ID} IN (${it.joinToString(",")})" }
        val columns = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.DATA)
        context.contentResolver.query(collection, columns, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1)?.substringBeforeLast('/', "").orEmpty()
                if (path.isNotEmpty()) folders.getOrPut(c.getLong(0)) { mutableSetOf() }.add(path)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pathsIn(collection: Uri, media: List<Medium>): Map<Long, String> {
        if (media.isEmpty()) return emptyMap()
        val ids = media.joinToString(",") { it.id.toString() }
        val paths = mutableMapOf<Long, String>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns._ID} IN ($ids)",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                c.getString(1)?.let { paths[c.getLong(0)] = it }
            }
        }
        return paths
    }
}
