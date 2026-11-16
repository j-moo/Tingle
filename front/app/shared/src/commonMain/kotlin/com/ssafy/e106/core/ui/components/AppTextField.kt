package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.VisualTransformation
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** Single-line input. The caller owns the value, validation timing, policy and saving. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String? = null,
    errorText: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,   // 문구 없이 테두리만 빨갛게(여러 칸에 한 문구를 따로 둘 때)
) {
    val title = if (required) stringResource(Res.string.field_required_label, label) else label
    val readOnlyDescription = stringResource(Res.string.field_read_only)
    val issue = errorText?.takeIf { it.isNotBlank() && enabled }
    val helper = issue ?: supportingText?.takeIf { it.isNotBlank() }
    val bad = issue != null || (isError && enabled)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val intoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(enabled, readOnly) {
        if (focused && !enabled) focusManager.clearFocus()
        if (focused && readOnly) keyboard?.hide()
    }
    // 포커스된 입력창만 키보드 높이를 본다. 컴포지션에서 읽지 않아 다른 입력창은 재구성되지 않고,
    // 높이가 바뀔 때마다 바로 따라 스크롤한다(snapshotFlow 가 스크롤 중 쌓인 값은 합친다).
    LaunchedEffect(focused, enabled, helper) {
        if (focused && enabled) snapshotFlow { ime.getBottom(density) }.collect { intoView.bringIntoView() }
    }
    Column(modifier.bringIntoViewRequester(intoView), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        // 라벨을 decorationBox 안에 그린다. 텍스트 필드는 자기 아래 노드만 합치는 경계라 바깥 Text 는 따로 읽히고,
        // contentDescription 은 TalkBack 이 입력값 대신 읽는다. 안에 두면 "라벨, 입력값, 편집창" 한 번으로 읽힌다.
        // (M3 의 위쪽 라벨 TextFieldLabelPosition.Above 는 1.4 기준 TextFieldState 판에만 있다)
        val colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AppColors.Background, unfocusedContainerColor = AppColors.Background,
            errorContainerColor = AppColors.Background, disabledContainerColor = AppColors.SurfaceMuted,
            focusedBorderColor = AppColors.TextPrimary, unfocusedBorderColor = AppColors.BorderControl,
            errorBorderColor = AppColors.Error, disabledBorderColor = AppColors.BorderControl,
            focusedPlaceholderColor = AppColors.TextSecondary, unfocusedPlaceholderColor = AppColors.TextSecondary,
            errorPlaceholderColor = AppColors.TextSecondary, disabledPlaceholderColor = AppColors.TextDisabled,
        )
        val interaction = remember { MutableInteractionSource() }
        BasicTextField(
            value = value,
            onValueChange = { if (enabled && !readOnly) onValueChange(it) },
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    if (issue != null) error(issue)
                    if (readOnly) stateDescription = readOnlyDescription
                },
            enabled = enabled, readOnly = readOnly, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled),
            cursorBrush = SolidColor(if (bad) AppColors.Error else AppColors.TextPrimary),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = visualTransformation, interactionSource = interaction,
        ) { innerTextField ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                TagLabel(title, color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled)
                Box(Modifier.fillMaxWidth().heightIn(min = AppSpacing.InputMinHeight).frost(AppShapes.Control), propagateMinConstraints = true) {
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = value, innerTextField = innerTextField, enabled = enabled, singleLine = true,
                        visualTransformation = visualTransformation, interactionSource = interaction, isError = bad,
                        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, style = MaterialTheme.typography.bodyLarge) }),
                        colors = colors,
                        container = {
                            OutlinedTextFieldDefaults.Container(enabled, bad, interaction, colors = colors, shape = AppShapes.Control)
                        },
                    )
                }
            }
        }
        if (helper != null) Text(helper, style = MaterialTheme.typography.bodySmall,
            color = if (issue != null) AppColors.Error else AppColors.TextSecondary,
            modifier = Modifier.semantics { if (issue != null) liveRegion = LiveRegionMode.Polite })
    }
}
