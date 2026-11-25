package app.cloudipix.gallery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.SettingsIcon
import app.cloudipix.gallery.ui.theme.GalleryTheme

internal val SettingsRowInset = 12.dp
internal val SettingsRowVerticalInset = 12.dp
private val SettingsScreenInset = 12.dp

@Composable
internal fun SettingsSurface(
    title: String,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GalleryTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        Modifier.fillMaxSize().background(colors.settingsBackground).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 56.dp)
                .padding(start = 4.dp, end = SettingsScreenInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GalleryIcons.Back, stringResource(R.string.cloudipix_back), Modifier.rotate(if (rtl) 180f else 0f), tint = colors.text)
            }
            Text(title, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
                color = colors.text, style = GalleryTheme.type.title)
        }
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)
                .verticalScroll(scrollState).padding(top = 6.dp, bottom = SettingsScreenInset),
            verticalArrangement = Arrangement.spacedBy(SettingsScreenInset),
            content = content,
        )
    }
}

@Composable
internal fun SettingsOverview(
    onOpenAdvanced: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenAbout: () -> Unit,
    primarySettings: @Composable () -> Unit,
) {
    primarySettings()
    SettingsGroup(stringResource(R.string.settings_group_app)) {
        SettingsMenuRow(R.string.remote_sources, R.string.remote_settings_hint, SettingsIcon.Cloud, onOpenCloud)
        SettingsMenuRow(R.string.settings_page_advanced, R.string.settings_page_advanced_hint, SettingsIcon.Advanced, onOpenAdvanced)
        SettingsMenuRow(R.string.setting_about, R.string.setting_about_hint, SettingsIcon.About, onOpenAbout)
    }
}

@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = GalleryTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsScreenInset)) {
        if (title != null) {
            Text(
                title,
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 5.dp).semantics { heading() },
                color = colors.text,
                style = GalleryTheme.type.section,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(shape).background(colors.settingsSurface).padding(vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsMenuRow(@StringRes title: Int, @StringRes description: Int, icon: SettingsIcon, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = SettingsRowInset, vertical = SettingsRowVerticalInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTonalIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), color = colors.text, style = GalleryTheme.type.body)
            Text(stringResource(description), color = colors.textMuted, style = GalleryTheme.type.meta)
        }
        Icon(GalleryIcons.Chevron, null, Modifier.size(16.dp).rotate(if (rtl) 90f else -90f), tint = colors.textMuted)
    }
}

@Composable
private fun SettingsTonalIcon(icon: SettingsIcon) {
    val colors = GalleryTheme.colors
    val light = colors.settingsSurface.luminance() > 0.5f
    val container = icon.tint.copy(alpha = if (light) 0.18f else 0.36f).compositeOver(colors.settingsSurface)
    val ink = colors.text.copy(alpha = if (light) 0.47f else 0.78f).compositeOver(icon.tint)
    Box(Modifier.size(36.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon.image, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
    }
}
