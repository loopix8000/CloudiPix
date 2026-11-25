package app.cloudipix.gallery.feature.permission

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.theme.GalleryTheme

val supportsMediaManagement: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun Context.canManageGalleryMedia(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(this)

fun Context.mediaManagementSettingsIntent(): Intent =
    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", packageName, null))

private const val OFFER_SEEN = "offer_seen"
private const val MOVE_LOCATION_REQUESTED = "move_location_requested"

private fun Context.mediaManagementPreferences() = getSharedPreferences("media_management", Context.MODE_PRIVATE)

fun Context.shouldOfferMediaManagement(): Boolean =
    supportsMediaManagement && !canManageGalleryMedia() && !mediaManagementPreferences().getBoolean(OFFER_SEEN, false)

fun Context.shouldRequestMoveMediaLocation(): Boolean =
    canManageGalleryMedia() &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        !mediaManagementPreferences().getBoolean(MOVE_LOCATION_REQUESTED, false)

fun Context.markMoveMediaLocationRequested() {
    mediaManagementPreferences().edit().putBoolean(MOVE_LOCATION_REQUESTED, true).apply()
}

@Composable
fun MediaManagementOffer(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onContinue()
    }

    fun markOfferSeen() = context.mediaManagementPreferences().edit().putBoolean(OFFER_SEEN, true).apply()

    DialogCard(stringResource(R.string.media_management_title), onDismiss = {
        markOfferSeen()
        onDismiss()
    }) {
        Text(
            stringResource(R.string.media_management_hint),
            modifier = Modifier.padding(top = 8.dp),
            style = GalleryTheme.type.body,
            color = GalleryTheme.colors.textMuted,
        )
        DialogButtons(
            enabled = true,
            onDismiss = {
                markOfferSeen()
                onContinue()
            },
            onConfirm = {
                markOfferSeen()
                try {
                    launcher.launch(context.mediaManagementSettingsIntent())
                } catch (_: ActivityNotFoundException) {
                    onContinue()
                }
            },
            confirmLabel = stringResource(R.string.media_management_open_settings),
            dismissLabel = stringResource(R.string.media_management_later),
        )
    }
}
