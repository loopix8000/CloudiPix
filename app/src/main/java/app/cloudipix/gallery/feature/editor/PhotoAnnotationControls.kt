package app.cloudipix.gallery.feature.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.component.DialogButtons
import app.cloudipix.gallery.ui.component.DialogCard
import app.cloudipix.gallery.ui.component.GalleryIcons
import app.cloudipix.gallery.ui.theme.GalleryTheme

internal enum class AnnotationInk(@StringRes val label: Int, val argb: Int) {
    White(R.string.photo_editor_ink_white, 0xFFFFFFFF.toInt()),
    Black(R.string.photo_editor_ink_black, 0xFF111114.toInt()),
    Red(R.string.photo_editor_ink_red, 0xFFFF4945.toInt()),
    Yellow(R.string.photo_editor_ink_yellow, 0xFFFFD64B.toInt()),
    Blue(R.string.photo_editor_ink_blue, 0xFF43A4FE.toInt()),
}

internal enum class AnnotationWeight(@StringRes val label: Int, val stroke: Float, val text: Float) {
    Small(R.string.photo_editor_size_small, 0.008f, 0.043f),
    Medium(R.string.photo_editor_size_medium, 0.018f, 0.070f),
    Large(R.string.photo_editor_size_large, 0.033f, 0.108f),
}

@Composable
internal fun DrawControls(
    ink: AnnotationInk,
    weight: AnnotationWeight,
    onInk: (AnnotationInk) -> Unit,
    onWeight: (AnnotationWeight) -> Unit,
) {
    val colors = GalleryTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkPicker(ink, onInk)
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnnotationWeight.entries.forEach { item ->
                val selected = weight == item
                val name = stringResource(item.label)
                Box(
                    Modifier.size(36.dp).background(if (selected) colors.surfaceAlt else Color.Transparent, CircleShape)
                        .selectable(selected, role = Role.RadioButton, onClick = { onWeight(item) })
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size((5 + item.ordinal * 4).dp)) {
                        drawCircle(if (selected) colors.accent else colors.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TextControls(
    selected: Boolean,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAdd, modifier = Modifier.height(44.dp)) {
            Icon(GalleryIcons.Add, null, Modifier.size(18.dp))
            Text(stringResource(R.string.photo_editor_add_text), Modifier.padding(start = 6.dp))
        }
        if (selected) {
            TextButton(onClick = onEdit, modifier = Modifier.height(44.dp)) { Text(stringResource(R.string.action_edit)) }
            TextButton(onClick = onDelete, modifier = Modifier.height(44.dp)) { Text(stringResource(R.string.action_delete)) }
        }
    }
}

@Composable
internal fun EditPhotoTextDialog(
    initial: PhotoText?,
    onConfirm: (String, Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var ink by rememberSaveable {
        mutableStateOf(AnnotationInk.entries.firstOrNull { it.argb == initial?.color } ?: AnnotationInk.White)
    }
    var weight by rememberSaveable {
        mutableStateOf(AnnotationWeight.entries.firstOrNull { it.text == initial?.size } ?: AnnotationWeight.Medium)
    }
    DialogCard(stringResource(if (initial == null) R.string.photo_editor_add_text else R.string.photo_editor_edit_text), onDismiss) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(160) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.editor_text)) },
                minLines = 2,
                maxLines = 4,
            )
            InkPicker(ink) { ink = it }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AnnotationWeight.entries.forEach { item ->
                    EditorPill(stringResource(item.label), weight == item) { weight = item }
                }
            }
        }
        DialogButtons(enabled = text.isNotBlank(), onDismiss = onDismiss, onConfirm = {
            onConfirm(text.trim(), ink.argb, weight.text)
        })
    }
}

@Composable
private fun InkPicker(selected: AnnotationInk, onSelect: (AnnotationInk) -> Unit) {
    val colors = GalleryTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        AnnotationInk.entries.forEach { ink ->
            val active = selected == ink
            val name = stringResource(ink.label)
            Box(Modifier.size(34.dp).selectable(active, role = Role.RadioButton, onClick = { onSelect(ink) })
                .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).border(1.5.dp, if (active) colors.accent else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).background(Color(ink.argb), CircleShape).border(1.dp, colors.hairline, CircleShape))
                }
            }
        }
    }
}
