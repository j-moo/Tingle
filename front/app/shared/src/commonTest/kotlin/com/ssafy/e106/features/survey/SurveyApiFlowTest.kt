package com.ssafy.e106.features.survey

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal val surveyManifest = SurveyManifest("v1", "word1", "a".repeat(64))
internal val numericQuestion = WireQuestion("Q001", 1, "number", Json.parseToJsonElement(
    """{"oneOf":[{"enum":[1,2,3,4,5]},{"const":"unknown"}]}""").jsonObject,
    (1..5).map { WireOption(JsonPrimitive(it), "$it") }, true, false)
internal val multiQuestion = WireQuestion("Q002", 2, "multiple", Json.parseToJsonElement(
    """{"oneOf":[{"type":"array","items":{"enum":["walk","talk","rest"]},"uniqueItems":true,"minItems":1,"maxItems":2},{"const":"unknown"},{"const":"any"}]}""").jsonObject,
    listOf("walk", "talk", "rest").map { WireOption(JsonPrimitive(it), it) }, true, true)
internal val surveyDefinition = Questionnaire(surveyManifest, 2, listOf(numericQuestion, multiQuestion))
internal class CursorMemory : SurveyCursorStore {
    override suspend fun activateIfCurrent(userId: Long, expectedId: Long?, sessionId: Long): Boolean {
        if (values[userId] != expectedId || pending[userId] != null) return false
        write(userId, sessionId)
        return true
    }
    override suspend fun clearActive(userId: Long, expectedId: Long) {
        if (values[userId] == expectedId) { values.remove(userId); references.remove(userId) }
    }
    val values = mutableMapOf<Long, Long>()
    val pending = mutableMapOf<Long, SurveyChangeIntent>()
    val previous = mutableMapOf<Long, SurveyBookmark>()
    override suspend fun readPrevious(userId: Long) = previous[userId]
    override suspend fun readChange(userId: Long) = pending[userId]
    override suspend fun writeChange(userId: Long, intent: SurveyChangeIntent?) { if (intent == null) pending.remove(userId) else pending[userId] = intent }
    val references = mutableMapOf<Long, AnalysisReference>()
    override suspend fun readAnalysis(userId: Long) = references[userId]
    override suspend fun writeAnalysis(userId: Long, reference: AnalysisReference) { references[userId] = reference }
    override suspend fun read(userId: Long) = values[userId]
    override suspend fun write(userId: Long, sessionId: Long?) { if (sessionId == null) values.remove(userId) else values[userId] = sessionId }
}
internal class SurveyServer : SurveyRepository {
    var recoveryResponse: ApiResult<SurveyAccountRecovery> = ApiResult.Ok(SurveyAccountRecovery(null, null, null, null))
    override suspend fun recovery(userId: Long) = recoveryResponse
    override suspend fun migrationPlan(id: Long, target: String): ApiResult<SurveyMigrationPlan> = error("not used")
    override suspend fun migrate(id: Long, target: String, policyHash: String): ApiResult<SurveySession> = error("not used")
    var definition = surveyDefinition
    var currentDefinition = surveyDefinition
    var data = SurveySession(11, surveyManifest, "DRAFT", 0, 2, "Q001", emptyList())
    var completes = 0; var analysisRequests = 0; var analysisGets = 0
    var loseComplete = false
    var failAnalysisRequest = false
    var analysisStatus = AnalysisStatus.PENDING
    var analysisSessionId = 11L
    var pendingAnalysis: CompletableDeferred<ApiResult<AnalysisResult>>? = null
    override suspend fun complete(id: Long): ApiResult<SurveySession> {
        completes++
        data = data.copy(status = "COMPLETED", completedAt = "2026-11-03T00:00:00Z")
        return if (loseComplete) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(data)
    }
    override suspend fun requestAnalysis(id: Long): ApiResult<AnalysisReceipt> {
        analysisRequests++
        return if (failAnalysisRequest) ApiResult.Fail(AppError.Offline)
        else ApiResult.Ok(AnalysisReceipt(21, id, "a1", analysisStatus))
    }
    override suspend fun analysis(id: Long): ApiResult<AnalysisResult> {
        analysisGets++
        pendingAnalysis?.let { return withContext(NonCancellable) { it.await() } }
        return ApiResult.Ok(AnalysisResult(21, analysisSessionId, AnalysisManifest("v1", "a1"), analysisStatus))
    }
    var starts = 0; var saves = 0; var deletes = 0; var gets = 0; var currents = 0
    var loseStart = false
    var lastValue: JsonElement? = null
    var failSaveAfterPersist = false
    /** 저장을 받지 않고 이 오류로 거절한다(서버에도 남지 않는다) */
    var saveError: AppError? = null
    var pendingSave: CompletableDeferred<ApiResult<SurveySession>>? = null
    var failGet: AppError? = null
    var failDefinition = false
    override suspend fun current(): ApiResult<Questionnaire> { currents++; return ApiResult.Ok(currentDefinition) }
    override suspend fun start(version: String): ApiResult<SurveySession> {
        starts++; return if (loseStart) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(data)
    }
    override suspend fun get(id: Long): ApiResult<SurveySession> { gets++; return failGet?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(data) }
    override suspend fun questionnaire(id: Long): ApiResult<Questionnaire> =
        if (failDefinition) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(definition)
    fun persist(questionId: String, value: JsonElement?) {
        val answers = data.answers.filterNot { it.questionId == questionId } + listOfNotNull(value?.let { SavedAnswer(questionId, it) })
        data = data.copy(answers = answers, answeredCount = answers.size,
            nextQuestionId = definition.questions.firstOrNull { q -> answers.none { it.questionId == q.questionId } }?.questionId)
    }
    override suspend fun save(id: Long, questionId: String, value: JsonElement): ApiResult<SurveySession> {
        saves++; lastValue = value
        pendingSave?.let { return withContext(NonCancellable) { it.await() } }
        saveError?.let { return ApiResult.Fail(it) }
        persist(questionId, value)
        return if (failSaveAfterPersist) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(data)
    }
    override suspend fun delete(id: Long, questionId: String): ApiResult<SurveySession> {
        deletes++; persist(questionId, null); return ApiResult.Ok(data)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyApiFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun vm(server: SurveyServer, cursor: CursorMemory = CursorMemory(), session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED), name: String = "survey") =
        SurveyViewModel(server, session, cursor, SurveyAccess(true)).also { models.put(name, it) }
    private suspend fun TestScope.open(model: SurveyViewModel) { model.load(); runCurrent(); model.start(); runCurrent() }

