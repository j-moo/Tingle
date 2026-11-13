package com.ssafy.e106.features.account.ui

import com.ssafy.e106.features.account.data.WithdrawalEligibility
import com.ssafy.e106.features.account.data.WithdrawalOperation
import com.ssafy.e106.features.account.data.withdrawalUuid
import kotlin.time.Instant

enum class WithdrawalStage { Loading, Unavailable, Ready, Blocked, Preparing, Confirm, Executing, Unknown, Expired, Completed }
enum class WithdrawalAction { Back, Reload, OpenKeyring, Prepare, Execute, CancelPreparation, CheckResult, OpenStart }

/**
 * HTTP/저장소에 의존하지 않는 표시 상태. 영수증은 보관하지 않으며 예시를 넘겨 Preview할 수 있다.
 * 회전 후에도 확인 체크를 유지할지는 실제 ViewModel 연결 시 정한다. 현재는 새 준비/조회마다 해제한다.
 */
data class WithdrawalState(
    val stage: WithdrawalStage = WithdrawalStage.Loading,
    val eligibility: WithdrawalEligibility? = null,
    val operation: WithdrawalOperation? = null,
    val accepted: Boolean = false,
    val localCleanupPending: Boolean = false,
    val canCancelPreparation: Boolean = true,
    // 실행 이력이 확인되지 않으면 보수적으로 결과 확인 안내를 쓴다. 준비만 실패한 경우에만 false다.
    val executionRequested: Boolean = true,
    // 취소 재시도와 준비/실행 결과 재조회를 사용자 안내에서도 구분한다.
    val cancellationRequested: Boolean = false,
) {
    // 화면 복원/직접 데이터 주입에서도 불완전한 완료·허용 상태를 성공으로 그리지 않는다.
    val displayStage get() = when {
        stage == WithdrawalStage.Completed && operation?.validCompleted() != true -> WithdrawalStage.Unknown
        stage == WithdrawalStage.Confirm && operation?.validPrepared() != true -> WithdrawalStage.Unknown
        stage == WithdrawalStage.Ready && eligibility?.safeToPrepare() != true -> WithdrawalStage.Unavailable
        else -> stage
    }
    val busy get() = stage in setOf(WithdrawalStage.Loading, WithdrawalStage.Preparing, WithdrawalStage.Executing)
    val canPrepare get() = stage == WithdrawalStage.Ready && eligibility?.safeToPrepare() == true
    val canExecute get() = stage == WithdrawalStage.Confirm && accepted && operation?.validPrepared() == true

    /** 모순/미확인 응답을 0개 또는 허용으로 변환하지 않는다. 조회 허용은 실행 시 서버 재검사를 대신하지 않는다. */
    fun checked(value: WithdrawalEligibility) = copy(
        stage = when {
            !value.valid() -> WithdrawalStage.Unavailable
            value.safeToPrepare() -> WithdrawalStage.Ready
            else -> WithdrawalStage.Blocked
        }, eligibility = value, operation = null, accepted = false, cancellationRequested = false,
    )

    fun preparing() = if (canPrepare) copy(stage = WithdrawalStage.Preparing, accepted = false) else this

    /** 준비 응답 수신은 탈퇴 완료가 아니다. 화면의 최종 체크와 실행 버튼을 다시 거친다. */
    fun prepared(value: WithdrawalOperation) = if (value.validPrepared())
        copy(stage = WithdrawalStage.Confirm, operation = value, accepted = false)
    else copy(stage = WithdrawalStage.Unknown, accepted = false)

    fun checkAccepted(value: Boolean) = if (stage == WithdrawalStage.Confirm) copy(accepted = value) else this
    fun executing() = if (canExecute) copy(stage = WithdrawalStage.Executing, accepted = false) else this

    /** timeout/401만으로 성공 또는 실패를 만들지 않는다. 기존 영수증으로 결과 조회할 때까지 불명 상태다. */
    fun resultUnknown(executionRequested: Boolean = this.executionRequested) =
        copy(stage = WithdrawalStage.Unknown, accepted = false, executionRequested = executionRequested)

    /** 다른 실행의 늦은 응답은 반영하지 않는다. COMPLETED라도 외부 정리 상태는 그대로 별도 표시한다. */
    fun resolved(value: WithdrawalOperation): WithdrawalState {
        if (operation == null || operation.withdrawalId != value.withdrawalId) return resultUnknown()
        return when {
            value.validCompleted() -> copy(stage = WithdrawalStage.Completed, operation = value, accepted = false)
            value.validPrepared() -> prepared(value)
            else -> resultUnknown()
        }
    }
}

private fun time(value: String?) = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun WithdrawalEligibility.valid() = registeredKeyringCount >= 0 && pendingResetCount >= 0 && time(checkedAt) != null &&
    eligible == (registeredKeyringCount == 0L && pendingResetCount == 0L && blockingCodes.isEmpty())
private fun WithdrawalEligibility.safeToPrepare() = valid() && eligible
private fun WithdrawalOperation.validTimes() = withdrawalUuid(withdrawalId) &&
    time(preparedAt) != null && time(expiresAt)?.let { it > time(preparedAt)!! } == true
private fun WithdrawalOperation.validPrepared() = validTimes() && status == "PREPARED" && completedAt == null &&
    rejoinAvailableAt == null && externalCleanupStatus == "NOT_STARTED" && !devicePermanentlyBlocked
private fun WithdrawalOperation.validCompleted() = validTimes() && status == "COMPLETED" &&
    time(completedAt)?.let { it >= time(preparedAt)!! } == true &&
    time(rejoinAvailableAt)?.let { it >= time(completedAt)!! } == true && !devicePermanentlyBlocked
