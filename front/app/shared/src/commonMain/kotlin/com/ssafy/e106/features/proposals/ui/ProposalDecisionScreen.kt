package com.ssafy.e106.features.proposals.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 열림 여부·처리 상태는 호출부가 소유한다. 이 컴포넌트는 제출이나 상태 전이를 하지 않는다. */
@Composable
fun ProposalDecisionConfirmation(
    choice: ProposalChoice,
    submitting: Boolean,
    onConfirm: (ProposalChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val accept = choice == ProposalChoice.Accept
    AppDialog(
        title = stringResource(if (accept) Res.string.proposal_decision_confirm_accept else Res.string.proposal_decision_confirm_reject),
        description = stringResource(if (accept) Res.string.proposal_decision_accept_help else Res.string.proposal_decision_reject_help),
        confirmText = stringResource(if (accept) Res.string.proposal_decision_accept else Res.string.proposal_decision_reject),
        dismissText = stringResource(Res.string.proposal_decision_reconsider),
        onConfirm = { if (!submitting) onConfirm(choice) },
        onDismiss = { if (!submitting) onDismiss() },
        confirmLoading = submitting,
    )
}

/** 끝난 제안(매칭 안 됨 · 기한 지남 · 볼 수 없음)과 응답 확인(확인 중 · 알 수 없음)만. 상태 조회 · 이동은 호출부가 한다. */
@Composable
fun ProposalDecisionScreen(
    state: ProposalDecisionState,
    onAction: (ProposalDecisionAction) -> Unit,
    modifier: Modifier = Modifier,
    canLeave: Boolean = true,   // 수락 · 거절을 보내는 중엔 목록으로 못 간다
) {
    val stage = state.stage
    val (title, help) = when (stage) {
        ProposalDecisionStage.Checking -> Res.string.proposal_decision_checking to Res.string.proposal_decision_checking_help
        ProposalDecisionStage.Unknown -> Res.string.proposal_decision_unknown to Res.string.proposal_decision_unknown_help
        ProposalDecisionStage.NotMatched -> Res.string.proposal_decision_not_matched to Res.string.proposal_decision_not_matched_help
        ProposalDecisionStage.Expired -> Res.string.proposal_decision_expired to Res.string.proposal_decision_expired_help
        ProposalDecisionStage.Unavailable -> Res.string.proposal_decision_unavailable to Res.string.proposal_decision_unavailable_help
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.proposal_decision_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    Surface(shape = AppShapes.Card, color = AppColors.SurfaceMuted) {
                        Column(Modifier.fillMaxWidth().padding(AppSpacing.Section),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(help), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    when (stage) {
                        ProposalDecisionStage.Checking -> AppButton(stringResource(Res.string.proposal_decision_check),
                            Modifier.fillMaxWidth(), loading = true, loadingText = stringResource(Res.string.proposal_decision_checking_button), onClick = {})
                        ProposalDecisionStage.Unknown -> AppButton(stringResource(Res.string.proposal_decision_check), Modifier.fillMaxWidth(),
                            onClick = { onAction(ProposalDecisionAction.CheckResult) })
                        else -> Unit
                    }
                    AppButton(stringResource(Res.string.proposal_decision_list), Modifier.fillMaxWidth(), enabled = canLeave,
                        variant = AppButtonVariant.Secondary, onClick = { onAction(ProposalDecisionAction.ProposalList) })
                }
            }
        }
    }
}
