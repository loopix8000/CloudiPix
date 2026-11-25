package app.cloudipix.gallery.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.cloudipix.gallery.core.database.MediumEntity
import app.cloudipix.gallery.core.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
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

/** 변경 알림이 몰려올 때 한 번에 모아 보내기까지 기다리는 시간입니다. */
private const val CHANGE_SETTLE_MS = 750L

/** 이름이 없는 폴더를 색인에 넣을 때 쓰는 이름입니다. */
private const val UNNAMED_BUCKET = "Unknown"

private val COMMON_COLUMNS = arrayOf(
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

/** MediaStore에서 사진과 동영상 색인을 읽고, 저장소 변경을 알려 줍니다. */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 사진·동영상 컬렉션 중 하나입니다. 동영상 컬렉션은 재생 시간 열을 더 읽습니다. */
    private enum class Kind(val columns: Array<String>) {
        Images(COMMON_COLUMNS),
        Videos(COMMON_COLUMNS + MediaStore.Video.VideoColumns.DURATION),
        ;

        fun collection(volume: String): Uri = when (this) {
            Images -> MediaStore.Images.Media.getContentUri(volume)
            Videos -> MediaStore.Video.Media.getContentUri(volume)
        }
    }

    /** 조회 조건입니다. 지정한 조건은 모두 만족해야 합니다. */
    private class Filter(
        val trashedOnly: Boolean = false,
        val ids: Set<Long>? = null,
        val pathPrefix: String? = null,
    )

    /**
     * 색인을 읽습니다. 권한이 없는 컬렉션은 건너뜁니다.
     * [ids]를 주면 그 항목만, [relativePath]를 주면 그 경로 아래 항목만 읽습니다.
     */
    suspend fun scan(
        trashed: Boolean = false,
        ids: Set<Long>? = null,
        volume: String = MediaStore.VOLUME_EXTERNAL,
        relativePath: String? = null,
    ): List<MediumEntity> = withContext(Dispatchers.IO) {
        if (ids != null && ids.isEmpty()) return@withContext emptyList()
        val filter = Filter(trashed, ids, relativePath)
        Kind.entries.flatMap { kind ->
            try {
                query(kind, volume, filter)
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }

    /** 한 폴더 아래 항목을 바로 읽습니다. 권한 오류는 호출한 쪽으로 전달합니다. */
    internal fun scanFolder(volume: String, relativePath: String): List<MediumEntity> {
        val filter = Filter(pathPrefix = relativePath)
        return Kind.entries.flatMap { query(it, volume, filter) }
            .filter { it.relativePath.startsWith(relativePath) }
    }

    internal fun externalVolumes(): Set<String> = MediaStore.getExternalVolumeNames(context)

    private fun query(kind: Kind, volume: String, filter: Filter): List<MediumEntity> {
        val result = ArrayList<MediumEntity>(512)
        context.contentResolver.query(kind.collection(volume), kind.columns, filter.toQueryArgs(), null)?.use { cursor ->
            val columns = Columns(cursor, kind == Kind.Videos)
            while (cursor.moveToNext()) result += columns.entity(cursor, kind)
        }
        return result
    }

    private fun Filter.toQueryArgs(): Bundle {
        val clauses = ArrayList<String>(2)
        val args = ArrayList<String>(1)
        ids?.let { clauses += "${MediaStore.MediaColumns._ID} IN (${it.joinToString(",")})" }
        pathPrefix?.let { prefix ->
            clauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            args += likePrefix(prefix)
        }
        return Bundle().apply {
            if (trashedOnly) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            if (clauses.isNotEmpty()) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND ") { "($it)" })
            }
            if (args.isNotEmpty()) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
        }
    }

    /** 커서의 열 위치를 한 번만 찾아 두고 행마다 색인 항목으로 바꿉니다. */
    private class Columns(cursor: Cursor, withDuration: Boolean) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val bucketId = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        private val relativePath = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        private val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        private val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val duration = if (withDuration) cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

        fun entity(cursor: Cursor, kind: Kind): MediumEntity {
            // DATE_MODIFIED는 초 단위이고, 촬영 시각이 없으면 수정 시각으로 대신합니다.
            val modifiedMillis = cursor.getLong(modified) * 1000
            val takenMillis = cursor.getLong(taken)
            val type = when {
                kind == Kind.Videos -> MediaType.Video
                cursor.getString(mime) == "image/gif" -> MediaType.Gif
                else -> MediaType.Image
            }
            return MediumEntity(
                id = cursor.getLong(id),
                name = cursor.getString(name).orEmpty(),
                bucketId = cursor.getLong(bucketId),
                bucketName = cursor.getString(bucketName) ?: UNNAMED_BUCKET,
                relativePath = cursor.getString(relativePath).orEmpty(),
                dateTaken = if (takenMillis > 0) takenMillis else modifiedMillis,
                dateModified = modifiedMillis,
                size = cursor.getLong(size),
                type = type.ordinal,
                durationMs = if (duration >= 0) cursor.getLong(duration) else 0L,
            )
        }
    }

    /** 폴더 경로들을 미디어 스캐너에 다시 읽히고, 모든 경로의 처리가 끝날 때까지 기다립니다. */
    suspend fun rescan(folders: List<String>) {
        if (folders.isEmpty()) return
        suspendCancellableCoroutine { continuation ->
            val left = AtomicInteger(folders.size)
            MediaScannerConnection.scanFile(context, folders.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() == 0) continuation.resume(Unit)
            }
        }
    }

    /** 문서 URI는 MediaStore URI로 바꿉니다. MediaStore 항목이 아니면 `null`입니다. */
    private fun mediaStoreUri(uri: Uri): Uri? {
        val resolved = if (!DocumentsContract.isDocumentUri(context, uri)) {
            uri
        } else {
            try {
                MediaStore.getMediaUri(context, uri)
            } catch (_: Exception) {
                null
            }
        }
        return resolved?.takeIf { it.authority == MediaStore.AUTHORITY }
    }

    fun mediaIdOf(uri: Uri): Long? {
        val media = mediaStoreUri(uri) ?: return null
        val id = try {
            ContentUris.parseId(media)
        } catch (_: RuntimeException) {
            return null
        }
        return id.takeIf { it >= 0 }
    }

    suspend fun mediaBucketOf(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val media = mediaStoreUri(uri) ?: return@withContext null
        try {
            context.contentResolver.query(media, arrayOf(MediaStore.MediaColumns.BUCKET_ID), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 사진·동영상 컬렉션이 바뀌면 바뀐 항목 ID를 보냅니다. 알림은 잠시 모았다가 한 번에 보내고,
     * 그사이 어떤 항목이 바뀌었는지 모르는 알림이 섞이면 `null`(전체 갱신)을 보냅니다.
     */
    val changes: Flow<Set<Long>?> = callbackFlow {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val lock = Any()
        // null이면 이번 묶음은 전체 갱신이 필요하다는 뜻입니다.
        var batch: MutableSet<Long>? = HashSet()

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = record(listOfNotNull(uri))
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) = record(uris)

            private fun record(uris: Collection<Uri>) {
                val ids = changedMediaIds(uris)
                synchronized(lock) {
                    if (ids == null) batch = null else batch?.addAll(ids)
                    wake.trySend(Unit)
                }
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)

        launch {
            for (ignored in wake) {
                delay(CHANGE_SETTLE_MS)
                val ready = synchronized(lock) {
                    // 기다리는 동안 들어온 깨우기 신호는 이번 묶음에 포함됩니다.
                    wake.tryReceive()
                    batch.also { batch = HashSet() }
                }
                send(ready)
            }
        }
        awaitClose {
            resolver.unregisterContentObserver(observer)
            wake.close()
        }
    }.buffer(0)
}

/**
 * 변경 알림 URI에서 항목 ID를 꺼냅니다. 항목 주소 없이 컬렉션 전체가 바뀌었다고 알리면
 * `null`을 돌려 기존 갤러리 색인을 한 번 전부 갱신하게 합니다.
 */
internal fun changedMediaIds(uris: Collection<Uri>): Set<Long>? {
    if (uris.isEmpty()) return null
    val ids = HashSet<Long>(uris.size)
    for (uri in uris) ids += uri.lastPathSegment?.toLongOrNull() ?: return null
    return ids
}

/** LIKE 패턴에서 특수 문자로 쓰이는 `\`, `%`, `_`를 이스케이프하고 접두사 검색용 `%`를 붙입니다. */
private fun likePrefix(prefix: String): String = buildString(prefix.length + 4) {
    for (ch in prefix) {
        if (ch == '\\' || ch == '%' || ch == '_') append('\\')
        append(ch)
    }
    append('%')
}
