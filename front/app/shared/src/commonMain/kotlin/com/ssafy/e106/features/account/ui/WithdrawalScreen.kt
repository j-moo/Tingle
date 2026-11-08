package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 계약 후보에 맞춘 순수 화면. 버튼은 의도만 전달하며 네트워크/세션/기기 데이터를 직접 변경하지 않는다.
 * 실제 요청은 인증 분기 밖의 Coordinator가 관리한다. 분실 문의는 사용자 결정으로 MVP에서 제외했다.
 */
@Composable
fun WithdrawalScreen(state: WithdrawalState, onAccepted: (Boolean) -> Unit, onAction: (WithdrawalAction) -> Unit) {
    val stage = state.displayStage
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.withdraw_title),
                onBack = if (state.busy || stage == WithdrawalStage.Completed) null else ({ onAction(WithdrawalAction.Back) }))
            // 긴 완료 안내와 큰 글씨에서도 본문·버튼을 함께 스크롤해 하단 버튼이 내용을 덮지 않게 한다.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                Text(stringResource(when (stage) {
                    WithdrawalStage.Loading -> Res.string.withdraw_loading
                    WithdrawalStage.Ready -> Res.string.withdraw_ready
                    WithdrawalStage.Blocked -> Res.string.withdraw_blocked
                    WithdrawalStage.Preparing -> Res.string.withdraw_preparing
                    WithdrawalStage.Confirm -> Res.string.withdraw_confirm
                    WithdrawalStage.Executing -> Res.string.withdraw_executing
                    WithdrawalStage.Unknown -> when {
                        state.cancellationRequested -> Res.string.withdraw_cancel_unknown
                        state.executionRequested -> Res.string.withdraw_unknown
                        else -> Res.string.withdraw_prepare_unknown
                    }
                    WithdrawalStage.Expired -> Res.string.withdraw_expired
                    WithdrawalStage.Completed -> Res.string.withdraw_complete
                    WithdrawalStage.Unavailable -> Res.string.withdraw_unavailable
                }), style = MaterialTheme.typography.headlineSmall)
                when (stage) {
                    WithdrawalStage.Ready, WithdrawalStage.Confirm -> {
                        Text(stringResource(Res.string.withdraw_effect))
                        Text(stringResource(Res.string.withdraw_retention))
                        Text(stringResource(Res.string.withdraw_rejoin))
                        Text(stringResource(Res.string.withdraw_recheck), style = MaterialTheme.typography.bodySmall)
                        if (stage == WithdrawalStage.Confirm) {
                            Text(stringResource(Res.string.withdraw_prepare_only))
                            AppCheckbox(state.accepted, onAccepted, stringResource(Res.string.withdraw_accept))
                        }
                    }
                    WithdrawalStage.Blocked -> {
                        state.eligibility?.let {
                            if (it.registeredKeyringCount > 0) Text(stringResource(Res.string.withdraw_keyring_count, it.registeredKeyringCount))
                            if (it.pendingResetCount > 0) Text(stringResource(Res.string.withdraw_reset_count, it.pendingResetCount))
                        }
                        Text(stringResource(Res.string.withdraw_keyring_help))
                    }
                    WithdrawalStage.Unknown -> Text(stringResource(when {
                        state.cancellationRequested -> Res.string.withdraw_cancel_unknown_help
                        state.executionRequested -> Res.string.withdraw_unknown_help
                        else -> Res.string.withdraw_prepare_unknown_help
                    }))
                    WithdrawalStage.Unavailable -> Text(stringResource(Res.string.withdraw_unavailable_help))
                    WithdrawalStage.Expired -> Text(stringResource(Res.string.withdraw_expired_help))
                    WithdrawalStage.Completed -> {
                        Text(stringResource(Res.string.withdraw_complete_help))
                        if (state.localCleanupPending) Text(stringResource(Res.string.withdraw_local_cleanup_pending))
                        // 서버 시각을 UTC라고 명시한다. 기기 시각에72시간을 더해 재가입 가능 시각을 만들지 않는다.
                        Text(stringResource(Res.string.withdraw_available_at, state.operation!!.rejoinAvailableAt!!))
                        Text(stringResource(when (state.operation.externalCleanupStatus) {
                            "PENDING" -> Res.string.withdraw_cleanup_pending
                            "COMPLETED" -> Res.string.withdraw_cleanup_complete
                            else -> Res.string.withdraw_cleanup_unknown
                        }))
                    }
                    else -> CircularProgressIndicator(color = AppColors.SelectionBorder)
                }
                when (stage) {
                    WithdrawalStage.Ready -> ActionButton(Res.string.withdraw_continue, WithdrawalAction.Prepare, onAction, state.canPrepare)
                    WithdrawalStage.Confirm -> {
                        AppButton(stringResource(Res.string.withdraw_execute), Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Danger, enabled = state.canExecute,
                            onClick = { if (state.canExecute) onAction(WithdrawalAction.Execute) })
                        if (state.canCancelPreparation)
                            ActionButton(Res.string.withdraw_cancel, WithdrawalAction.CancelPreparation, onAction)
                    }
                    WithdrawalStage.Blocked -> {
                        ActionButton(Res.string.withdraw_keyring, WithdrawalAction.OpenKeyring, onAction)
                        ActionButton(Res.string.withdraw_reload, WithdrawalAction.Reload, onAction)
                    }
                    WithdrawalStage.Unknown, WithdrawalStage.Expired -> {
                        // 준비 응답 복구와 실행 결과 확인은 같은 안전한 복구 동작이지만 사용자 설명은 구분한다.
                        ActionButton(when {
                            state.cancellationRequested -> Res.string.withdraw_cancel_check
                            stage == WithdrawalStage.Unknown && !state.executionRequested -> Res.string.withdraw_prepare_check
                            else -> Res.string.withdraw_check
                        }, WithdrawalAction.CheckResult, onAction)
                        // 실제 execute를 한 적 없는 만료된 준비만 명시적으로 취소할 수 있다.
                        if (stage == WithdrawalStage.Expired && state.canCancelPreparation)
                            ActionButton(Res.string.withdraw_cancel, WithdrawalAction.CancelPreparation, onAction)
                    }
                    WithdrawalStage.Unavailable -> ActionButton(Res.string.withdraw_reload, WithdrawalAction.Reload, onAction)
                    WithdrawalStage.Completed -> {
                        if (state.localCleanupPending || state.operation?.externalCleanupStatus != "COMPLETED")
                            ActionButton(Res.string.withdraw_check, WithdrawalAction.CheckResult, onAction)
                        ActionButton(Res.string.withdraw_start, WithdrawalAction.OpenStart, onAction, enabled = !state.localCleanupPending)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** 공통 버튼은 실행 의도를 위임한다. Preview에서는 콜백을 비워 실제 탈퇴로 이어지지 않게 한다. */
@Composable
private fun ActionButton(label: org.jetbrains.compose.resources.StringResource, action: WithdrawalAction,
    onAction: (WithdrawalAction) -> Unit, enabled: Boolean = true) {
    AppButton(stringResource(label), Modifier.fillMaxWidth(), enabled = enabled,
        variant = AppButtonVariant.Secondary, onClick = { if (enabled) onAction(action) })
}
