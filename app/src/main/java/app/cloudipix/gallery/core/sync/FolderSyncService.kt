package app.cloudipix.gallery.core.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BackgroundSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: FolderSync,
) : DefaultLifecycleObserver {
    private val lifecycle get() = ProcessLifecycleOwner.get().lifecycle
    private val visible get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private val notifications by lazy { SyncNotifications(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableSetOf<UUID>()
    private var starting = false
    private var service: FolderSyncService? = null
    private var updates: Job? = null

    fun canContinueTransfer(id: UUID) = id in requests && (visible || service?.promoted == true)

    // 작업, 서비스, 화면 전환을 모두 메인 스레드에서 처리한다.
    fun begin(id: UUID) {
        if (!requests.add(id) || requests.size != 1) return
        lifecycle.addObserver(this)
        if (!visible) show()
    }

    fun end(id: UUID) {
        if (!requests.remove(id) || requests.isNotEmpty()) return
        lifecycle.removeObserver(this)
        hide()
        service?.stopSelf()
        service = null
    }

    override fun onStart(owner: LifecycleOwner) {
        hide()
        startService()
    }

    override fun onStop(owner: LifecycleOwner) = show()

    private fun startService() {
        if (starting || service != null) return
        try {
            // 일반 서비스로 준비해 두면 화면이 보이는 동안 알림을 게시할 필요가 없다.
            starting = context.startService(Intent(context, FolderSyncService::class.java)) != null
        } catch (_: IllegalStateException) {
            // 백그라운드 서비스 시작이 제한된 예약 작업은 WorkManager가 계속 실행한다.
        }
    }

    fun connected(instance: FolderSyncService) {
        starting = false
        if (requests.isEmpty()) {
            instance.stopSelf()
            return
        }
        service = instance
        if (!visible) promote(instance)
    }

    fun disconnected(instance: FolderSyncService) {
        if (service !== instance) return
        service = null
        if (requests.isNotEmpty() && !visible) notifications.update(sync.progress.value.notificationCounts())
    }

    private fun show() {
        if (updates != null) return
        val instance = service
        if (instance != null) promote(instance) else startService()
        updates = scope.launch {
            sync.progress.map { it.notificationCounts() }.distinctUntilChanged().collect { counts ->
                if (!visible) notifications.update(counts)
                delay(1500)
            }
        }
    }

    private fun promote(instance: FolderSyncService) {
        try {
            instance.promote(notifications.notification(sync.progress.value.notificationCounts()))
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || error !is android.app.ServiceStartNotAllowedException) throw error
        }
    }

    private fun hide() {
        if (updates == null) return
        updates?.cancel()
        updates = null
        service?.demote()
        notifications.remove()
    }
}

private fun Map<String, FolderSyncProgress>.notificationCounts(): SyncFileCounts? =
    values.map { it.counts }.reduceOrNull { a, b -> SyncFileCounts(
        unchanged = a.unchanged + b.unchanged, uploaded = a.uploaded + b.uploaded,
        downloaded = a.downloaded + b.downloaded, deleted = a.deleted + b.deleted,
    ) }

@AndroidEntryPoint
class FolderSyncService : Service() {
    @Inject internal lateinit var background: BackgroundSync
    internal var promoted = false
        private set
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        background.connected(this)
        return START_NOT_STICKY
    }

    internal fun promote(notification: Notification) {
        if (promoted) return
        startForeground(SyncNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        promoted = true
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudifyGallery:FolderSync")
        wakeLock?.acquire()
    }

    internal fun demote() {
        if (!promoted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.release()
        wakeLock = null
        promoted = false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        demote()
        stopSelf()
    }

    override fun onDestroy() {
        demote()
        background.disconnected(this)
        super.onDestroy()
    }
}