    @Test fun invalidLegacyAnswerCanBeReplacedAndSubmittedInSameSession() = runTest(dispatcher) {
        val s = SurveyServer().apply {
            persist("Q001", JsonPrimitive("4")); persist("Q002", JsonPrimitive("any"))
        }
        val c = CursorMemory().apply { values[1] = 11 }; val m = vm(s, c)
        m.load(); runCurrent(); m.resume()
        assertEquals(SurveyPage.Question, m.state.value.page)
        assertEquals("Q001", m.state.value.question?.questionId)
        assertNull(m.state.value.draft); assertEquals(SurveySaveState.Unanswered, m.state.value.saveState)
        assertFalse(m.state.value.recoveryRequired); assertEquals(0, s.starts)
        m.answer(SurveyAnswer.Choices(setOf("4"))); runCurrent(); m.next(); m.next()
        assertEquals(JsonPrimitive(4), s.data.answers.first { it.questionId == "Q001" }.value)
        assertEquals(SurveyPage.Review, m.state.value.page); m.submit(); runCurrent()
        assertEquals(1, s.completes); assertEquals(SurveyPage.Completed, m.state.value.page)
    }

    @Test fun non404ReadFailureKeepsActiveSessionUntilSuccessfulRetry() = runTest(dispatcher) {
        for (error in listOf(AppError.Offline, AppError.Server(500, "RESOURCE_NOT_FOUND", "server failed"))) {
            val s = SurveyServer().apply { failGet = error }
            val c = CursorMemory().apply { values[1] = 11; references[1] = AnalysisReference(11, 21, "a1") }
            val m = vm(s, c, name = error.toString()); m.load(); runCurrent()
            assertEquals(11L, c.read(1)); assertEquals(21L, c.readAnalysis(1)?.resultId)
            assertEquals(error, m.state.value.error)
            m.leave(); s.failGet = null; m.load(); runCurrent()
            assertEquals(11L, m.state.value.session?.sessionId); assertEquals(0, s.starts)
        }
    }

