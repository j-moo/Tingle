package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 순수 확인/상태 화면. API·캐시 삭제·화면 이동·가짜 성공을 실행하지 않는다. */
@Composable
fun SafetyActionScreen(state: SafetyActionState, onAction: (SafetyUiAction) -> Unit) {
    val phase = state.displayPhase
    val block = state.operation == SafetyOperation.Block
    val busy = phase == SafetyPhase.Processing
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(if (block) Res.string.safety_block_title else Res.string.safety_leave_title),
                onBack = if (busy) null else ({ onAction(SafetyUiAction.Back) }))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = screenPaddingValues(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                when (phase) {
                    SafetyPhase.Overview, SafetyPhase.Confirm, SafetyPhase.Processing -> {
                        item {
                            Text(stringResource(if (block) Res.string.safety_block_question else Res.string.safety_leave_question),
                                style = MaterialTheme.typography.headlineSmall)
                            Text(state.targetLabel?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.safety_target_unknown),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            StatusCard(stringResource(Res.string.safety_before_title),
                                stringResource(if (block) Res.string.safety_block_effect else Res.string.safety_leave_effect))
                        }
                        item {
                            Text(stringResource(if (block) Res.string.safety_block_matching else Res.string.safety_leave_matching))
                        }
                        item {
                            Text(stringResource(if (block) Res.string.safety_no_unblock else Res.string.safety_no_history),
                                color = AppColors.Error, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (phase == SafetyPhase.Confirm) item { Text(stringResource(Res.string.safety_final_check)) }
                        if (busy) item { Text(stringResource(Res.string.safety_processing_help)) }
                    }
                    else -> item {
                        StatusCard(stringResource(when (phase) {
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
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                val action = when (phase) {
                    SafetyPhase.Overview -> SafetyUiAction.OpenConfirmation
                    SafetyPhase.Confirm, SafetyPhase.Processing -> SafetyUiAction.Confirm
                    SafetyPhase.Failed -> SafetyUiAction.Retry
                    SafetyPhase.Unknown -> SafetyUiAction.CheckResult
                    else -> SafetyUiAction.Close
                }
                AppButton(stringResource(when (action) {
                    SafetyUiAction.OpenConfirmation -> Res.string.safety_continue
                    SafetyUiAction.Confirm -> if (block) Res.string.safety_block_confirm else Res.string.safety_leave_confirm
                    SafetyUiAction.Retry -> Res.string.safety_retry
                    SafetyUiAction.CheckResult -> Res.string.safety_check_result
                    else -> if (state.context == SafetyContext.Chat) Res.string.safety_chat_list else Res.string.safety_proposal_list
                }), Modifier.fillMaxWidth(),
                    variant = if (phase == SafetyPhase.Confirm || busy) AppButtonVariant.Danger else AppButtonVariant.Primary,
                    enabled = !busy, loading = busy, loadingText = stringResource(Res.string.safety_processing),
                    onClick = { if (!busy) onAction(action) })
                if (phase == SafetyPhase.Confirm) AppButton(stringResource(Res.string.safety_cancel), Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Secondary, onClick = { onAction(SafetyUiAction.Cancel) })
            }
        }
    }
}
