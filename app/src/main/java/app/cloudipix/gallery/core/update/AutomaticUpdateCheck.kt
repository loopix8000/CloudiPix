package app.cloudipix.gallery.core.update

import android.content.Context
import androidx.core.content.edit
import app.cloudipix.gallery.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱이 전경으로 올 때 마지막 확인에서 24시간이 지났으면 GitHub의 최신 릴리스를 백그라운드에서 조회합니다.
 * 화면 표시는 기다리지 않으며, 새 버전을 찾은 경우에만 [available]로 알립니다.
 */
internal object AutomaticUpdateCheck {
    private val interval = TimeUnit.HOURS.toMillis(24)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private val _available = MutableStateFlow<AppUpdate?>(null)

    /** 자동 확인으로 찾은 새 버전입니다. 사용자가 창을 닫거나 다운로드를 시작하면 비웁니다. */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    fun checkIfDue(context: Context) {
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty() || running?.isActive == true) return
        val app = context.applicationContext
        running = scope.launch {
            val prefs = withContext(Dispatchers.IO) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { it.getLong(KEY_LAST_CHECK, 0L) }
            }
            val now = System.currentTimeMillis()
            // 기기 시계를 과거로 돌려 기록이 미래 시각이 되어도 확인이 멈추지 않게 한다.
            if (prefs.getLong(KEY_LAST_CHECK, 0L) in (now - interval + 1)..now) return@launch
            val found = try {
                GitHubAppUpdates.check(BuildConfig.VERSION_NAME)
            } catch (_: AppUpdateException) {
                // 릴리스나 APK가 없다는 응답은 확인한 것으로 보고 다음 주기를 기다린다.
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 연결 실패나 잘못된 응답은 기록하지 않아 다음에 앱을 열 때 다시 시도한다.
                return@launch
            }
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            if (found != null) _available.value = found
        }
    }

    fun dismiss() {
        _available.value = null
    }

    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_automatic_check"
}