    @Test fun missingEarlierAnswerInLatestSaveReturnsToThatQuestionInsteadOfReview() = runTest(dispatcher) {
        val s = SurveyServer(); val m = vm(s); open(m)
        m.answer(SurveyAnswer.Unknown); runCurrent(); m.next()
        // 다른 기기가 앞 문항을 삭제한 뒤 마지막 문항의 저장 응답을 받는 상황이다.
        s.persist("Q001", null); m.answer(SurveyAnswer.Any); runCurrent(); m.next()
        assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(0, m.state.value.index)
        assertTrue(m.state.value.returnedToMissing); assertNull(m.state.value.draft)
        m.submit(); runCurrent(); assertEquals(0, s.completes)
        m.answer(SurveyAnswer.Unknown); runCurrent(); m.next(); m.next()
        assertEquals(SurveyPage.Review, m.state.value.page)
    }
    @Test fun handingScreenToTransitionRejectsStaleMainCallbacksUntilItReturns() = runTest(dispatcher) {
        val s = SurveyServer(); val m = vm(s); open(m)
        assertTrue(m.beginChange()); assertFalse(m.beginChange())
        m.answer(SurveyAnswer.Unknown); m.start(); m.load(); runCurrent()
        assertEquals(0, s.saves); assertNull(m.state.value.draft)
        m.finishChange(); runCurrent(); m.answer(SurveyAnswer.Unknown); runCurrent(); assertEquals(1, s.saves)
    }

    // 복귀와 명시적 재조회 모두 저장 중이거나 검토하던 문항을 첫 미응답으로 바꾸지 않아야 한다.
    @Test fun attachAndReloadKeepQuestionAndReviewPosition() = runTest(dispatcher) {
        val s = SurveyServer(); val m = vm(s); open(m)
        m.answer(SurveyAnswer.Unknown); runCurrent(); m.next(); m.answer(SurveyAnswer.Any); runCurrent(); m.next()
        assertEquals(SurveyPage.Review, m.state.value.page)
        m.attach(); runCurrent(); assertEquals(SurveyPage.Review, m.state.value.page)
        m.load(); runCurrent(); assertEquals(SurveyPage.Review, m.state.value.page)
        m.reviewAnswers(); m.load(); runCurrent()
        assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(0, m.state.value.index)
    }
    @Test fun lostInitialStartReentersSameServerDraftWithoutInventingAnId() = runTest(dispatcher) {
        val s = SurveyServer().apply { loseStart = true }; val c = CursorMemory(); val m = vm(s, c)
        open(m); assertNull(c.read(1)); m.leave()
        s.loseStart = false; val restored = vm(s, c, name = "restored"); open(restored)
        assertEquals(11L, restored.state.value.session?.sessionId); assertEquals(11L, c.read(1))
        assertEquals(2, s.starts)
    }
    @Test fun deletedActiveSessionKeepsPreviousAndPendingRecoveryInformation() = runTest(dispatcher) {
        val s = SurveyServer().apply { failGet = AppError.Server(404, "RESOURCE_NOT_FOUND", "missing") }
        val c = CursorMemory().apply {
            values[1] = 11; previous[1] = SurveyBookmark(10, AnalysisReference(10, 20, "a1"))
            pending[1] = SurveyChangeIntent(11, "v2", SurveyChangeKind.MIGRATION, "b".repeat(64))
        }
        val m = vm(s, c); m.load(); runCurrent()
        assertNull(c.read(1)); assertEquals(10L, m.state.value.previousBookmark?.sessionId)
        assertTrue(m.state.value.pendingChange); assertEquals(11L, m.state.value.pendingSourceId)
    }

