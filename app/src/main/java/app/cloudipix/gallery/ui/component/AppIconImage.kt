package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import app.cloudipix.gallery.R

@Composable
internal fun AppIconImage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        requireNotNull(context.getDrawable(R.mipmap.ic_launcher)).toBitmap().asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier = modifier)
}
