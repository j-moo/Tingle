package com.ssafy.e106.features.proposals.ui

/** 조회 세대는 로컬 식별값, profileVersion은 서버가 준 공개 프로필의 비교값이다. */
data class ProposalReviewContext(val viewerId: Long, val proposalId: Long, val generation: Long) {
    val valid: Boolean get() = viewerId > 0 && proposalId > 0 && generation > 0
}
data class ReviewedProposalProfile(val context: ProposalReviewContext, val profileVersion: String,
    val profile: ProposalProfileState) {
    val valid: Boolean get() = context.valid && profileVersion.length == 64 &&
        profileVersion.all { it in 'a'..'f' || it in '0'..'9' } &&
        profile.load == ProposalProfileLoad.Ready && profile.profile != null &&
        profile.profile.photoState != ProposalPhotoState.Unavailable
}
enum class ProposalReviewStage { Changed, Loading, Failed, Review, Submitting, Unknown, Unavailable }
data class ProposalReviewConfirmation(val choice: ProposalChoice, val snapshot: ReviewedProposalProfile)

sealed interface ProposalReviewAction {
    data object Reload : ProposalReviewAction
    data object Back : ProposalReviewAction
    data object CheckResult : ProposalReviewAction
    data object Dismiss : ProposalReviewAction
    data class Ask(val confirmation: ProposalReviewConfirmation) : ProposalReviewAction
    data class Submit(val confirmation: ProposalReviewConfirmation) : ProposalReviewAction
    data class ProfileAction(val action: ProposalProfileAction) : ProposalReviewAction
}

/** 상태·확인 창은 호출부가 관리한다. 조회 완료만으로 수락/거절 동작을 만들지 않는다. */
data class ProposalReviewState(
    val context: ProposalReviewContext,
    val stage: ProposalReviewStage = ProposalReviewStage.Changed,
    val latest: ReviewedProposalProfile? = null,
    val confirmation: ProposalReviewConfirmation? = null,
) {
    val current: ReviewedProposalProfile? get() = latest?.takeIf {
        (stage == ProposalReviewStage.Review || stage == ProposalReviewStage.Submitting) &&
            it.context == context && it.valid
    }
    val canChoose: Boolean get() = stage == ProposalReviewStage.Review && current?.profile?.canRespond == true &&
        current?.profile?.myDecision == MyProposalDecision.NotResponded
    val visibleConfirmation: ProposalReviewConfirmation? get() = confirmation?.takeIf {
        current != null && it.snapshot == current && current!!.profile.canRespond &&
            current!!.profile.myDecision == MyProposalDecision.NotResponded
    }
    val submitAction: ProposalReviewAction.Submit? get() = visibleConfirmation?.takeIf { canChoose }
        ?.let { ProposalReviewAction.Submit(it) }

    fun ask(choice: ProposalChoice): ProposalReviewConfirmation? = current?.takeIf { canChoose }
        ?.let { ProposalReviewConfirmation(choice, it) }

    fun profileChanged() = copy(stage = ProposalReviewStage.Changed, latest = null, confirmation = null)
    fun beginReload() = copy(context = context.copy(generation = context.generation + 1),
        stage = ProposalReviewStage.Loading, latest = null, confirmation = null)
    fun received(snapshot: ReviewedProposalProfile): ProposalReviewState =
        if (stage == ProposalReviewStage.Loading && snapshot.context == context)
            copy(stage = ProposalReviewStage.Review, latest = snapshot, confirmation = null) else this
}
