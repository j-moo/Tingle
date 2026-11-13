package com.ssafy.e106.features.proposals.ui

enum class ProposalChoice { Accept, Reject }

/**
 * 결과 화면은 끝난 제안과 확인이 필요한 경우만. 서버의 확정 결과를 호출부가 매핑하고, 통신 실패는 NotMatched로 매핑하지 않는다.
 * 수락 후 기다림은 프로필 화면, 서로 수락은 바로 채팅, 거절은 목록으로 간다.
 */
enum class ProposalDecisionStage { Checking, Unknown, NotMatched, Expired, Unavailable }

data class ProposalDecisionState(val stage: ProposalDecisionStage)

enum class ProposalDecisionAction { CheckResult, ProposalList }
