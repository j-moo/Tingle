package com.ssafy.e106.features.proposals.ui

enum class ProposalChoice { Accept, Reject }

/** 서버의 확정 결과를 호출부가 매핑한다. 통신 실패는 NotMatched로 매핑하지 않는다. */
enum class ProposalDecisionStage {
    Checking, Unknown, Waiting, MatchedPreparing, MatchedReady,
    NotMatched, Rejected, Expired, Unavailable, ProfileChanged,
}

data class ProposalDecisionState(
    val stage: ProposalDecisionStage,
    val deadlineLabel: String? = null,
)

enum class ProposalDecisionAction { CheckResult, OpenChat, ProposalList, ReviewProfile }
