package app.cloudipix.gallery.feature.permission

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.hasAllFilesAccess
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.FolderBrowser
import app.cloudipix.gallery.ui.theme.GalleryTheme

private fun Context.allFilesAccessIntents(): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
)

/**
 * 권한이 있으면 바로 [onGranted]를 부르고, 없으면 설정 화면으로 안내한다.
 * 설정에서 돌아왔을 때 허용됐으면 [onGranted], 아니면 안내 문구를 보이고 [onDismiss]를 부른다.
 */
@Composable
fun AllFilesAccessRequest(onGranted: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasAllFilesAccess()) onGranted() else denied(context, onDismiss)
    }
    if (hasAllFilesAccess()) {
        LaunchedEffect(Unit) { onGranted() }
        return
    }
    DialogCard(stringResource(R.string.all_files_access_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.all_files_access_hint),
            modifier = Modifier.padding(top = 8.dp),
            style = GalleryTheme.type.body,
            color = GalleryTheme.colors.textMuted,
        )
        DialogButtons(
            enabled = true,
            onDismiss = { denied(context, onDismiss) },
            onConfirm = {
                val opened = context.allFilesAccessIntents().any { intent ->
                    try {
                        launcher.launch(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
                if (!opened) denied(context, onDismiss)
            },
            confirmLabel = stringResource(R.string.media_management_open_settings),
            dismissLabel = stringResource(R.string.media_management_later),
        )
    }
}

private fun denied(context: Context, onDismiss: () -> Unit) {
    Toast.makeText(context, R.string.all_files_access_denied, Toast.LENGTH_LONG).show()
    onDismiss()
}

/** 모든 파일 접근을 먼저 확인한 뒤 앱 안 폴더 탐색기로 기기 폴더 하나를 고른다. 결과는 절대 경로다. */
@Composable
internal fun DeviceFolderPicker(
    title: String,
    initialPath: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    excluded: String? = null,
    selectStorageRoot: Boolean = true,
) {
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    if (!granted) AllFilesAccessRequest(onGranted = { granted = true }, onDismiss = onDismiss)
    else FolderBrowser(title, initialPath, onPick, onDismiss, excluded, selectStorageRoot)
}
