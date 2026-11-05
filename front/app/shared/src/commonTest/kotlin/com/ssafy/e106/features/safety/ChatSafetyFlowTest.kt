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
    var requests = 0; var queries = 0; var acks = 0
    val consentWrites = mutableListOf<RiskConsentBody>()
    var consent = RiskConsentRecord(true, "v1", "2026-11-03T00:00:00Z")
    var consentError: AppError? = null
    override suspend fun requestQuestions(room: Long, key: String): ApiResult<IcebreakerTask> {
        requests++; return ApiResult.Ok(IcebreakerTask(room,"ICEBREAKERS","PENDING","/api/chat-rooms/$room/icebreakers",null,false,2))
    }
    override suspend fun questions(room: Long): ApiResult<IcebreakerResult> { queries++; return onQuestions?.invoke() ?: result }
    override suspend fun notices(room: Long) = ApiResult.Ok(ChatNotices(listOf(ChatNoticeRecord("notice", "ONE_HOUR_BEFORE_END",
        "2026-11-03T00:00:00Z", "2026-11-10T00:00:00Z", "곧 종료", null))))
    override suspend fun acknowledge(room: Long, id: String, key: String): ApiResult<ChatNoticeAck> { acks++; return ApiResult.Ok(ChatNoticeAck("2026-11-03T00:00:00Z")) }
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
