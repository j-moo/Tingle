package com.ssafy.e106.features.proposals.ui

enum class MatchReasonStatus { Pending, Ready, Failed, Unknown }

/** 생성 요청의 상태 정보. 조회 응답에는 retryable/pollAfterSeconds가 없으므로 추정하지 않는다. */
data class MatchReasonTask(val status: String, val retryable: Boolean, val pollAfterSeconds: Long)

/** 공개 조회 응답의 표시용 입력. 통신/역직렬화/권한 확인은 후속 연결부의 책임이다. */
data class MatchReasonResult(val status: String, val source: String? = null, val text: String? = null)

data class MatchReasonState(
    val status: MatchReasonStatus = MatchReasonStatus.Unknown,
    val text: String? = null,
    val source: String? = null,
    val retryable: Boolean = false,
    val pollAfterSeconds: Long? = null,
    val busy: Boolean = false,
    val accessible: Boolean = true,
) {
    val visibleText: String? get() = text?.takeIf {
        accessible && status == MatchReasonStatus.Ready && source == "RULE_FALLBACK" && it.isNotBlank()
    }
    val canRetry: Boolean get() = accessible && !busy && status == MatchReasonStatus.Failed && retryable
    val canCheck: Boolean get() = accessible && !busy &&
        (status == MatchReasonStatus.Unknown || status == MatchReasonStatus.Pending || status == MatchReasonStatus.Ready && visibleText == null)
    // 서버가 알려준 PENDING 조회 간격만 전달한다. 타이머·자동 요청을 실행하지 않는다.
    val nextPollSeconds: Long? get() = pollAfterSeconds?.takeIf {
        accessible && !busy && status == MatchReasonStatus.Pending && it > 0
    }

    companion object {
        fun fromTask(task: MatchReasonTask): MatchReasonState = when (task.status) {
            "PENDING" -> MatchReasonState(MatchReasonStatus.Pending, pollAfterSeconds = task.pollAfterSeconds)
            "FAILED" -> MatchReasonState(MatchReasonStatus.Failed, retryable = task.retryable)
            // POST 성공은 본문을 제공하지 않는다. GET으로 설명을 확인한다.
            else -> MatchReasonState()
        }

        fun fromResult(result: MatchReasonResult): MatchReasonState = when (result.status) {
            "PENDING" -> MatchReasonState(MatchReasonStatus.Pending)
            "FAILED" -> MatchReasonState(MatchReasonStatus.Failed)
            "SUCCEEDED" -> if (result.source == "RULE_FALLBACK" && !result.text.isNullOrBlank())
                MatchReasonState(MatchReasonStatus.Ready, result.text, result.source) else MatchReasonState()
            else -> MatchReasonState()
        }
    }
}
