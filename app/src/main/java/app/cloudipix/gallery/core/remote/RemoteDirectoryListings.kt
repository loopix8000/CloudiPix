package app.cloudipix.gallery.core.remote

import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import android.util.LruCache
import app.cloudipix.gallery.core.database.SyncIndexDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class RemoteReadStats : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RemoteReadStats>
    val listingRequests = AtomicInteger()
    val sharedListings = AtomicInteger()
    // 폴더 ETag가 그대로라 서버에 묻지 않고 저장된 목록을 쓴 횟수.
    val cachedListings = AtomicInteger()
    val downloadedBytes = AtomicLong()
}

internal data class DirectoryListing(
    val entries: List<RemoteEntry>,
    val token: String? = null,
    val changesFrom: String? = null,
    val changes: DavListing? = null,
    val complete: Boolean = true,
    // 목록과 같은 응답에서 읽은 폴더 자신의 ETag. 하위 변경이 전파되는 서버에서만 채운다.
    val etag: String? = null,
)

@Serializable
private data class LegacyDirectoryListing(val entries: List<RemoteEntry>, val token: String? = null)

/** 변경 목록 요청을 거절한 서버입니다. 주소가 바뀌거나 [checkedAt]에서 일정 기간이 지나면 다시 확인합니다. */
@Serializable
private data class ReportRejection(val address: String, val checkedAt: Long)

// 서버를 업데이트해 변경 목록을 지원하게 될 수 있으므로 거절 기록은 일주일만 믿는다.
private const val REPORT_RECHECK_MS = 7 * 24 * 60 * 60 * 1000L

// 저장소 밖에서 바뀐 파일은 상위 폴더 ETag에 늦게 반영될 수 있다. ETag가 같아도 하루에 한 번은 실제 목록을 읽는다.
private const val ETAG_TRUST_MS = 24 * 60 * 60 * 1000L

// 최근 목록은 폴더 수가 아니라 항목 수로 제한한다. 작은 폴더가 많은 트리도 동기화 뒤 갤러리 갱신과 공유된다.
private const val RECENT_ENTRY_LIMIT = 32_768

internal class RemoteDirectoryListings(cacheDir: File, private val db: SyncIndexDatabase, private val transport: RemoteTransport) {
    private val oldDirectory = File(cacheDir, "remote-directory-lists")
    private val index get() = db.syncIndexDao()
    private val locks = Array(16) { Mutex() }
    private class RecentRead(val server: RemoteServer, val path: String, val started: Long, val finished: Long,
        val forSync: Boolean, val listing: DirectoryListing)

    private val recent = object : LruCache<Long, RecentRead>(RECENT_ENTRY_LIMIT) {
        override fun sizeOf(key: Long, value: RecentRead) = value.listing.entries.size + 1
    }
    private val withoutReports = ConcurrentHashMap.newKeySet<Long>()
    // 이번 실행에서 변경 목록을 받은 서버와, 처음 거절한 폴더. 폴더 하나의 거절로 서버 전체를 판단하지 않는다.
    private val reportingServers = ConcurrentHashMap.newKeySet<String>()
    private val firstRejectedFolders = ConcurrentHashMap<String, Long>()
    private val removedServers = mutableSetOf<String>()
    private val rejectionFile = AtomicFile(File(cacheDir, "dav-report-rejections.json"))
    private var rejections: MutableMap<String, ReportRejection>? = null

    fun removeServer(id: String) = synchronized(removedServers) {
        removedServers.add(id)
        recent.evictAll()
        reportingServers.remove(id)
        firstRejectedFolders.remove(id)
        forgetRejection(id)
        index.removeServer(id)
    }

    // 거절 기록은 removedServers → rejectionFile 순서로만 잠근다.
    private fun loadedRejections(): MutableMap<String, ReportRejection> = rejections ?: try {
        Json.decodeFromString<Map<String, ReportRejection>>(rejectionFile.readFully().decodeToString()).toMutableMap()
    } catch (_: IOException) {
        mutableMapOf()
    } catch (_: SerializationException) {
        mutableMapOf()
    }.also { rejections = it }

    private fun saveRejections(values: Map<String, ReportRejection>) {
        val output = try { rejectionFile.startWrite() } catch (_: IOException) { return }
        try {
            output.write(Json.encodeToString(values).encodeToByteArray())
            rejectionFile.finishWrite(output)
        } catch (_: IOException) {
            // 저장하지 못해도 이번 실행 동안에는 메모리 기록으로 건너뛴다.
            rejectionFile.failWrite(output)
        }
    }

