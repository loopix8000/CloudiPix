package app.cloudipix.gallery.feature.settings

import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import app.cloudipix.gallery.core.settings.Language
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.heightIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.model.Grouping
import app.cloudipix.gallery.core.model.ScrollDirection
import app.cloudipix.gallery.core.model.SortKey
import app.cloudipix.gallery.core.settings.GallerySettings
import app.cloudipix.gallery.feature.permission.AllFilesAccessRequest
import app.cloudipix.gallery.feature.permission.mediaManagementSettingsIntent
import app.cloudipix.gallery.feature.permission.supportsMediaManagement
import app.cloudipix.gallery.ui.component.AccentHsl
import app.cloudipix.gallery.ui.component.AccentPicker
import app.cloudipix.gallery.feature.security.LocalGalleryAccess
import app.cloudipix.gallery.feature.security.LocalGalleryLocks
import app.cloudipix.gallery.feature.viewer.DefaultPhotoEditorDialog
import app.cloudipix.gallery.feature.viewer.defaultPhotoEditorLabel
import app.cloudipix.gallery.ui.component.DropdownSetting
import app.cloudipix.gallery.ui.component.GalleryAlertDialog
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.component.MenuStepper
import app.cloudipix.gallery.ui.component.SegmentedPicker
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.component.toAccentHsl
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.Accent
import app.cloudipix.gallery.ui.theme.DetailsPlacement
import app.cloudipix.gallery.ui.theme.GalleryFont
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.TextScale
import app.cloudipix.gallery.ui.theme.ThemeMode
import app.cloudipix.gallery.ui.theme.ScreenTheme
import app.cloudipix.gallery.ui.SettingsGroup
import app.cloudipix.gallery.ui.SettingsOverview
import app.cloudipix.gallery.ui.SettingsRowInset
import app.cloudipix.gallery.ui.SettingsRowVerticalInset
import app.cloudipix.gallery.ui.SettingsSurface

