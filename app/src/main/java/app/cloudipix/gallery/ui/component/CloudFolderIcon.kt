package app.cloudipix.gallery.ui.component

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.core.sync.AlbumSyncState

// Lucide 구름 아이콘의 라이선스 전문은 assets/licenses/Lucide-ISC.txt에 있다.
private val CloudPath = PathParser()
    .parsePathString("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    .toPath()
    .asAndroidPath()

private val CloudSyncPath = PathParser()
    .parsePathString(
        "M17 18l-1.535 1.605a5 5 0 0 1-8-1.5 " +
            "M17 22v-4h-4 " +
            "M20.996 15.251A4.5 4.5 0 0 0 17.495 8h-1.79a7 7 0 1 0-12.709 5.607 " +
            "M7 10v4h4 " +
            "M7 14l1.535-1.605a5 5 0 0 1 8 1.5",
    )
    .toPath()
    .asAndroidPath()

private val CloudCheckPath = PathParser()
    .parsePathString("M17 15l-5.5 5.5L9 18 M5.516 16.07A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 3.501 7.327")
    .toPath()
    .asAndroidPath()

internal fun cloudFolderGlyph(syncState: AlbumSyncState?) = when (syncState) {
    AlbumSyncState.Syncing -> CloudSyncPath to R.string.sync_notification_title
    AlbumSyncState.Complete -> CloudCheckPath to R.string.sync_completed
    AlbumSyncState.Incomplete -> CloudPath to R.string.sync_folder_icon
    null -> CloudPath to R.string.remote_folder
}

@Composable
internal fun CloudFolderIcon(
    settings: GallerySettings,
    isRemote: Boolean,
    syncState: AlbumSyncState?,
    modifier: Modifier,
) {
    if (!settings.showCloudFolderIcons) return
    if (settings.showCloudFolderIconsOnlyWhileSyncing && syncState != AlbumSyncState.Syncing) return
    if (!isRemote && syncState == null) return

    val (icon, label) = cloudFolderGlyph(syncState)
    val description = stringResource(label)
    Box(
        modifier.padding(6.dp).size(12.dp)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawWithCache {
                val scale = Matrix().apply { setScale(size.width / 24f, size.height / 24f) }
                val path = Path().also { icon.transform(scale, it) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = size.minDimension * (2f / 24f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = Color.White.copy(alpha = 0.70f).toArgb()
                    setShadowLayer(1.25.dp.toPx(), 0f, 0.75.dp.toPx(), Color.Black.copy(alpha = 0.4f).toArgb())
                }
                onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawPath(path, paint) } }
            },
    )
}
