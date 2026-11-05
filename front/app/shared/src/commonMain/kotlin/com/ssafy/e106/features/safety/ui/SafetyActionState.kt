package com.ssafy.e106.features.safety.ui

enum class SafetyOperation { Block, Leave }
enum class SafetyContext { Proposal, Chat }
enum class SafetyPhase { Overview, Confirm, Processing, Failed, Unknown, Succeeded, ClosedUnconfirmed, Unavailable }
enum class SafetyUiAction { OpenConfirmation, Confirm, Cancel, Retry, CheckResult, Back, Close }

/** 요청의 결과는 호출부가 판별한다. 방 CLOSED만으로 Succeeded를 지정하지 않는다. */
data class SafetyActionState(
    val operation: SafetyOperation,
    val context: SafetyContext,
    val phase: SafetyPhase = SafetyPhase.Overview,
    val canRequest: Boolean = false,
    val targetLabel: String? = null,
) {
    val displayPhase: SafetyPhase get() = when {
        operation == SafetyOperation.Leave && context != SafetyContext.Chat -> SafetyPhase.Unavailable
        !canRequest && phase in listOf(SafetyPhase.Overview, SafetyPhase.Confirm, SafetyPhase.Failed) -> SafetyPhase.Unavailable
        else -> phase
    }
}
