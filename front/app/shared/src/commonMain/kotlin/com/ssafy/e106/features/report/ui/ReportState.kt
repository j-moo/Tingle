package com.ssafy.e106.features.report.ui

enum class ReportReason { ABUSE, SEXUAL, SPAM, ETC }
enum class ReportStep { Form, Evidence, Confirm, Sending, Failed, Unknown, Received, Conflict }
enum class EvidenceLoad { Loading, Failed, Ready }
// Notion POST /api/reports (2026-10-29): detail 선택/1000, messageIds 선택, 개수 상한 미명시.
// API에 없는 기타 필수/증거 5개 제한을 추가하지 않는다. 전송 크기/서버 처리 상한은 별도 계약 확인 대상.
data class ReportRules(val maxDetailUnits: Int)
data class ReportEvidence(val id: Long, val roomKey: String, val mine: Boolean, val text: String, val timeLabel: String)
data class ReportState(
    val roomKey: String? = null,
    val canReport: Boolean = false,
    val rules: ReportRules? = null,
    val step: ReportStep = ReportStep.Form,
    val reason: ReportReason? = null,
    val detail: String = "",
    val evidenceLoad: EvidenceLoad = EvidenceLoad.Loading,
    val evidence: List<ReportEvidence> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val hasMoreEvidence: Boolean = false,
    // 권한 거절과 일시적인 권한 조회 실패를 구분한다. 후자만 본문을 숨긴 채 재조회를 제공한다.
    val accessRetryAvailable: Boolean = false,
) {
    val available: Boolean get() = canReport && !roomKey.isNullOrBlank() && rules != null && rules.maxDetailUnits > 0
    // 신규 신고 가능 여부와 기존 요청의 처리 상태는 별개다. 만료 후에도 결과 확인·접수 완료를 표시한다.
    val showsSubmissionStatus: Boolean get() = step in listOf(ReportStep.Sending, ReportStep.Unknown, ReportStep.Received)
    val visibleEvidence: List<ReportEvidence> get() = evidence.filter { it.roomKey == roomKey && it.id > 0 }.distinctBy { it.id }
    // 현재 BE @Size와 같은 UTF-16 단위. 확장 그래핌 정책으로 변경되면 BE/FE 함께 변경한다.
    val detailCount: Int get() = detail.length
    val validForm: Boolean get() = available && reason != null && detailCount <= rules!!.maxDetailUnits
    val validEvidence: Boolean get() = available && (selectedIds.isEmpty() ||
        (evidenceLoad == EvidenceLoad.Ready && selectedIds.all { id -> visibleEvidence.any { it.id == id } }))
    val validSubmission: Boolean get() = validForm && validEvidence
}
enum class ReportAction { Back, NextEvidence, Review, Edit, Submit, Retry, CheckResult, ReloadEvidence, LoadMoreEvidence, ClearEvidence, Close }

// Retry/CheckResult는 명세의 동일 Idempotency-Key·동일 본문 POST 재시도에 연결한다.
// 전용 GET 조회 API나 같은 사유 중복 거절 코드를 가정하지 않는다. Conflict는 IDEMPOTENCY_CONFLICT 전용이다.
