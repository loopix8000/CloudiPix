package app.cloudipix.gallery.feature.viewer

import android.icu.text.BreakIterator
import android.text.format.DateFormat
import androidx.annotation.StringRes
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.core.media.MediaDetails
import app.cloudipix.gallery.core.media.MetadataEdit
import app.cloudipix.gallery.core.model.LocalStoragePath
import app.cloudipix.gallery.core.model.Medium
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryDialogShape
import app.cloudipix.gallery.ui.component.GallerySheet
import app.cloudipix.gallery.ui.component.IconAction
import app.cloudipix.gallery.ui.component.TextDialog
import app.cloudipix.gallery.ui.component.iconSize
import app.cloudipix.gallery.ui.component.storagePathLabel
import app.cloudipix.gallery.ui.formatSize
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 값이 비었을 때 보여 주는 표시입니다. */
private const val EMPTY_VALUE = "—"

/**
 * 파일 위치 줄들입니다. 원격 경로와 기기 경로가 모두 있으면 둘을 나눠 보여 주고,
 * 하나만 있으면 '경로' 한 줄로 보여 줍니다. 경로는 파일 이름을 뺀 폴더까지입니다.
 */
@Composable
internal fun MediaDetailLocation(details: MediaDetails, fileName: String, row: @Composable (Int, String) -> Unit) {
    val remoteFolder = details.path?.let { path -> path.substring(0, path.lastIndexOf('/') + 1) }
    val deviceFolder = details.localPath?.let { local ->
        storagePathLabel(LocalStoragePath.relative(local.path.substringBeforeLast('/', ""), local.volumeName))
    }
    when {
        remoteFolder != null && deviceFolder != null -> {
            row(R.string.sync_network_path, remoteFolder)
            row(R.string.sync_local_path, deviceFolder)
        }
        else -> (deviceFolder ?: remoteFolder)?.let { row(R.string.details_path, it) }
    }
    if (fileName.isNotEmpty()) row(R.string.details_file_name, fileName)
}

/**
 * 정보 값 하나입니다. 보통은 오른쪽 정렬하고, [wrapAtCharacters]이면 경로처럼 긴 값을
 * 글자 단위로 줄바꿈해 왼쪽 정렬합니다. 이때 화면 낭독기에는 원래 값을 그대로 읽힙니다.
 */
@Composable
internal fun MediaDetailValue(value: String, style: TextStyle, color: Color, modifier: Modifier, wrapAtCharacters: Boolean) {
    if (!wrapAtCharacters) {
        Text(value, style = style, color = color, textAlign = TextAlign.End, modifier = modifier)
        return
    }
    val breakable = remember(value) { value.breakableAtEveryCharacter() }
    Text(
        breakable,
        style = style.copy(lineBreak = LineBreak.Simple),
        color = color,
        textAlign = TextAlign.Left,
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(value) },
    )
}

/** 글자(문자소) 사이마다 폭 없는 공백을 넣습니다. 결합 문자와 이모지는 쪼개지 않습니다. */
private fun String.breakableAtEveryCharacter(): String {
    val source = this
    val graphemes = BreakIterator.getCharacterInstance().apply { setText(source) }
    return buildString(source.length * 2) {
        var from = graphemes.first()
        var to = graphemes.next()
        while (to != BreakIterator.DONE) {
            if (from > 0) append('​')
            append(source, from, to)
            from = to
            to = graphemes.next()
        }
    }
}

private enum class EditTarget { Date, Time, Description, Author }

/**
 * 촬영 일시, 설명, 작성자, 위치를 보여 주고 고치는 시트입니다. 바꾼 항목만 [MetadataEdit]에 담아
 * 저장하며, 편집할 수 없는 형식이면 읽기 전용 안내를 보여 줍니다.
 */
