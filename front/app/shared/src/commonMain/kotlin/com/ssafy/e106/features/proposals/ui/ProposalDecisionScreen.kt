package com.ssafy.e106.features.proposals.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.safety.ui.StateNoteScreen
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

/**
 * 끝난 제안(매칭 안 됨 · 기한 지남 · 볼 수 없음)과 응답 확인(확인 중 · 알 수 없음)만. 상태 조회 · 이동은 호출부가 한다.
 * 모두 상태 화면 모양이다. 결과를 모를 때는 "다시 보내기 전에 결과를 확인" 안내를 지우지 않는다.
 */
@Composable
fun ProposalDecisionScreen(
    state: ProposalDecisionState,
    onAction: (ProposalDecisionAction) -> Unit,
    modifier: Modifier = Modifier,
    canLeave: Boolean = true,   // 수락 · 거절을 보내는 중엔 목록으로 못 간다
) {
    val bar = stringResource(Res.string.proposal_decision_title)
    val check = stringResource(Res.string.proposal_decision_check)
    val list = StateAction(stringResource(Res.string.proposal_decision_list), enabled = canLeave) { onAction(ProposalDecisionAction.ProposalList) }
    when (state.stage) {
        ProposalDecisionStage.Checking -> StateScreen(StateKind.Unconfirmed, stringResource(Res.string.proposal_decision_checking), modifier,
            barTitle = bar, primary = StateAction(check, loading = true) {}, secondary = list)
        ProposalDecisionStage.Unknown -> StateNoteScreen(StateKind.Unconfirmed, stringResource(Res.string.proposal_decision_unknown),
            stringResource(Res.string.proposal_decision_unknown_help), modifier, barTitle = bar,
            primary = StateAction(check) { onAction(ProposalDecisionAction.CheckResult) }, secondary = list)
        ProposalDecisionStage.NotMatched, ProposalDecisionStage.Expired, ProposalDecisionStage.Unavailable -> StateScreen(StateKind.Locked,
            stringResource(when (state.stage) {
                ProposalDecisionStage.NotMatched -> Res.string.proposal_decision_not_matched
                ProposalDecisionStage.Expired -> Res.string.proposal_decision_expired
                else -> Res.string.proposal_decision_unavailable
            }), modifier, barTitle = bar, primary = list)
    }
}
