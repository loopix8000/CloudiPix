package app.cloudipix.gallery.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.update.AppUpdate
import app.cloudipix.gallery.core.update.AppUpdateException
import app.cloudipix.gallery.core.update.AutomaticUpdateCheck
import app.cloudipix.gallery.core.update.GitHubAppUpdates
import app.cloudipix.gallery.ui.component.GalleryAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateRow(installedVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var downloading by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            val label = if (checking) R.string.update_checking else R.string.update_check
            Text(stringResource(label))
        },
        trailingContent = {
            if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        },
        modifier = Modifier.clickable(enabled = !checking && !downloading, role = Role.Button) {
            if (checking || downloading) return@clickable
            checking = true
            scope.launch {
                try {
                    update = GitHubAppUpdates.check(installedVersion)
                    if (update == null) Toast.makeText(context, R.string.update_latest, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = (e as? AppUpdateException)?.messageRes ?: R.string.update_check_failed
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } finally {
                    checking = false
                }
            }
        },
    )

    AppUpdateDialogs(update, onDismiss = { update = null }, onDownloadingChange = { downloading = it })
}

/** 하루 한 번 자동으로 확인한 새 버전을 알리고, 사용자가 원하면 바로 내려받습니다. */
@Composable
internal fun AutomaticUpdatePrompt() {
    val update by AutomaticUpdateCheck.available.collectAsStateWithLifecycle()
    AppUpdateDialogs(update, onDismiss = AutomaticUpdateCheck::dismiss)
}

/**
 * 새 버전 안내, 다운로드 진행, 설치 안내 창을 차례로 보여줍니다.
 * 다운로드는 안내 창을 닫은 뒤에도 이어지므로 이 함수는 [update]가 없어도 계속 구성해 둡니다.
 */
@Composable
private fun AppUpdateDialogs(
    update: AppUpdate?,
    onDismiss: () -> Unit,
    onDownloadingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var download by remember { mutableStateOf<Job?>(null) }
    val downloadProgress = remember { MutableStateFlow(0) }
    var showDownloaded by remember { mutableStateOf(false) }

    if (update != null) {
        GalleryAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_available)) },
            text = { Text(stringResource(R.string.update_available_version, update.versionName)) },
            confirmButton = {
                TextButton(onClick = startDownload@{
                    if (download != null) return@startDownload
                    onDismiss()
                    downloadProgress.value = 0
                    onDownloadingChange(true)
                    download = scope.launch {
                        try {
                            GitHubAppUpdates.download(context, update) { downloadProgress.value = it }
                            // 받은 APK가 있는 폴더를 바로 열어 한 번만 누르면 설치되게 합니다.
                            // 열 수 있는 파일 앱이 없으면 직접 찾아가는 방법을 안내합니다.
                            if (GitHubAppUpdates.openDownloadFolder(context)) {
                                val hint = context.getString(R.string.update_tap_apk, GitHubAppUpdates.apkFileName(update))
                                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                            } else {
                                showDownloaded = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val message = (e as? AppUpdateException)?.messageRes ?: R.string.update_download_failed
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } finally {
                            download = null
                            onDownloadingChange(false)
                        }
                    }
                }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val runningDownload = download
    if (runningDownload != null) {
        val percent by downloadProgress.collectAsStateWithLifecycle()
        val label = if (percent == 100) R.string.update_verifying else R.string.update_downloading
        GalleryAlertDialog(
            onDismissRequest = { runningDownload.cancel() },
            title = { Text(stringResource(label)) },
            text = { LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { runningDownload.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showDownloaded) {
        GalleryAlertDialog(
            onDismissRequest = { showDownloaded = false },
            title = { Text(stringResource(R.string.update_install)) },
            text = { Text(stringResource(R.string.update_open_download)) },
            confirmButton = {
                TextButton(onClick = { showDownloaded = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}
