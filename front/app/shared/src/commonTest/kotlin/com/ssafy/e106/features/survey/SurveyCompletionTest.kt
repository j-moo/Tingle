package com.ssafy.e106.features.survey

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun completedAnswers() = SurveyServer().apply {
        persist("Q001", JsonPrimitive(4))
        persist("Q002", JsonArray(listOf(JsonPrimitive("walk"))))
    }
    private fun vm(server: SurveyServer, cursor: CursorMemory = CursorMemory(), session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED), name: String = "main") =
        SurveyViewModel(server, session, cursor, SurveyAccess(true)).also { models.put(name, it) }
    private fun TestScope.review(m: SurveyViewModel) {
        m.load(); runCurrent(); m.start(); runCurrent(); m.resume()
        assertEquals(SurveyPage.Review, m.state.value.page)
    }
    @Test fun failedAnalysisCanBeRequestedAgainWithoutNewSurveyOrCompletion() = runTest(dispatcher) {
        val s = completedAnswers().apply { analysisStatus = AnalysisStatus.FAILED }; val c = CursorMemory(); val m = vm(s, c)
        review(m); m.submit(); runCurrent(); assertEquals(AnalysisStatus.FAILED, m.state.value.analysisResult?.status)
        s.analysisStatus = AnalysisStatus.PENDING
        m.requestAnalysis(); m.requestAnalysis(); runCurrent()
        assertEquals(2, s.analysisRequests); assertEquals(1, s.completes); assertEquals(1, s.starts)
        assertEquals(21L, c.readAnalysis(1)?.resultId); assertEquals(AnalysisStatus.PENDING, m.state.value.analysisResult?.status)
    }
    @Test fun completionAndAnalysisSuccessRefreshStageAndExplainUnchangedResult() = runTest(dispatcher) {
        val s = completedAnswers(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(s, session = session)
        review(m); m.submit(); runCurrent(); assertEquals(1, session.refreshes)
        s.analysisStatus = AnalysisStatus.SUCCEEDED; m.refreshAnalysis(); runCurrent()
        assertEquals(2, session.refreshes); assertTrue(m.state.value.stageUnchanged)
        session.refreshResult = ApiResult.Fail(AppError.Offline); m.refreshStage(); runCurrent()
        assertEquals(AppError.Offline, m.state.value.stageError)
        assertEquals(AnalysisStatus.SUCCEEDED, m.state.value.analysisResult?.status)
        assertFalse(m.state.value.recoveryRequired)
    }
    @Test fun submitLocksCompletedAnswersAndRequestsAnalysisOnce() = runTest(dispatcher) {
        val server = completedAnswers(); val m = vm(server); review(m)
        m.submit(); m.submit(); runCurrent()
        assertEquals(1, server.completes); assertEquals(1, server.analysisRequests)
        assertEquals("COMPLETED", m.state.value.session?.status)
        assertEquals(AnalysisStatus.PENDING, m.state.value.analysisResult?.status)
        m.answer(SurveyAnswer.Unknown); m.reviewAnswers(); m.submit(); m.requestAnalysis(); runCurrent()
        assertEquals(0, server.saves); assertEquals(1, server.completes); assertEquals(1, server.analysisRequests)
        assertEquals(SurveyPage.Completed, m.state.value.page)
    }
    @Test fun lostCompleteResponseReadsCompletedSessionWithoutSubmittingAgain() = runTest(dispatcher) {
        val server = completedAnswers().apply { loseComplete = true }; val m = vm(server); review(m)
        m.submit(); runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertEquals(0, server.analysisRequests)
        m.load(); runCurrent()
        assertEquals(SurveyPage.Completed, m.state.value.page)
        m.requestAnalysis(); runCurrent()
        assertEquals(1, server.completes); assertEquals(1, server.analysisRequests)
    }
    @Test fun analysisFailureDoesNotRollBackSubmissionAndRequiresExplicitRequestAfterRead() = runTest(dispatcher) {
        val server = completedAnswers().apply { failAnalysisRequest = true }; val m = vm(server); review(m)
        m.submit(); runCurrent()
        assertEquals("COMPLETED", m.state.value.session?.status)
        assertTrue(m.state.value.recoveryRequired)
        m.requestAnalysis(); runCurrent(); assertEquals(1, server.analysisRequests)
        m.load(); runCurrent(); assertEquals(SurveyPage.Completed, m.state.value.page)
        server.failAnalysisRequest = false; m.requestAnalysis(); runCurrent()
        assertEquals(1, server.completes); assertEquals(2, server.analysisRequests)
    }
    @Test fun restartReadsRememberedResultWithoutRepostingAnalysisAndDistinguishesOutcomes() = runTest(dispatcher) {
        val server = completedAnswers(); val cursor = CursorMemory(); val m = vm(server, cursor); review(m)
        m.submit(); runCurrent(); m.leave()
        server.analysisStatus = AnalysisStatus.SUCCEEDED
        val restored = vm(server, cursor, name = "restored"); restored.load(); runCurrent()
        assertEquals(AnalysisStatus.SUCCEEDED, restored.state.value.analysisResult?.status)
        assertEquals(1, server.analysisRequests)
        for (status in listOf(AnalysisStatus.INSUFFICIENT, AnalysisStatus.PENDING)) {
            server.analysisStatus = status; restored.refreshAnalysis(); runCurrent()
            assertEquals(status, restored.state.value.analysisResult?.status)
            restored.requestAnalysis(); runCurrent(); assertEquals(1, server.analysisRequests)
        }
    }
    @Test fun resultFromOtherSessionIsRejected() = runTest(dispatcher) {
        val server = completedAnswers().apply { analysisSessionId = 999 }; val m = vm(server); review(m)
        m.submit(); runCurrent()
        assertNull(m.state.value.analysisResult); assertTrue(m.state.value.recoveryRequired)
        assertEquals("COMPLETED", m.state.value.session?.status)
    }
    @Test fun lateAnalysisCannotReturnAfterLeavingOrChangingAccount() = runTest(dispatcher) {
        for (accountChange in listOf(false, true)) {
            val server = completedAnswers(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
            val m = vm(server, session = session, name = accountChange.toString()); review(m)
            val pending = CompletableDeferred<ApiResult<AnalysisResult>>(); server.pendingAnalysis = pending
            m.submit(); runCurrent()
            if (accountChange) session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false) else m.requestLeave()
            runCurrent()
            if (accountChange) {
                // 새 계정의 정상 화면을 먼저 구성해 이전 분석이 NPE 없이도 덮어쓸 수 있는 상황을 검증한다.
                server.pendingAnalysis = null; server.data = server.data.copy(sessionId = 22, status = "DRAFT")
                m.load(); runCurrent(); m.start(); runCurrent(); m.resume()
                assertEquals(22L, m.state.value.session?.sessionId)
            }
            val before = m.state.value
            pending.complete(ApiResult.Ok(AnalysisResult(21, 11, AnalysisManifest("v1", "a1"), AnalysisStatus.SUCCEEDED))); runCurrent()
            if (accountChange) assertEquals(before, m.state.value) else { m.leave(); assertNull(m.state.value.session) }
            assertNull(m.state.value.analysisResult); assertFalse(m.state.value.busy)
        }
    }
    @Test fun missingAnswersCannotBeSubmittedAndAnalysisIsNotRequestedForDraft() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); m.load(); runCurrent(); m.start(); runCurrent()
        m.submit(); m.requestAnalysis(); runCurrent()
        assertEquals(0, server.completes); assertEquals(0, server.analysisRequests)
    }
    @Test fun staleAnalysisReferenceFromDifferentSessionIsNotQueried() = runTest(dispatcher) {
        val server = completedAnswers().apply { data = data.copy(status = "COMPLETED") }
        val cursor = CursorMemory().apply { values[1] = 11; references[1] = AnalysisReference(99, 999, "a1") }
        val m = vm(server, cursor); m.load(); runCurrent()
        assertEquals(SurveyPage.Completed, m.state.value.page)
        assertNull(m.state.value.analysisReference); assertEquals(0, server.analysisGets)
    }
    @Test fun completionAndAnalysisUseBodylessPublicContractsAndDecodeOnlyRequiredFields() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val client = createHttpClient("https://test/dev/api/", httpLog = false, engine = MockEngine { request ->
            requests += request.method.value to request.url.encodedPath
            assertTrue(request.body.toByteArray().isEmpty())
            assertNull(request.headers["Idempotency-Key"]); assertNull(request.headers["If-Match"])
            val body = when {
                request.url.encodedPath.endsWith("complete") -> ApiJson.encodeToString(SurveySession.serializer(), completedAnswers().data.copy(status = "COMPLETED"))
                request.url.encodedPath.endsWith("analyses") -> """{"resultId":21,"sessionId":11,"analysisVersion":"a1","status":"PENDING"}"""
                else -> """{"resultId":21,"sessionId":11,"manifest":{"surveyVersion":"v1","analysisVersion":"a1"},"status":"SUCCEEDED","scores":{"E":72},"readings":[],"missingQuestionIds":[]}"""
            }
            respond(body, if (request.url.encodedPath.endsWith("analyses")) HttpStatusCode.Accepted else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repo = HttpSurveyRepository(client)
            assertEquals("COMPLETED", (repo.complete(11) as ApiResult.Ok).value.status)
            assertEquals(AnalysisStatus.PENDING, (repo.requestAnalysis(11) as ApiResult.Ok).value.status)
            assertEquals(AnalysisStatus.SUCCEEDED, (repo.analysis(21) as ApiResult.Ok).value.status)
            assertEquals(listOf("POST" to "/dev/api/survey-sessions/11/complete", "POST" to "/dev/api/survey-sessions/11/analyses", "GET" to "/dev/api/analysis-results/21"), requests)
        } finally { client.close() }
    }
}
