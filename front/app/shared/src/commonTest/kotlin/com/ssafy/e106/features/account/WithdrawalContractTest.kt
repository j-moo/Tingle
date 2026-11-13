package com.ssafy.e106.features.account

import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.data.*
import com.ssafy.e106.features.account.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

private const val id = "11111111-1111-4111-8111-111111111111"
private const val receipt = "synthetic-receipt-only"
private val eligible = WithdrawalEligibility(true, 0, 0, emptyList(), "2026-11-06T00:00:00Z")
private val prepared = WithdrawalOperation(id, "PREPARED", "2026-11-06T00:00:00Z", "2026-11-06T00:30:00Z",
    externalCleanupStatus = "NOT_STARTED", devicePermanentlyBlocked = false)
private val completed = prepared.copy(status = "COMPLETED", completedAt = "2026-11-06T00:01:00Z",
    rejoinAvailableAt = "2026-11-09T00:01:00Z", expiresAt = "2026-11-09T00:01:00Z", externalCleanupStatus = "PENDING")

/** 합성 응답은 테스트에만 사용한다. 실제 계정의 탈퇴나 결과 영수증을 사용하지 않는다. */
class WithdrawalContractTest {
    @Test fun zeroCountDoesNotOverridePendingResetOrUnknownBlocker() {
        assertEquals(WithdrawalStage.Blocked, WithdrawalState().checked(eligible.copy(eligible = false,
            pendingResetCount = 1, blockingCodes = listOf("WITHDRAWAL_RESET_PENDING"))).stage)
        assertEquals(WithdrawalStage.Blocked, WithdrawalState().checked(eligible.copy(eligible = false,
            blockingCodes = listOf("NEW_POLICY"))).stage)
    }
    @Test fun missingOrContradictoryEligibilityCannotPrepare() {
        for (value in listOf(eligible.copy(registeredKeyringCount = -1), eligible.copy(registeredKeyringCount = 1),
            eligible.copy(checkedAt = ""), eligible.copy(eligible = false))) {
            val state = WithdrawalState().checked(value)
            assertFalse(state.canPrepare); assertEquals(WithdrawalStage.Unavailable, state.stage)
        }
        assertFalse(WithdrawalState(stage = WithdrawalStage.Ready).canPrepare)
    }
    @Test fun preparationNeverCompletesWithdrawalAndRequiresFreshConsent() {
        val state = WithdrawalState().checked(eligible).preparing().prepared(prepared)
        assertEquals(WithdrawalStage.Confirm, state.stage); assertFalse(state.canExecute)
        assertEquals(state, state.executing())
        val checked = state.checkAccepted(true)
        assertTrue(checked.canExecute)
        assertEquals(WithdrawalStage.Executing, checked.executing().stage)
        assertFalse(checked.prepared(prepared).canExecute)
    }
    @Test fun timeoutAndSessionLossDoNotMeanSuccessOrAllowNewPrepare() {
        val state = WithdrawalState().prepared(prepared).checkAccepted(true).executing().resultUnknown()
        assertEquals(WithdrawalStage.Unknown, state.stage)
        assertFalse(state.canExecute); assertFalse(state.canPrepare)
        assertEquals(id, state.operation?.withdrawalId)
    }
    @Test fun resultForDifferentOperationCannotReplaceCurrentOne() {
        val state = WithdrawalState().prepared(prepared).resolved(completed.copy(withdrawalId = "22222222-2222-4222-8222-222222222222"))
        assertEquals(WithdrawalStage.Unknown, state.stage); assertEquals(id, state.operation?.withdrawalId)
    }
    @Test fun completedDatabaseAndPendingPhotoDeletionRemainSeparate() {
        val state = WithdrawalState().prepared(prepared).resolved(completed)
        assertEquals(WithdrawalStage.Completed, state.displayStage)
        assertEquals("PENDING", state.operation?.externalCleanupStatus)
        assertEquals("2026-11-09T00:01:00Z", state.operation?.rejoinAvailableAt)
    }
    @Test fun malformedOrUnexpectedCompletionNeverShowsSuccess() {
        for (value in listOf(completed.copy(completedAt = null), completed.copy(rejoinAvailableAt = "bad"),
            completed.copy(status = "NEW_STATE"), completed.copy(devicePermanentlyBlocked = true))) {
            assertEquals(WithdrawalStage.Unknown, WithdrawalState().prepared(prepared).resolved(value).stage)
            assertEquals(WithdrawalStage.Unknown, WithdrawalState(stage = WithdrawalStage.Completed, operation = value).displayStage)
        }
    }
    @Test fun unknownCleanupIsNotConvertedToCompleted() {
        val state = WithdrawalState().prepared(prepared).resolved(completed.copy(externalCleanupStatus = "UNKNOWN"))
        assertEquals(WithdrawalStage.Completed, state.stage)
        assertEquals("UNKNOWN", state.operation?.externalCleanupStatus)
    }
    @Test fun recoveryOfPreparedStillRequiresNewExplicitConsent() {
        val state = WithdrawalState().prepared(prepared).checkAccepted(true).resultUnknown().resolved(prepared)
        assertEquals(WithdrawalStage.Confirm, state.stage); assertFalse(state.canExecute)
    }
    @Test fun wirePathsBodiesReceiptAndRepeatedPreparationKeyMatchCandidateContract() = runTest {
        val calls = mutableListOf<String>()
        val auth = createHttpClient("https://test/dev/api/", false, MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            val content = request.body.toByteArray().decodeToString()
            val body = when {
                request.method == HttpMethod.Delete -> return@MockEngine respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.endsWith("withdrawal-eligibility") -> ApiJson.encodeToString(eligible)
                request.url.encodedPath.endsWith("execute") -> {
                    assertEquals(receipt, request.headers["X-Withdrawal-Receipt"])
                    assertEquals(Json.parseToJsonElement("""{"confirmed":true}"""), Json.parseToJsonElement(content))
                    ApiJson.encodeToString(completed)
                }
                else -> {
                    assertEquals(id, request.headers["Idempotency-Key"]); assertEquals("", content)
                    ApiJson.encodeToString(WithdrawalPrepared(receipt, prepared))
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var results = 0
        val public = createHttpClient("https://test/dev/api/", false, MockEngine { request ->
            results++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/dev/api/withdrawals/$id/result", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(receipt, request.headers["X-Withdrawal-Receipt"])
            assertEquals("", request.url.encodedQuery)
            assertEquals("", request.body.toByteArray().decodeToString())
            respond(ApiJson.encodeToString(completed), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = WithdrawalApi(auth, public)
            assertIs<ApiResult.Ok<WithdrawalEligibility>>(api.eligibility(75))
            repeat(2) { assertIs<ApiResult.Ok<WithdrawalPrepared>>(api.prepare(75, id)) }
            assertIs<ApiResult.Ok<WithdrawalOperation>>(api.execute(75, id, receipt))
            assertIs<ApiResult.Ok<WithdrawalOperation>>(api.result(id, receipt))
            assertIs<ApiResult.Ok<Unit>>(api.cancel(75, id))
            assertEquals(listOf("GET /dev/api/users/75/withdrawal-eligibility", "POST /dev/api/users/75/withdrawals",
                "POST /dev/api/users/75/withdrawals", "POST /dev/api/users/75/withdrawals/$id/execute",
                "DELETE /dev/api/users/75/withdrawals/$id"), calls)
            assertEquals(1, results)
        } finally { auth.close(); public.close() }
    }
    @Test fun sensitivePreparationAndParseErrorAreRedacted() = runTest {
        assertFalse(WithdrawalPrepared(receipt, prepared).toString().contains(receipt))
        val client = createHttpClient("https://test/dev/api/", false, MockEngine {
            respond("""{"receiptToken":"$receipt","operation":42}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val failure = assertIs<ApiResult.Fail>(WithdrawalApi(client, client).prepare(75, id))
            assertFalse(assertIs<AppError.Unknown>(failure.error).cause.toString().contains(receipt))
        } finally { client.close() }
    }
    @Test fun candidateErrorsRemainDistinct() = runTest {
        for ((code, status) in listOf("WITHDRAWAL_KEYRING_REGISTERED" to 409, "WITHDRAWAL_RESET_PENDING" to 409,
            "WITHDRAWAL_OPERATION_EXPIRED" to 410, "POLICY_UNRESOLVED" to 422)) {
            val client = createHttpClient("https://test/dev/api/", false, MockEngine {
                respond("""{"code":"$code","message":"blocked"}""", HttpStatusCode.fromValue(status),
                    headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try { assertEquals(code, assertIs<AppError.Server>(assertIs<ApiResult.Fail>(
                WithdrawalApi(client, client).result(id, receipt)).error).code) }
            finally { client.close() }
        }
    }
    @Test fun requestFailureDoesNotAutomaticallyRepeatDestructiveExecution() = runTest {
        var calls = 0
        val client = createHttpClient("https://test/dev/api/", false, MockEngine { calls++; throw IOException("offline") })
        try {
            assertEquals(AppError.Offline, assertIs<ApiResult.Fail>(WithdrawalApi(client, client).execute(75, id, receipt)).error)
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun coroutineCancellationPropagates() = runTest {
        val client = createHttpClient("https://test/dev/api/", false, MockEngine { throw CancellationException("cancelled") })
        try { assertFailsWith<CancellationException> { WithdrawalApi(client, client).prepare(75, id) } }
        finally { client.close() }
    }
    @Test fun invalidIdentifiersCannotEscapeRequestPath() = runTest {
        var calls = 0
        val client = createHttpClient("https://test/dev/api/", false, MockEngine { calls++; respond("") })
        try {
            assertFailsWith<IllegalArgumentException> { WithdrawalApi(client, client).result("../other", receipt) }
            assertFailsWith<IllegalArgumentException> { WithdrawalApi(client, client).execute(75, id, "secret\r\ninjection") }
            assertEquals(0, calls)
        } finally { client.close() }
    }
}