    private fun rejectsReports(server: RemoteServer): Boolean = synchronized(rejectionFile) {
        val rejection = loadedRejections()[server.id] ?: return false
        rejection.address == server.address && System.currentTimeMillis() - rejection.checkedAt in 0 until REPORT_RECHECK_MS
    }

    private fun rememberRejection(server: RemoteServer) = synchronized(removedServers) {
        if (server.id in removedServers) return@synchronized
        synchronized(rejectionFile) {
            val values = loadedRejections()
            values[server.id] = ReportRejection(server.address, System.currentTimeMillis())
            saveRejections(values)
        }
    }

    // 서로 다른 두 폴더가 거절하고 변경 목록을 받은 폴더가 없을 때만 서버 전체가 지원하지 않는 것으로 기록한다.
    private fun noteRejection(server: RemoteServer, key: Long) {
        if (server.id in reportingServers) return
        val first = firstRejectedFolders.putIfAbsent(server.id, key) ?: return
        if (first != key) rememberRejection(server)
    }

    private fun forgetRejection(id: String) = synchronized(rejectionFile) {
        val values = loadedRejections()
        if (values.remove(id) != null) saveRejections(values)
    }

    /**
     * 폴더 목록을 읽습니다. [knownEtag]는 상위 폴더 목록에서 본 이 폴더의 ETag이고, 저장된 목록의 ETag와 같으면
     * 서버에 묻지 않습니다. [probeEtag]이면 저장된 목록이 있을 때 폴더 ETag만 먼저 확인합니다.
     */
    suspend fun read(
        server: RemoteServer,
        path: String,
        requireComplete: Boolean,
        scanStarted: Long?,
        knownEtag: String? = null,
        probeEtag: Boolean = false,
        onListing: (suspend (DirectoryListing) -> Unit)? = null,
    ): DirectoryListing {
        val key = remoteId(server.id, path)
        val requested = System.nanoTime()
        val stats = currentCoroutineContext()[RemoteReadStats]
        return locks[key.toInt() and (locks.size - 1)].withLock {
            val previous = recent.get(key)?.takeIf { it.server === server && it.path == path }
            // 겹치는 갤러리 조회와 동기화만 공유한다. 변경 뒤 명시적으로 요청한 새로고침은 새 목록을 받는다.
            if (scanStarted != null && previous != null && previous.forSync != requireComplete &&
                (previous.started >= scanStarted || previous.finished >= requested) &&
                (!requireComplete || previous.listing.complete)
            ) {
                stats?.sharedListings?.incrementAndGet()
                onListing?.invoke(previous.listing)
                return@withLock previous.listing
            }
            val started = System.nanoTime()
            fun remember(listing: DirectoryListing) = synchronized(removedServers) {
                if (server.id !in removedServers) {
                    recent.put(key, RecentRead(server, path, started, System.nanoTime(), requireComplete, listing))
                }
                listing
            }
            if (server.protocol == RemoteProtocol.WebDav && (knownEtag != null || probeEtag)) {
                unchangedListing(server, key, path, knownEtag, stats)?.let { listing ->
                    stats?.cachedListings?.incrementAndGet()
                    onListing?.invoke(listing)
                    return@withLock remember(listing)
                }
            }
            var rejected = false
            if (server.protocol == RemoteProtocol.WebDav && key !in withoutReports && !rejectsReports(server)) {
                val cached = previous?.listing?.takeIf { it.token != null } ?: try {
                    db.runInTransaction<DirectoryListing?> {
                        index.token(key)?.let { token -> DirectoryListing(index.entries(key).map { it.entry }, token) }
                    }
                } catch (_: SQLiteException) {
                    null
                }
                val oldFile = if (cached == null) AtomicFile(File(oldDirectory, "$key.json")) else null
                val legacy = oldFile?.let { file ->
                    try {
                        val old = Json.decodeFromString<LegacyDirectoryListing>(file.readFully().decodeToString())
                        old.token?.let { DirectoryListing(old.entries, it) }
                    } catch (_: IOException) { null } catch (_: SerializationException) { null }
                }
                val saved = cached ?: legacy
                var baseline = saved
                stats?.listingRequests?.incrementAndGet()
                var changes = transport.davChanges(server, path, baseline?.token)
                if (changes.listing == null && baseline != null) {
                    // 서버가 토큰 이력을 폐기한 경우 이 폴더의 기준 목록만 다시 받는다.
                    baseline = null
                    stats?.listingRequests?.incrementAndGet()
                    changes = transport.davChanges(server, path, null)
                }
                val report = changes.listing
                // 여기까지 목록을 얻지 못했다면 마지막 요청은 토큰 없이 보낸 첫 요청이다.
                rejected = changes.rejected
                if (report != null) {
                    reportingServers.add(server.id)
                    val listing = when {
                        baseline == null -> DirectoryListing(report.entries, report.token)
                        report.entries.isEmpty() && report.removed.isEmpty() -> {
                            if (baseline.token == report.token) baseline else DirectoryListing(baseline.entries, report.token, baseline.token, report)
                        }
                        else -> {
                            val entries = baseline.entries.associateByTo(LinkedHashMap(), RemoteEntry::name)
                            report.removed.forEach(entries::remove)
                            report.entries.forEach { entries[it.name] = it }
                            DirectoryListing(entries.values.toList(), report.token, baseline.token, report)
                        }
                    }
                    // 갤러리는 조회한 폴더를 먼저 반영하고 토큰과 항목은 이어서 저장한다.
                    onListing?.invoke(listing)
                    synchronized(removedServers) {
                        if (server.id !in removedServers) {
                            if (listing !== saved || legacy != null) try {
                                val replace = baseline == null || legacy != null
                                index.saveListing(key, server.id, requireNotNull(report.token), null,
                                    if (replace) listing.entries else report.entries, report.removed, replace, System.currentTimeMillis())
                                oldFile?.delete()
                            } catch (_: SQLiteException) {
                                // 디스크 토큰을 앞지른 메모리 목록에 다음 변경분을 이어 붙이지 않는다.
                                recent.remove(key)
                                return@withLock listing
                            }
                        }
                    }
                    return@withLock remember(listing)
                }
            }
            stats?.listingRequests?.incrementAndGet()
            val listing = transport.listDirectory(server, path, requireComplete)
            // 변경 목록을 받지 못한 폴더는 이번 실행 동안 일반 목록으로 읽는다.
            // 일반 목록은 읽히는데 변경 목록만 거절했다면 서버 전체가 지원하지 않는지 함께 판단한다.
            if (server.protocol == RemoteProtocol.WebDav) {
                withoutReports.add(key)
                if (rejected) noteRejection(server, key)
            }
            onListing?.invoke(listing)
            if (server.protocol == RemoteProtocol.WebDav && listing.complete && listing.etag != null) saveEtagListing(server, key, listing)
            remember(listing)
        }
    }

