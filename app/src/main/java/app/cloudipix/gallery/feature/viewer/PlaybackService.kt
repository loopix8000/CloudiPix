package app.cloudipix.gallery.feature.viewer

import android.content.ComponentName
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import app.cloudipix.gallery.MainActivity

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (session == null) {
            session = activePlayer?.let { player ->
                MediaSession.Builder(this, player)
                    .setSessionActivity(MainActivity.launchPendingIntent(this))
                    .build()
            }
        }
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        var activePlayer: Player? = null
    }
}

@Composable
fun PlaybackSessionEffect(player: Player) {
    val context = LocalContext.current
    DisposableEffect(player) {
        PlaybackService.activePlayer = player
        val service = ComponentName(context, PlaybackService::class.java)
        val controller = MediaController.Builder(context, SessionToken(context, service)).buildAsync()
        onDispose {
            MediaController.releaseFuture(controller)
            if (PlaybackService.activePlayer === player) PlaybackService.activePlayer = null
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
