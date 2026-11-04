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

/** 실제 상태 조회·동일 요청 복구·채팅 권한 확인·이동은 후속 연결부에서 수행한다. */
@Composable
fun ProposalDecisionScreen(
    state: ProposalDecisionState,
    onAction: (ProposalDecisionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = state.stage
    val title = when (stage) {
        ProposalDecisionStage.Checking -> Res.string.proposal_decision_checking
        ProposalDecisionStage.Unknown -> Res.string.proposal_decision_unknown
        ProposalDecisionStage.Waiting -> Res.string.proposal_decision_waiting
        ProposalDecisionStage.MatchedPreparing, ProposalDecisionStage.MatchedReady -> Res.string.proposal_decision_matched
        ProposalDecisionStage.NotMatched -> Res.string.proposal_decision_not_matched
        ProposalDecisionStage.Rejected -> Res.string.proposal_decision_rejected
        ProposalDecisionStage.Expired -> Res.string.proposal_decision_expired
        ProposalDecisionStage.Unavailable -> Res.string.proposal_decision_unavailable
        ProposalDecisionStage.ProfileChanged -> Res.string.proposal_review_changed
    }
    val help = when (stage) {
        ProposalDecisionStage.Checking -> Res.string.proposal_decision_checking_help
        ProposalDecisionStage.Unknown -> Res.string.proposal_decision_unknown_help
        ProposalDecisionStage.Waiting -> Res.string.proposal_decision_waiting_help
        ProposalDecisionStage.MatchedPreparing -> Res.string.proposal_decision_preparing_help
        ProposalDecisionStage.MatchedReady -> Res.string.proposal_decision_matched_help
        ProposalDecisionStage.NotMatched -> Res.string.proposal_decision_not_matched_help
        ProposalDecisionStage.Rejected -> Res.string.proposal_decision_rejected_help
        ProposalDecisionStage.Expired -> Res.string.proposal_decision_expired_help
        ProposalDecisionStage.Unavailable -> Res.string.proposal_decision_unavailable_help
        ProposalDecisionStage.ProfileChanged -> Res.string.proposal_review_changed_help
    }
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppTopBar(stringResource(Res.string.proposal_decision_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
                    Surface(shape = AppShapes.Card, color = if (stage == ProposalDecisionStage.MatchedReady)
                        AppColors.SelectionContainer else AppColors.SurfaceMuted) {
                        Column(Modifier.fillMaxWidth().padding(AppSpacing.Section),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(help), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (stage == ProposalDecisionStage.Waiting) Text(
                        state.deadlineLabel?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.proposal_decision_deadline, it) }
                            ?: stringResource(Res.string.proposal_decision_deadline_unknown),
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    when (stage) {
                        ProposalDecisionStage.ProfileChanged -> AppButton(stringResource(Res.string.proposal_review_reload),
                            Modifier.fillMaxWidth(), onClick = { onAction(ProposalDecisionAction.ReviewProfile) })
                        ProposalDecisionStage.Checking -> AppButton(stringResource(Res.string.proposal_decision_check),
                            Modifier.fillMaxWidth(), loading = true, loadingText = stringResource(Res.string.proposal_decision_checking_button), onClick = {})
                        ProposalDecisionStage.Unknown, ProposalDecisionStage.Waiting, ProposalDecisionStage.MatchedPreparing ->
                            AppButton(stringResource(Res.string.proposal_decision_check), Modifier.fillMaxWidth(),
                                onClick = { onAction(ProposalDecisionAction.CheckResult) })
                        ProposalDecisionStage.MatchedReady -> AppButton(stringResource(Res.string.proposal_decision_open_chat),
                            Modifier.fillMaxWidth(), onClick = { onAction(ProposalDecisionAction.OpenChat) })
                        else -> Unit
                    }
                    AppButton(stringResource(Res.string.proposal_decision_list), Modifier.fillMaxWidth(),
                        variant = AppButtonVariant.Secondary, onClick = { onAction(ProposalDecisionAction.ProposalList) })
                }
            }
        }
    }
}
