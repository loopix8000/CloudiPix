package app.cloudipix.gallery.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.LruCache
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal class FolderSyncScheduler(private val context: Context) {
    private val requests = Mutex()

    suspend fun enqueue(id: String?, automatic: Boolean = false, requestedAt: Long? = null, subfolder: String? = null) = requests.withLock {
        val work = WorkManager.getInstance(context)
        val rootWork = workName(id)
        val name = if (subfolder == null) rootWork else "$rootWork/$subfolder"
        val existing = if (automatic && requestedAt == null) emptyList() else work.getWorkInfosForUniqueWorkFlow(name).first()
        // 자동으로 요청할 때는 실행 중이거나 재시도를 기다리는 작업을 그대로 이어간다.
        val inBackoff = existing.any {
            it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 && it.nextScheduleTimeMillis > System.currentTimeMillis()
        }
        val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
            .apply { if (subfolder != null) addTag("$rootWork/subfolders") }
            .setInputData(workDataOf(KEY_FOLDER to id, KEY_AUTOMATIC to automatic, KEY_REQUESTED_AT to (requestedAt ?: 0L), KEY_SUBFOLDER to subfolder))
            .build()
        val policy = when {
            // 앱이나 폴더를 열어 요청하면 실행 중인 작업만 유지하고, 기다리는 작업은 이 요청으로 바꾼다.
            requestedAt != null ->
                if (existing.any { it.state == WorkInfo.State.RUNNING }) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            inBackoff -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        work.enqueueUniqueWork(name, policy, request).await()
    }

    fun cancel(id: String) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(workName(id))
        work.cancelAllWorkByTag("${workName(id)}/subfolders")
        work.cancelAllWorkByTag(localMoveTag(id))
    }

    suspend fun enqueueChange(id: String, mediaToken: String) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(workDataOf(KEY_FOLDER to id, KEY_MEDIA_TOKEN to mediaToken))
                .addTag(localMoveTag(id))
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build(),
        ).await()
    }

    suspend fun enqueueCleanup() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_CLEANUP, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FolderSyncCleanupWorker>().build(),
        ).await()
    }

    fun schedule(interval: Int, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(WORK_PERIODIC)
            work.cancelUniqueWork(WORK_NOW)
            return
        }
        val period = interval.toLong()
        val request = PeriodicWorkRequestBuilder<FolderSyncWorker>(period, TimeUnit.MINUTES)
            .setInitialDelay(period, TimeUnit.MINUTES)
            .addTag(WORK_PERIODIC)
            .build()
        work.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun workName(id: String?) = if (id == null) WORK_NOW else "$WORK_NOW-$id"
    private fun localMoveTag(id: String) = "${workName(id)}-local-move"
}

class FolderSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // 인터넷 연결이 확인되지 않은 내부망에서도 서버에 접속할 수 있다.
        if (applicationContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null) return Result.retry()
        val transfers = EntryPointAccessors.fromApplication(applicationContext, FolderSyncEntryPoint::class.java).transfers()
        val outcome = transfers.run(id, inputData.getString(KEY_FOLDER), WORK_PERIODIC in tags,
            inputData.getBoolean(KEY_AUTOMATIC, false), runAttemptCount,
            inputData.getLong(KEY_REQUESTED_AT, 0L).takeIf { it > 0 }, inputData.getString(KEY_MEDIA_TOKEN), inputData.getString(KEY_SUBFOLDER)) {
            // 실행 시간 초과나 발열 제한처럼 시스템이 작업을 멈춘 경우에만 전송을 이어간다.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason !in STOPPED_ON_REQUEST
        }
        return when (outcome) {
            SyncOutcome.Success -> Result.success()
            SyncOutcome.Failed -> Result.failure()
            SyncOutcome.Retry -> Result.retry()
        }
    }
}

@Singleton
internal class SyncTransfers @Inject constructor(private val sync: FolderSync, private val background: BackgroundSync) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = mutableMapOf<UUID, Deferred<SyncOutcome>>()
    private val completed = LruCache<UUID, SyncOutcome>(32)

    suspend fun run(workId: UUID, folder: String?, periodic: Boolean, automatic: Boolean, attempt: Int,
        requestedAt: Long?, mediaToken: String?, subfolder: String?, stoppedBySystem: () -> Boolean): SyncOutcome = withContext(Dispatchers.Main.immediate) {
        val previous = completed.remove(workId)
        if (previous != null && (!periodic || attempt > 0)) return@withContext previous
        // 작업자의 실행 시간이 끝나도 화면이나 서비스가 유지하는 전송은 같은 작업 ID로 이어받는다.
        val task = active[workId] ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    sync.run(folder, periodic, automatic, retrying = attempt > 0, requestedAt = requestedAt, mediaToken = mediaToken, subfolder = subfolder) {
                        withContext(Dispatchers.Main.immediate) { background.begin(workId) }
                    }
                } catch (_: IOException) {
                    SyncOutcome.Retry
                }
                completed.put(workId, result)
                result
            } finally {
                background.end(workId)
                active.remove(workId)
            }
        }.also { active[workId] = it }
        try {
            task.await().also { completed.remove(workId) }
        } catch (error: CancellationException) {
            if (!stoppedBySystem() || !background.canContinueTransfer(workId)) {
                withContext(NonCancellable) { task.cancelAndJoin() }
                if (active[workId] === task) active.remove(workId)
            }
            throw error
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FolderSyncEntryPoint {
    fun folderSync(): FolderSync
    fun transfers(): SyncTransfers
}

private const val KEY_FOLDER = "folder"
private const val KEY_SUBFOLDER = "subfolder"
private const val KEY_MEDIA_TOKEN = "media-token"
private const val KEY_AUTOMATIC = "automatic"
private const val KEY_REQUESTED_AT = "requested-at"
private const val WORK_PERIODIC = "cloud-folder-sync-periodic"
private const val WORK_NOW = "cloud-folder-sync-now"
private const val WORK_CLEANUP = "cloud-folder-sync-local-cleanup"

// 앱이 작업을 취소·교체했거나 사용자가 앱을 중지한 경우, 또는 중지 사유를 알 수 없는 경우에는 전송도 멈춘다.
private val STOPPED_ON_REQUEST = setOf(
    WorkInfo.STOP_REASON_CANCELLED_BY_APP,
    WorkInfo.STOP_REASON_USER,
    WorkInfo.STOP_REASON_NOT_STOPPED,
    WorkInfo.STOP_REASON_UNKNOWN,
)

class FolderSyncCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val sync = EntryPointAccessors.fromApplication(applicationContext, FolderSyncEntryPoint::class.java).folderSync()
        if (sync.cleanLocalCopies()) Result.success() else Result.retry()
    } catch (_: IOException) {
        Result.retry()
    }
}
