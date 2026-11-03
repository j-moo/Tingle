package com.ssafy.e106.features.account.ui

enum class AiConsentStage { Loading, Ready, ConfirmWithdrawal, Saving, Failed, Unknown, VersionChanged, LoginRequired, Forbidden }
data class AiConsentScope(val userId: Long, val generation: Long) {
    val valid get() = userId > 0 && generation > 0
}
/** GET/PUT 응답의 표시 값. grantedAtLabel은 서버 시각을 표시 언어로 변환한 값이며 앱이 생성하지 않는다. */
data class AiConsentSnapshot(val enabled: Boolean, val noticeVersion: String, val grantedAtLabel: String? = null)
/** 실제 게시 고지문을 받은 뒤 전달한다. 요약 문구나 빈 전문을 실제 고지로 대체하지 않는다. */
data class AiConsentNotice(val version: String, val body: String, val contentUrl: String? = null)
data class AiConsentState(
    val scope: AiConsentScope? = null,
    val stage: AiConsentStage = AiConsentStage.Loading,
    val snapshot: AiConsentSnapshot? = null,
    val notice: AiConsentNotice? = null,
    val reviewedVersion: String? = null,
    val checked: Boolean = false,
    val pendingEnabled: Boolean? = null,
    val savedEnabled: Boolean? = null,
) {
    val busy get() = stage == AiConsentStage.Saving
    val noticeAvailable get() = snapshot != null && notice?.version == snapshot.noticeVersion && (notice.body.isNotBlank() || notice.contentUrl != null)
    val canGrant get() = stage == AiConsentStage.Ready && snapshot?.enabled == false && noticeAvailable &&
        reviewedVersion == snapshot.noticeVersion && checked
    val canWithdraw get() = stage == AiConsentStage.Ready && snapshot?.enabled == true && snapshot.noticeVersion.isNotBlank()
    fun begin(next: AiConsentScope) = AiConsentState(scope = next,
        stage = if (next.valid) AiConsentStage.Loading else AiConsentStage.Forbidden)
    fun loaded(request: AiConsentScope, value: AiConsentSnapshot, document: AiConsentNotice?): AiConsentState {
        if (scope != request || !request.valid || stage != AiConsentStage.Loading) return this
        if (value.noticeVersion.isBlank() || value.noticeVersion.length > 100) return copy(stage = AiConsentStage.Failed)
        return copy(stage = AiConsentStage.Ready, snapshot = value, notice = document,
            reviewedVersion = null, checked = false, pendingEnabled = null, savedEnabled = null)
    }
    fun reviewed() = if (stage == AiConsentStage.Ready && noticeAvailable) copy(reviewedVersion = notice?.version) else this
    fun check(value: Boolean) = if (stage == AiConsentStage.Ready && noticeAvailable && reviewedVersion == snapshot?.noticeVersion)
        copy(checked = value) else this
    fun requestWithdrawal() = if (canWithdraw) copy(stage = AiConsentStage.ConfirmWithdrawal, savedEnabled = null) else this
    fun cancelWithdrawal() = if (stage == AiConsentStage.ConfirmWithdrawal) copy(stage = AiConsentStage.Ready) else this
    fun submitGrant() = if (canGrant) copy(stage = AiConsentStage.Saving, pendingEnabled = true, savedEnabled = null) else this
    fun confirmWithdrawal() = if (stage == AiConsentStage.ConfirmWithdrawal && snapshot?.enabled == true)
        copy(stage = AiConsentStage.Saving, pendingEnabled = false, savedEnabled = null) else this
    fun saved(request: AiConsentScope, value: AiConsentSnapshot): AiConsentState {
        if (scope != request || stage != AiConsentStage.Saving) return this
        if (value.enabled != pendingEnabled || value.noticeVersion != snapshot?.noticeVersion)
            return failed(request, "RESULT_UNKNOWN")
        return copy(stage = AiConsentStage.Ready, snapshot = value, pendingEnabled = null,
            reviewedVersion = null, checked = false, savedEnabled = value.enabled)
    }
    /** 응답 유실·일시 실패는 성공/실패로 단정하지 않고 GET 확인부터 한다. 자동 PUT 재시도 없음. */
    fun failed(request: AiConsentScope, code: String): AiConsentState {
        if (scope != request || stage !in listOf(AiConsentStage.Loading, AiConsentStage.Saving)) return this
        val next = when (code) {
            "AGREEMENT_VERSION_OUTDATED" -> AiConsentStage.VersionChanged
            "AUTH_REQUIRED", "ACCESS_TOKEN_INVALID", "SESSION_REVOKED" -> AiConsentStage.LoginRequired
            "FORBIDDEN" -> AiConsentStage.Forbidden
            else -> if (stage == AiConsentStage.Saving) AiConsentStage.Unknown else AiConsentStage.Failed
        }
        return copy(stage = next, snapshot = null, notice = null, reviewedVersion = null,
            checked = false, pendingEnabled = null, savedEnabled = null)
    }
}
enum class AiConsentAction { Back, Reload, OpenNotice, CheckResult, Grant, Withdraw, CancelWithdrawal, ConfirmWithdrawal, Login }
