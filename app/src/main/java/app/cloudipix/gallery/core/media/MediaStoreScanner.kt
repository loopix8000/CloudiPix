package app.cloudipix.gallery.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import app.cloudipix.gallery.core.database.MediumEntity
import app.cloudipix.gallery.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
private val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

private val BASE_COLUMNS = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.BUCKET_ID,
    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
    MediaStore.MediaColumns.RELATIVE_PATH,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_MODIFIED,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.MIME_TYPE,
)

private val VIDEO_COLUMNS = BASE_COLUMNS + MediaStore.Video.VideoColumns.DURATION

@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scan(trashed: Boolean = false, ids: Set<Long>? = null, volume: String = MediaStore.VOLUME_EXTERNAL,
        relativePath: String? = null): List<MediumEntity> = withContext(Dispatchers.IO) {
        readAccessible(MediaStore.Images.Media.getContentUri(volume), BASE_COLUMNS, false, trashed, ids, relativePath) +
            readAccessible(MediaStore.Video.Media.getContentUri(volume), VIDEO_COLUMNS, true, trashed, ids, relativePath)
    }

    internal fun scanFolder(volume: String, relativePath: String): List<MediumEntity> =
        (read(MediaStore.Images.Media.getContentUri(volume), BASE_COLUMNS, false, false, relativePath) +
            read(MediaStore.Video.Media.getContentUri(volume), VIDEO_COLUMNS, true, false, relativePath))
            .filter { it.relativePath.startsWith(relativePath) }

    internal fun externalVolumes(): Set<String> = MediaStore.getExternalVolumeNames(context)

    private fun readAccessible(collection: Uri, columns: Array<String>, video: Boolean, trashed: Boolean,
        ids: Set<Long>?, relativePath: String?): List<MediumEntity> =
        try {
            read(collection, columns, video, trashed, relativePath, ids)
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun read(
        collection: Uri, columns: Array<String>, video: Boolean, trashed: Boolean, relativePath: String? = null,
        ids: Set<Long>? = null,
    ): List<MediumEntity> {
        val out = ArrayList<MediumEntity>(512)
        val args = Bundle().apply {
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            if (ids != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id IN (${ids.joinToString(",")})")
            if (relativePath != null) {
                val escaped = relativePath.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path LIKE ? ESCAPE '\\'")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$escaped%"))
            }
        }
        context.contentResolver.query(collection, columns, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val bucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val duration = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

            while (c.moveToNext()) {
                val modifiedMs = c.getLong(modified) * 1000
                out += MediumEntity(
                    id = c.getLong(id),
                    name = c.getString(name).orEmpty(),
                    bucketId = c.getLong(bucketId),
                    bucketName = c.getString(bucketName) ?: "Unknown",
                    relativePath = c.getString(path).orEmpty(),
                    dateTaken = c.getLong(taken).takeIf { it > 0 } ?: modifiedMs,
                    dateModified = modifiedMs,
                    size = c.getLong(size),
                    type = typeOf(video, c.getString(mime)).ordinal,
                    durationMs = if (duration >= 0) c.getLong(duration) else 0L,
                )
            }
        }
        return out
    }

    suspend fun rescan(folders: List<String>) {
        if (folders.isEmpty()) return
        suspendCancellableCoroutine { done ->
            val remaining = AtomicInteger(folders.size)
            MediaScannerConnection.scanFile(context, folders.toTypedArray(), null) { _, _ ->
                if (remaining.decrementAndGet() == 0) done.resume(Unit)
            }
        }
    }

    private fun mediaUriOf(uri: Uri): Uri? {
        val media = if (DocumentsContract.isDocumentUri(context, uri)) {
            runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        } else {
            uri
        }
        return media?.takeIf { it.authority == MediaStore.AUTHORITY }
    }

    fun mediaIdOf(uri: Uri): Long? {
        val media = mediaUriOf(uri) ?: return null
        return runCatching { ContentUris.parseId(media) }.getOrNull()?.takeIf { it >= 0 }
    }

    suspend fun mediaBucketOf(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val media = mediaUriOf(uri) ?: return@withContext null
        runCatching {
            context.contentResolver.query(media, arrayOf(MediaStore.MediaColumns.BUCKET_ID), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        }.getOrNull()
    }

    val changes: Flow<Set<Long>?> = callbackFlow {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val lock = Any()
        var pending: MutableSet<Long>? = mutableSetOf()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = collectIds(listOfNotNull(uri))
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) = collectIds(uris)

            private fun collectIds(uris: Collection<Uri>) {
                val ids = changedMediaIds(uris)
                synchronized(lock) {
                    if (ids == null) pending = null else pending?.addAll(ids)
                    signal.trySend(Unit)
                }
            }
        }
        context.contentResolver.registerContentObserver(IMAGES, true, observer)
        context.contentResolver.registerContentObserver(VIDEOS, true, observer)
        launch {
            for (event in signal) {
                delay(750)
                val batch = synchronized(lock) {
                    signal.tryReceive()
                    pending.also { pending = mutableSetOf() }
                }
                send(batch)
            }
        }
        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
            signal.close()
        }
    }.buffer(0)
}

// 항목 주소 없이 컬렉션 전체가 바뀌었다고 알리면 기존 갤러리 색인을 한 번 갱신한다.
internal fun changedMediaIds(uris: Collection<Uri>): Set<Long>? {
    if (uris.isEmpty()) return null
    return uris.mapTo(mutableSetOf()) { uri -> uri.lastPathSegment?.toLongOrNull() ?: return null }
}

private fun typeOf(video: Boolean, mime: String?): MediaType = when {
    video -> MediaType.Video
    mime == "image/gif" -> MediaType.Gif
    else -> MediaType.Image
}