@Composable
fun MetadataSheet(
    medium: Medium,
    details: MediaDetails,
    onSave: (MetadataEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    // 원격 항목은 서버 색인보다 파일의 EXIF 촬영 시각을 우선합니다.
    val initialTaken = if (medium.isRemote) details.taken ?: medium.dateTaken else medium.dateTaken
    var taken by remember(medium.id, initialTaken) { mutableLongStateOf(initialTaken) }
    var description by remember(details) { mutableStateOf(details.description) }
    var author by remember(details) { mutableStateOf(details.author) }
    var dropLocation by remember(details) { mutableStateOf(false) }
    var target by remember { mutableStateOf<EditTarget?>(null) }

    val changes = MetadataEdit(
        taken = taken.takeIf { it != initialTaken },
        description = description.takeIf { it != details.description },
        author = author.takeIf { it != details.author },
        removeLocation = dropLocation,
    )
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val moment = taken.atSystemZone()
    val editable = details.editable
    fun editAction(of: EditTarget): (() -> Unit)? = if (editable) ({ target = of }) else null

    // 항목이 화면 높이를 넘는 경우가 많아 반만 펼치면 저장 버튼까지 가려집니다. 처음부터 끝까지 펼칩니다.
    GallerySheet(stringResource(R.string.action_metadata), onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.base)) {
            MetadataRow(R.string.metadata_date, dateFormat.format(moment), onEdit = editAction(EditTarget.Date))
            MetadataRow(R.string.metadata_time, timeFormat.format(moment), onEdit = editAction(EditTarget.Time))
            MetadataRow(R.string.metadata_description, description.ifEmpty { EMPTY_VALUE }, onEdit = editAction(EditTarget.Description))
            MetadataRow(R.string.metadata_author, author.ifEmpty { EMPTY_VALUE }, onEdit = editAction(EditTarget.Author))

            val location = details.location
            if (location != null && !dropLocation) {
                MetadataLine(R.string.metadata_location, location) {
                    if (editable) {
                        IconAction(
                            ViewerIcons.Close,
                            onClick = { dropLocation = true },
                            label = stringResource(R.string.metadata_remove_location),
                            tint = GalleryTheme.colors.textFaint,
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.details_title))
            val facts = listOf(
                R.string.details_camera to details.camera,
                R.string.details_lens to details.lens,
                R.string.details_exposure to details.exposure,
                R.string.details_focal_length to details.focalLength,
                R.string.details_dimensions to details.dimensions,
            )
            for ((label, value) in facts) {
                if (value != null) MetadataRow(label, value)
            }
            MetadataRow(R.string.details_size, formatSize(medium.size))
            MediaDetailLocation(details, medium.name) { label, value -> MetadataLine(label, value, wrapAtCharacters = true) }

            if (editable) {
                DialogButtons(
                    enabled = changes != MetadataEdit(),
                    onDismiss = onDismiss,
                    onConfirm = { onSave(changes) },
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                    confirmLabel = stringResource(R.string.action_save),
                )
            } else {
                Text(
                    stringResource(R.string.metadata_read_only),
                    modifier = Modifier.padding(horizontal = Spacing.screen).padding(top = 18.dp),
                    style = GalleryTheme.type.meta,
                    color = GalleryTheme.colors.textFaint,
                )
            }
        }
    }

    val dismissEditor = { target = null }
    when (target) {
        EditTarget.Date -> DatePickDialog(moment, onPick = { taken = it; dismissEditor() }, onDismiss = dismissEditor)
        EditTarget.Time -> TimePickDialog(moment, onPick = { taken = it; dismissEditor() }, onDismiss = dismissEditor)
        EditTarget.Description -> TextDialog(
            title = stringResource(R.string.metadata_description),
            initial = description,
            isValid = { it.trim() != description },
            onConfirm = { description = it.trim(); dismissEditor() },
            onDismiss = dismissEditor,
        )
        EditTarget.Author -> TextDialog(
            title = stringResource(R.string.metadata_author),
            initial = author,
            isValid = { it.trim() != author },
            onConfirm = { author = it.trim(); dismissEditor() },
            onDismiss = dismissEditor,
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = Spacing.screen).padding(top = 18.dp, bottom = Spacing.base),
        style = GalleryTheme.type.section,
        color = GalleryTheme.colors.textFaint,
    )
}

/** 누르면 편집할 수 있는 줄입니다. 편집할 수 있으면 오른쪽에 연필 아이콘을 둡니다. */
@Composable
private fun MetadataRow(@StringRes label: Int, value: String, onEdit: (() -> Unit)? = null) {
    val tap = Modifier.clickable(enabled = onEdit != null) { onEdit?.invoke() }
    MetadataLine(label, value, tap) {
        if (onEdit != null) EditMark(ViewerIcons.Edit)
    }
}

@Composable
private fun MetadataLine(
    @StringRes label: Int,
    value: String,
    modifier: Modifier = Modifier,
    wrapAtCharacters: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val colors = GalleryTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), style = GalleryTheme.type.body, color = colors.textMuted)
        MediaDetailValue(value, GalleryTheme.type.body, colors.text, Modifier.weight(1f), wrapAtCharacters)
        trailing()
    }
}

@Composable
private fun EditMark(icon: ImageVector) {
    Box(Modifier.size(GalleryTheme.hitTarget), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = GalleryTheme.colors.textFaint, modifier = Modifier.iconSize(16.dp))
    }
}

/**
 * 날짜를 고릅니다. DatePicker는 UTC 자정 기준 밀리초를 주고받으므로 날짜만 주고받고,
 * 시각과 시간대는 원래 값을 그대로 둡니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(moment: ZonedDateTime, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val pickerColors = DatePickerDefaults.colors(containerColor = GalleryTheme.colors.dialogSurface)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = moment.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DialogButtons(
                enabled = state.selectedDateMillis != null,
                onDismiss = onDismiss,
                onConfirm = {
                    val selected = state.selectedDateMillis ?: return@DialogButtons
                    val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(moment.with(day).toInstant().toEpochMilli())
                },
            )
        },
        shape = GalleryDialogShape,
        colors = pickerColors,
    ) {
        DatePicker(state, colors = pickerColors, showModeToggle = false)
    }
}

/** 시각을 고릅니다. 날짜·초·시간대는 원래 값을 그대로 둡니다. 12/24시간 표시는 기기 설정을 따릅니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(moment: ZonedDateTime, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val colors = GalleryTheme.colors
    val state = rememberTimePickerState(
        initialHour = moment.hour,
        initialMinute = moment.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    val pickerColors = TimePickerDefaults.colors(
        clockDialColor = colors.surface,
        selectorColor = colors.accent,
        periodSelectorBorderColor = colors.hairline,
        periodSelectorSelectedContainerColor = colors.accent,
        periodSelectorSelectedContentColor = colors.onAccent,
        periodSelectorUnselectedContentColor = colors.textMuted,
        timeSelectorSelectedContainerColor = colors.accent,
        timeSelectorSelectedContentColor = colors.onAccent,
        timeSelectorUnselectedContainerColor = colors.surface,
        timeSelectorUnselectedContentColor = colors.text,
    )
    DialogCard(stringResource(R.string.metadata_time), onDismiss) {
        TimePicker(state, Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp), colors = pickerColors)
        DialogButtons(
            enabled = true,
            onDismiss = onDismiss,
            onConfirm = { onPick(moment.withHour(state.hour).withMinute(state.minute).toInstant().toEpochMilli()) },
        )
    }
}

private fun Long.atSystemZone(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