    // 저장된 목록이 폴더의 현재 ETag와 같은 상태에서 읽은 것이면 돌려준다. 확인할 수 없으면 null이다.
    private fun unchangedListing(server: RemoteServer, key: Long, path: String, knownEtag: String?, stats: RemoteReadStats?): DirectoryListing? {
        val saved = try { index.directory(key) } catch (_: SQLiteException) { null } ?: return null
        val etag = saved.etag ?: return null
        if (saved.token != null || System.currentTimeMillis() - saved.checkedAt !in 0 until ETAG_TRUST_MS) return null
        val current = knownEtag ?: try {
            stats?.listingRequests?.incrementAndGet()
            transport.folderEtag(server, path)
        } catch (_: IOException) {
            // 확인 요청이 실패하면 일반 목록 요청이 실제 오류를 알려 준다.
            null
        } ?: return null
        if (current != etag) return null
        return try {
            db.runInTransaction<DirectoryListing?> {
                index.directory(key)?.takeIf { it.etag == etag && it.token == null }
                    ?.let { DirectoryListing(index.entries(key).map { it.entry }, etag = etag) }
            }
        } catch (_: SQLiteException) {
            null
        }
    }

    private fun saveEtagListing(server: RemoteServer, key: Long, listing: DirectoryListing) = synchronized(removedServers) {
        if (server.id in removedServers) return@synchronized
        val now = System.currentTimeMillis()
        try {
            val saved = index.directory(key)
            // ETag가 같으면 항목은 그대로이므로 확인 시각만 고친다.
            if (saved != null && saved.token == null && saved.etag == listing.etag) index.markChecked(key, now)
            else index.saveListing(key, server.id, null, listing.etag, listing.entries, emptyList(), replace = true, checkedAt = now)
        } catch (_: SQLiteException) {
            // 저장하지 못하면 다음에 다시 목록을 읽는다.
        }
    }
}
