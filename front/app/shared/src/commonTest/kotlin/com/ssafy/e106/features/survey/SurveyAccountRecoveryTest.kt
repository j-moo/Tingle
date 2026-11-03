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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyAccountRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun vm(repo: SurveyRepository, cursor: CursorMemory = CursorMemory(), sessions: TestSession = TestSession(OnboardingStatus.SURVEY_REQUIRED)) =
        SurveyViewModel(repo, sessions, cursor, SurveyAccess()).also { models.put("survey", it) }
    private fun submitted(status: AnalysisStatus? = null, draft: Long? = null, active: Long? = null) = SurveyServer().apply {
        persist("Q001", JsonPrimitive(4)); persist("Q002", JsonPrimitive("any"))
        data = data.copy(status = "COMPLETED", completedAt = "2026-11-03T01:00:00Z")
        analysisStatus = status ?: AnalysisStatus.PENDING
        recoveryResponse = ApiResult.Ok(SurveyAccountRecovery(draft, 11, status?.let { AnalysisReceipt(21, 11, "a1", it) }, active))
    }

    @Test fun emptyLocalStoreRestoresSubmittedSessionAndRequestsOnlyMissingAnalysis() = runTest(dispatcher) {
        val server = submitted(); val cursor = CursorMemory(); val model = vm(server, cursor)
        model.load(); runCurrent()
        assertEquals(SurveyPage.Completed, model.state.value.page)
        assertEquals(11L, cursor.read(1)); assertEquals(21L, cursor.readAnalysis(1)?.resultId)
        assertEquals(1, server.analysisRequests); assertEquals(0, server.starts); assertEquals(0, server.completes)
        assertEquals(2, model.state.value.session?.answeredCount)
    }
    @Test fun pendingAnalysisIsReadWithoutPostingItAgain() = runTest(dispatcher) {
        val server = submitted(AnalysisStatus.PENDING, draft = 12); val model = vm(server)
        model.load(); runCurrent()
        assertEquals(AnalysisStatus.PENDING, model.state.value.analysisResult?.status)
        assertEquals(12L, model.state.value.serverDraftId)
        assertEquals(0, server.analysisRequests); assertEquals(1, server.analysisGets)
    }
    @Test fun failedAnalysisCanBeRetriedWithoutRewritingSurvey() = runTest(dispatcher) {
        val server = submitted(AnalysisStatus.FAILED); val model = vm(server)
        model.load(); runCurrent(); assertEquals(AnalysisStatus.FAILED, model.state.value.analysisResult?.status)
        assertEquals(0, server.analysisRequests)
        server.analysisStatus = AnalysisStatus.PENDING; model.requestAnalysis(); runCurrent()
        assertEquals(1, server.analysisRequests); assertEquals(0, server.starts); assertEquals(0, server.completes)
        assertEquals(11L, model.state.value.analysisReference?.sessionId)
    }
    @Test fun succeededButNotAppliedIsKeptDistinctFromApplied() = runTest(dispatcher) {
        for (active in listOf(null, 20L, 21L)) {
            val server = submitted(AnalysisStatus.SUCCEEDED, active = active); val model = vm(server)
            model.load(); runCurrent()
            assertEquals(active == 21L, model.state.value.analysisApplied)
            assertEquals(AnalysisStatus.SUCCEEDED, model.state.value.analysisResult?.status)
            assertEquals(0, server.starts)
        }
    }
    @Test fun insufficientRestoresExistingMissingAnswerFlow() = runTest(dispatcher) {
        val server = submitted(AnalysisStatus.INSUFFICIENT); val model = vm(server)
        model.load(); runCurrent()
        assertEquals(SurveyPage.Completed, model.state.value.page)
        assertEquals(AnalysisStatus.INSUFFICIENT, model.state.value.analysisResult?.status)
        assertEquals(0, server.analysisRequests)
    }
    @Test fun draftOnlyRestoresAnswersWithoutStartingAnotherSession() = runTest(dispatcher) {
        val server = SurveyServer().apply {
            persist("Q001", JsonPrimitive(4))
            recoveryResponse = ApiResult.Ok(SurveyAccountRecovery(11, null, null, null))
        }
        val model = vm(server); model.load(); runCurrent()
        assertEquals(SurveyPage.Resume, model.state.value.page); model.resume()
        assertEquals("Q002", model.state.value.question?.questionId); assertEquals(0, server.starts)
    }
    @Test fun completedRecoveryKeepsCoexistingDraftAccessible() = runTest(dispatcher) {
        val server = submitted(AnalysisStatus.PENDING, draft = 12)
        val draft = SurveySession(12, surveyManifest, "DRAFT", 1, 2, "Q002", listOf(SavedAnswer("Q001", JsonPrimitive(4))))
        val repo = object : SurveyRepository by server {
            override suspend fun get(id: Long) = if (id == 12L) ApiResult.Ok(draft) else server.get(id)
        }
        val model = vm(repo); model.load(); runCurrent(); model.openServerDraft(); runCurrent()
        assertEquals(12L, model.state.value.session?.sessionId); assertEquals(SurveyPage.Resume, model.state.value.page)
        assertEquals(0, server.starts); assertNull(model.state.value.analysisResult)
    }
    @Test fun appliedResultYieldsToDraftButHeldResultDoesNotHideBehindIt() {
        val receipt = AnalysisReceipt(21, 11, "a1", AnalysisStatus.SUCCEEDED)
        assertEquals(12L, SurveyAccountRecovery(12, 11, receipt, 21).checked().preferredSessionId())
        assertEquals(11L, SurveyAccountRecovery(12, 11, receipt, 20).checked().preferredSessionId())
    }
    @Test fun offlineOrServerFailureDoesNotBecomeNewSurvey() = runTest(dispatcher) {
        for (error in listOf(AppError.Offline, AppError.Server(500, "FAILED", "failure"))) {
            val server = SurveyServer().apply { recoveryResponse = ApiResult.Fail(error) }
            val cursor = CursorMemory(); val model = vm(server, cursor)
            model.load(); runCurrent(); model.start(); runCurrent()
            assertEquals(error, model.state.value.error); assertTrue(model.state.value.recoveryRequired)
            assertNull(cursor.read(1)); assertEquals(0, server.starts); assertEquals(0, server.currents)
            server.recoveryResponse = ApiResult.Ok(SurveyAccountRecovery(null, null, null, null))
            model.load(); runCurrent(); assertEquals(SurveyPage.Intro, model.state.value.page)
        }
    }
    @Test fun oldAccountRecoveryCannotReplaceNewAccountScreenOrCursor() = runTest(dispatcher) {
        val server = SurveyServer(); val sessions = TestSession(OnboardingStatus.SURVEY_REQUIRED)
        val delayed = CompletableDeferred<ApiResult<SurveyAccountRecovery>>()
        val repo = object : SurveyRepository by server {
            override suspend fun recovery(userId: Long) = if (userId == 1L)
                withContext(NonCancellable) { delayed.await() } else server.recovery(userId)
        }
        val cursor = CursorMemory(); val model = vm(repo, cursor, sessions); model.load(); runCurrent()
        sessions.state.value = Session.SignedIn(2, OnboardingStatus.SURVEY_REQUIRED, false); runCurrent()
        model.load(); runCurrent(); model.start(); runCurrent()
        val before = model.state.value
        delayed.complete(ApiResult.Ok(SurveyAccountRecovery(null, 99, null, null))); runCurrent()
        assertEquals(before, model.state.value); assertNull(cursor.read(1)); assertEquals(11L, cursor.read(2))
    }
    @Test fun conflictingRecoveryIdsFailBeforeLoadingOrWritingAnything() = runTest(dispatcher) {
        val server = submitted().apply { recoveryResponse = ApiResult.Ok(SurveyAccountRecovery(null, 11, AnalysisReceipt(21, 99, "a1", AnalysisStatus.PENDING), null)) }
        val cursor = CursorMemory(); val model = vm(server, cursor); model.load(); runCurrent()
        assertTrue(model.state.value.recoveryRequired); assertNull(cursor.read(1)); assertEquals(0, server.gets)
    }
    @Test fun publicRecoveryContractRequiresKeysAndUsesNumericIdsAndCurrentUserPath() = runTest {
        var body = """{"nextStep":"SURVEY_REQUIRED","surveySessionId":12,"latestCompletedSessionId":11,"latestAnalysis":{"resultId":21,"sessionId":11,"analysisVersion":"a1","status":"PENDING"},"activeResultId":20}"""
        val client = createHttpClient("https://test/dev/api/", false, engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method); assertEquals("/dev/api/users/7/onboarding", request.url.encodedPath)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repo = HttpSurveyRepository(client)
            assertEquals(11L, (repo.recovery(7) as ApiResult.Ok).value.preferredSessionId())
            body = """{"surveySessionId":null}""" // 구버전 배포를 이력 없음으로 읽지 않는다.
            assertIs<ApiResult.Fail>(repo.recovery(7))
            body = """{"surveySessionId":null,"latestCompletedSessionId":null,"latestAnalysis":null,"activeResultId":null}"""
            assertNull((repo.recovery(7) as ApiResult.Ok).value.preferredSessionId())
        } finally { client.close() }
    }
}