private val COLUMN_RANGE = 2..6

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenActionBars: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenRemoteSources: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val overviewScroll = rememberScrollState()
    val navigateBack = { if (showAdvanced) showAdvanced = false else onBack() }
    BackHandler(enabled = showAdvanced, onBack = navigateBack)
    LaunchedEffect(showAdvanced) {
        if (showAdvanced) viewModel.refreshThumbnailCacheUsage()
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsFileBusy by viewModel.settingsFileBusy.collectAsStateWithLifecycle()
    val syncRestoreAccess by viewModel.syncRestoreAccess.collectAsStateWithLifecycle()
    val thumbnailCache by viewModel.thumbnailCacheUsage.collectAsStateWithLifecycle()
    val thumbnailCacheBusy by viewModel.thumbnailCacheBusy.collectAsStateWithLifecycle()
    var choosingThumbnailCacheLimit by rememberSaveable { mutableStateOf(false) }
    var thumbnailCacheFailed by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val context = LocalContext.current
    var importNotes by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var syncAccessFailed by rememberSaveable { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportSettings(uri) { saved ->
            val message = if (saved) R.string.settings_export_saved else R.string.settings_export_failed
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSettings(uri) { result ->
            if (result == null) {
                Toast.makeText(context, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
            } else {
                importNotes = arrayListOf<Int>().apply {
                    if (!result.syncRestored) add(R.string.settings_import_sync_review)
                    if (!result.cacheRestored) add(R.string.settings_import_cache_review)
                    if (!result.thumbnailCacheRestored) add(R.string.settings_import_thumbnail_cache_review)
                    if (!result.appLockMatches) add(R.string.settings_import_lock_review)
                }
                if (importNotes.isEmpty() && viewModel.syncRestoreAccess.value.isEmpty()) {
                    Toast.makeText(context, R.string.settings_import_applied, Toast.LENGTH_SHORT).show()
                }
                if (result.languageChanged) activity?.recreate()
            }
        }
    }
    val accessRequest = syncRestoreAccess.firstOrNull()
    var requestingAllFiles by remember { mutableStateOf(false) }
    // 모든 파일 접근이 허용되면 백업에 적힌 경로를 폴더 선택 없이 그대로 복원한다.
    if (requestingAllFiles && accessRequest != null) AllFilesAccessRequest(
        onGranted = {
            requestingAllFiles = false
            val uri = accessRequest.initialUri
            if (uri == null) syncAccessFailed = true
            else viewModel.allowSyncRestore(uri) { restored ->
                syncAccessFailed = restored == null
                if (restored == false && R.string.settings_import_sync_review !in importNotes) {
                    importNotes = ArrayList(importNotes + R.string.settings_import_sync_review)
                }
                if (restored == true && viewModel.syncRestoreAccess.value.isEmpty() && importNotes.isEmpty()) {
                    Toast.makeText(context, R.string.settings_import_applied, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDismiss = { requestingAllFiles = false },
    ) else if (accessRequest != null) {
        val dismiss = {
            if (!settingsFileBusy) {
                syncAccessFailed = false
                viewModel.closeSyncRestore()
            }
        }
        GalleryAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.settings_import_sync_access)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_restore_all_files, storagePathLabel(accessRequest.localPath)))
                    if (syncAccessFailed) Text(stringResource(R.string.settings_import_sync_access_failed))
                }
            },
            dismissButton = {
                TextButton(enabled = !settingsFileBusy, onClick = dismiss) { Text(stringResource(R.string.action_close)) }
            },
            confirmButton = {
                TextButton(enabled = !settingsFileBusy, onClick = {
                    syncAccessFailed = false
                    requestingAllFiles = true
                }) { Text(stringResource(R.string.media_management_open_settings)) }
            },
        )
    } else if (choosingThumbnailCacheLimit && thumbnailCache != null) {
        val dismiss = {
            choosingThumbnailCacheLimit = false
            thumbnailCacheFailed = false
        }
        CacheLimitDialog(
            currentLimitMiB = thumbnailCache!!.limitMiB,
            hint = stringResource(R.string.setting_thumbnail_cache_limit_hint),
            busy = thumbnailCacheBusy,
            error = if (thumbnailCacheFailed) stringResource(R.string.remote_storage_error) else null,
            onDismiss = dismiss,
            onSave = { limit ->
                thumbnailCacheFailed = false
                viewModel.setThumbnailCacheLimit(limit) { saved -> if (saved) dismiss() else thumbnailCacheFailed = true }
            },
        )
    } else if (importNotes.isNotEmpty()) {
        val dismiss = { importNotes = arrayListOf() }
        GalleryAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.settings_import_applied)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    importNotes.forEach { Text(stringResource(it)) }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }

    key(showAdvanced) {
        SettingsSurface(
            title = stringResource(if (showAdvanced) R.string.settings_page_advanced else R.string.settings_title),
            onBack = navigateBack,
            scrollState = if (showAdvanced) rememberScrollState() else overviewScroll,
        ) {
            if (!showAdvanced) {
                SettingsOverview(
                    onOpenAdvanced = { showAdvanced = true },
                    onOpenCloud = onOpenRemoteSources,
                    onOpenAbout = onOpenAbout,
                ) { BasicSettings(settings, viewModel, onLanguageApplied = { activity?.recreate() }) }
                return@SettingsSurface
            }

            SettingsGroup(stringResource(R.string.section_folders)) {
                ToggleRow(
                    label = stringResource(R.string.setting_folder_name),
                    checked = settings.showFolderNames,
                    onChange = viewModel::setShowFolderNames,
                )
                ToggleRow(
                    stringResource(R.string.setting_folder_count),
                    stringResource(R.string.setting_folder_count_hint),
                    settings.showFolderCount,
                    viewModel::setShowFolderCount,
                )
                ToggleRow(
                    stringResource(R.string.setting_folder_size),
                    stringResource(R.string.setting_folder_size_hint),
                    settings.showFolderSize,
                    viewModel::setShowFolderSize,
                )
                ToggleRow(
                    label = stringResource(R.string.setting_cloud_folder_icons),
                    checked = settings.showCloudFolderIcons,
                    onChange = viewModel::setShowCloudFolderIcons,
                )
                if (settings.showCloudFolderIcons) {
                    ToggleRow(
                        label = stringResource(R.string.setting_cloud_folder_icons_only_while_syncing),
                        checked = settings.showCloudFolderIconsOnlyWhileSyncing,
                        onChange = viewModel::setShowCloudFolderIconsOnlyWhileSyncing,
                    )
                }
                SettingDropdown(
                    label = R.string.setting_details_position,
                    options = DetailsPlacement.entries,
                    selected = settings.detailsPlacement,
                    onSelect = viewModel::setDetailsPlacement,
                ) { stringResource(it.labelRes) }
                LinkRow(
                    stringResource(R.string.setting_ignored),
                    stringResource(R.string.setting_ignored_hint),
                    onClick = onOpenIgnored,
                )
            }

            SettingsGroup(stringResource(R.string.settings_section_viewer)) {
                LinkRow(
                    stringResource(R.string.section_actions),
                    stringResource(R.string.setting_actions_hint),
                    onClick = onOpenActionBars,
                )
                ToggleRow(
                    stringResource(R.string.setting_volume_button_paging),
                    stringResource(R.string.setting_volume_button_paging_hint),
                    settings.volumeButtonPaging,
                    viewModel::setVolumeButtonPaging,
                )
                ToggleRow(
                    stringResource(R.string.setting_panorama_all),
                    stringResource(R.string.setting_panorama_all_hint),
                    settings.panoramaForAll,
                    viewModel::setPanoramaForAll,
                )
            }

            val access = LocalGalleryAccess.current
            val locks = LocalGalleryLocks.current
            SettingsGroup(stringResource(R.string.settings_section_privacy)) {
                ToggleRow(
                    stringResource(R.string.setting_app_lock),
                    stringResource(R.string.setting_app_lock_hint),
                    locks.app,
                    access::changeAppLock,
                )
                ToggleRow(
                    label = stringResource(R.string.setting_hide_recents_preview),
                    hint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        stringResource(R.string.setting_hide_recents_preview_legacy_hint)
                    } else null,
                    checked = locks.hideRecentsPreview,
                    onChange = access::setHideRecentsPreview,
                )
                ToggleRow(
                    stringResource(R.string.setting_confirm_trash),
                    stringResource(R.string.setting_confirm_trash_hint),
                    settings.confirmTrash,
                    viewModel::setConfirmTrash,
                )
                if (supportsMediaManagement && activity != null) {
                    LinkRow(
                        stringResource(R.string.media_management_title),
                        stringResource(R.string.media_management_hint),
                    ) {
                        try {
                            activity.startActivity(activity.mediaManagementSettingsIntent())
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(activity, R.string.media_management_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            thumbnailCache?.let { cache ->
                SettingsGroup(stringResource(R.string.settings_section_cache)) {
                    LinkRow(
                        stringResource(R.string.setting_thumbnail_cache),
                        stringResource(R.string.setting_thumbnail_cache_hint, formatSize(cache.usedBytes), cache.limitMiB),
                        enabled = !thumbnailCacheBusy,
                    ) { choosingThumbnailCacheLimit = true }
                    LinkRow(
                        stringResource(R.string.setting_clear_thumbnail_cache),
                        stringResource(R.string.setting_clear_thumbnail_cache_hint),
                        enabled = !thumbnailCacheBusy && cache.usedBytes > 0L,
                    ) {
                        viewModel.clearThumbnailCache { cleared ->
                            if (!cleared) Toast.makeText(context, R.string.remote_storage_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_section_backup)) {
                LinkRow(
                    stringResource(R.string.settings_export),
                    stringResource(R.string.settings_export_hint),
                    enabled = !settingsFileBusy,
                ) {
                    try {
                        exportPicker.launch("cloudipix-settings.json")
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.settings_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                LinkRow(
                    stringResource(R.string.settings_import),
                    stringResource(R.string.settings_import_hint),
                    enabled = !settingsFileBusy,
                ) {
                    try {
                        importPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicSettings(settings: GallerySettings, viewModel: SettingsViewModel, onLanguageApplied: () -> Unit) {
    var choosingEditor by rememberSaveable { mutableStateOf(false) }
    var customAccent by rememberSaveable { mutableStateOf(false) }
    val savedAccent = remember(settings.accentArgb) { Color(settings.accentArgb).toAccentHsl() }
    // Keep slider positions independent from the quantized colors emitted by the settings store.
    var accentDraft by remember { mutableStateOf<AccentHsl?>(null) }
    SettingsGroup(stringResource(R.string.settings_quick_controls)) {
        SettingDropdown(
            label = R.string.setting_gallery_theme,
            options = ThemeMode.entries,
            selected = settings.themeMode,
            onSelect = viewModel::setThemeMode,
        ) { stringResource(it.labelRes) }
        SettingDropdown(
            label = R.string.setting_settings_theme,
            options = ScreenTheme.entries,
            selected = settings.settingsTheme,
            onSelect = viewModel::setSettingsTheme,
        ) { stringResource(it.labelRes) }
        AccentRow(
            argb = accentDraft?.argb ?: settings.accentArgb,
            custom = customAccent,
            onSelect = {
                accentDraft = Color(it).toAccentHsl()
                viewModel.setAccent(it)
            },
            onToggleCustom = { customAccent = !customAccent },
        )
        AnimatedVisibility(
            visible = customAccent,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            AccentPicker(
                selection = accentDraft ?: savedAccent,
                onChange = { update -> accentDraft = update(accentDraft ?: savedAccent) },
                onChangeFinished = { accentDraft?.let { viewModel.setAccent(it.argb) } },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        SettingDropdown(
            label = R.string.setting_font,
            options = GalleryFont.entries,
            selected = settings.font,
            onSelect = viewModel::setFont,
        ) { font -> font.labelRes?.let { stringResource(it) } ?: font.label }
        SettingDropdown(
            label = R.string.setting_language,
            options = Language.options,
            selected = settings.language,
            onSelect = { viewModel.setLanguage(it, onLanguageApplied) },
        ) { if (it == Language.System) stringResource(R.string.follow_system) else it.label }
        PickerRow(stringResource(R.string.setting_interface_size)) {
            SegmentedPicker(TextScale.entries, settings.textScale, viewModel::setTextScale) { it.label }
        }
        LinkRow(stringResource(R.string.photo_editor_default_app), defaultPhotoEditorLabel(settings.photoEditorPackage)) {
            choosingEditor = true
        }
    }

    SettingsGroup(stringResource(R.string.section_grid)) {
        SettingDropdown(
            label = R.string.setting_sort_all,
            options = SortKey.general,
            selected = settings.sorting.key,
            onSelect = viewModel::setSortKey,
        ) { stringResource(it.labelRes) }
        SettingDropdown(
            label = R.string.settings_sort_direction,
            options = listOf(true, false),
            selected = settings.sorting.descending,
            onSelect = viewModel::setSortDescending,
        ) { stringResource(if (it) R.string.sort_descending else R.string.sort_ascending) }
        SettingDropdown(
            label = R.string.setting_group_media,
            options = Grouping.entries,
            selected = settings.grouping,
            onSelect = viewModel::setGrouping,
        ) { stringResource(it.labelRes) }
        MenuStepper(
            label = stringResource(R.string.setting_folder_columns),
            value = settings.folderColumns,
            range = COLUMN_RANGE,
            modifier = Modifier.padding(horizontal = SettingsRowInset),
            onChange = viewModel::setFolderColumns,
        )
        MenuStepper(
            label = stringResource(R.string.setting_media_columns),
            value = settings.mediaColumns,
            range = COLUMN_RANGE,
            modifier = Modifier.padding(horizontal = SettingsRowInset),
            onChange = viewModel::setMediaColumns,
        )
        PickerRow(stringResource(R.string.setting_scroll_direction)) {
            SegmentedPicker(ScrollDirection.entries, settings.scrollDirection, viewModel::setScrollDirection) {
                stringResource(it.labelRes)
            }
        }
        ToggleRow(
            label = stringResource(R.string.setting_show_recent),
            checked = settings.showRecent,
            onChange = viewModel::setShowRecent,
        )
    }

    SettingsGroup(stringResource(R.string.section_video)) {
        ToggleRow(
            label = stringResource(R.string.setting_video_duration),
            hint = stringResource(R.string.setting_video_duration_hint),
            checked = settings.showVideoDuration,
            onChange = viewModel::setShowVideoDuration,
        )
        ToggleRow(
            stringResource(R.string.setting_autoplay),
            stringResource(R.string.setting_autoplay_hint),
            settings.autoplayNextVideo,
            viewModel::setAutoplayNextVideo,
        )
        ToggleRow(
            stringResource(R.string.setting_background_playback),
            stringResource(R.string.setting_background_playback_hint),
            settings.backgroundPlayback,
            viewModel::setBackgroundPlayback,
        )
    }

    if (choosingEditor) DefaultPhotoEditorDialog(
        currentPackage = settings.photoEditorPackage,
        onSelect = {
            viewModel.setPhotoEditorPackage(it)
            choosingEditor = false
        },
        onDismiss = { choosingEditor = false },
    )
}

@Composable
private fun <T> SettingDropdown(
    @androidx.annotation.StringRes label: Int,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
) {
    val title = stringResource(label)
    DropdownSetting(
        title, options, selected, onSelect,
        modifier = Modifier.padding(horizontal = SettingsRowInset),
        optionLabel = optionLabel,
    )
}

@Composable
internal fun LinkRow(
    label: String,
    hint: String?,
    horizontalPadding: Dp = SettingsRowInset,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = GalleryTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = SettingsRowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Box(Modifier.padding(end = 12.dp)) { leadingIcon() }
        Column(Modifier.weight(1f)) {
            Text(label, style = GalleryTheme.type.body, color = colors.text)
            if (hint != null) Text(hint, style = GalleryTheme.type.meta, color = colors.textMuted)
        }
        Icon(
            GalleryIcons.Chevron,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(16.dp).rotate(if (rtl) 90f else -90f),
        )
    }
}

@Composable
private fun PickerRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .padding(horizontal = SettingsRowInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = GalleryTheme.colors.text, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun AccentRow(argb: Int, custom: Boolean, onSelect: (Int) -> Unit, onToggleCustom: () -> Unit) {
    val colors = GalleryTheme.colors
    val label = stringResource(R.string.setting_accent)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .padding(horizontal = SettingsRowInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = GalleryTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
        Accent.entries.forEach { preset ->
            val selected = preset.color.toArgb() == argb
            Box(
                modifier = Modifier
                    .size(if (selected) 26.dp else 22.dp)
                    .clip(CircleShape)
                    .background(preset.color)
                    .clickable { onSelect(preset.color.toArgb()) }
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (custom) Color(argb) else colors.settingsControl)
                .clickable(onClick = onToggleCustom),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (custom) "−" else "+", style = GalleryTheme.type.label, color = if (custom) colors.onAccent else colors.settingsControlMuted)
        }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    hint: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    horizontalPadding: Dp = SettingsRowInset,
) {
    val colors = GalleryTheme.colors
    val onRowTap by rememberUpdatedState { onChange(!checked) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onRowTap() }
            }
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = SettingsRowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(label, style = GalleryTheme.type.body, color = colors.text)
            if (hint != null) Text(hint, style = GalleryTheme.type.meta, color = colors.textMuted)
        }
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 24.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = listOfNotNull(label, hint).joinToString("\n") },
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.requiredSize(width = 52.dp, height = 32.dp).scale(0.75f),
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.accent,
                    checkedThumbColor = colors.onAccent,
                    uncheckedTrackColor = colors.settingsControl,
                    uncheckedThumbColor = colors.textMuted,
                    uncheckedBorderColor = Color.Transparent,
                    disabledCheckedTrackColor = colors.accent.copy(alpha = 0.38f),
                    disabledCheckedThumbColor = colors.onAccent,
                    disabledUncheckedTrackColor = colors.settingsControl,
                    disabledUncheckedThumbColor = colors.textMuted.copy(alpha = 0.38f),
                    disabledUncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}
