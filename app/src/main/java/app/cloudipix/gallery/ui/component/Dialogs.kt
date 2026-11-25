package app.cloudipix.gallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.cloudipix.gallery.R
import app.cloudipix.gallery.ui.theme.ControlRadius
import app.cloudipix.gallery.ui.theme.GalleryTheme
import app.cloudipix.gallery.ui.theme.Spacing

/** 시트와 대화상자 뒤를 어둡게 덮는 색입니다. */
private val Scrim = Color.Black.copy(alpha = 0.64f)
private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val ButtonShape = RoundedCornerShape(ControlRadius)
internal val GalleryDialogShape = RoundedCornerShape(12.dp)

/** 시트·대화상자 제목에 쓰는 굵은 본문 글꼴입니다. */
private val titleStyle: TextStyle
    @Composable get() = GalleryTheme.type.body.copy(fontWeight = FontWeight.SemiBold)

/**
 * 시트 내용 아래 여백입니다. 기본값(`safeDrawing`)은 보이는 시스템 막대만 계산해서, 뷰어처럼 시스템 막대를
 * 숨겼다 보이는 화면 위에서는 내비게이션 바가 그려져 있어도 0이 되어 마지막 줄이 막대 뒤에 가려집니다.
 * 내비게이션 바 높이를 표시 여부와 관계없이 함께 따져 더 큰 쪽을 씁니다. 키보드 여백은 그대로 둡니다.
 */
@OptIn(ExperimentalLayoutApi::class)
private val SheetContentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing
        .union(WindowInsets.navigationBarsIgnoringVisibility)
        .only(WindowInsetsSides.Bottom)

/** 제목이 있는 아래쪽 시트입니다. [skipPartiallyExpanded]이면 처음부터 끝까지 펼칩니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySheet(
    title: String,
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GalleryTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = SheetShape,
        containerColor = colors.menuSurface ?: colors.surfaceAlt,
        contentColor = colors.text,
        tonalElevation = 0.dp,
        scrimColor = Scrim,
        dragHandle = { SheetHandle(colors.hairline) },
        contentWindowInsets = { SheetContentInsets },
    ) {
        Text(
            title,
            style = titleStyle,
            color = colors.text,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.base),
        )
        content()
    }
}

@Composable
private fun SheetHandle(color: Color) {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(color, CircleShape),
    )
}

/** 앱 색과 모양을 입힌 Material 경고 대화상자입니다. */
@Composable
internal fun GalleryAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = GalleryDialogShape,
        containerColor = GalleryTheme.colors.dialogSurface,
        tonalElevation = 0.dp,
    )
}

/** 제목과 자유로운 내용을 담는 카드형 대화상자입니다. */
@Composable
fun DialogCard(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = GalleryTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        val card = Modifier
            .fillMaxWidth()
            .clip(GalleryDialogShape)
            .background(colors.dialogSurface)
            .padding(20.dp)
        Column(card) {
            Text(title, style = titleStyle, color = colors.text)
            content()
        }
    }
}

/**
 * 한 줄 입력 대화상자입니다. 처음 열면 [initial] 전체를 선택한 채 입력란에 포커스를 둡니다.
 * [isValid]가 거짓이면 확인 버튼과 키보드 완료 키가 동작하지 않습니다.
 */
@Composable
fun TextDialog(
    title: String,
    initial: String,
    isValid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GalleryTheme.colors
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val acceptable = isValid(field.text)
    val submit = { if (acceptable) onConfirm(field.text) }

    DialogCard(title, onDismiss) {
        // 입력란과 같은 창의 컴포지션에서 포커스를 요청해야 입력란이 붙은 뒤에 실행됩니다.
        val focus = remember { FocusRequester() }
        LaunchedEffect(focus) { focus.requestFocus() }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .focusRequester(focus)
                .clip(ButtonShape)
                .background(colors.settingsControl)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = GalleryTheme.type.body.copy(color = colors.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
        )
        DialogButtons(enabled = acceptable, onDismiss = onDismiss, onConfirm = submit)
    }
}

/** 대화상자 아래쪽 오른편의 취소·확인 버튼 한 쌍입니다. */
@Composable
fun DialogButtons(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = stringResource(R.string.action_done),
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.base, Alignment.End),
    ) {
        DialogButton(dismissLabel, primary = false, enabled = true, onClick = onDismiss)
        DialogButton(confirmLabel, primary = true, enabled = enabled, onClick = onConfirm)
    }
}

/** [primary]이면 강조색을 채운 버튼, 아니면 글자만 있는 버튼입니다. */
@Composable
private fun DialogButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = GalleryTheme.colors
    val button = Modifier
        .height(34.dp)
        .alpha(if (enabled) 1f else 0.38f)
        .clip(ButtonShape)
        .background(if (primary) colors.accent else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 14.dp)
    Box(button, contentAlignment = Alignment.Center) {
        Text(label, style = GalleryTheme.type.label, color = if (primary) colors.onAccent else colors.textMuted)
    }
}
