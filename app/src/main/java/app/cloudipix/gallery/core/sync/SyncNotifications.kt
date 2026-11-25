package app.cloudipix.gallery.core.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.cloudipix.gallery.R
import app.cloudipix.gallery.MainActivity
import app.cloudipix.gallery.core.settings.storedLanguage
import app.cloudipix.gallery.core.settings.withLanguage

internal class SyncNotifications(context: Context) {
    private val app = context.withLanguage(context.storedLanguage())
    private val manager = requireNotNull(app.getSystemService(NotificationManager::class.java))
    private val openApp = MainActivity.launchPendingIntent(app)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.sync_title), NotificationManager.IMPORTANCE_LOW))
    }

    fun update(counts: SyncFileCounts?) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (manager.areNotificationsEnabled()) manager.notify(ID, notification(counts))
    }

    fun remove() = manager.cancel(ID)

    fun notification(counts: SyncFileCounts?): Notification {
        val text = counts?.let { app.getString(R.string.sync_file_counts, it.completed, it.uploaded, it.downloaded, it.deleted, it.unchanged) }
            ?: app.getString(R.string.sync_notification_active)
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(app.getString(R.string.sync_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        const val ID = 0x43465359
        private const val CHANNEL = "folder_sync"

        fun settingsIntent(context: Context): Intent {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelAvailable = manager?.areNotificationsEnabled() == true && manager.getNotificationChannel(CHANNEL) != null
            return Intent(if (channelAvailable) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        }
    }
}
