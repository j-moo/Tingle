package com.ssafy.e106.features.account.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable

/** develop에 병합된 BE268 회원탈퇴 계약. 서버 활성화 전 오류를 성공으로 바꾸지 않는다. */
@Serializable
data class WithdrawalEligibility(
    val eligible: Boolean,
    val registeredKeyringCount: Long,
    val pendingResetCount: Long,
    val blockingCodes: List<String>,
    val checkedAt: String,
)

/** DB 탈퇴 완료와 사진 등 외부 파일 정리 완료는 별개다. 알 수 없는 상태도 문자열 그대로 보존한다. */
@Serializable
data class WithdrawalOperation(
    val withdrawalId: String,
    val status: String,
    val preparedAt: String,
    val expiresAt: String,
    val completedAt: String? = null,
    val rejoinAvailableAt: String? = null,
    val externalCleanupStatus: String,
    val devicePermanentlyBlocked: Boolean,
)

/** 영수증은 인증 정보이므로 화면 상태/로그/AppPrefs에 복사하지 않는다. 자동 toString도 생성하지 않는다. */
@Serializable
class WithdrawalPrepared(val receiptToken: String, val operation: WithdrawalOperation) {
    override fun toString() = "WithdrawalPrepared(REDACTED)"
}

@Serializable
// 공통 JSON은 기본값을 생략한다. 필수 동의값이 {}로 직렬화되지 않도록 기본값 없이 명시한다.
private class WithdrawalConfirmation(val confirmed: Boolean)

/**
 * HTTP 어댑터. 재시도·새 실행 생성·탈퇴 후 정리는 Coordinator가 내구 기록과 함께 관리한다.
 * receiptClient는 FE276의 무인증/본문·영수증 헤더 비기록 클라이언트를 주입한다.
 * 로그인 클라이언트를 재사용하면 결과 확인 중 401이 새 계정 세션에 영향을 줄 수 있어 분리한다.
 * 영수증은 암호화 저널을 거쳐서만 실행/복구한다.
 */
internal interface WithdrawalRepository {
    suspend fun eligibility(userId: Long): ApiResult<WithdrawalEligibility>
    suspend fun prepare(userId: Long, requestKey: String): ApiResult<WithdrawalPrepared>
    suspend fun execute(userId: Long, withdrawalId: String, receipt: String): ApiResult<WithdrawalOperation>
    suspend fun result(withdrawalId: String, receipt: String): ApiResult<WithdrawalOperation>
    suspend fun cancel(userId: Long, withdrawalId: String): ApiResult<Unit>
}

internal class WithdrawalApi(
    private val authenticatedClient: HttpClient,
    private val receiptClient: HttpClient,
) : WithdrawalRepository {
    override suspend fun eligibility(userId: Long): ApiResult<WithdrawalEligibility> {
        require(userId > 0)
        return authenticatedClient.safeWithdrawalCall { get("users/$userId/withdrawal-eligibility") }
    }

    // 요청 전에 같은 키를 내구 저장해야 한다. 응답 유실 때 새 UUID로 바꾸는 것은 호출자도 금지한다.
    override suspend fun prepare(userId: Long, requestKey: String): ApiResult<WithdrawalPrepared> {
        require(userId > 0 && withdrawalUuid(requestKey))
        return authenticatedClient.safeWithdrawalCall {
            post("users/$userId/withdrawals") { header("Idempotency-Key", requestKey) }
        }
    }

    // 영수증 저장 확인과 최종 사용자 동의 이후에만 호출한다. 타임아웃을 탈퇴 실패로 단정하지 않는다.
    override suspend fun execute(userId: Long, withdrawalId: String, receipt: String): ApiResult<WithdrawalOperation> {
        require(userId > 0 && withdrawalUuid(withdrawalId) && safeReceipt(receipt))
        return authenticatedClient.safeWithdrawalCall {
            post("users/$userId/withdrawals/$withdrawalId/execute") {
                header("X-Withdrawal-Receipt", receipt)
                setBody(WithdrawalConfirmation(confirmed = true))
            }
        }
    }

    // 탈퇴 후 JWT가 무효여도 이 경로로 조회한다. 영수증은 URL/본문에 포함하지 않는다.
    override suspend fun result(withdrawalId: String, receipt: String): ApiResult<WithdrawalOperation> {
        require(withdrawalUuid(withdrawalId) && safeReceipt(receipt))
        return receiptClient.safeWithdrawalCall {
            post("withdrawals/$withdrawalId/result") { header("X-Withdrawal-Receipt", receipt) }
        }
    }

    // PREPARED 취소일 뿐 완료된 탈퇴 복구가 아니다. 실행 결과가 불명확하면 result를 먼저 호출한다.
    override suspend fun cancel(userId: Long, withdrawalId: String): ApiResult<Unit> {
        require(userId > 0 && withdrawalUuid(withdrawalId))
        return authenticatedClient.safeWithdrawalCall { delete("users/$userId/withdrawals/$withdrawalId") }
    }
}

internal fun withdrawalUuid(value: String) =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)

private fun safeReceipt(value: String) = value.isNotBlank() && value.none { it.isWhitespace() || it.code < 32 }

/** JSON 파싱 예외에 영수증 일부가 포함될 수 있어 원인 객체를 버린다. 코루틴 취소는 call에서 그대로 전파한다. */
private suspend inline fun <reified T> HttpClient.safeWithdrawalCall(
    crossinline request: suspend HttpClient.() -> HttpResponse,
): ApiResult<T> = when (val result = call<T>(request)) {
    is ApiResult.Fail -> if (result.error is AppError.Unknown)
        ApiResult.Fail(AppError.Unknown(IllegalStateException("Withdrawal response unavailable"))) else result
    is ApiResult.Ok -> result
}
