package app.cloudipix.gallery.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.cloudipix.gallery.core.update.GitHubAppUpdates
import app.cloudipix.gallery.ui.component.GalleryAlertDialog
import java.io.File
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
    var download by remember { mutableStateOf<Job?>(null) }
    val downloadProgress = remember { MutableStateFlow(0) }
    var readyApk by remember { mutableStateOf<File?>(null) }
    var showPermission by remember { mutableStateOf(false) }

    fun showMessage(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun install(apk: File) {
        if (!apk.isFile) {
            readyApk = null
            showMessage(R.string.update_apk_removed)
        } else if (!context.packageManager.canRequestPackageInstalls()) {
            showPermission = true
        } else {
            try {
                context.startActivity(GitHubAppUpdates.installIntent(context, apk))
            } catch (_: Exception) {
                showMessage(R.string.update_install_failed)
            }
        }
    }

    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) readyApk?.let(::install)
    }

    ListItem(
        headlineContent = {
            val label = when {
                checking -> R.string.update_checking
                readyApk != null -> R.string.update_install
                else -> R.string.update_check
            }
            Text(stringResource(label))
        },
        trailingContent = {
            if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        },
        modifier = Modifier.clickable(enabled = !checking && download == null, role = Role.Button) {
            if (checking || download != null) return@clickable
            val apk = readyApk
            if (apk != null) {
                install(apk)
            } else {
                checking = true
                scope.launch {
                    try {
                        update = GitHubAppUpdates.check(installedVersion)
                        if (update == null) showMessage(R.string.update_latest)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showMessage((e as? AppUpdateException)?.messageRes ?: R.string.update_check_failed)
                    } finally {
                        checking = false
                    }
                }
            }
        },
    )

    val available = update
    if (available != null) {
        GalleryAlertDialog(
            onDismissRequest = { update = null },
            title = { Text(stringResource(R.string.update_available)) },
            text = { Text(stringResource(R.string.update_available_version, available.versionName)) },
            confirmButton = {
                TextButton(onClick = startDownload@{
                    if (update == null || download != null) return@startDownload
                    update = null
                    downloadProgress.value = 0
                    download = scope.launch {
                        try {
                            val apk = GitHubAppUpdates.download(context, available) { downloadProgress.value = it }
                            readyApk = apk
                            install(apk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            showMessage((e as? AppUpdateException)?.messageRes ?: R.string.update_download_failed)
                        } finally {
                            download = null
                        }
                    }
                }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text(stringResource(R.string.action_cancel)) }
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

    if (showPermission) {
        GalleryAlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text(stringResource(R.string.update_install)) },
            text = { Text(stringResource(R.string.update_permission)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermission = false
                    val packageUri = Uri.fromParts("package", context.packageName, null)
                    try {
                        permissionSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
                    } catch (_: Exception) {
                        showMessage(R.string.update_install_failed)
                    }
                }) { Text(stringResource(R.string.permission_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermission = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
