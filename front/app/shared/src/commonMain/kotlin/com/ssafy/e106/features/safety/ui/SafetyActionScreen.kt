package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 순수 확인/상태 화면. API·캐시 삭제·화면 이동·가짜 성공을 실행하지 않는다.
 * 아래 시트 한 장: 질문 · 일어나는 일 3줄 · 빨간 확인 + 돌아가기(안내 → 확인 두 단계를 한 번으로). 결과도 같은 시트에서 보여 준다.
 */
@Composable
fun SafetyActionScreen(state: SafetyActionState, onAction: (SafetyUiAction) -> Unit) {
    val phase = state.displayPhase
    val block = state.operation == SafetyOperation.Block
    val busy = phase == SafetyPhase.Processing
    val asking = phase in listOf(SafetyPhase.Overview, SafetyPhase.Confirm, SafetyPhase.Processing)
    // 바탕을 눌러 닫는 건 아직 보내기 전일 때만. 결과 화면은 버튼으로 나간다(성공이면 목록으로 가야 한다)
    ActionSheet(onDismiss = if (phase == SafetyPhase.Overview || phase == SafetyPhase.Confirm) ({ onAction(SafetyUiAction.Back) }) else null) {
        if (asking) {
            Text(stringResource(if (block) Res.string.safety_block_question else Res.string.safety_leave_question), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Effect(stringResource(if (block) Res.string.safety_block_effect else Res.string.safety_leave_effect))
                Effect(stringResource(if (block) Res.string.safety_no_unblock else Res.string.safety_no_history))
                Effect(stringResource(if (block) Res.string.safety_block_matching else Res.string.safety_leave_matching))
            }
            AppButton(stringResource(if (block) Res.string.safety_block_confirm else Res.string.safety_leave_confirm), Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Danger, enabled = !busy, loading = busy, loadingText = stringResource(Res.string.safety_processing),
                onClick = { if (!busy) onAction(SafetyUiAction.Confirm) })
            AppButton(stringResource(Res.string.safety_cancel), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                enabled = !busy, onClick = { if (!busy) onAction(SafetyUiAction.Back) })
        } else {
            SheetResult(phase == SafetyPhase.Succeeded, stringResource(when (phase) {
                    SafetyPhase.Succeeded -> if (block) Res.string.safety_block_done else Res.string.safety_leave_done
                    SafetyPhase.Failed -> Res.string.safety_failed
                    SafetyPhase.Unknown -> Res.string.safety_unknown
                    SafetyPhase.ClosedUnconfirmed -> if (state.context == SafetyContext.Chat) Res.string.safety_closed else Res.string.safety_proposal_closed
                    else -> Res.string.safety_unavailable
                }), stringResource(when (phase) {
                    SafetyPhase.Succeeded -> if (block) Res.string.safety_block_done_help else Res.string.safety_leave_done_help
                    SafetyPhase.Failed -> Res.string.safety_failed_help
                    SafetyPhase.Unknown, SafetyPhase.ClosedUnconfirmed -> Res.string.safety_unknown_help
                    else -> Res.string.safety_unavailable_help
                }))
            val action = when (phase) {
                SafetyPhase.Failed -> SafetyUiAction.Retry
                SafetyPhase.Unknown -> SafetyUiAction.CheckResult
                else -> SafetyUiAction.Close
            }
            AppButton(stringResource(when (action) {
                SafetyUiAction.Retry -> Res.string.safety_retry
                SafetyUiAction.CheckResult -> Res.string.safety_check_result
                else -> if (state.context == SafetyContext.Chat) Res.string.safety_chat_list else Res.string.safety_proposal_list
            }), Modifier.fillMaxWidth(), onClick = { onAction(action) })
            // 실패 · 확인 필요일 때는 그만두고 돌아갈 수도 있다
            if (action != SafetyUiAction.Close) AppButton(stringResource(Res.string.safety_cancel), Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Secondary, onClick = { onAction(SafetyUiAction.Back) })
        }
    }
}

@Composable
private fun Effect(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.clearAndSetSemantics {})
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
    }
}
