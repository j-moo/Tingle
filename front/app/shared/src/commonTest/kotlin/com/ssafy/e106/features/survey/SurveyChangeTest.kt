package com.ssafy.e106.features.survey

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class ChangeServer : SurveyRepository by SurveyServer() {
    val source = SurveySession(11, surveyManifest, "COMPLETED", 2, 2, null,
        listOf(SavedAnswer("Q001", JsonPrimitive(4)), SavedAnswer("Q002", JsonArray(listOf(JsonPrimitive("walk"))))))
    var target = surveyDefinition
    var draft: SurveySession? = null
    var starts = 0; var moves = 0; var plans = 0
    var hash = "b".repeat(64)
    var receivedHash: String? = null
    var error: AppError? = null
    var planError: AppError? = null
    var loseResponse = false
    var delayed: CompletableDeferred<ApiResult<SurveySession>>? = null
    override suspend fun get(id: Long): ApiResult<SurveySession> = ApiResult.Ok(if (id == 11L) source else draft!!)
    override suspend fun current(): ApiResult<Questionnaire> = ApiResult.Ok(target)
    override suspend fun questionnaire(id: Long): ApiResult<Questionnaire> = ApiResult.Ok(if (id == 11L) surveyDefinition else target)
    override suspend fun start(version: String): ApiResult<SurveySession> {
        starts++
        error?.let { return ApiResult.Fail(it) }
        if (draft == null) draft = SurveySession(12, target.manifest, "DRAFT", 0, 2, "Q001", emptyList())
        return if (loseResponse) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(draft!!)
    }
    override suspend fun migrationPlan(id: Long, target: String): ApiResult<SurveyMigrationPlan> {
        plans++
        return planError?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(SurveyMigrationPlan(id, target,
            listOf("Q001"), listOf("Q002"), emptyList(), hash))
    }
    override suspend fun migrate(id: Long, target: String, policyHash: String): ApiResult<SurveySession> {
        moves++
        receivedHash = policyHash
        if (policyHash != hash) return ApiResult.Fail(AppError.Server(409, "MIGRATION_POLICY_CHANGED", "changed"))
        delayed?.let { return withContext(NonCancellable) { it.await() } }
        error?.let { return ApiResult.Fail(it) }
        if (draft == null) draft = SurveySession(12, this.target.manifest, "DRAFT", 1, 2, "Q002", source.answers.take(1))
        return if (loseResponse) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(draft!!)
    }
}
private class JsonPrefs : AppPrefs {
    val encoded = MutableStateFlow<String?>(null)
    var beforeSet: suspend () -> Unit = {}
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = encoded.map { it?.let { ApiJson.decodeFromString(key.serializer, it) } }
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        beforeSet()
        if (forUser == 1L) encoded.value = value?.let { ApiJson.encodeToString(key.serializer, it) }
    }
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> =
        encoded.value?.let { PrefRead.Found(ApiJson.decodeFromString(key.serializer, it)) } ?: PrefRead.Missing
}
@OptIn(ExperimentalCoroutinesApi::class)
class SurveyChangeTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun vm(server: ChangeServer, cursor: SurveyCursorStore, session: TestSession = TestSession(OnboardingStatus.KEYRING_REQUIRED), name: String = "change") =
        SurveyChangeViewModel(server, session, cursor, SurveyAccess(true)).also { models.put(name, it) }
    private suspend fun cursor(): PrefsSurveyCursorStore = PrefsSurveyCursorStore(JsonPrefs()).also {
        it.write(1, 11); it.writeAnalysis(1, AnalysisReference(11, 21, "a1"))
    }
    @Test fun delayedPreviousReadCannotReplaceNewlyMigratedActiveSession() = runTest(dispatcher) {
        val s = ChangeServer().apply { target = surveyDefinition.copy(manifest = surveyManifest.copy(surveyVersion = "v2")) }
        val c = PrefsSurveyCursorStore(JsonPrefs()); c.write(1, 10); c.write(1, 11)
        val previous = CompletableDeferred<ApiResult<SurveySession>>()
        val repository = object : SurveyRepository by s {
            override suspend fun get(id: Long): ApiResult<SurveySession> =
                if (id == 10L) withContext(NonCancellable) { previous.await() } else s.get(id)
        }
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val main = SurveyViewModel(repository, session, c, SurveyAccess(true)); models.put("main", main)
        main.load(); runCurrent(); main.openPrevious(); runCurrent()
        assertTrue(main.state.value.busy); assertFalse(main.beginChange())
        // 화면의 진입 잠금 외에도 커서가 이동한 경우 늦은 조회가 덮어쓰지 못하는지 검증한다.
        val change = vm(s, c); change.load(11, SurveyChangeKind.MIGRATION); runCurrent(); change.confirm(); runCurrent()
        assertEquals(12L, c.read(1)); main.load(); runCurrent()
        previous.complete(ApiResult.Ok(s.source.copy(sessionId = 10))); runCurrent()
        assertEquals(12L, c.read(1)); assertEquals(12L, main.state.value.session?.sessionId)
        assertEquals(SurveyPage.Resume, main.state.value.page)
    }
    @Test fun rejectedRequestClearsIntentButTimeoutAndServerFailureKeepIt() = runTest(dispatcher) {
        for ((error, rejected) in listOf(
            AppError.Server(400, "INVALID_REQUEST", "invalid") to true,
            AppError.Server(403, "FORBIDDEN", "forbidden") to true,
            AppError.Server(404, "RESOURCE_NOT_FOUND", "missing") to true,
            AppError.Server(400, "PROFILE_INCOMPLETE", "profile") to true,
            AppError.Server(408, "REQUEST_TIMEOUT", "timeout") to false,
            // 코드만 보거나 모든 4xx를 거절로 오인하지 않도록 상태/코드 교차 경계를 검사한다.
            AppError.Server(500, "INVALID_REQUEST", "server failed") to false,
            AppError.Server(500, "MIGRATION_UNSUPPORTED", "server failed") to false,
            AppError.Server(422, "UNRECOGNIZED", "unknown") to false,
            AppError.Server(429, "TOO_MANY_REQUESTS", "retry") to false,
            AppError.Server(503, "SERVICE_UNAVAILABLE", "retry") to false,
            AppError.Offline to false)) {
            val s = ChangeServer().apply { this.error = error }; val c = cursor(); val m = vm(s, c, name = error.toString())
            m.load(11, SurveyChangeKind.RETEST); runCurrent(); m.confirm(); runCurrent()
            assertEquals(!rejected, c.readChange(1) != null)
            assertEquals(if (rejected) SurveyChangePage.Error else SurveyChangePage.Uncertain, m.state.value.page)
            assertEquals(11L, c.read(1)); assertEquals(21L, c.readAnalysis(1)?.resultId)
        }
    }
    @Test fun transitionNeverPostsUntilIntentCanBeReadBack() = runTest(dispatcher) {
        for (kind in SurveyChangeKind.entries) {
            val s = ChangeServer().apply { target = surveyDefinition.copy(manifest = surveyManifest.copy(surveyVersion = "v2")) }
            val backing = CursorMemory().apply { values[1] = 11 }
            var persist = false
            val gate = CompletableDeferred<Unit>()
            val c = object : SurveyCursorStore by backing {
                override suspend fun writeChange(userId: Long, intent: SurveyChangeIntent?) {
                    gate.await()
                    if (persist) backing.writeChange(userId, intent)
                }
            }
            val m = vm(s, c, name = kind.name); m.load(11, kind); runCurrent(); m.confirm(); runCurrent()
            assertTrue(m.state.value.busy); assertEquals(0, s.starts + s.moves)
            // 저장 함수가 돌아와도 재조회 값이 없으면 요청하면 안 된다.
            gate.complete(Unit); runCurrent()
            assertEquals(0, s.starts + s.moves); assertEquals(SurveyChangePage.Error, m.state.value.page)
            persist = true; m.reload(); runCurrent(); m.confirm(); runCurrent()
            assertEquals(1, s.starts + s.moves); assertEquals(SurveyChangePage.Done, m.state.value.page)
        }
    }

    @Test fun backDuringMigrationWaitsForResultWithoutLosingRecoveryIntent() = runTest(dispatcher) {
        val s = ChangeServer().apply { target = surveyDefinition.copy(manifest = surveyManifest.copy(surveyVersion = "v2")) }
        val c = cursor(); val m = vm(s, c); m.load(11, SurveyChangeKind.MIGRATION); runCurrent()
        val gate = CompletableDeferred<ApiResult<SurveySession>>(); s.delayed = gate
        m.confirm(); runCurrent(); m.requestLeave(); m.leave(); m.confirm(); runCurrent()
        assertTrue(m.state.value.busy); assertTrue(m.state.value.exitRequested)
        assertNotNull(c.readChange(1)); assertEquals(1, s.moves)
        gate.complete(ApiResult.Ok(SurveySession(12, s.target.manifest, "DRAFT", 0, 2, "Q001", emptyList()))); runCurrent()
        assertEquals(SurveyChangePage.Done, m.state.value.page); assertFalse(m.state.value.busy)
        assertEquals(12L, c.read(1)); assertNull(c.readChange(1)); m.leave()
        assertFalse(m.state.value.exitRequested)
    }
    @Test fun returningToPreviousRestoresItsAnalysisAndPendingTransitionBlocksIt() = runTest(dispatcher) {
        val s = ChangeServer(); val c = cursor(); val change = vm(s, c)
        change.load(11, SurveyChangeKind.RETEST); runCurrent(); change.confirm(); runCurrent()
        val session = TestSession(OnboardingStatus.KEYRING_REQUIRED)
        val main = SurveyViewModel(s, session, c, SurveyAccess(true)); models.put("main", main)
        main.load(); runCurrent(); assertEquals(12L, main.state.value.session?.sessionId)
        c.writeChange(1, SurveyChangeIntent(12, "v2", SurveyChangeKind.MIGRATION, "b".repeat(64)))
        main.load(); runCurrent(); main.openPrevious(); runCurrent(); assertEquals(12L, c.read(1))
        c.writeChange(1, null); main.load(); runCurrent(); main.openPrevious(); runCurrent()
        assertEquals(11L, c.read(1)); assertEquals(21L, main.state.value.analysisReference?.resultId)
        assertEquals(SurveyPage.Completed, main.state.value.page)
    }
    @Test fun clearActivePreservesBookmarksAndConcurrentWritesKeepBothFields() = runTest(dispatcher) {
        val prefs = JsonPrefs(); val c = PrefsSurveyCursorStore(prefs)
        c.write(1, 10); c.writeAnalysis(1, AnalysisReference(10, 20, "a1")); c.write(1, 11)
        prefs.beforeSet = { yield() }
        val pending = SurveyChangeIntent(11, "v2", SurveyChangeKind.RETEST)
        coroutineScope {
            launch { c.writeAnalysis(1, AnalysisReference(11, 21, "a1")) }
            launch { c.writeChange(1, pending) }
        }
        assertEquals(21L, c.readAnalysis(1)?.resultId); assertEquals(pending, c.readChange(1))
        c.clearActive(1, 999); assertEquals(11L, c.read(1))
        c.clearActive(1, 11); assertNull(c.read(1)); assertNull(c.readAnalysis(1))
        assertEquals(10L, c.readPrevious(1)?.sessionId); assertEquals(pending, c.readChange(1))
    }
    @Test fun preparingAndCancelingRetestDoesNotCreateOrDeleteAnything() = runTest(dispatcher) {
        val s = ChangeServer(); val c = cursor(); val m = vm(s, c)
        m.load(11, SurveyChangeKind.RETEST); runCurrent()
        assertEquals(SurveyChangePage.Retest, m.state.value.page); assertEquals(0, s.starts)
        m.leave(); assertEquals(11L, c.read(1)); assertNull(c.readChange(1))
    }
    @Test fun retestCreatesBlankDraftAndPreservesPreviousAnalysisBookmark() = runTest(dispatcher) {
        val s = ChangeServer(); val c = cursor(); val m = vm(s, c)
        m.load(11, SurveyChangeKind.RETEST); runCurrent(); m.confirm(); m.confirm(); runCurrent()
        assertEquals(1, s.starts); assertEquals(0, s.draft!!.answeredCount); assertEquals("Q001", s.draft!!.nextQuestionId)
        assertEquals(SurveyChangePage.Done, m.state.value.page); assertEquals(12L, c.read(1)); assertNull(c.readChange(1))
        assertEquals(AnalysisReference(11,21,"a1"), c.readPrevious(1)?.analysis)
        c.write(1, 11); assertEquals(21L, c.readAnalysis(1)?.resultId); assertEquals(12L, c.readPrevious(1)?.sessionId)
        assertEquals(2, s.source.answeredCount); assertEquals("COMPLETED", s.source.status)
    }
    @Test fun existingDraftIsResumedWithoutResettingAnswers() = runTest(dispatcher) {
        val s = ChangeServer().apply { draft = SurveySession(12, surveyManifest, "DRAFT", 1, 2, "Q002", source.answers.take(1)) }
        val c = cursor(); val m = vm(s, c); m.load(11, SurveyChangeKind.RETEST); runCurrent(); m.confirm(); runCurrent()
        assertEquals(1, s.draft!!.answeredCount); assertEquals(12L, c.read(1))
    }
    @Test fun lostStartResponseSurvivesViewModelRecreationAndRequiresExplicitRecovery() = runTest(dispatcher) {
        val s = ChangeServer().apply { loseResponse = true }; val c = cursor(); val m = vm(s,c)
        m.load(11, SurveyChangeKind.RETEST); runCurrent(); m.confirm(); runCurrent(); m.leave()
        assertEquals(11L,c.read(1)); assertNotNull(c.readChange(1))
        val restored = vm(s,c,name="restored"); restored.load(11,SurveyChangeKind.RETEST);runCurrent()
        assertEquals(SurveyChangePage.Uncertain,restored.state.value.page);assertEquals(1,s.starts)
        s.loseResponse=false;restored.confirm();runCurrent()
        assertEquals(12L,c.read(1));assertEquals(2,s.starts);assertNull(c.readChange(1))
    }
    @Test fun migrationUsesReviewedHashAndLeavesOriginalAnswersIntact() = runTest(dispatcher) {
        val s=ChangeServer().apply { target=surveyDefinition.copy(manifest=surveyManifest.copy(surveyVersion="v2")) }
        val c=cursor();val m=vm(s,c);m.load(11,SurveyChangeKind.MIGRATION);runCurrent()
        assertEquals(SurveyChangePage.Migration,m.state.value.page);assertEquals(0,s.moves)
        assertEquals(s.hash,m.state.value.intent?.policyHash)
        m.confirm();runCurrent();assertEquals(1,s.moves);assertEquals(1,s.draft!!.answeredCount)
        assertEquals(s.hash, s.receivedHash)
        assertEquals(2,s.source.answeredCount);assertEquals(SurveyChangePage.Done,m.state.value.page)
    }
    @Test fun policyChangeRequiresNewPreviewAndConfirmation() = runTest(dispatcher) {
        val s=ChangeServer().apply { target=surveyDefinition.copy(manifest=surveyManifest.copy(surveyVersion="v2")); error=AppError.Server(409,"MIGRATION_POLICY_CHANGED","changed") }
        val c=cursor();val m=vm(s,c);m.load(11,SurveyChangeKind.MIGRATION);runCurrent();m.confirm();runCurrent()
        assertEquals(SurveyChangePage.PolicyChanged,m.state.value.page);assertNull(m.state.value.intent);assertNull(c.readChange(1))
        m.confirm();runCurrent();assertEquals(1,s.moves)
        s.hash="c".repeat(64);s.error=null;m.reload();runCurrent()
        assertEquals(s.hash,m.state.value.intent?.policyHash);assertEquals(1,s.moves)
        m.confirm();runCurrent();assertEquals(2,s.moves)
    }
    @Test fun currentVersionAndUnsupportedMigrationNeverPost() = runTest(dispatcher) {
        val s=ChangeServer();val c=cursor();val m=vm(s,c)
        m.load(11,SurveyChangeKind.MIGRATION);runCurrent();m.confirm();runCurrent()
        assertEquals(SurveyChangePage.Current,m.state.value.page);assertEquals(0,s.plans)
        s.target=surveyDefinition.copy(manifest=surveyManifest.copy(surveyVersion="v2"))
        s.planError=AppError.Server(409,"MIGRATION_UNSUPPORTED","unsupported");m.reload();runCurrent();m.confirm();runCurrent()
        assertEquals(SurveyChangePage.Unsupported,m.state.value.page);assertEquals(0,s.moves)
    }
    @Test fun lateMigrationAfterAccountChangeCannotReplaceCursor() = runTest(dispatcher) {
        val s=ChangeServer().apply { target=surveyDefinition.copy(manifest=surveyManifest.copy(surveyVersion="v2")) }
        val c=cursor();val session=TestSession(OnboardingStatus.KEYRING_REQUIRED);val m=vm(s,c,session)
        m.load(11,SurveyChangeKind.MIGRATION);runCurrent();val delayed=CompletableDeferred<ApiResult<SurveySession>>();s.delayed=delayed
        m.confirm();runCurrent();session.state.value=Session.SignedIn(2,OnboardingStatus.KEYRING_REQUIRED,false);runCurrent()
        delayed.complete(ApiResult.Ok(SurveySession(12,s.target.manifest,"DRAFT",0,2,"Q001",emptyList())));runCurrent()
        assertEquals(11L,c.read(1));assertNull(m.state.value.intent);assertFalse(m.state.value.busy)
    }
    @Test fun oldCursorJsonLoadsWithNewOptionalFieldsAndIsAccountScoped() = runTest(dispatcher) {
        val prefs=JsonPrefs().apply { encoded.value="""{"userId":1,"sessionId":11,"analysis":{"sessionId":11,"resultId":21,"analysisVersion":"a1"}}""" }
        val c=PrefsSurveyCursorStore(prefs);assertEquals(11L,c.read(1));assertNull(c.read(2));assertNull(c.readChange(1))
        c.writeChange(1,SurveyChangeIntent(11,"v2",SurveyChangeKind.MIGRATION,"b".repeat(64)))
        c.write(1,12);assertNotNull(c.readChange(1));assertEquals(21L,c.readPrevious(1)?.analysis?.resultId)
        c.write(1,null);assertNull(c.readPrevious(1));assertNull(c.readChange(1))
    }
    @Test fun migrationHttpUsesQueryAndExactReviewedBodyWithoutRevisionHeaders() = runTest {
        var calls=0;val hash="d".repeat(64)
        val client=createHttpClient("https://test/dev/api/",httpLog=false,engine=MockEngine { req ->
            calls++;assertNull(req.headers["If-Match"]);assertNull(req.headers["Idempotency-Key"])
            val body=if(req.method==HttpMethod.Get) {
                assertEquals("/dev/api/survey-sessions/11/migration-plan",req.url.encodedPath);assertEquals("v2",req.url.parameters["targetSurveyVersion"])
                ApiJson.encodeToString(SurveyMigrationPlan.serializer(),SurveyMigrationPlan(11,"v2",listOf("Q001"),listOf("Q002"),emptyList(),hash))
            } else {
                assertEquals("/dev/api/survey-sessions/11/migrations",req.url.encodedPath)
                assertEquals(buildJsonObject { put("targetSurveyVersion","v2");put("policyHash",hash) },Json.parseToJsonElement(req.body.toByteArray().decodeToString()))
                ApiJson.encodeToString(SurveySession.serializer(),SurveySession(12,surveyManifest.copy(surveyVersion="v2"),"DRAFT",0,2,"Q001",emptyList()))
            }
            respond(body,if(req.method==HttpMethod.Get) HttpStatusCode.OK else HttpStatusCode.Created,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try { val repo=HttpSurveyRepository(client);assertIs<ApiResult.Ok<*>>(repo.migrationPlan(11,"v2"));assertIs<ApiResult.Ok<*>>(repo.migrate(11,"v2",hash));assertEquals(2,calls) } finally { client.close() }
    }
}
