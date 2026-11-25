package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.ui.theme.FavoriteRed
import app.cloudipix.gallery.ui.theme.GalleryTheme

val BarAction.icon: ImageVector
    get() = when (this) {
        BarAction.Share -> GalleryIcons.Share
        BarAction.Favorite -> GalleryIcons.Heart
        BarAction.Edit -> GalleryIcons.Edit
        BarAction.SetAs -> GalleryIcons.SetAs
        BarAction.Copy -> GalleryIcons.Duplicate
        BarAction.Move -> GalleryIcons.Move
        BarAction.Rename -> GalleryIcons.Rename
        BarAction.CopyPath -> GalleryIcons.Link
        BarAction.Metadata -> GalleryIcons.Tag
        BarAction.Delete -> GalleryIcons.Trash
    }

private fun BarAction.displayIcon(favoriteActive: Boolean): ImageVector =
    if (favoriteActive) GalleryIcons.HeartFilled else icon

@Composable
fun ActionBar(
    layout: BarLayout,
    onAction: (BarAction) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GalleryTheme.colors.text,
    favoritesSelected: Boolean = false,
) {
    fun removesFavorite(action: BarAction) = favoritesSelected && action == BarAction.Favorite

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fitted = layout.fitting(actionBarCapacity(maxWidth))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fitted.bar.forEach { action ->
                val active = removesFavorite(action)
                IconAction(
                    icon = action.displayIcon(active),
                    onClick = { onAction(action) },
                    label = stringResource(if (active) R.string.action_remove_favorite else action.labelRes),
                    tint = if (active) FavoriteRed else tint,
                )
            }
            ActionOverflowMenu(fitted.menu, onAction, tint, favoritesSelected)
        }
    }
}

@Composable
private fun actionBarCapacity(width: Dp): Int = (width / (GalleryTheme.hitTarget + 12.dp)).toInt()

@Composable
private fun ActionOverflowMenu(
    actions: List<BarAction>,
    onAction: (BarAction) -> Unit,
    tint: Color,
    favoritesSelected: Boolean,
) {
    if (actions.isEmpty()) return
    OverflowMenu(tint = tint) { dismiss ->
        actions.forEach { action ->
            val active = favoritesSelected && action == BarAction.Favorite
            MenuEntry(
                label = stringResource(if (active) R.string.action_remove_favorite else action.labelRes),
                icon = action.displayIcon(active),
                iconTint = if (active) FavoriteRed else GalleryTheme.colors.textMuted,
            ) {
                dismiss()
                onAction(action)
            }
        }
    }
}
