package com.ssafy.e106.features.proposals.ui

enum class ProposalProfileLoad { Loading, Ready, Failed, Unavailable }
/** 서버가 공개를 허용한 값만 전달한다. 원설문·생년월일·상대 응답은 받지 않는다. */
data class PublicProposalProfile(
    val nickname: String? = null,
    val age: Int? = null,
    val genderLabel: String? = null,
    val smokingLabel: String? = null,
    val drinkingLabel: String? = null,
    val religionLabel: String? = null,
    val introduction: String? = null,
    val photoState: ProposalPhotoState = ProposalPhotoState.NotProvided,
    val photoRequest: ProposalPhotoRequest? = null,
    val photoImage: ProposalPhotoImage? = null,
)

data class ProposalProfileState(
    val load: ProposalProfileLoad = ProposalProfileLoad.Loading,
    val profile: PublicProposalProfile? = null,
    val myDecision: MyProposalDecision = MyProposalDecision.NotResponded,
    // 서버의 최신 권한·기한 확인 후 호출부가 결정한다. 화면이 시간으로 추정하지 않는다.
    val canRespond: Boolean = false,
    val deadlineLabel: String? = null,
    val reason: MatchReasonState = MatchReasonState(),
    val reviewRequired: Boolean = false,
)

enum class ProposalProfileAction { Back, Reload, RetryPhoto, RetryReason, CheckReason, Accept, Reject }
