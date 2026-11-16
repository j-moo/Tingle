package com.ssafy.e106.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing

/**
 * 확인 대화상자. 주요 버튼 위, 보조 버튼 아래. Figma 01 Tingle/Dialog (Confirm · Danger).
 * 위험 행동이면 [danger] 로 주요 버튼이 빨간 AppButton Danger 가 된다. 열림 여부와 실제 작업은 호출 화면이 소유한다.
 * 뒤로가기·포커스 가두기·뒤 배경 스크림은 Dialog 창이 처리한다. [confirmLoading] 중에는 바깥 탭·뒤로가기로 닫히지 않는다.
 */
@Composable
fun AppDialog(
    title: String,
    description: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    confirmLoading: Boolean = false,
) {
    Dialog(onDismissRequest = { if (!confirmLoading) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 창을 전체 화면으로 키워 그림자가 잘리지 않게 하고, 카드 옆 여백을 눌러도 닫히게 한다.
        // pointerInput 은 접근성 노드를 만들지 않아 배경이 버튼으로 읽히지 않는다.
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(confirmLoading) {
                detectTapGestures { if (!confirmLoading) onDismiss() }
            },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = AppSpacing.Section)
                    .widthIn(min = DialogMinWidth, max = DialogMaxWidth)
                    // 카드 안을 눌렀을 때 바깥 탭으로 새지 않게 소비한다
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title },
                shape = AppShapes.Card,
                color = AppColors.Sheet,
                border = BorderStroke(AppSpacing.Stroke, AppColors.BorderSubtle),
            ) {
                Column(Modifier.padding(AppSpacing.Section), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary,
                        modifier = Modifier.semantics { heading() })
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                        AppButton(
                            text = confirmText, modifier = Modifier.fillMaxWidth(),
                            variant = if (danger) AppButtonVariant.Danger else AppButtonVariant.Primary,
                            loading = confirmLoading, onClick = onConfirm,
                        )
                        AppButton(
                            text = dismissText, modifier = Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Secondary, enabled = !confirmLoading, onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/** Figma 권장 너비 320~400 */
private val DialogMinWidth = 320.dp
private val DialogMaxWidth = 400.dp
