package app.cloudipix.gallery

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import app.cloudipix.gallery.core.settings.storedLanguage
import app.cloudipix.gallery.core.settings.withLanguage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dagger.hilt.android.AndroidEntryPoint
import app.cloudipix.gallery.core.data.MediaRepository
import app.cloudipix.gallery.core.security.GalleryLocks
import app.cloudipix.gallery.core.sync.FolderSync
import app.cloudipix.gallery.feature.permission.MediaPermissionGate
import app.cloudipix.gallery.feature.permission.MediaAccess
import app.cloudipix.gallery.feature.security.GalleryAccess
import app.cloudipix.gallery.feature.security.GalleryAccessHost
import app.cloudipix.gallery.feature.security.GalleryUnlockSession
import app.cloudipix.gallery.feature.viewer.OpenedMediaScreen
import app.cloudipix.gallery.feature.viewer.openedMedium
import app.cloudipix.gallery.navigation.GalleryNavHost
import app.cloudipix.gallery.navigation.Folders
import app.cloudipix.gallery.navigation.Media
import androidx.compose.ui.graphics.Color
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

@AndroidEntryPoint
class MainActivity : GalleryActivity() {
    companion object {
        fun launchPendingIntent(context: Context): PendingIntent {
            // 팔레트를 바꾼 뒤에도 기존 알림은 고정된 액티비티를 열어야 한다.
            val intent = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

@AndroidEntryPoint
class AlbumActivity : GalleryActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.album_enter, R.anim.folders_recede)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.folders_return, R.anim.album_exit)
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.folders_return, R.anim.album_exit)
    }
}

abstract class GalleryActivity : ComponentActivity() {
    @Inject lateinit var locks: GalleryLocks
    @Inject lateinit var mediaRepository: MediaRepository
    @Inject lateinit var folderSync: FolderSync
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val localSession: GalleryUnlockSession by viewModels()
    private lateinit var sessionKey: String
    private lateinit var unlockSession: GalleryUnlockSession
    private lateinit var access: GalleryAccess
    private var openingAlbum = false
    private val navigationVolumeKeys = mutableSetOf<Int>()
    private var repeatingVolumeKey: Int? = null
    private var volumeRepeat: Job? = null
    internal var onVolumeNavigationPress: ((Int) -> Job)? = null
        set(value) {
            stopVolumeRepeat()
            field = value
        }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            navigationVolumeKeys.remove(event.keyCode)
            val navigate = onVolumeNavigationPress
            if (navigate != null && hasWindowFocus()) {
                navigationVolumeKeys.add(event.keyCode)
                stopVolumeRepeat()
                repeatingVolumeKey = event.keyCode
                volumeRepeat = navigate(step)
                return true
            }
        }
        // 화면 이동 중에도 이미 처리한 버튼의 시스템 반복 입력과 떼는 입력을 소비한다.
        if (event.keyCode in navigationVolumeKeys) {
            if (event.action == KeyEvent.ACTION_UP) {
                navigationVolumeKeys.remove(event.keyCode)
                if (event.keyCode == repeatingVolumeKey) stopVolumeRepeat()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopVolumeRepeat()
    }

    private fun stopVolumeRepeat() {
        volumeRepeat?.cancel()
        volumeRepeat = null
        repeatingVolumeKey = null
    }

    override fun onStart() {
        super.onStart()
        unlockSession.windowStarted()
    }

    override fun onResume() {
        super.onResume()
        openingAlbum = false
    }

    override fun onStop() {
        access.dispose()
        unlockSession.windowStopped(isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        access.dispose()
        unlockSession.detachWindow(sessionKey, isChangingConfigurations)
        super.onDestroy()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withLanguage(base.storedLanguage()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestSyncNotificationsIfNeeded()
        sessionKey = savedInstanceState?.getString(SESSION_KEY)
            ?: (if (this is AlbumActivity) intent.getStringExtra(SESSION_KEY) else null)
            ?: UUID.randomUUID().toString()
        unlockSession = GalleryUnlockSession.attachWindow(sessionKey, localSession)
        access = GalleryAccess(this, locks, unlockSession)
        lifecycleScope.launch {
            locks.settings.collect { settings ->
                // 앱 잠금이 켜져 있으면 백그라운드에서 세션이 잠기므로 미리보기 캡처를 미리 막는다.
                val hidePreview = settings.app || settings.hideRecentsPreview
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!hidePreview)
                } else if (hidePreview) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        val opened = intent.openedMedium(intent.resolveType(this))
        val album = if (this is AlbumActivity) Media(
            intent.getLongExtra(BUCKET_ID, 0L),
            intent.getStringExtra(ALBUM_TITLE).orEmpty(),
            intent.getBooleanExtra(CHOOSE_COVER, false),
        ) else null
        setContent {
            val viewModel: ThemeViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            GalleryTheme(
                mode = settings.themeMode,
                accent = Color(settings.accentArgb),
                contrast = settings.contrast,
                corners = settings.corners,
                font = settings.font,
                textScale = settings.textScale,
            ) {
                GalleryAccessHost(access) {
                    if (album != null) GalleryNavHost(start = album, onExit = ::finish, settings = settings, openAlbum = ::openAlbum)
                    else if (opened != null) OpenedMediaScreen(opened, settings, onClose = ::finish, openAlbum = ::openAlbum)
                    else MediaPermissionGate { mediaAccess, request ->
                        LifecycleStartEffect(mediaAccess, lifecycleOwner = ProcessLifecycleOwner.get()) {
                            lifecycleScope.launch { mediaRepository.sync() }
                            onStopOrDispose {}
                        }
                        GalleryNavHost(
                            start = Folders,
                            requestLocalAccess = request.takeIf { mediaAccess == MediaAccess.Denied },
                            settings = settings,
                            openAlbum = ::openAlbum,
                        )
                    }
                }
            }
        }
    }

    private fun requestSyncNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val prefs = getSharedPreferences("sync_notifications", MODE_PRIVATE)
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED || prefs.getBoolean("requested", false)) return
        lifecycleScope.launch {
            folderSync.linkedFolders.first { folders -> folders.any { it.folder.sync != null } }
            lifecycle.withResumed {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("requested", false)) {
                    prefs.edit().putBoolean("requested", true).apply()
                    notificationPermission.launch(permission)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SESSION_KEY, sessionKey)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun openAlbum(route: Media) {
        if (openingAlbum) return
        openingAlbum = true
        startActivity(Intent(this, AlbumActivity::class.java).apply {
            putExtra(BUCKET_ID, route.bucketId)
            putExtra(ALBUM_TITLE, route.title)
            putExtra(CHOOSE_COVER, route.chooseCover)
            putExtra(SESSION_KEY, sessionKey)
        })
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.album_enter, R.anim.folders_recede)
    }
}

private const val SESSION_KEY = "galleryWindowSession"
private const val BUCKET_ID = "albumBucketId"
private const val ALBUM_TITLE = "albumTitle"
private const val CHOOSE_COVER = "albumChooseCover"

