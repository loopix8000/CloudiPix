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
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.BarAction
import app.cloudipix.gallery.core.model.BarLayout
import app.cloudipix.gallery.ui.theme.FavoriteRed
import app.cloudipix.gallery.ui.theme.GalleryTheme

/** 동작 바와 설정 화면에 쓰는 동작 아이콘입니다. */
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

/** 버튼 사이에 두는 최소 여백입니다. 바에 들어갈 버튼 수를 셀 때 씁니다. */
private val SlotGap = 12.dp

/**
 * 동작 하나를 화면에 어떻게 보일지 정합니다. 선택한 항목이 모두 즐겨찾기면
 * 즐겨찾기 동작은 채운 하트와 '즐겨찾기 해제'로 바뀝니다.
 */
private class ActionLook(val icon: ImageVector, val labelRes: Int, val removesFavorite: Boolean)

private fun BarAction.look(favoritesSelected: Boolean): ActionLook {
    val removes = favoritesSelected && this == BarAction.Favorite
    return if (removes) {
        ActionLook(GalleryIcons.HeartFilled, R.string.action_remove_favorite, removesFavorite = true)
    } else {
        ActionLook(icon, labelRes, removesFavorite = false)
    }
}

/** 너비에 맞춰 동작 버튼을 고르게 놓고, 남는 동작은 더보기 메뉴에 넣습니다. */
@Composable
fun ActionBar(
    layout: BarLayout,
    onAction: (BarAction) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GalleryTheme.colors.text,
    favoritesSelected: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slots = (maxWidth / (GalleryTheme.hitTarget + SlotGap)).toInt()
        val fitted = layout.fitting(slots)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in fitted.bar) {
                val look = action.look(favoritesSelected)
                IconAction(
                    icon = look.icon,
                    onClick = { onAction(action) },
                    label = stringResource(look.labelRes),
                    tint = if (look.removesFavorite) FavoriteRed else tint,
                )
            }
            if (fitted.menu.isNotEmpty()) MoreActions(fitted.menu, onAction, tint, favoritesSelected)
        }
    }
}

@Composable
private fun MoreActions(actions: List<BarAction>, onAction: (BarAction) -> Unit, tint: Color, favoritesSelected: Boolean) {
    OverflowMenu(tint = tint) { dismiss ->
        for (action in actions) {
            val look = action.look(favoritesSelected)
            MenuEntry(
                label = stringResource(look.labelRes),
                icon = look.icon,
                iconTint = if (look.removesFavorite) FavoriteRed else GalleryTheme.colors.textMuted,
            ) {
                dismiss()
                onAction(action)
            }
        }
    }
}