    // 답을 고르면 저장을 기다리지 않고 바로 "다음"이 켜진다. 보내기 전에 답을 바꾸면 마지막 답만 한 번 보낸다
    @Test fun choosingEnablesNextAtOnceAndOnlyLatestQueuedValueIsSentWithItsType() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m)
        assertEquals(SurveyPage.Question, m.state.value.page)
        assertFalse(m.state.value.questionScreen!!.canContinue)
        m.answer(SurveyAnswer.Unknown); m.answer(SurveyAnswer.Choices(setOf("4")))
        assertTrue(m.state.value.questionScreen!!.canContinue); assertEquals(1, m.state.value.pending.size); assertFalse(m.state.value.busy)
        runCurrent(); assertEquals(JsonPrimitive(4), server.lastValue); assertEquals(1, server.saves)
        assertTrue(m.state.value.pending.isEmpty())
        m.next(); m.answer(SurveyAnswer.Choices(setOf("\"walk\"", "\"talk\""))); runCurrent()
        assertEquals(JsonArray(listOf(JsonPrimitive("walk"), JsonPrimitive("talk"))), server.lastValue)
        m.next(); assertEquals(SurveyPage.Review, m.state.value.page)
    }
    @Test fun unknownAndAnyAreScalarAndRemovingLastChoiceDeletes() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m)
        m.answer(SurveyAnswer.Any); runCurrent(); assertEquals(0, server.saves)
        m.answer(SurveyAnswer.Unknown); runCurrent(); assertEquals(JsonPrimitive("unknown"), server.lastValue)
        m.next(); m.answer(SurveyAnswer.Any); runCurrent(); assertEquals(JsonPrimitive("any"), server.lastValue)
        m.answer(SurveyAnswer.Choices(setOf("\"walk\""))); runCurrent()
        m.answer(SurveyAnswer.Choices(emptySet())); runCurrent()
        assertEquals(1, server.deletes); assertEquals(SurveySaveState.Unanswered, m.state.value.saveState)
        assertFalse(m.state.value.questionScreen!!.canContinue)
    }
    @Test fun restartRestoresServerAnswersAndPinnedVersionWithoutCreatingNewSession() = runTest(dispatcher) {
        val server = SurveyServer(); val cursor = CursorMemory(); val m = vm(server, cursor); open(m)
        m.answer(SurveyAnswer.Choices(setOf("4"))); runCurrent(); m.leave()
        server.currentDefinition = surveyDefinition.copy(manifest = surveyManifest.copy(surveyVersion = "v2"))
        val restored = vm(server, cursor, name = "restored"); restored.load(); runCurrent()
        assertEquals(SurveyPage.Resume, restored.state.value.page); assertEquals(1, server.starts)
        assertEquals("v1", restored.state.value.definition?.manifest?.surveyVersion)
        restored.resume(); assertEquals("Q002", restored.state.value.question?.questionId)
        restored.previous(); assertEquals(SurveyAnswer.Choices(setOf("4")), restored.state.value.draft)
    }
    // 저장이 안 돼도 다음 문항으로 넘어갈 수 있고, 못 보낸 답은 줄에 남아 뒤에서 계속 다시 보낸다(같은 답을 다시 보내도 결과는 같다)
    @Test fun failedSaveStaysQueuedLetsUserMoveOnAndRetriesUntilAccepted() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m); server.failSaveAfterPersist = true
        m.answer(SurveyAnswer.Choices(setOf("3"))); runCurrent()
        assertFalse(m.state.value.recoveryRequired); assertEquals(1, m.state.value.pending.size); assertEquals(1, server.saves)
        m.next(); assertEquals(1, m.state.value.index)
        m.previous(); assertEquals(SurveyAnswer.Choices(setOf("3")), m.state.value.draft)   // 못 보낸 답도 돌아가면 그대로 보인다
        advanceTimeBy(1_001); runCurrent(); assertEquals(2, server.saves); assertEquals(1, m.state.value.pending.size)
        server.failSaveAfterPersist = false
        advanceTimeBy(2_001); runCurrent(); assertEquals(3, server.saves)
        assertTrue(m.state.value.pending.isEmpty()); assertFalse(m.state.value.recoveryRequired)
    }
    @Test fun delayedOldAccountResponseCannotPopulateNewAccount() = runTest(dispatcher) {
        val server = SurveyServer(); val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val m = vm(server, session = session); open(m)
        val pending = CompletableDeferred<ApiResult<SurveySession>>(); server.pendingSave = pending
        m.answer(SurveyAnswer.Unknown); runCurrent()
        session.state.value = Session.SignedIn(2, OnboardingStatus.KEYRING_REQUIRED, false); runCurrent()
        // 새 계정 화면까지 준비해야 가드 제거 시 NPE로 우연히 통과하지 않고 실제 덮어쓰기를 잡는다.
        val old = server.data.copy(answers = listOf(SavedAnswer("Q001", JsonPrimitive("unknown"))),
            answeredCount = 1, nextQuestionId = "Q002")
        server.pendingSave = null; server.data = server.data.copy(sessionId = 22)
        open(m); m.answer(SurveyAnswer.Choices(setOf("4"))); runCurrent()
        val expected = m.state.value
        pending.complete(ApiResult.Ok(old)); runCurrent()
        assertEquals(expected, m.state.value)
        assertEquals(2L, m.state.value.owner?.userId); assertEquals(22L, m.state.value.session?.sessionId)
    }
    // 답 저장은 화면을 막지 않는다: 보내는 중에도 나갈 수 있고, 나간 뒤 늦게 온 응답은 새 화면 상태를 건드리지 않는다
    @Test fun leavingDuringBackgroundSaveResetsScreenAndLateResponseIsIgnored() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m)
        val pending = CompletableDeferred<ApiResult<SurveySession>>(); server.pendingSave = pending
        m.answer(SurveyAnswer.Unknown); runCurrent()
        assertFalse(m.state.value.busy); assertEquals(1, server.saves)
        m.requestLeave(); m.leave()
        assertNull(m.state.value.definition); assertTrue(m.state.value.pending.isEmpty())
        server.persist("Q001", JsonPrimitive("unknown"))
        pending.complete(ApiResult.Ok(server.data)); runCurrent()
        assertNull(m.state.value.definition); assertFalse(m.state.value.busy)
        server.pendingSave = null
        m.load(); runCurrent(); assertEquals(SurveyPage.Resume, m.state.value.page)
    }
    // 못 보낸 답이 남아 있으면 제출하지 않는다(답이 빠진 채 제출되면 분석이 "답 부족"으로 끝난다). 다 저장되면 제출된다
    @Test fun submitWaitsUntilEveryQueuedAnswerIsSaved() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m); server.saveError = AppError.Offline
        m.answer(SurveyAnswer.Choices(setOf("4"))); m.next()
        m.answer(SurveyAnswer.Choices(setOf("\"walk\""))); runCurrent()
        m.next(); assertEquals(SurveyPage.Review, m.state.value.page); assertEquals(2, m.state.value.pending.size)
        m.submit(); runCurrent(); assertEquals(0, server.completes)
        server.saveError = null
        advanceTimeBy(1_001); runCurrent(); assertTrue(m.state.value.pending.isEmpty())
        m.submit(); runCurrent(); assertEquals(1, server.completes)
    }
    // 다시 보내도 소용없는 거절(회차가 이미 제출됨 등)은 반복하지 않고 서버 상태를 다시 불러오게 한다
    @Test fun rejectedSaveIsNotRepeatedAndRequiresReload() = runTest(dispatcher) {
        val server = SurveyServer(); val m = vm(server); open(m)
        server.saveError = AppError.Server(409, "SURVEY_SESSION_COMPLETED", "")
        m.answer(SurveyAnswer.Choices(setOf("4"))); runCurrent()
        assertTrue(m.state.value.recoveryRequired); assertTrue(m.state.value.pending.isEmpty()); assertEquals(1, server.saves)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, server.saves)
    }
    @Test fun failedQuestionnaireReadKeepsSessionPointerForRetry() = runTest(dispatcher) {
        val server = SurveyServer(); val cursor = CursorMemory(); val m = vm(server, cursor)
        m.load(); runCurrent(); server.failDefinition = true; m.start(); runCurrent()
        assertEquals(11L, cursor.values[1]); assertNotNull(m.state.value.error)
        server.failDefinition = false; m.load(); runCurrent()
        assertEquals(SurveyPage.Question, m.state.value.page); assertEquals(1, server.starts)
    }
    @Test fun unavailableCursorIsClearedWithoutCreatingOrShowingOtherSession() = runTest(dispatcher) {
        val server = SurveyServer(); val cursor = CursorMemory(); cursor.values[1] = 99
        server.failGet = AppError.Server(404, "RESOURCE_NOT_FOUND", "없음")
        val m = vm(server, cursor); m.load(); runCurrent()
        assertNull(cursor.values[1]); assertNull(m.state.value.session); assertEquals(0, server.starts)
        server.failGet = null; m.load(); runCurrent(); assertEquals(SurveyPage.Intro, m.state.value.page)
    }
    @Test fun mismatchedManifestFailsClosedAndDoesNotAllowSaving() = runTest(dispatcher) {
        val server = SurveyServer(); server.definition = surveyDefinition.copy(manifest = surveyManifest.copy(wordingVersion = "changed"))
        val m = vm(server); open(m)
        assertNotNull(m.state.value.error); assertTrue(m.state.value.recoveryRequired)
        m.answer(SurveyAnswer.Unknown); runCurrent(); assertEquals(0, server.saves)
    }
    @Test fun productionDoesNotOpenKeyringBypassAndAgreementGateAlwaysApplies() {
        for (stage in OnboardingStatus.entries) {
            val s = Session.SignedIn(1, stage, false)
            assertEquals(stage in setOf(OnboardingStatus.SURVEY_REQUIRED, OnboardingStatus.READY), SurveyAccess().allowed(s))
            assertEquals(stage in setOf(OnboardingStatus.KEYRING_REQUIRED, OnboardingStatus.SURVEY_REQUIRED, OnboardingStatus.READY), SurveyAccess(true).allowed(s))
        }
    }
}
