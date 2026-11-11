package com.ssafy.e106.features.proposals.ui

import androidx.compose.runtime.Composable

/** 프로필 열람 · 수락/거절 확인. API 호출·자동 재전송·성공 상태 전환을 하지 않는다. */
@Composable
fun ProposalReviewScreen(state: ProposalReviewState, canLeave: Boolean = true, onAction: (ProposalReviewAction) -> Unit) {
    if (state.stage == ProposalReviewStage.Submitting && state.visibleConfirmation == null) {
        // 수락 · 거절을 보내는 중엔 목록으로 못 간다(연결부가 판단)
        ProposalDecisionScreen(ProposalDecisionState(ProposalDecisionStage.Checking), {
            if (it == ProposalDecisionAction.ProposalList) onAction(ProposalReviewAction.Back)
        }, canLeave = canLeave)
        return
    }
    if (state.latest?.context == state.context &&
        (state.stage == ProposalReviewStage.Review || state.stage == ProposalReviewStage.Submitting) &&
        (state.latest.profile.load == ProposalProfileLoad.Unavailable ||
            state.latest.profile.profile?.photoState == ProposalPhotoState.Unavailable)) {
        ProposalProfileScreen(ProposalProfileState(load = ProposalProfileLoad.Unavailable), {
            if (it == ProposalProfileAction.Back) onAction(ProposalReviewAction.Back)
        })
        return
    }
    when (state.stage) {
        ProposalReviewStage.Unavailable -> ProposalProfileScreen(ProposalProfileState(load = ProposalProfileLoad.Unavailable), {
            if (it == ProposalProfileAction.Back) onAction(ProposalReviewAction.Back)
        })
        ProposalReviewStage.Loading, ProposalReviewStage.Failed -> ReloadProfileScreen(state.stage == ProposalReviewStage.Loading, onAction)
        ProposalReviewStage.Review, ProposalReviewStage.Submitting -> {
            val current = state.current
            if (current == null) ReloadProfileScreen(false, onAction)
            else {
                // 보내는 중엔 확인창 뒤 버튼을 그대로 두어 "응답 가능 여부 확인" 문구가 비치지 않게(누르기는 아래에서 막힘)
                ProposalProfileScreen(current.profile.copy(canRespond = state.canChoose || state.stage == ProposalReviewStage.Submitting), { action ->
                    if (state.stage != ProposalReviewStage.Submitting) when (action) {
                        ProposalProfileAction.Accept, ProposalProfileAction.Reject -> state.ask(
                            if (action == ProposalProfileAction.Accept) ProposalChoice.Accept else ProposalChoice.Reject)
                            ?.let { onAction(ProposalReviewAction.Ask(it)) }
                        ProposalProfileAction.Back -> onAction(ProposalReviewAction.Back)
                        else -> onAction(ProposalReviewAction.ProfileAction(action))
                    }
                })
                state.visibleConfirmation?.let { pending ->
                    ProposalDecisionConfirmation(pending.choice, state.stage == ProposalReviewStage.Submitting,
                        onConfirm = { state.submitAction?.let(onAction) },
                        onDismiss = { if (state.stage != ProposalReviewStage.Submitting) onAction(ProposalReviewAction.Dismiss) })
                }
            }
        }
    }
}

@Composable
private fun ReloadProfileScreen(loading: Boolean, onAction: (ProposalReviewAction) -> Unit) {
    ProposalProfileScreen(ProposalProfileState(load = if (loading) ProposalProfileLoad.Loading else ProposalProfileLoad.Failed), {
        when (it) {
            ProposalProfileAction.Back -> onAction(ProposalReviewAction.Back)
            ProposalProfileAction.Reload -> if (!loading) onAction(ProposalReviewAction.Reload)
            else -> Unit
        }
    })
}
