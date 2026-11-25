package app.cloudipix.gallery.feature.security

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.security.LockSettings
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.first

internal val LocalGalleryAccess = staticCompositionLocalOf<GalleryAccess> { error("잠금 화면 설정이 없습니다.") }
internal val LocalGalleryLocks = staticCompositionLocalOf<LockSettings> { error("잠금 설정이 없습니다.") }

@Composable
fun GalleryAccessHost(access: GalleryAccess, content: @Composable () -> Unit) {
    val locks by access.settings.collectAsStateWithLifecycle()
    val savedGallery = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalGalleryAccess provides access, LocalGalleryLocks provides locks) {
        if (locks.app && !access.appUnlocked) {
            LockScreen(R.string.app_locked, access::unlockApp)
        } else {
            savedGallery.SaveableStateProvider("gallery", content)
        }
    }
}

@Composable
internal fun FolderAccessGate(bucketId: Long?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val access = LocalGalleryAccess.current
    val locked = LocalGalleryLocks.current.folders.let { ids ->
        if (bucketId == null) ids.isNotEmpty() else bucketId in ids
    }
    if (locked && !access.hasFolderAccess(bucketId)) {
        LockScreen(
            title = if (bucketId == null) R.string.photo_auth_required else R.string.folder_locked,
            authenticate = { access.unlockFolder(bucketId) },
            onBack = onBack,
        )
    } else content()
}

@Composable
private fun LockScreen(@StringRes title: Int, authenticate: () -> Unit, onBack: (() -> Unit)? = null) {
    val access = LocalGalleryAccess.current
    val colors = GalleryTheme.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(title, lifecycle) {
        lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
        if (access.canAuthenticate()) authenticate()
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(GalleryIcons.Lock, null, tint = colors.text, modifier = Modifier.size(32.dp))
        Text(stringResource(title), style = GalleryTheme.type.title, color = colors.text, textAlign = TextAlign.Center)
        LockAction(R.string.lock_authenticate, authenticate)
        if (!access.canAuthenticate()) LockAction(R.string.open_device_security, access::openDeviceSecurity)
        if (onBack != null) LockAction(R.string.lock_back, onBack)
    }
}

@Composable
private fun LockAction(@StringRes label: Int, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    Text(
        stringResource(label), style = GalleryTheme.type.label, color = colors.accent,
        modifier = Modifier.background(colors.surface).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
