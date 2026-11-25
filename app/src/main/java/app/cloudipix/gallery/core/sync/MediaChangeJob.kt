package app.cloudipix.gallery.core.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.media.changedMediaIds
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class MediaChangeJob : JobService() {
    @Inject lateinit var repository: MediaRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var current: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        current = scope.launch {
            try {
                // Android는 한 작업에 최대 50개 주소를 보관한다. 한도에 닿으면 빠진 항목도 색인에 반영한다.
                val uris = params.triggeredContentUris
                val ids = uris?.takeIf { it.size < 50 }?.let { changedMediaIds(it.asList()) }
                repository.syncWatchedMedia(ids)
                // 같은 ID로 다음 감시를 등록하면 처리 도중 들어온 변경도 시스템이 넘겨준다.
                if (!rearm(this@MediaChangeJob)) jobFinished(params, true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        current?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x43474D

        @Synchronized
        internal fun setEnabled(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val scheduled = scheduler.getPendingJob(JOB_ID) != null
            if (enabled && !scheduled) schedule(context, scheduler)
            if (!enabled && scheduled) scheduler.cancel(JOB_ID)
        }

        @Synchronized
        private fun rearm(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (scheduler.getPendingJob(JOB_ID) == null) return true
            return schedule(context, scheduler)
        }

        private fun schedule(context: Context, scheduler: JobScheduler): Boolean {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, MediaChangeJob::class.java))
            for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                job.addTriggerContentUri(JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            }
            return scheduler.schedule(job.setTriggerContentUpdateDelay(3_000).setTriggerContentMaxDelay(15_000).build()) ==
                JobScheduler.RESULT_SUCCESS
        }
    }
}

// Android는 변경 알림 작업을 재부팅 후에 유지하지 않는다.
@AndroidEntryPoint
class MediaWatchBootReceiver : BroadcastReceiver() {
    @Inject lateinit var folderSync: FolderSync

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                folderSync.restoreMediaWatch()
            } catch (_: IOException) {
                // 다음 앱 실행 때 연결 설정을 다시 읽는다.
            } finally {
                pending.finish()
            }
        }
    }
}
