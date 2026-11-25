package app.cloudipix.gallery.feature.viewer

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmapOrNull
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.core.remote.photoMime
import app.cloudipix.gallery.ui.component.AppIconImage
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ExternalEditor(val component: ComponentName, val label: String, val icon: ImageBitmap?)
private data class PhotoEditors(val mime: String, val external: List<ExternalEditor>)

@Composable
internal fun EditPhotoAppDialog(
    photo: Medium,
    defaultPackage: String?,
    readableUri: suspend () -> Uri,
    onSetDefault: (String) -> Unit,
    onBuiltIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = GalleryTheme.colors
    val scope = rememberCoroutineScope()
    var opening by remember(photo) { mutableStateOf<String?>(null) }
    var tryingDefault by remember(photo) { mutableStateOf(defaultPackage != null) }
    var makeDefault by remember(photo) { mutableStateOf(false) }
    val editors by rememberPhotoEditors(photo)

    suspend fun openExternal(editor: ExternalEditor, mime: String, rememberChoice: Boolean) {
        opening = editor.component.packageName
        try {
            val uri = readableUri()
            val request = Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime)
                .setComponent(editor.component)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            request.clipData = ClipData.newRawUri(null, uri)
            context.startActivity(request)
            if (rememberChoice) onSetDefault(editor.component.packageName)
            onDismiss()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(context, R.string.photo_editor_external_failed, Toast.LENGTH_SHORT).show()
        } finally {
            opening = null
        }
    }

    LaunchedEffect(editors) {
        val available = editors ?: return@LaunchedEffect
        if (!tryingDefault) return@LaunchedEffect
        available.external.firstOrNull { it.component.packageName == defaultPackage }?.let {
            openExternal(it, available.mime, rememberChoice = false)
        }
        tryingDefault = false
    }

    if (tryingDefault) {
        if (photo.isRemote) {
            DialogCard(stringResource(R.string.photo_editor_opening_app), onDismiss) {
                CircularProgressIndicator(Modifier.padding(top = 14.dp).size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                EditorDialogCancel(Modifier.align(Alignment.End), onDismiss)
            }
        }
        return
    }

    DialogCard(stringResource(R.string.photo_editor_choose_app), onDismiss) {
        EditorChoices(editors?.external, opening = opening, onChoose = { packageName ->
            if (packageName == context.packageName) {
                if (makeDefault) onSetDefault(context.packageName)
                onBuiltIn()
            } else {
                val available = editors
                val editor = available?.external?.firstOrNull { it.component.packageName == packageName }
                if (available != null && editor != null) scope.launch { openExternal(editor, available.mime, makeDefault) }
            }
        })
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(makeDefault, enabled = opening == null, role = Role.Checkbox) { makeDefault = it },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(makeDefault, onCheckedChange = null, modifier = Modifier.size(36.dp),
                colors = CheckboxDefaults.colors(checkedColor = colors.accent))
            Text(stringResource(R.string.photo_editor_use_by_default), style = GalleryTheme.type.label, color = colors.text)
        }
        EditorDialogCancel(Modifier.align(Alignment.End), onDismiss)
    }
}

@Composable
internal fun DefaultPhotoEditorDialog(
    currentPackage: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val editors by rememberPhotoEditors(photo = null)
    DialogCard(stringResource(R.string.photo_editor_default_app), onDismiss) {
        EditorChoices(editors?.external, showAlwaysAsk = true, currentPackage = currentPackage, onChoose = onSelect)
        EditorDialogCancel(Modifier.align(Alignment.End), onDismiss)
    }
}

@Composable
internal fun defaultPhotoEditorLabel(packageName: String?): String {
    val context = LocalContext.current
    if (packageName == context.packageName) return stringResource(R.string.photo_editor_built_in)
    val label = remember(context, packageName) {
        packageName?.let {
            val packages = context.packageManager
            try {
                packages.getApplicationInfo(it, 0).loadLabel(packages).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    return label ?: stringResource(R.string.photo_editor_always_ask)
}

@Composable
private fun rememberPhotoEditors(photo: Medium?): State<PhotoEditors?> {
    val context = LocalContext.current
    return produceState<PhotoEditors?>(null, photo) {
        value = withContext(Dispatchers.IO) { context.queryPhotoEditors(photo) }
    }
}

private fun Context.queryPhotoEditors(photo: Medium?): PhotoEditors {
    val localUri = photo?.takeUnless { it.isRemote }?.uri
    val uri = localUri ?: Uri.parse("content://$packageName.sharedphotos/photo")
    val mime = localUri?.let(contentResolver::getType)?.takeIf { it.startsWith("image/") }
        ?: photo?.name?.let(::photoMime)?.takeIf { it.startsWith("image/") }
        ?: "image/*"
    val request = Intent(Intent.ACTION_EDIT).setDataAndType(uri, mime)
    val packages = packageManager
    val installed = packages.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
        .filter { it.activityInfo.exported && it.activityInfo.packageName != packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            val app = it.activityInfo.applicationInfo
            ExternalEditor(
                ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                app.loadLabel(packages).toString(),
                app.loadIcon(packages).toBitmapOrNull(96, 96)?.asImageBitmap(),
            )
        }.sortedBy { it.label.lowercase() }
    return PhotoEditors(mime, installed)
}

@Composable
private fun EditorChoices(
    external: List<ExternalEditor>?,
    opening: String? = null,
    showAlwaysAsk: Boolean = false,
    currentPackage: String? = null,
    onChoose: (String?) -> Unit,
) {
    val ownPackage = LocalContext.current.packageName
    val colors = GalleryTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        if (showAlwaysAsk) {
            EditorChoice(stringResource(R.string.photo_editor_always_ask), true,
                selected = currentPackage == null, onClick = { onChoose(null) }) {}
        }
        EditorChoice(stringResource(R.string.photo_editor_built_in), opening == null,
            selected = showAlwaysAsk && currentPackage == ownPackage, onClick = { onChoose(ownPackage) }) {
            AppIconImage(Modifier.size(28.dp))
        }
        if (external == null) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = colors.accent, strokeWidth = 2.dp)
        } else if (external.isEmpty() && !showAlwaysAsk) {
            Text(stringResource(R.string.photo_editor_no_external_app), Modifier.padding(vertical = 8.dp),
                color = colors.textMuted, style = GalleryTheme.type.label)
        } else {
            external.forEach { editor ->
                val packageName = editor.component.packageName
                EditorChoice(editor.label, opening == null, selected = showAlwaysAsk && currentPackage == packageName,
                    onClick = { onChoose(packageName) }) {
                    if (opening == packageName) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
                    } else if (editor.icon != null) {
                        Image(editor.icon, null, Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDialogCancel(modifier: Modifier, onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss, modifier = modifier) {
        Text(stringResource(R.string.action_cancel), color = GalleryTheme.colors.accent)
    }
}

@Composable
private fun EditorChoice(
    label: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit,
) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(ControlRadius))
            .background(if (selected) colors.settingsControl else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, Modifier.weight(1f), color = colors.text, style = GalleryTheme.type.body)
        if (selected) Icon(GalleryIcons.Check, null, Modifier.size(20.dp), tint = colors.accent)
    }
}
