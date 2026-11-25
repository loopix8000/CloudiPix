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
    val downloadedBytes = AtomicLong()
}

internal data class DirectoryListing(
    val entries: List<RemoteEntry>,
    val token: String? = null,
    val changesFrom: String? = null,
    val changes: DavListing? = null,
    val complete: Boolean = true,
)

@Serializable
private data class LegacyDirectoryListing(val entries: List<RemoteEntry>, val token: String? = null)

internal class RemoteDirectoryListings(cacheDir: File, private val db: SyncIndexDatabase, private val transport: RemoteTransport) {
    private val oldDirectory = File(cacheDir, "remote-directory-lists")
    private val index get() = db.syncIndexDao()
    private val locks = Array(16) { Mutex() }
    private class RecentRead(val server: RemoteServer, val path: String, val started: Long, val finished: Long,
        val forSync: Boolean, val listing: DirectoryListing)

    private val recent = LruCache<Long, RecentRead>(8)
    private val withoutReports = ConcurrentHashMap.newKeySet<Long>()
    private val removedServers = mutableSetOf<String>()

    fun removeServer(id: String) = synchronized(removedServers) {
        removedServers.add(id)
        recent.evictAll()
        index.removeServer(id)
    }

    suspend fun read(
        server: RemoteServer,
        path: String,
        requireComplete: Boolean,
        scanStarted: Long?,
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
            if (server.protocol == RemoteProtocol.WebDav && key !in withoutReports) {
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
                var report = transport.davChanges(server, path, baseline?.token)
                if (report == null && baseline != null) {
                    // 서버가 토큰 이력을 폐기한 경우 이 폴더의 기준 목록만 다시 받는다.
                    baseline = null
                    stats?.listingRequests?.incrementAndGet()
                    report = transport.davChanges(server, path, null)
                }
                if (report != null) {
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
                                index.saveListing(key, server.id, requireNotNull(report.token), if (replace) listing.entries else report.entries,
                                    report.removed, replace)
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
            if (server.protocol == RemoteProtocol.WebDav) withoutReports.add(key)
            onListing?.invoke(listing)
            remember(listing)
        }
    }
}
