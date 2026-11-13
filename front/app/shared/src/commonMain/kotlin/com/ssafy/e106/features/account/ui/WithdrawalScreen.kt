package com.ssafy.e106.features.account.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 계약 후보에 맞춘 순수 화면. 버튼은 의도만 전달하며 네트워크/세션/기기 데이터를 직접 변경하지 않는다.
 * 실제 요청은 인증 분기 밖의 Coordinator가 관리한다. 분실 문의는 사용자 결정으로 MVP에서 제외했다.
 * 확인 중은 뼈대, 준비 · 실행 중은 보던 화면 그대로 버튼만 처리 중, 확인 불가 · 키링 정리 · 결과 확인 · 완료는 상태 표시(아이콘 + 제목)로 보인다.
 * 탈퇴 안내 · 보관 · 재가입 제한 · "기존 요청 결과부터 확인" 안내 글은 상태 표시 아래에 그대로 둔다.
 */
@Composable
fun WithdrawalScreen(state: WithdrawalState, onAccepted: (Boolean) -> Unit, onAction: (WithdrawalAction) -> Unit) {
    val stage = state.displayStage
    // 준비 · 실행 요청 중에는 "…하고 있어요" 화면으로 바꾸지 않고 누른 화면을 그대로 둔다(버튼만 처리 중)
    val shown = when (stage) {
        WithdrawalStage.Preparing -> WithdrawalStage.Ready
        WithdrawalStage.Executing -> WithdrawalStage.Confirm
        else -> stage
    }
    val cleanupUnconfirmed = state.localCleanupPending || state.operation?.externalCleanupStatus != "COMPLETED"
    // 당겨서 새로고침은 "다시 확인" 버튼이 있는 상태에서만 같은 동작으로 둔다. 안내 · 최종 확인 화면은 당겨도 요청을 보내지 않는다
    val refresh: (() -> Unit)? = when (stage) {
        WithdrawalStage.Blocked, WithdrawalStage.Unavailable -> ({ onAction(WithdrawalAction.Reload) })
        WithdrawalStage.Unknown, WithdrawalStage.Expired -> ({ onAction(WithdrawalAction.CheckResult) })
        WithdrawalStage.Completed -> if (cleanupUnconfirmed) ({ onAction(WithdrawalAction.CheckResult) }) else null
        else -> null
    }
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.withdraw_title),
                onBack = if (state.busy || stage == WithdrawalStage.Completed) null else ({ onAction(WithdrawalAction.Back) }))
            val body: @Composable BoxScope.() -> Unit = {
                // 긴 완료 안내와 큰 글씨에서도 본문·버튼을 함께 스크롤해 하단 버튼이 내용을 덮지 않게 한다.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    when (shown) {
                        // 탈퇴 가능 여부 확인 중: 안내 글 자리는 뼈대, 버튼은 실제 자리에 막아 둔다
                        WithdrawalStage.Loading -> SkeletonGroup {
                            SkeletonBone(Modifier.fillMaxWidth(0.6f), height = 28.dp)
                            repeat(3) {
                                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                                    SkeletonBone(); SkeletonBone(); SkeletonBone(Modifier.fillMaxWidth(0.7f))
                                }
                            }
                        }
                        WithdrawalStage.Ready, WithdrawalStage.Confirm -> Text(stringResource(
                            if (shown == WithdrawalStage.Ready) Res.string.withdraw_ready else Res.string.withdraw_confirm),
                            style = MaterialTheme.typography.headlineSmall)
                        // 확인 불가는 작은 "다시 시도"만, 나머지는 아래에 큰 버튼이 있다
                        else -> StateView(when (shown) {
                            WithdrawalStage.Blocked -> StateKind.Locked
                            WithdrawalStage.Completed -> StateKind.Done
                            WithdrawalStage.Unavailable -> StateKind.Unknown   // 화면이 오류 종류를 받지 않는다
                            else -> StateKind.Unconfirmed
                        }, stringResource(when (shown) {
                            WithdrawalStage.Blocked -> Res.string.withdraw_blocked
                            WithdrawalStage.Completed -> Res.string.withdraw_complete
                            WithdrawalStage.Unavailable -> Res.string.withdraw_unavailable
                            WithdrawalStage.Expired -> Res.string.withdraw_expired
                            else -> when {
                                state.cancellationRequested -> Res.string.withdraw_cancel_unknown
                                state.executionRequested -> Res.string.withdraw_unknown
                                else -> Res.string.withdraw_prepare_unknown
                            }
                        }), onRetry = refresh.takeIf { shown == WithdrawalStage.Unavailable },
                            // 못 확인한 키링 수를 0으로 보지 않는다는 사실은 사용자가 알아야 한다
                            note = if (shown == WithdrawalStage.Unavailable) stringResource(Res.string.withdraw_unavailable_help) else null)
                    }
                    when (shown) {
                        WithdrawalStage.Ready, WithdrawalStage.Confirm -> {
                            Text(stringResource(Res.string.withdraw_effect))
                            Text(stringResource(Res.string.withdraw_retention))
                            Text(stringResource(Res.string.withdraw_rejoin))
                            Text(stringResource(Res.string.withdraw_recheck), style = MaterialTheme.typography.bodySmall)
                            if (shown == WithdrawalStage.Confirm) {
                                // 이전 실행 후 서버가 같은 요청을 PREPARED로 확인하면 미완료 사실과 재동의 필요를 알린다.
                                Text(stringResource(if (state.canCancelPreparation) Res.string.withdraw_prepare_only
                                    else Res.string.withdraw_execute_retry))
                                // 실행 요청 중에는 방금 한 동의를 체크된 채로 잠가 둔다(상태는 요청을 보내며 체크를 푼다)
                                AppCheckbox(state.accepted || stage == WithdrawalStage.Executing, onAccepted, stringResource(Res.string.withdraw_accept),
                                    enabled = stage == WithdrawalStage.Confirm)
                            }
                        }
                        WithdrawalStage.Blocked -> {
                            state.eligibility?.let {
                                if (it.registeredKeyringCount > 0) Text(stringResource(Res.string.withdraw_keyring_count, it.registeredKeyringCount))
                                if (it.pendingResetCount > 0) Text(stringResource(Res.string.withdraw_reset_count, it.pendingResetCount))
                            }
                            Text(stringResource(Res.string.withdraw_keyring_help))
                        }
                        // 중복 탈퇴 요청을 막는 안내라 상태 표시의 "설명 없음" 규칙과 별개로 남긴다
                        WithdrawalStage.Unknown -> Text(stringResource(when {
                            state.cancellationRequested -> Res.string.withdraw_cancel_unknown_help
                            state.executionRequested -> Res.string.withdraw_unknown_help
                            else -> Res.string.withdraw_prepare_unknown_help
                        }))
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
                        else -> Unit
                    }
                    when (shown) {
                        WithdrawalStage.Loading -> ActionButton(Res.string.withdraw_continue, WithdrawalAction.Prepare, onAction, enabled = false)
                        WithdrawalStage.Ready -> ActionButton(Res.string.withdraw_continue, WithdrawalAction.Prepare, onAction, state.canPrepare,
                            loading = stage == WithdrawalStage.Preparing)
                        WithdrawalStage.Confirm -> {
                            AppButton(stringResource(Res.string.withdraw_execute), Modifier.fillMaxWidth(),
                                variant = AppButtonVariant.Danger, enabled = state.canExecute, loading = stage == WithdrawalStage.Executing,
                                onClick = { if (state.canExecute) onAction(WithdrawalAction.Execute) })
                            if (state.canCancelPreparation && stage == WithdrawalStage.Confirm)
                                ActionButton(Res.string.withdraw_cancel, WithdrawalAction.CancelPreparation, onAction)
                        }
                        WithdrawalStage.Blocked -> {
                            ActionButton(Res.string.withdraw_keyring, WithdrawalAction.OpenKeyring, onAction, variant = AppButtonVariant.Primary)
                            ActionButton(Res.string.withdraw_reload, WithdrawalAction.Reload, onAction)
                        }
                        WithdrawalStage.Unknown, WithdrawalStage.Expired -> {
                            // 준비 응답 복구 · 취소 결과 · 실행 결과 확인은 같은 안전한 복구 동작이라 버튼 이름은 "다시 확인" 하나로 두고, 차이는 제목과 안내 글이 알린다
                            ActionButton(Res.string.withdraw_reload, WithdrawalAction.CheckResult, onAction, variant = AppButtonVariant.Primary)
                            // 실제 execute를 한 적 없는 만료된 준비만 명시적으로 취소할 수 있다.
                            if (shown == WithdrawalStage.Expired && state.canCancelPreparation)
                                ActionButton(Res.string.withdraw_cancel, WithdrawalAction.CancelPreparation, onAction)
                        }
                        WithdrawalStage.Completed -> {
                            ActionButton(Res.string.withdraw_start, WithdrawalAction.OpenStart, onAction, enabled = !state.localCleanupPending,
                                variant = AppButtonVariant.Primary)
                            if (cleanupUnconfirmed) ActionButton(Res.string.withdraw_reload, WithdrawalAction.CheckResult, onAction)
                        }
                        else -> Unit   // 확인 불가: 상태 표시의 작은 "다시 시도"
                    }
                }
            }
            if (refresh != null) PullDownRefreshBox(false, refresh, Modifier.weight(1f).fillMaxWidth(), content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), content = body)
        }
    }
}

/** 공통 버튼은 실행 의도를 위임한다. Preview에서는 콜백을 비워 실제 탈퇴로 이어지지 않게 한다. */
@Composable
private fun ActionButton(label: org.jetbrains.compose.resources.StringResource, action: WithdrawalAction,
    onAction: (WithdrawalAction) -> Unit, enabled: Boolean = true, loading: Boolean = false,
    variant: AppButtonVariant = AppButtonVariant.Secondary) {
    AppButton(stringResource(label), Modifier.fillMaxWidth(), enabled = enabled, loading = loading,
        variant = variant, onClick = { if (enabled) onAction(action) })
}
