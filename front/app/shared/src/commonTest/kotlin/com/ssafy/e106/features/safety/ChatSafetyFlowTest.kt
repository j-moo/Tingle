package com.ssafy.e106.features.safety

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.safety.data.*
import com.ssafy.e106.features.safety.ui.*
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

// 호스트/기기 테스트가 같은 응답을 쓰며 실제 앱 DI에는 포함하지 않는다.
internal class ExtraServer : ChatExtrasRepository {
    var result: ApiResult<IcebreakerResult> = ApiResult.Fail(AppError.Server(404, "RESOURCE_NOT_FOUND", ""))
    var onQuestions: (suspend () -> ApiResult<IcebreakerResult>)? = null
    // 안내 지연/실패도 질문 요청과 독립적으로 재현한다. 실제 앱의 Repository에는 넣지 않는다.
    var onNotices: (suspend () -> ApiResult<ChatNotices>)? = null
    var requests = 0; var queries = 0; var acks = 0
    val consentWrites = mutableListOf<RiskConsentBody>()
    var consent = RiskConsentRecord(true, "v1", "2026-11-03T00:00:00Z")
    var consentError: AppError? = null
    override suspend fun requestQuestions(room: Long, key: String): ApiResult<IcebreakerTask> {
        requests++; return ApiResult.Ok(IcebreakerTask(room,"ICEBREAKERS","PENDING","/api/chat-rooms/$room/icebreakers",null,false,2))
    }
    override suspend fun questions(room: Long): ApiResult<IcebreakerResult> { queries++; return onQuestions?.invoke() ?: result }
    override suspend fun notices(room: Long) = onNotices?.invoke() ?: ApiResult.Ok(ChatNotices(listOf(ChatNoticeRecord("notice", "ONE_HOUR_BEFORE_END",
        "2026-11-03T00:00:00Z", "2026-11-10T00:00:00Z", "곧 종료", null))))
    override suspend fun acknowledge(room: Long, id: String, key: String): ApiResult<ChatNoticeAck> { acks++; return ApiResult.Ok(ChatNoticeAck("2026-11-03T00:00:00Z")) }
    var topic = TopicConsentRecord(false, TOPIC_CONSENT_VERSION, null)
    val topicWrites = mutableListOf<TopicConsentBody>()
    override suspend fun topicConsent(user: Long) = ApiResult.Ok(topic)
    override suspend fun topicConsent(user: Long, body: TopicConsentBody): ApiResult<TopicConsentRecord> {
        topicWrites += body; topic = TopicConsentRecord(body.enabled, body.noticeVersion, null); return ApiResult.Ok(topic)
    }
    var safety: ApiResult<SafetyNoticeList> = ApiResult.Ok(SafetyNoticeList(emptyList()))
    val shown = mutableListOf<Long>()
    var shownResult: ApiResult<SafetyNoticeShown>? = null
    var safetyQueries = 0
    override suspend fun safetyNotices(room: Long): ApiResult<SafetyNoticeList> { safetyQueries++; return safety }
    override suspend fun safetyShown(room: Long, id: Long): ApiResult<SafetyNoticeShown> {
        shown += id; return shownResult ?: ApiResult.Ok(SafetyNoticeShown(id, "2026-11-03T00:00:00Z"))
    }
    override suspend fun consent(user: Long) = ApiResult.Ok(consent)
    override suspend fun consent(user: Long, body: RiskConsentBody): ApiResult<RiskConsentRecord> {
        consentWrites += body
        return consentError?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(RiskConsentRecord(body.enabled, body.noticeVersion, null))
    }
}
// 고지 조회만 실패하는 경로를 호스트와 실제 화면 테스트에서 함께 재현한다.
internal class NoticeServer(var url: String? = null) : OnboardingRepository {
    var failure: AppError? = null
    override suspend fun agreements() = failure?.let { ApiResult.Fail(it) }
        ?: ApiResult.Ok(AgreementsResponse(listOf(AgreementItem("AI_RISK_DETECTION","v1","AI",true,url))))
    override suspend fun agree(userId: Long, answers: List<AgreementAnswer>) = ApiResult.Ok(Unit)
    override suspend fun purchaseInfo() = ApiResult.Ok(PurchaseInfo(null,true))
}
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSafetyFlowTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { store.clear() } }
    private fun session() = TestSession(OnboardingStatus.READY)
    /** 위험 안내는 아는 유형만 보이고, 닫아야 표시 확인을 보내며 확인 실패면 그대로 남는다. */
    @Test fun safetyNoticesShowKnownTypesAndCloseSendsDisplayed() = test {
        val api = ExtraServer().apply { safety = ApiResult.Ok(SafetyNoticeList(listOf(
            SafetyNoticeItem(7, "MONEY_REQUEST", "RECIPIENT", "2026-11-03T00:00:00Z"),
            SafetyNoticeItem(8, "UNKNOWN_KIND", "RECIPIENT", "2026-11-03T00:00:00Z")))) }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals(listOf(7L), m.state.value.safety.map { it.noticeId })
        assertTrue(api.shown.isEmpty())
        api.shownResult = ApiResult.Fail(AppError.Offline)
        m.closeSafety(7); runCurrent()
        assertEquals(listOf(7L), m.state.value.safety.map { it.noticeId })
        api.shownResult = null
        m.closeSafety(7); runCurrent()
        assertEquals(listOf(7L, 7L), api.shown); assertTrue(m.state.value.safety.isEmpty())
        m.check(); runCurrent()
        assertTrue(m.state.value.safety.isEmpty())
    }
    /** 공통 관심 주제 질문(COMMON_TOPIC_TEMPLATE)도 정상 질문으로 보여 주고 출처를 남긴다. */
    @Test fun commonTopicQuestionsAreShown() = test {
        val api = ExtraServer().apply { result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"COMMON_TOPIC_TEMPLATE","v1")) }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals(listOf("하나","둘","셋"), m.state.value.questions)
        assertEquals("COMMON_TOPIC_TEMPLATE", m.state.value.source); assertNull(m.state.value.error)
    }
    /** 관심 주제 동의는 서버 버전으로 저장하고, 응답 값으로만 스위치를 바꾼다. */
    @Test fun topicConsentLoadsAndSavesWithServerVersion() = test {
        val api = ExtraServer()
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals(false, m.state.value.topicConsent)
        m.setTopicConsent(true); runCurrent()
        assertEquals(listOf(TopicConsentBody(true, "q040-consent-v1")), api.topicWrites)
        assertEquals(true, m.state.value.topicConsent); assertFalse(m.state.value.topicSaving)
        m.setTopicConsent(true); runCurrent()
        assertEquals(1, api.topicWrites.size)
    }
    /** 동의를 끄면 보이던 공통 주제 질문을 바로 지우고 서버의 일반 질문을 다시 받는다. 생성 요청은 하지 않는다. */
    @Test fun topicConsentOffHidesCommonTopicQuestionsAndReloads() = test {
        val api = ExtraServer().apply { topic = TopicConsentRecord(true, TOPIC_CONSENT_VERSION, null)
            result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"COMMON_TOPIC_TEMPLATE","v1")) }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals("COMMON_TOPIC_TEMPLATE", m.state.value.source)
        // 다시 조회가 실패해도 옛 공통 주제 질문은 남지 않는다
        api.onQuestions = { ApiResult.Fail(AppError.Offline) }
        m.setTopicConsent(false); runCurrent()
        assertEquals(false, m.state.value.topicConsent)
        assertTrue(m.state.value.questions.isEmpty()); assertNull(m.state.value.source)
        api.onQuestions = null
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("넷","다섯","여섯"),"GENERAL_FALLBACK","v1"))
        m.check(); runCurrent()
        assertEquals(listOf("넷","다섯","여섯"), m.state.value.questions)
        assertEquals("GENERAL_FALLBACK", m.state.value.source); assertEquals(0, api.requests)
        // 조회가 되는 경우: 끄자마자 일반 질문으로 바뀐다
        m.setTopicConsent(true); runCurrent()
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("일곱","여덟","아홉"),"GENERAL_FALLBACK","v1"))
        m.setTopicConsent(false); runCurrent()
        assertEquals(listOf("일곱","여덟","아홉"), m.state.value.questions); assertEquals(0, api.requests)
    }
    /** 동의를 끄기 전에 시작한 질문 조회의 응답이 늦게 와도 옛 공통 주제 질문을 되살리지 않는다. */
    @Test fun topicConsentOffDiscardsLateQuestionResponse() = test {
        val late = CompletableDeferred<ApiResult<IcebreakerResult>>()
        val api = ExtraServer().apply { topic = TopicConsentRecord(true, TOPIC_CONSENT_VERSION, null); onQuestions = { late.await() } }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertTrue(m.state.value.busy); assertEquals(true, m.state.value.topicConsent)
        // 끈 뒤의 조회는 서버가 바꿔 둔 일반 질문을 받는다
        api.onQuestions = null
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("넷","다섯","여섯"),"GENERAL_FALLBACK","v1"))
        m.setTopicConsent(false); runCurrent()
        assertEquals(listOf("넷","다섯","여섯"), m.state.value.questions)
        late.complete(ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"COMMON_TOPIC_TEMPLATE","v1"))); runCurrent()
        assertEquals(listOf("넷","다섯","여섯"), m.state.value.questions)
        assertEquals("GENERAL_FALLBACK", m.state.value.source)
        assertFalse(m.state.value.busy); assertEquals(0, api.requests)
    }
    /** 첫 재조회(3초) 뒤에 생긴 위험 안내도 같은 화면에서 보인다. 재조회는 정해진 횟수만 하고 질문은 건드리지 않는다. */
    @Test fun lateSafetyNoticeAppearsOnLaterRecheckWithinLimit() = test {
        val api = ExtraServer()
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        val queries = api.queries; val base = api.safetyQueries
        m.watchSafety()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(base + 1, api.safetyQueries); assertTrue(m.state.value.safety.isEmpty())
        // 서버 분석이 6초쯤 끝나 안내가 생긴 경우
        api.safety = ApiResult.Ok(SafetyNoticeList(listOf(SafetyNoticeItem(7, "MONEY_REQUEST", "RECIPIENT", "2026-11-03T00:00:00Z"))))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(7L), m.state.value.safety.map { it.noticeId })
        advanceTimeBy(60_000); runCurrent()
        assertEquals(base + SAFETY_REFRESH_AT_MS.size, api.safetyQueries)
        assertEquals(queries, api.queries); assertEquals(0, api.requests)
        // 새 메시지가 오면 처음부터 다시 센다
        m.watchSafety(); advanceTimeBy(2_000); m.watchSafety(); advanceTimeBy(2_000); runCurrent()
        assertEquals(base + SAFETY_REFRESH_AT_MS.size, api.safetyQueries)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(base + SAFETY_REFRESH_AT_MS.size + 1, api.safetyQueries)
    }
    /** 입장 때 없던 위험 안내가 채팅 중 생기면 같은 방에서 보이고, 이때 질문은 다시 조회 · 생성하지 않는다. */
    @Test fun safetyNoticeCreatedDuringChatAppearsWithoutQuestionRequests() = test {
        val api = ExtraServer()
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertTrue(m.state.value.safety.isEmpty())
        val queries = api.queries; val questions = m.state.value.questions
        api.safety = ApiResult.Ok(SafetyNoticeList(listOf(SafetyNoticeItem(7, "HARASSMENT", "RECIPIENT", "2026-11-03T00:00:00Z"))))
        m.refreshSafety(); runCurrent()
        assertEquals(listOf(7L), m.state.value.safety.map { it.noticeId })
        m.closeSafety(7); runCurrent()
        assertEquals(listOf(7L), api.shown); assertTrue(m.state.value.safety.isEmpty())
        // 같은 방으로 돌아오면(bind) 닫은 안내는 빼고 새 안내만 보인다
        api.safety = ApiResult.Ok(SafetyNoticeList(listOf(SafetyNoticeItem(7, "HARASSMENT", "RECIPIENT", "2026-11-03T00:00:00Z"),
            SafetyNoticeItem(9, "MONEY_REQUEST", "RECIPIENT", "2026-11-03T00:00:00Z"))))
        m.bind(20,true); runCurrent()
        assertEquals(listOf(9L), m.state.value.safety.map { it.noticeId })
        assertEquals(queries, api.queries); assertEquals(0, api.requests); assertEquals(questions, m.state.value.questions)
    }
    /** 방 확인 이후 도착한 안내가 방 종료를 넘어 잠깐이라도 화면에 노출되면 안 된다. */
    @Test fun delayedNoticeRechecksRoomBeforePublishingOrRequestingQuestions() = test {
        val gate = CompletableDeferred<Unit>(); val chats = ChatTestServer(); val api = ExtraServer()
        val original = api.notices(20)
        api.onNotices = { gate.await(); original }
        api.onQuestions = { awaitCancellation() }
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        chats.roomRecord = chats.roomRecord.copy(status="CLOSED")
        gate.complete(Unit); runCurrent()
        assertTrue(m.state.value.notices.isEmpty())
        assertNull(m.state.value.room); assertEquals(0,api.queries)
    }
    /** 질문 GET의 통신 실패는 자동 반복을 끝내고, 재생성 없이 사용자의 재조회로 복구한다. */
    @Test fun questionPollingFailureStopsAndManualCheckRecovers() = test {
        val api = ExtraServer().apply { result = ApiResult.Ok(IcebreakerResult("PENDING",emptyList(),null,null)) }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); m.generate(); runCurrent()
        api.result = ApiResult.Fail(AppError.Offline)
        advanceTimeBy(2_000); runCurrent()
        assertFalse(m.state.value.busy)
        assertEquals(AppError.Offline,m.state.value.error)
        val queries = api.queries; advanceTimeBy(10_000); runCurrent()
        assertEquals(queries,api.queries)
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERAL_FALLBACK","v1"))
        m.check(); runCurrent()
        assertEquals(3,m.state.value.questions.size); assertEquals(1,api.requests)
    }
    /** 방 조회 직후 질문 API가 종료/동의 철회를 알리면 이전 안내도 즉시 제거한다. */
    @Test fun questionAccessRevocationClearsRoomAndNotices() = test {
        for (error in listOf(AppError.Server(410,"CHAT_CLOSED",""),
            AppError.Server(403,"AI_RISK_CONSENT_REQUIRED",""),AppError.SessionExpired)) {
            val api = ExtraServer().apply { result = ApiResult.Fail(error) }
            val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
            m.bind(20,true); runCurrent()
            assertNull(m.state.value.room); assertTrue(m.state.value.notices.isEmpty())
            assertTrue(m.state.value.questions.isEmpty()); assertFalse(m.state.value.busy)
        }
    }
    /** 안내 API가 종료를 반환하면 질문 요청/표시 ACK를 추가로 보내지 않는다. */
    @Test fun noticeRevocationStopsOtherExtrasRequests() = test {
        val api = ExtraServer().apply { onNotices = { ApiResult.Fail(AppError.Server(410,"CHAT_CLOSED","")) } }
        val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); m.displayedNotice("notice"); runCurrent()
        assertNull(m.state.value.room); assertEquals(0,api.queries); assertEquals(0,api.acks)
    }
    /** 질문 미생성 404는 방 접근이 유효할 때만 생성 버튼을 허용한다. */
    @Test fun missingQuestionsKeepsGenerationButMissingRoomDoesNot() = test {
        val api = ExtraServer(); val chats = ChatTestServer()
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals(20L,m.state.value.room); assertFalse(m.state.value.accessRetryRequired)
        m.generate(); runCurrent(); assertEquals(1,api.requests)
        // 시작 후 참가 권한을 잃은 404는 질문 미생성으로 되돌리지 않는다.
        chats.roomFailure = AppError.Server(404,"RESOURCE_NOT_FOUND","")
        advanceTimeBy(2_000); runCurrent()
        assertNull(m.state.value.room); assertFalse(m.state.value.busy)
    }
    @Test fun noticeFailureOffersReloadAndRecoveryStillRequiresExplicitConsent() = test {
        for (failure in listOf(AppError.Offline, AppError.Server(503,"SERVICE_UNAVAILABLE","잠시 후 다시 시도해 주세요"))) {
            val api = ExtraServer().apply { consent = consent.copy(enabled=false) }
            val docs = NoticeServer("https://tingle.test/ai-notice").apply { this.failure = failure }
            val m = AiConsentViewModel(api,docs,session()).also { store.put("a",it) }
            m.load(); runCurrent()
            assertEquals(AiConsentStage.Failed,m.state.value.ui.stage); assertEquals(failure,m.state.value.error)
            m.reviewed(); m.check(true); m.save(true); runCurrent(); assertTrue(api.consentWrites.isEmpty())
            docs.failure = null; m.load(); runCurrent()
            assertEquals(AiConsentStage.Ready,m.state.value.ui.stage); assertTrue(m.state.value.ui.noticeAvailable)
            assertNull(m.state.value.error); assertFalse(m.state.value.ui.canGrant); assertTrue(api.consentWrites.isEmpty())
            // 재조회가 자동 동의를 뜻하지 않으며 전문 확인·체크 뒤에만 저장할 수 있다.
            m.reviewed(); m.check(true); m.save(true); runCurrent()
            assertEquals(listOf(RiskConsentBody(true,"v1")),api.consentWrites)
        }
    }
    @Test fun noticeSessionFailureUsesLoginStateInsteadOfReady() = test {
        val docs = NoticeServer().apply { failure = AppError.SessionExpired }
        val m = AiConsentViewModel(ExtraServer(),docs,session()).also { store.put("a",it) }
        m.load(); runCurrent()
        assertEquals(AiConsentStage.LoginRequired,m.state.value.ui.stage)
        assertEquals(AppError.SessionExpired,m.state.value.error); assertNull(m.state.value.ui.notice)
    }
    @Test fun temporaryRoomFailureKeepsBindingAndRecoversWithoutReentry() = test {
        for (failure in listOf(AppError.Offline, AppError.Server(503,"SERVICE_UNAVAILABLE","잠시 후 다시 시도해 주세요"))) {
            val api = ExtraServer().apply { result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERATED","v1")) }
            val chats = ChatTestServer().apply { roomFailure = failure }
            val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
            m.bind(20,true); runCurrent()
            // 같은 화면에서 재시도할 대상을 잃지 않으며 실패를 권한 승인으로 간주하지 않는다.
            assertEquals(20L,m.state.value.room); assertEquals(failure,m.state.value.error)
            assertFalse(m.state.value.busy); assertEquals(0,api.queries)
            chats.roomFailure = null; m.check(); runCurrent()
            assertEquals(listOf("하나","둘","셋"),m.state.value.questions); assertNull(m.state.value.error)
            assertEquals(0,api.requests)
        }
    }
    @Test fun temporaryRoomFailureAfterQuestionResponseCanRetryWithoutGeneration() = test {
        val chats = ChatTestServer(); val api = ExtraServer()
        api.onQuestions = { chats.roomFailure = AppError.Offline
            ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERATED","v1")) }
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent()
        assertEquals(20L,m.state.value.room); assertTrue(m.state.value.questions.isEmpty()); assertEquals(AppError.Offline,m.state.value.error)
        chats.roomFailure = null; api.onQuestions = null
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERATED","v1"))
        m.check(); runCurrent(); assertEquals(3,m.state.value.questions.size); assertEquals(0,api.requests)
    }
    @Test fun temporaryRoomFailureDuringGenerationPollingCanRecoverAndRevocationStillClears() = test {
        val chats = ChatTestServer(); val api = ExtraServer()
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); m.generate(); runCurrent()
        chats.roomFailure = AppError.Offline; advanceTimeBy(2_000); runCurrent()
        assertEquals(20L,m.state.value.room); assertFalse(m.state.value.busy)
        chats.roomFailure = null; api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERATED","v1"))
        m.check(); runCurrent(); assertEquals(3,m.state.value.questions.size); assertEquals(1,api.requests)
        chats.roomFailure = AppError.Server(403,"AI_RISK_CONSENT_REQUIRED","")
        m.check(); runCurrent(); assertNull(m.state.value.room); assertTrue(m.state.value.questions.isEmpty())
    }
    @Test fun blockUnknownRestoresExactKeyEvenIfRoomClosed() = test {
        val calls = mutableListOf<PendingSafety>(); var fail = true
        val api = object : SafetyRepository { override suspend fun execute(request: PendingSafety): ApiResult<Unit> {
            calls += request; return if(fail) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(Unit)
        } }
        val prefs = ChatMemoryPrefs(); val chats = ChatTestServer(); val s = session()
        var m = SafetyViewModel(api,chats,s,prefs).also { store.put("s",it) }
        m.bind(20,SafetyOperation.Block); runCurrent(); m.ask(); m.submit(); runCurrent()
        assertEquals(SafetyPhase.Unknown,m.state.value.ui.phase)
        chats.roomRecord = chats.roomRecord.copy(status="CLOSED")
        m = SafetyViewModel(api,chats,s,prefs).also { store.put("s",it) }; m.bind(20,SafetyOperation.Block); runCurrent()
        assertEquals(1,calls.size); fail = false; m.recover(); runCurrent()
        assertEquals(calls[0],calls[1]); assertEquals(SafetyPhase.Succeeded,m.state.value.ui.phase)
    }
    @Test fun closedErrorDoesNotPretendLeaveSucceeded() = test {
        val api = object : SafetyRepository { override suspend fun execute(request: PendingSafety) = ApiResult.Fail(AppError.Server(410,"CHAT_CLOSED","")) }
        val m = SafetyViewModel(api,ChatTestServer(),session(),ChatMemoryPrefs()).also { store.put("s",it) }
        m.bind(20,SafetyOperation.Leave); runCurrent(); m.ask(); m.submit(); runCurrent()
        assertEquals(SafetyPhase.ClosedUnconfirmed,m.state.value.ui.phase); assertNull(m.state.value.pending)
    }
    @Test fun roomDenialDoesNotGenerateOrAcknowledgeAnything() = test {
        val api = ExtraServer(); val chats = ChatTestServer().apply { roomRecord = roomRecord.copy(status="CLOSED") }
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); m.generate(); m.displayedNotice("notice"); runCurrent()
        assertEquals(0,api.requests); assertEquals(0,api.acks); assertTrue(m.state.value.questions.isEmpty())
    }
    @Test fun pendingWaitsAdvertisedIntervalAndShowsOnlyThreeServerQuestions() = test {
        val api = ExtraServer(); val m = ChatExtrasViewModel(api,ChatTestServer(),session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); assertEquals(0,api.acks)
        api.result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERAL_FALLBACK","v1"))
        m.generate(); runCurrent(); val before = api.queries
        advanceTimeBy(1999); runCurrent(); assertEquals(before,api.queries)
        advanceTimeBy(1); runCurrent(); assertEquals(3,m.state.value.questions.size)
        m.displayedNotice("notice"); runCurrent(); assertEquals(1,api.acks)
    }
    @Test fun delayedQuestionsAreDiscardedWhenRoomCloses() = test {
        val api = ExtraServer(); val chats = ChatTestServer(); val gate = CompletableDeferred<Unit>()
        api.onQuestions = { gate.await(); ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERATED","v1")) }
        val m = ChatExtrasViewModel(api,chats,session()).also { store.put("e",it) }
        m.bind(20,true); runCurrent(); chats.roomRecord = chats.roomRecord.copy(status="CLOSED"); gate.complete(Unit); runCurrent()
        assertTrue(m.state.value.questions.isEmpty())
    }
    @Test fun consentGrantRequiresPublishedMatchingNoticeAndExplicitReview() = test {
        val api = ExtraServer().apply { consent = consent.copy(enabled=false) }; val docs = NoticeServer()
        val m = AiConsentViewModel(api,docs,session()).also { store.put("a",it) }
        m.load(); runCurrent(); m.reviewed(); m.check(true); m.save(true); runCurrent(); assertTrue(api.consentWrites.isEmpty())
        docs.url = "https://tingle.test/ai-notice"; m.load(); runCurrent(); m.check(true); m.save(true); runCurrent(); assertTrue(api.consentWrites.isEmpty())
        m.reviewed(); m.check(true); m.save(true); runCurrent(); assertEquals(RiskConsentBody(true,"v1"),api.consentWrites.single())
    }
    @Test fun withdrawalHasConfirmationAndLostResponseOnlyReloads() = test {
        val api = ExtraServer().apply { consentError = AppError.Offline }
        val m = AiConsentViewModel(api,NoticeServer(),session()).also { store.put("a",it) }
        m.load(); runCurrent(); m.save(false); runCurrent(); assertTrue(api.consentWrites.isEmpty())
        m.withdraw(); m.save(false); runCurrent(); assertEquals(AiConsentStage.Unknown,m.state.value.ui.stage)
        m.load(); runCurrent(); assertEquals(1,api.consentWrites.size)
    }
    @Test fun outdatedConsentVersionClearsOldChecks() = test {
        val api = ExtraServer().apply { consentError = AppError.Server(409,"AGREEMENT_VERSION_OUTDATED","") }
        val m = AiConsentViewModel(api,NoticeServer(),session()).also { store.put("a",it) }
        m.load(); runCurrent(); m.withdraw(); m.save(false); runCurrent()
        assertEquals(AiConsentStage.VersionChanged,m.state.value.ui.stage); assertNull(m.state.value.ui.snapshot)
    }
}
