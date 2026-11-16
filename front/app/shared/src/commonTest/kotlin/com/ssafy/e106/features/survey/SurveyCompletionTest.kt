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
    // 분석은 서버에서 몇 초 뒤에 끝난다. PENDING 인 동안 뒤에서 다시 조회해, 버튼을 누르지 않아도 끝난 결과와 가입 단계가 반영된다
    @Test fun pendingAnalysisIsPolledUntilFinishedWithoutPressingTheButton() = runTest(dispatcher) {
        val s = completedAnswers(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED); val m = vm(s, session = session)
        review(m); m.submit(); runCurrent()
        assertEquals(AnalysisStatus.PENDING, m.state.value.analysisResult?.status); assertEquals(1, s.analysisGets)
        advanceTimeBy(2_501); runCurrent(); assertEquals(2, s.analysisGets)
        assertEquals(AnalysisStatus.PENDING, m.state.value.analysisResult?.status)
        s.analysisStatus = AnalysisStatus.SUCCEEDED; val refreshes = session.refreshes
        advanceTimeBy(2_501); runCurrent()
        assertEquals(AnalysisStatus.SUCCEEDED, m.state.value.analysisResult?.status); assertEquals(refreshes + 1, session.refreshes)
        val gets = s.analysisGets; advanceTimeBy(60_000); runCurrent(); assertEquals(gets, s.analysisGets)   // 끝난 뒤에는 더 조회하지 않는다
    }
    // 1분이 지나도 안 끝나면 멈추고 버튼에 맡긴다. 화면을 나가면 바로 멈춘다
    @Test fun analysisPollingStopsAfterTheLimitAndWhenLeaving() = runTest(dispatcher) {
        val s = completedAnswers(); val m = vm(s); review(m); m.submit(); runCurrent()
        advanceTimeBy(120_000); runCurrent(); assertEquals(1 + 24, s.analysisGets)
        assertEquals(AnalysisStatus.PENDING, m.state.value.analysisResult?.status)
        m.refreshAnalysis(); runCurrent(); val gets = s.analysisGets          // 버튼으로 다시 보면 다시 지켜본다
        advanceTimeBy(2_501); runCurrent(); assertEquals(gets + 1, s.analysisGets)
        m.leave(); advanceTimeBy(60_000); runCurrent(); assertEquals(gets + 1, s.analysisGets)
    }
    // 제출한 답 고치기: 새 회차를 열어 이전 답을 채우고("모르겠어요"는 비워 둔다) 답이 빈 문항부터 보여 준다.
    // "다음"은 한 문항씩 넘기고(다른 답도 고칠 수 있게), "수정 마치기"는 빈 문항이 있으면 그리로, 없으면 제출 단계로 간다
    @Test fun editOpensNewSessionPrefilledWithPreviousAnswersAndJumpsToGaps() = runTest(dispatcher) {
        val s = SurveyServer().apply {
            persist("Q001", JsonPrimitive("unknown")); persist("Q002", JsonArray(listOf(JsonPrimitive("walk"))))
        }
        val m = vm(s); review(m); m.submit(); runCurrent()
        assertEquals(SurveyPage.Completed, m.state.value.page)
        s.data = s.data.copy(sessionId = 12, status = "DRAFT", answeredCount = 0, nextQuestionId = "Q001", answers = emptyList(), completedAt = null)
        val saves = s.saves
        m.edit(); runCurrent()
        assertEquals(12L, m.state.value.session?.sessionId); assertTrue(m.state.value.editing)
        assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(0, m.state.value.index); assertNull(m.state.value.draft)
        assertEquals(saves + 1, s.saves); assertEquals(listOf("Q002"), s.data.answers.map { it.questionId })   // 이전 답만 다시 저장됐다
        m.finishEdit(); assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(0, m.state.value.index)   // 빈 문항이 남아 있으면 마치지 못한다
        assertTrue(m.state.value.returnedToMissing)
        m.answer(SurveyAnswer.Choices(setOf("4"))); runCurrent(); m.next()
        assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(1, m.state.value.index)             // "다음"은 다음 문항으로(건너뛰지 않는다)
        m.previous(); m.finishEdit()
        assertEquals(SurveyPage.Review, m.state.value.page)                                                    // 빈 문항이 없으면 어디서든 제출 단계로
        m.submit(); runCurrent(); assertEquals(2, s.completes)
    }
    // 답 고치기에서 새 회차는 열렸는데 문항을 못 받은 채 계정이 바뀌면, 이전 계정의 답이 새 계정 회차에 채워지거나 저장되면 안 된다
    @Test fun editPrefillIsDroppedWhenQuestionsFailAndAccountChanges() = runTest(dispatcher) {
        val s = completedAnswers(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val m = vm(s, session = session); review(m); m.submit(); runCurrent()
        s.data = s.data.copy(sessionId = 12, status = "DRAFT", answeredCount = 0, nextQuestionId = "Q001", answers = emptyList(), completedAt = null)
        s.failDefinition = true
        m.edit(); runCurrent()
        assertTrue(m.state.value.recoveryRequired)
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false); runCurrent()
        // 같은 회차 번호를 돌려줘도(회차 비교로 막히지 않게) 계정이 바뀌었으니 이전 답을 채우지 않는다
        s.failDefinition = false
        val saves = s.saves
        m.load(); runCurrent(); m.start(); runCurrent()
        assertEquals(12L, m.state.value.session?.sessionId)
        assertEquals(saves, s.saves); assertTrue(m.state.value.pending.isEmpty()); assertFalse(m.state.value.editing)
    }
    // 답 고치기로 채운 이전 답을 보내는 중에 나가면 버리지 않고 계속 보낸다. 다시 열면 "수정 마치기"가 있는 상태 그대로다
    @Test fun leavingWhileEditPrefillIsSendingKeepsTheQueue() = runTest(dispatcher) {
        val s = completedAnswers(); val m = vm(s); review(m); m.submit(); runCurrent()
        s.data = s.data.copy(sessionId = 12, status = "DRAFT", answeredCount = 0, nextQuestionId = "Q001", answers = emptyList(), completedAt = null)
        val pending = CompletableDeferred<ApiResult<SurveySession>>(); s.pendingSave = pending
        m.edit(); runCurrent()
        assertTrue(m.state.value.editing); assertTrue(m.state.value.pending.isNotEmpty())
        m.requestLeave(); m.leave(); runCurrent()
        assertTrue(m.state.value.editing); assertTrue(m.state.value.pending.isNotEmpty()); assertFalse(m.state.value.exitRequested)
        val saves = s.saves
        s.persist("Q001", JsonPrimitive(4))   // 멈춰 있던 첫 저장이 서버에 반영된 것으로 둔다
        s.pendingSave = null; pending.complete(ApiResult.Ok(s.data)); runCurrent()
        assertTrue(m.state.value.editing); assertTrue(m.state.value.pending.isEmpty())   // 나간 뒤에도 남은 답을 다 보냈다
        // 나간 뒤에 남은 답(Q002)까지 보내 이전 답이 모두 저장됐다
        assertTrue(s.saves > saves); assertEquals(listOf("Q001", "Q002"), s.data.answers.map { it.questionId })
    }
    // 서버가 이미 답이 있는 작성 중 회차를 돌려주면(다른 기기에서 시작한 재검사 등) 이전 답을 섞지 않고 이어하기로 간다
    @Test fun editDoesNotMixPreviousAnswersIntoAnAnsweredDraft() = runTest(dispatcher) {
        val s = completedAnswers(); val m = vm(s); review(m); m.submit(); runCurrent()
        s.data = s.data.copy(sessionId = 12, status = "DRAFT", completedAt = null, answers = emptyList(), answeredCount = 0)
        s.persist("Q001", JsonPrimitive(2))
        val saves = s.saves
        m.edit(); runCurrent()
        assertEquals(12L, m.state.value.session?.sessionId); assertEquals(SurveyPage.Resume, m.state.value.page)
        assertFalse(m.state.value.editing); assertTrue(m.state.value.pending.isEmpty()); assertEquals(saves, s.saves)
    }
    // 문항 조회가 실패한 채 나갔다가 다시 열어도(같은 계정) 새 회차에 이전 답을 채운다
    @Test fun editPrefillSurvivesLeavingAfterQuestionsFail() = runTest(dispatcher) {
        val s = completedAnswers(); val m = vm(s); review(m); m.submit(); runCurrent()
        s.data = s.data.copy(sessionId = 12, status = "DRAFT", answeredCount = 0, nextQuestionId = "Q001", answers = emptyList(), completedAt = null)
        s.failDefinition = true
        m.edit(); runCurrent(); assertTrue(m.state.value.recoveryRequired)
        m.requestLeave(); m.leave(); runCurrent()
        s.failDefinition = false
        m.load(); runCurrent()
        assertEquals(12L, m.state.value.session?.sessionId); assertTrue(m.state.value.editing)
        assertEquals(listOf("Q001", "Q002"), s.data.answers.map { it.questionId })
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
