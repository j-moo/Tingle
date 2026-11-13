package com.ssafy.e106.features.proposals

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.proposals.reason.*
import com.ssafy.e106.features.proposals.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private class ReasonServer : MatchReasonRepository {
    val keys = mutableListOf<String>()
    var task = ReasonTask(10, "MATCH_EXPLANATION", "SUCCEEDED", "/api/match-proposals/10/explanation", null, false, 0)
    var result = ReasonResult("SUCCEEDED", "RULE_FALLBACK", "함께 산책하는 시간을 선호해요.", emptyList(), "v1")
    var onRequest: (suspend () -> ApiResult<ReasonTask>)? = null
    var onGet: (suspend () -> ApiResult<ReasonResult>)? = null
    override suspend fun request(id: Long, key: String): ApiResult<ReasonTask> { keys += key; return onRequest?.invoke() ?: ApiResult.Ok(task) }
    override suspend fun get(id: Long) = onGet?.invoke() ?: ApiResult.Ok(result)
}
@OptIn(ExperimentalCoroutinesApi::class)
class MatchReasonFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(server: ReasonServer, session: TestSession = TestSession(OnboardingStatus.READY)) =
        MatchReasonViewModel(server, session).also { store.put("reason", it); it.bind(ProposalReviewContext(1, 10, 1)) }
    @Test fun generatedAndRuleTextAreVisibleButUnknownSourceIsNot() = runTest(dispatcher) {
        val server = ReasonServer(); val m = vm(server); m.check(); runCurrent()
        assertEquals(server.result.text, m.state.value.view.visibleText)
        server.result = server.result.copy(source = "GENERATED", text = "두 분 모두 조용한 산책을 좋아해요."); m.check(); runCurrent()
        assertEquals("두 분 모두 조용한 산책을 좋아해요.", m.state.value.view.visibleText)
        server.result = server.result.copy(source = "UNREVIEWED"); m.check(); runCurrent()
        assertNull(m.state.value.view.visibleText)
    }
    @Test fun networkRetryUsesSameKeyAndDoesNotInventAnExplanation() = runTest(dispatcher) {
        val server = ReasonServer(); server.onRequest = { ApiResult.Fail(AppError.Offline) }
        val m = vm(server); m.check(); runCurrent(); m.check(); runCurrent()
        assertEquals(2, server.keys.size); assertEquals(1, server.keys.distinct().size); assertNull(m.state.value.view.visibleText)
    }
    @Test fun explicitRetryOnlyWhenServerAllowsIt() = runTest(dispatcher) {
        val server = ReasonServer(); server.task = server.task.copy(status = "FAILED", retryable = false)
        val m = vm(server); m.check(); runCurrent(); m.check(retry = true); runCurrent()
        assertEquals(1, server.keys.size)
        m.bind(ProposalReviewContext(1, 10, 2)); server.task = server.task.copy(retryable = true)
        m.check(); runCurrent(); m.check(retry = true); runCurrent()
        assertNotEquals(server.keys[1], server.keys[2])
    }
    @Test fun lateResultAfterExitIsDiscarded() = runTest(dispatcher) {
        val server = ReasonServer(); val gate = CompletableDeferred<Unit>()
        server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.result) }
        val m = vm(server); m.check(); runCurrent(); m.bind(null); gate.complete(Unit); runCurrent()
        assertNull(m.state.value.context); assertNull(m.state.value.view.visibleText)
    }
    @Test fun wrongProposalOrStatusUrlIsNeverFollowed() = runTest(dispatcher) {
        val server = ReasonServer(); server.task = server.task.copy(statusUrl = "https://elsewhere.test/secret")
        val m = vm(server); m.check(); runCurrent(); assertNotNull(m.state.value.error); assertNull(m.state.value.view.visibleText)
    }
    @Test fun pendingPollingStopsAfterThirtyChecks() = runTest(dispatcher) {
        val server = ReasonServer(); server.task = server.task.copy(status = "PENDING", pollAfterSeconds = 1)
        var gets = 0; server.onGet = { gets++; ApiResult.Ok(ReasonResult("PENDING", null, null, emptyList(), null)) }
        val m = vm(server); m.check(); advanceUntilIdle()
        assertEquals(30, gets); assertFalse(m.state.value.view.busy); assertTrue(m.state.value.view.canCheck)
    }
}
