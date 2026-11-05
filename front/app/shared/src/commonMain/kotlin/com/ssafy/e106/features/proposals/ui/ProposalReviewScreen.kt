package com.ssafy.e106.features.proposals.ui

import androidx.compose.runtime.Composable

/** PROFILE_CHANGED 후 재확인 UI. API 호출·자동 재전송·성공 상태 전환을 하지 않는다. */
@Composable
fun ProposalReviewScreen(state: ProposalReviewState, onAction: (ProposalReviewAction) -> Unit) {
    if (state.stage == ProposalReviewStage.Submitting && state.visibleConfirmation == null) {
        ProposalDecisionScreen(ProposalDecisionState(ProposalDecisionStage.Checking), {
            if (it == ProposalDecisionAction.ProposalList) onAction(ProposalReviewAction.Back)
        })
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
        ProposalReviewStage.Changed -> ProposalDecisionScreen(ProposalDecisionState(ProposalDecisionStage.ProfileChanged), {
            if (it == ProposalDecisionAction.ReviewProfile) onAction(ProposalReviewAction.Reload)
            else if (it == ProposalDecisionAction.ProposalList) onAction(ProposalReviewAction.Back)
        })
        ProposalReviewStage.Unknown -> ProposalDecisionScreen(ProposalDecisionState(ProposalDecisionStage.Unknown), {
            if (it == ProposalDecisionAction.CheckResult) onAction(ProposalReviewAction.CheckResult)
            else if (it == ProposalDecisionAction.ProposalList) onAction(ProposalReviewAction.Back)
        })
        ProposalReviewStage.Unavailable -> ProposalProfileScreen(ProposalProfileState(load = ProposalProfileLoad.Unavailable), {
            if (it == ProposalProfileAction.Back) onAction(ProposalReviewAction.Back)
        })
        ProposalReviewStage.Loading, ProposalReviewStage.Failed -> ReloadProfileScreen(state.stage == ProposalReviewStage.Loading, onAction)
        ProposalReviewStage.Review, ProposalReviewStage.Submitting -> {
            val current = state.current
            if (current == null) ReloadProfileScreen(false, onAction)
            else {
                ProposalProfileScreen(current.profile.copy(canRespond = state.canChoose), { action ->
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
