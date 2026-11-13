package com.ssafy.e106.features.proposals

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.proposals.data.*
import com.ssafy.e106.features.proposals.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// 캐시/영속 키를 분리해 실제 저장소의 읽기 실패 및 이관 실패를 재현한다.
private class MemoryPrefs : AppPrefs {
    val values = mutableMapOf<String, Any?>()
    var failure: PrefRead<Nothing>? = null
    var dropWrites = false
    var keepLegacy = false
    var failAfterWrite = false
    var writeGate: CompletableDeferred<Unit>? = null
    var writeStarted = false
    var sets = 0
    private fun id(key: PrefKey<*>) = "${key.durable}:${key.name}"
    @Suppress("UNCHECKED_CAST") override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(if(failure != null) null else values[id(key)] as T?)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        sets++
        if (value is ProposalRecoveryRecord && value.request != null) { writeStarted = true; writeGate?.await() }
        if (!dropWrites && !(keepLegacy && !key.durable)) values[id(key)] = value
        if (failAfterWrite) failure = PrefRead.Unreadable
    }
    @Suppress("UNCHECKED_CAST") override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> =
        failure ?: values[id(key)]?.let { PrefRead.Found(it as T) } ?: PrefRead.Missing
}
private class ProposalServer : ProposalRepository {
    var record = ProposalRecord(10, "PENDING", "NONE", "2026-11-03T00:00:00Z", "2026-11-04T00:00:00Z", false)
    var person = PartnerProfile("상대", 25, "FEMALE", null, "NON_SMOKER", "NEVER", null, false, "a".repeat(64))
    val writes = mutableListOf<PendingDecision>()
    var onWrite: (suspend (PendingDecision) -> ApiResult<ProposalRecord>)? = null
    var onProfile: (suspend () -> ApiResult<PartnerProfile>)? = null
    var onList: (suspend (String?) -> ApiResult<ProposalPage>)? = null
    // 서버 시각 경과·느린 GET·늦게 도착한 이전 계정 응답을 재현한다.
    var onGet: (suspend () -> ApiResult<ProposalRecord>)? = null
    var reads = 0
    var photoError: AppError = AppError.Server(410, "PHOTO_REMOVED", "removed")
    var photoGate: CompletableDeferred<Unit>? = null
    override suspend fun list(cursor: String?) = onList?.invoke(cursor) ?: ApiResult.Ok(ProposalPage(listOf(record), null, "2026-11-03T00:00:00Z"))
    override suspend fun get(id: Long): ApiResult<ProposalRecord> { reads++; return onGet?.invoke() ?: ApiResult.Ok(record) }
    override suspend fun profile(id: Long) = onProfile?.invoke() ?: ApiResult.Ok(person)
    override suspend fun photo(id: Long): ApiResult<ByteArray> {
        val gate = photoGate; val error = photoError
        // 실제 HTTP 처럼 취소를 늦게 처리해도 응답이 온다고 가정한다(늦은 사진은 요청 번호로만 걸러져야 한다)
        withContext(NonCancellable) { gate?.await() }
        return ApiResult.Fail(if (gate != null && photoError != error) photoError else error)
    }
    override suspend fun decide(request: PendingDecision): ApiResult<ProposalRecord> {
        writes += request
        return onWrite?.invoke(request) ?: ApiResult.Ok(record.copy(status = "WAITING_FOR_OTHER", myDecision = request.choice))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProposalFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(server: ProposalServer, session: TestSession = TestSession(OnboardingStatus.READY), prefs: MemoryPrefs = MemoryPrefs()) =
        ProposalViewModel(server, session, prefs, ProposalAccess(true)).also { store.put("proposal", it); it.resume() }
    // runTest가 유휴 상태를 기다리기 전에 화면의 반복 작업을 정리해 테스트 간 조회가 남지 않게 한다.
    private fun flowTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { store.clear() }
    }
    private fun TestScope.profile(m: ProposalViewModel) { runCurrent(); m.open(10); runCurrent() }

    // 실제 수락 경로를 거친 뒤 서버 상태만 바꿔 자동 갱신의 결과/수명을 검증한다. 수락하면 결과 화면이 아니라 프로필에 "수락했어요"
    private fun TestScope.waiting(m: ProposalViewModel) {
        profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertWaiting(m)
    }
    private fun assertWaiting(m: ProposalViewModel) {
        assertEquals(ProposalPageMode.Profile, m.state.value.page)
        assertEquals(MyProposalDecision.AcceptedWaiting, m.state.value.review?.current?.profile?.myDecision)
        assertFalse(m.state.value.review!!.canChoose)
    }

    @Test fun automaticRefreshUsesServerExpiryAndStopsAfterTerminalResult() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.record = server.record.copy(status = "EXPIRED", myDecision = "ACCEPT")
        val before = server.reads
        advanceTimeBy(4_999); runCurrent(); assertEquals(before, server.reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(ProposalDecisionStage.Expired, m.state.value.result?.stage)
        advanceTimeBy(30_000); runCurrent(); assertEquals(before + 1, server.reads)
        assertEquals(1, server.writes.size)
    }

    @Test fun automaticRefreshContinuesUntilChatReadyWithoutOverlappingSlowRequests() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        val gate = CompletableDeferred<ApiResult<ProposalRecord>>()
        server.onGet = { gate.await() }
        val before = server.reads
        advanceTimeBy(5_000); runCurrent()
        assertTrue(m.state.value.refreshingResult)
        assertWaiting(m)
        // 느린 자동 확인이 끝나기 전엔 다음 자동 확인을 겹쳐 보내지 않는다
        advanceTimeBy(20_000); runCurrent(); assertEquals(before + 1, server.reads)
        // 아직 상대 응답 전이면 그대로 "수락했어요"에서 계속 확인
        gate.complete(ApiResult.Ok(server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")))
        runCurrent(); assertWaiting(m)
        server.onGet = null
        server.record = server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT", chatAvailable = true, chatRoomId = 3)
        advanceTimeBy(5_000); runCurrent()
        // 방이 생기면 결과 화면 없이 바로 채팅을 열게 하고, 목록으로 돌려 둔다
        assertEquals(3L, m.state.value.openChat); assertEquals(ProposalPageMode.List, m.state.value.page)
        m.chatOpened(); assertNull(m.state.value.openChat)
        advanceTimeBy(15_000); runCurrent(); assertEquals(before + 2, server.reads)
    }

    @Test fun repeatedWaitingResponsesKeepPollingAndPauseCancelsItUntilResume() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")
        val before = server.reads
        repeat(3) { advanceTimeBy(5_000); runCurrent() }
        assertEquals(before + 3, server.reads)
        m.pause(); advanceTimeBy(20_000); runCurrent(); assertEquals(before + 3, server.reads)
        server.record = server.record.copy(status = "REJECTED")
        m.resume(); runCurrent(); assertEquals(ProposalDecisionStage.NotMatched, m.state.value.result?.stage)
        advanceTimeBy(10_000); runCurrent(); assertEquals(before + 4, server.reads)
    }

    // 취소를 무시하는 느린 GET도 목록 복귀와 새 목록 응답을 덮어쓸 수 없어야 한다.
    @Test fun listNavigationCancelsAutomaticGetAndIgnoresItsLateResponse() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        val gate = CompletableDeferred<Unit>()
        server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.record.copy(status = "EXPIRED")) }
        try {
            advanceTimeBy(5_000); runCurrent(); assertTrue(m.state.value.refreshingResult)
            m.refresh(); runCurrent()
            assertEquals(ProposalPageMode.List, m.state.value.page)
            assertFalse(m.state.value.refreshingResult); assertFalse(m.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertEquals(ProposalPageMode.List, m.state.value.page); assertNull(m.state.value.result)
            val reads = server.reads
            advanceTimeBy(20_000); runCurrent(); assertEquals(reads, server.reads)
            assertEquals(1, server.writes.size)
        } finally { gate.complete(Unit) }
    }

    // 기다리는 중 자동 확인이 잠깐 실패해도 화면은 그대로 두고 다음 주기에 다시 확인한다
    @Test fun automaticFailureKeepsWaitingAndRetriesNextCycle() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onGet = { ApiResult.Fail(AppError.Offline) }
        val before = server.reads
        advanceTimeBy(5_000); runCurrent()
        assertWaiting(m); assertNull(m.state.value.result)
        server.onGet = null; server.record = server.record.copy(status = "REJECTED", myDecision = "ACCEPT")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before + 2, server.reads)
        assertEquals(ProposalDecisionStage.NotMatched, m.state.value.result?.stage)
    }

    @Test fun leavingResultOrSwitchingAccountIgnoresLateAutomaticResponse() = flowTest {
        val server = ProposalServer(); val session = TestSession(OnboardingStatus.READY)
        val m = vm(server, session); waiting(m)
        val gate = CompletableDeferred<Unit>()
        server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.record.copy(status = "EXPIRED")) }
        advanceTimeBy(5_000); runCurrent(); m.pause()
        session.state.value = Session.SignedOut; runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(m.state.value.session); assertNull(m.state.value.result)
        val count = server.reads
        advanceTimeBy(30_000); runCurrent(); assertEquals(count, server.reads)
    }

    @Test fun returningToListStopsPollingAndLostWritesAreNeverAutomaticallyRetried() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        m.refresh(); runCurrent(); val count = server.reads
        advanceTimeBy(30_000); runCurrent(); assertEquals(count, server.reads)
        server.onWrite = { ApiResult.Fail(AppError.Offline) }
        profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        val writes = server.writes.size
        advanceTimeBy(30_000); runCurrent(); assertEquals(writes, server.writes.size)
        assertNotNull(m.state.value.pending)
    }

    @Test fun unreadablePendingDecisionBlocksNewChoiceAndRecoversExactRequest() = flowTest {
        val server = ProposalServer(); val prefs = MemoryPrefs()
        server.onWrite = { ApiResult.Fail(AppError.Offline) }
        val first = vm(server, prefs = prefs)
        profile(first); first.ask(ProposalChoice.Accept); first.submit(); runCurrent()
        val original = server.writes.single()
        val disk = prefs.values.toMap()
        prefs.failure = PrefRead.Unreadable
        val restored = vm(server, prefs = prefs); runCurrent()
        assertTrue(restored.state.value.storageIssue)
        prefs.failure = null
        profile(restored); restored.ask(ProposalChoice.Reject); restored.submit(); runCurrent()
        assertEquals(1, server.writes.size); assertEquals(disk, prefs.values)
        restored.resume(); runCurrent(); restored.recover(); runCurrent()
        assertEquals(listOf(original, original), server.writes)
    }
    @Test fun corruptOrOtherAccountRecordNeverAllowsDecision() = flowTest {
        for (failure in listOf(PrefRead.Corrupt, PrefRead.OtherUser)) {
            val server = ProposalServer(); val prefs = MemoryPrefs().also { it.failure = failure }
            val m = vm(server, prefs = prefs); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
            assertTrue(server.writes.isEmpty()); assertTrue(prefs.values.isEmpty())
        }
    }
    @Test fun unverifiedWriteDoesNotSend() = flowTest {
        val server = ProposalServer(); val prefs = MemoryPrefs(); val m = vm(server, prefs = prefs)
        profile(m); prefs.dropWrites = true; m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertTrue(m.state.value.storageIssue); assertTrue(server.writes.isEmpty())
    }
    @Test fun failedReadAfterWriteRecoversSamePersistedKeyWithoutNewDecision() = flowTest {
        val server = ProposalServer(); val prefs = MemoryPrefs(); val m = vm(server, prefs = prefs)
        profile(m); prefs.failAfterWrite = true; m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertTrue(m.state.value.storageIssue); assertTrue(server.writes.isEmpty())
        val saved = (prefs.values["true:proposal.recovery.v2"] as ProposalRecoveryRecord).request!!
        prefs.failAfterWrite = false; prefs.failure = null
        m.resume(); runCurrent(); assertEquals(saved,m.state.value.pending)
        m.recover(); runCurrent(); assertEquals(listOf(saved),server.writes)
    }
    @Test fun backgroundWhilePersistingDoesNotOpenASecondDecision() = flowTest {
        val server = ProposalServer(); val prefs = MemoryPrefs(); val m = vm(server, prefs = prefs)
        profile(m); val gate = CompletableDeferred<Unit>(); prefs.writeGate = gate
        m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertTrue(prefs.writeStarted); m.pause(); m.resume(); m.submit(); runCurrent()
        assertTrue(server.writes.isEmpty()); assertTrue(m.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals(1,server.writes.size); assertEquals("ACCEPT",server.writes.single().choice)
    }
    @Test fun legacyMigrationPreservesKeyAndCompletedMarkerPreventsResurrection() = flowTest {
        val prefs = MemoryPrefs()
        val old = PendingDecision(1,10,"ACCEPT","original-key","a".repeat(64))
        prefs.values["false:proposal.pending-decision.v1"] = old
        prefs.keepLegacy = true
        val server = ProposalServer(); val m = vm(server,prefs=prefs); runCurrent()
        assertEquals(old,m.state.value.pending); m.recover(); runCurrent()
        assertEquals(listOf(old),server.writes)
        val recreated = vm(server,prefs=prefs); runCurrent()
        assertNull(recreated.state.value.pending); assertFalse(recreated.state.value.storageIssue)
    }
    @Test fun failedMigrationKeepsLegacyAndBlocksNewDecision() = flowTest {
        val prefs = MemoryPrefs().also { it.dropWrites = true }
        val old = PendingDecision(1,10,"ACCEPT","original-key","a".repeat(64))
        prefs.values["false:proposal.pending-decision.v1"] = old
        val server = ProposalServer(); val m = vm(server,prefs=prefs); profile(m)
        m.ask(ProposalChoice.Reject); m.submit(); runCurrent()
        assertTrue(m.state.value.storageIssue); assertTrue(server.writes.isEmpty())
        assertEquals(old,prefs.values["false:proposal.pending-decision.v1"])
    }

    // 당겨서 새로고침: 보던 목록은 그대로 두고 refreshing 만 켠다. 실패하면 목록 유지 + refreshFailed, 성공하면 새 목록
    @Test fun pullRefreshKeepsListAndMarksFailure() = flowTest {
        val server = ProposalServer(); val m = vm(server); runCurrent()
        assertEquals(1, m.state.value.list.items.size)
        val gate = CompletableDeferred<ApiResult<ProposalPage>>()
        server.onList = { gate.await() }
        m.refresh(); runCurrent()
        assertTrue(m.state.value.list.refreshing); assertEquals(ProposalListLoad.Ready, m.state.value.list.load)
        assertEquals(1, m.state.value.list.items.size)
        gate.complete(ApiResult.Fail(AppError.Offline)); runCurrent()
        assertFalse(m.state.value.list.refreshing); assertTrue(m.state.value.list.refreshFailed)
        assertEquals(1, m.state.value.list.items.size)
        server.onList = null
        m.refresh(); runCurrent()
        assertFalse(m.state.value.list.refreshing); assertFalse(m.state.value.list.refreshFailed)
    }

    @Test fun stageDeniedIsFailureNotAnEmptyList() = flowTest {
        val server = ProposalServer(); server.onList = { ApiResult.Fail(AppError.OnboardingRequired("complete signup")) }
        val m = vm(server, TestSession(OnboardingStatus.KEYRING_REQUIRED)); runCurrent()
        assertEquals(ProposalListLoad.Failed, m.state.value.list.load)
        assertIs<AppError.OnboardingRequired>(m.state.value.error)
    }
    @Test fun pagingKeepsOpaqueCursorAndDeduplicatesRows() = flowTest {
        val server = ProposalServer(); val cursors = mutableListOf<String?>()
        server.onList = { cursor -> cursors += cursor; ApiResult.Ok(ProposalPage(listOf(server.record), if (cursor == null) "a+/=" else null, "now")) }
        val m = vm(server); runCurrent(); m.more(); runCurrent()
        assertEquals(listOf(null, "a+/="), cursors); assertEquals(1, m.state.value.list.items.size)
        assertEquals(ProposalPageState.End, m.state.value.list.page)
    }
    @Test fun filteredEmptyPageKeepsCursorAndRetryDoesNotDuplicateRequests() = flowTest {
        val server = ProposalServer()
        val cursors = mutableListOf<String?>()
        val gate = CompletableDeferred<ApiResult<ProposalPage>>()
        server.onProfile = { ApiResult.Fail(AppError.Server(410, "PROPOSAL_EXPIRED", "expired")) }
        server.onList = { cursor ->
            cursors += cursor
            if (cursor == null) ApiResult.Ok(ProposalPage(listOf(server.record), "next+/=", "now"))
            else gate.await()
        }
        val m = vm(server); runCurrent()
        assertTrue(m.state.value.list.items.isEmpty())
        assertEquals(ProposalPageState.More, m.state.value.list.page)
        // 더 보기 연타는 동일 페이지 요청 하나로 합쳐지고 실패 뒤에도 커서를 잃지 않는다.
        m.more(); m.more(); runCurrent()
        assertEquals(listOf(null, "next+/="), cursors)
        assertEquals(ProposalPageState.Loading, m.state.value.list.page)
        gate.complete(ApiResult.Fail(AppError.Offline)); runCurrent()
        assertEquals(ProposalPageState.Failed, m.state.value.list.page)
        server.onProfile = null
        server.onList = { cursor -> cursors += cursor; ApiResult.Ok(ProposalPage(listOf(server.record), null, "now")) }
        m.more(); runCurrent()
        assertEquals(listOf(null, "next+/=", "next+/="), cursors)
        assertEquals(listOf(10L), m.state.value.list.items.map { it.proposalId })
        assertEquals(ProposalPageState.End, m.state.value.list.page)
    }
    // 상대가 프로필을 바꿨으면 안내 화면 없이 최신 프로필로 다시 보여 주고, 수락은 새로 눌러야(새 버전 · 새 키) 간다
    @Test fun changedProfileReloadsSilentlyAndNeedsNewChoice() = flowTest {
        val server = ProposalServer()
        server.onWrite = { server.person = server.person.copy(profileVersion = "b".repeat(64)); ApiResult.Fail(AppError.Server(409, "PROFILE_CHANGED", "changed")) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalPageMode.Profile, m.state.value.page); assertNull(m.state.value.result)
        assertNull(m.state.value.pending); assertNull(m.state.value.review?.confirmation)
        assertTrue(m.state.value.review!!.canChoose)
        assertEquals(1, server.writes.size)
        server.onWrite = null
        m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals("b".repeat(64), server.writes.last().profileVersion)
        assertNotEquals(server.writes.first().key, server.writes.last().key)
        assertWaiting(m)
    }
    // 거절하면 결과 화면 없이 목록으로
    @Test fun declineReturnsToList() = flowTest {
        val server = ProposalServer(); val m = vm(server); profile(m)
        m.ask(ProposalChoice.Reject); m.submit(); runCurrent()
        assertEquals(ProposalPageMode.List, m.state.value.page); assertNull(m.state.value.result)
        assertEquals("DECLINE", server.writes.single().choice)
    }
    // 수락한 제안이 매칭 안 됨으로 끝나 목록에서 빠지면 "응답 대기"에 한 번 보이고, 열어 확인하면 사라진다
    @Test fun endedAcceptedProposalShowsOnceInList() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.record = server.record.copy(status = "REJECTED", myDecision = "ACCEPT")
        server.onList = { ApiResult.Ok(ProposalPage(emptyList(), null, "now")) }
        m.refresh(); runCurrent()
        val card = m.state.value.list.items.single()
        assertEquals(10L, card.proposalId); assertEquals(ProposalDecisionStage.NotMatched, card.ended)
        assertNull(card.nickname)   // 끝난 제안의 상대 이름은 남기지 않는다
        m.open(10); runCurrent()
        assertEquals(ProposalDecisionStage.NotMatched, m.state.value.result?.stage)
        m.refresh(); runCurrent()
        assertTrue(m.state.value.list.items.isEmpty())
    }
    @Test fun lostResponseRestoresExactRequestAcrossViewModelRecreation() = flowTest {
        val server = ProposalServer(); val prefs = MemoryPrefs(); server.onWrite = { ApiResult.Fail(AppError.Offline) }
        val m = vm(server, prefs = prefs); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalDecisionStage.Unknown, m.state.value.result?.stage)
        val first = server.writes.single()
        val restored = vm(server, prefs = prefs); runCurrent()
        assertEquals(1, server.writes.size) // 재시작만으로 자동 전송하지 않는다.
        restored.recover(); runCurrent(); assertEquals(first, server.writes.last())
    }
    @Test fun rotationDoesNotCancelOrDuplicateInFlightWrite() = flowTest {
        val server = ProposalServer(); val reply = CompletableDeferred<Unit>()
        // 실제 서버처럼 저장된 상태를 남긴다(숨겨졌다 돌아오면 프로필을 다시 받아 "수락했어요"를 보여 준다)
        server.onWrite = { reply.await(); server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT"); ApiResult.Ok(server.record) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        m.pause(); m.resume(); m.submit(); runCurrent()
        assertEquals(1, server.writes.size); assertTrue(m.state.value.busy)
        reply.complete(Unit); runCurrent(); assertFalse(m.state.value.busy)
        assertWaiting(m)
    }
    @Test fun oldAccountResponseCannotPopulateNewAccountScreen() = flowTest {
        val server = ProposalServer(); val session = TestSession(OnboardingStatus.READY)
        val gate = CompletableDeferred<Unit>()
        server.onProfile = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.person) }
        val m = vm(server, session); runCurrent(); session.state.value = Session.SignedOut; runCurrent()
        gate.complete(Unit); runCurrent(); assertNull(m.state.value.session); assertTrue(m.state.value.list.items.isEmpty())
    }
    @Test fun hiddenScreenDropsPhotoAndConfirmationBeforeResume() = flowTest {
        val m = vm(ProposalServer()); profile(m); m.ask(ProposalChoice.Accept)
        assertNotNull(m.state.value.review?.confirmation); m.pause()
        assertNull(m.state.value.review?.latest); m.submit(); runCurrent(); assertNull(m.state.value.pending)
    }
    // 목록의 남은 시간은 서버 시각 기준: 서버 시각 11-03 00:00, 마감 11-04 00:00 이면 기기 날짜와 상관없이 약 24시간
    @Test fun listRemainingUsesServerTime() = flowTest {
        val server = ProposalServer(); val m = vm(server); runCurrent()
        val left = assertNotNull(m.state.value.list.items.single().remaining())
        assertTrue(left > 23.hours + 59.minutes && left <= 24.hours, "남은 시간 $left")
    }
    // 사진을 기다리지 않고 목록 · 프로필을 먼저 보여 준다(그동안 "받는 중" = 회색 원 + 첫 글자). 사진이 오면 그 줄만 바뀐다
    @Test fun listAndProfileShowBeforePhoto() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        val gate = CompletableDeferred<Unit>(); server.photoGate = gate
        val m = vm(server); runCurrent()
        assertEquals(ProposalListLoad.Ready, m.state.value.list.load)
        assertEquals(ProposalPhotoState.Loading, m.state.value.list.items.single().photoState)
        assertFalse(m.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals(ProposalPhotoState.Removed, m.state.value.list.items.single().photoState)
        server.photoGate = CompletableDeferred()
        m.open(10); runCurrent()
        assertEquals(ProposalPhotoState.Loading, m.state.value.review?.current?.profile?.profile?.photoState)
        assertTrue(m.state.value.review!!.canChoose)
        server.photoGate!!.complete(Unit); runCurrent()
        assertEquals(ProposalPhotoState.Removed, m.state.value.review?.current?.profile?.profile?.photoState)
    }
    // 숨겼다 돌아오면(목록이 비워짐) 빈 목록 안내가 아니라 로딩부터
    @Test fun resumeAfterPauseShowsLoadingNotEmpty() = flowTest {
        val server = ProposalServer(); val m = vm(server); runCurrent()
        val gate = CompletableDeferred<ApiResult<ProposalPage>>(); server.onList = { gate.await() }
        m.pause(); m.resume(); runCurrent()
        assertEquals(ProposalListLoad.Loading, m.state.value.list.load)
        gate.complete(ApiResult.Ok(ProposalPage(listOf(server.record), null, "now"))); runCurrent()
        assertEquals(1, m.state.value.list.items.size)
    }
    // 기한 지남도 한 번 카드로, 서로 수락(방 있음)으로 끝난 기록은 카드 없이 지운다, 다음 쪽에선 붙이지 않는다
    @Test fun endedExpiredShowsCardAndMatchedIsDroppedSilently() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onList = { cursor -> ApiResult.Ok(ProposalPage(emptyList(), if (cursor == null) "next" else null, "now")) }
        server.record = server.record.copy(status = "EXPIRED", myDecision = "ACCEPT")
        m.refresh(); runCurrent()
        assertEquals(ProposalDecisionStage.Expired, m.state.value.list.items.single().ended)
        val readsBeforeMore = server.reads
        m.more(); runCurrent()
        assertEquals(1, m.state.value.list.items.size); assertEquals(readsBeforeMore, server.reads)   // 다음 쪽에선 끝난 제안을 다시 묻지 않는다
        server.record = server.record.copy(status = "ACCEPTED", chatAvailable = true, chatRoomId = 3)
        m.refresh(); runCurrent(); assertTrue(m.state.value.list.items.isEmpty())
        server.record = server.record.copy(status = "REJECTED")
        m.refresh(); runCurrent(); assertTrue(m.state.value.list.items.isEmpty())
    }
    // 목록 사진에서 "볼 수 없음"이 오면 그 줄을 뺀다
    @Test fun unavailableListPhotoRemovesRow() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        server.photoError = AppError.Server(410, "PROPOSAL_EXPIRED", "expired")
        val m = vm(server); runCurrent()
        assertTrue(m.state.value.list.items.isEmpty()); assertEquals(ProposalListLoad.Ready, m.state.value.list.load)
    }
    // 서로 수락인데 방을 쓸 수 없음(닫힘): 기다리지 않고 "볼 수 없음"으로 끝내고 프로필 · 수락 기록을 지운다
    @Test fun matchedWithClosedRoomEndsAsUnavailable() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.record = server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(ProposalDecisionStage.Unavailable, m.state.value.result?.stage); assertNull(m.state.value.review)
        val reads = server.reads
        advanceTimeBy(20_000); runCurrent(); assertEquals(reads, server.reads)   // 더 확인하지 않는다
        server.onList = { ApiResult.Ok(ProposalPage(emptyList(), null, "now")) }
        m.refresh(); runCurrent()
        assertTrue(m.state.value.list.items.isEmpty()); assertEquals(reads, server.reads)   // 기록이 지워져 다시 묻지 않는다
    }
    // 내가 두 번째로 수락해 바로 서로 수락 + 방 있음: 결과 화면 없이 채팅, 쓰기 표시 풀림, 기록 지움
    @Test fun acceptThatMatchesOpensChatImmediately() = flowTest {
        val server = ProposalServer()
        server.onWrite = { ApiResult.Ok(server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT", chatAvailable = true, chatRoomId = 5)) }
        // 예전에 이 기기에서 기다리던 기록이 있었다고 두고, 서로 수락으로 끝나면 지워지는지 본다
        val prefs = MemoryPrefs().also { it.values["false:proposal.accepted.v1"] = listOf(AcceptedProposal(10)) }
        val m = vm(server, prefs = prefs); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(emptyList<AcceptedProposal>(), prefs.values["false:proposal.accepted.v1"])
        assertEquals(5L, m.state.value.openChat); assertEquals(ProposalPageMode.List, m.state.value.page)
        assertFalse(m.state.value.writing); assertEquals(1, server.writes.size)
        m.chatOpened()
        server.onList = { ApiResult.Ok(ProposalPage(emptyList(), null, "now")) }
        val reads = server.reads
        m.refresh(); runCurrent()
        assertTrue(m.state.value.list.items.isEmpty()); assertEquals(reads, server.reads)
    }
    // 다른 기기에서 이미 수락했으면(충돌) 서버 상태를 다시 받아 "수락했어요"로
    @Test fun conflictFromOtherDeviceShowsServerState() = flowTest {
        val server = ProposalServer()
        server.onWrite = { server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")
            ApiResult.Fail(AppError.Server(409, "PROPOSAL_STATE_CONFLICT", "conflict")) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertWaiting(m); assertNull(m.state.value.pending)
    }
    // 회전 · 화면 이동은 pause 가 두 번 불린다: 그래도 기다리던 화면이면 돌아와서 "확인 중" 자동 확인으로 이어 간다
    @Test fun doublePauseKeepsWaitingResume() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onGet = { ApiResult.Fail(AppError.Offline) }
        m.pause(); m.pause(); m.resume(); runCurrent()
        assertEquals(ProposalDecisionStage.Checking, m.state.value.result?.stage)
    }
    // 숨기지 않고 ON_RESUME 만 온 경우(권한 창 등): 다시 받지 않아 열린 확인창이 그대로
    @Test fun resumeWithoutPauseKeepsConfirmation() = flowTest {
        val server = ProposalServer(); val m = vm(server); profile(m); m.ask(ProposalChoice.Accept)
        val reads = server.reads
        m.resume(); runCurrent()
        assertNotNull(m.state.value.review?.visibleConfirmation); assertEquals(reads, server.reads)
    }
    // 목록을 보는 중에 같은 계정의 세션 값만 바뀌어도(토큰 재발급 등) 로딩에 멈추지 않고 다시 받는다
    @Test fun sessionUpdateWhileVisibleReloads() = flowTest {
        val server = ProposalServer(); val session = TestSession(OnboardingStatus.READY)
        val m = vm(server, session); runCurrent()
        session.state.value = Session.SignedIn(1, OnboardingStatus.READY, true); runCurrent()
        assertEquals(ProposalListLoad.Ready, m.state.value.list.load); assertEquals(1, m.state.value.list.items.size)
    }
    // 확인창을 연 뒤 사진이 도착해도 수락이 그대로 간다
    @Test fun photoArrivingWhileConfirmingStillSubmits() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        val m = vm(server); runCurrent()
        server.photoGate = CompletableDeferred()
        m.open(10); runCurrent(); m.ask(ProposalChoice.Accept)
        server.photoGate!!.complete(Unit); runCurrent()
        assertEquals(ProposalPhotoState.Removed, m.state.value.review?.current?.profile?.profile?.photoState)
        m.submit(); runCurrent()
        assertEquals(1, server.writes.size); assertWaiting(m)
    }
    // 다시 불러온 프로필에 지난 요청의 사진 결과(볼 수 없음 포함)가 덮이지 않는다
    @Test fun stalePhotoDoesNotTouchReloadedProfile() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        val m = vm(server); runCurrent()
        val first = CompletableDeferred<Unit>(); server.photoGate = first
        m.open(10); runCurrent()
        server.photoGate = CompletableDeferred()
        m.reloadProfile(); runCurrent()
        server.photoError = AppError.Server(410, "PROPOSAL_EXPIRED", "expired")
        first.complete(Unit); runCurrent()
        assertEquals(ProposalReviewStage.Review, m.state.value.review?.stage)
        assertEquals(ProposalPhotoState.Loading, m.state.value.review?.current?.profile?.profile?.photoState)
        server.photoError = AppError.Server(410, "PHOTO_REMOVED", "removed")
        server.photoGate!!.complete(Unit); runCurrent()
        assertEquals(ProposalPhotoState.Removed, m.state.value.review?.current?.profile?.profile?.photoState)
    }
    // 끝난 제안 조회가 잠깐 실패하면 기록을 남겨 다음에 다시 묻고, 404 면 기록을 지워 다시 묻지 않는다
    @Test fun endedLookupKeepsOnTransientFailureAndDropsOnNotFound() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onList = { ApiResult.Ok(ProposalPage(emptyList(), null, "now")) }
        server.onGet = { ApiResult.Fail(AppError.Offline) }
        m.refresh(); runCurrent(); assertTrue(m.state.value.list.items.isEmpty())
        val kept = server.reads
        server.onGet = { ApiResult.Fail(AppError.Server(404, "RESOURCE_NOT_FOUND", "gone")) }
        m.refresh(); runCurrent(); assertTrue(m.state.value.list.items.isEmpty())
        assertEquals(kept + 1, server.reads)   // 일시 실패 뒤에도 기록이 남아 다시 물었다
        val reads = server.reads
        m.refresh(); runCurrent(); assertEquals(reads, server.reads)
    }
    // 목록은 끝난 제안 조회를 기다리지 않고 먼저 보이고, 카드는 나중에 맨 앞에 붙는다
    @Test fun listShowsBeforeEndedLookup() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        val gate = CompletableDeferred<ApiResult<ProposalRecord>>()
        server.onList = { ApiResult.Ok(ProposalPage(emptyList(), null, "now")) }
        server.onGet = { gate.await() }
        m.refresh(); runCurrent()
        assertEquals(ProposalListLoad.Ready, m.state.value.list.load); assertFalse(m.state.value.busy)
        gate.complete(ApiResult.Ok(server.record.copy(status = "EXPIRED", myDecision = "ACCEPT"))); runCurrent()
        assertEquals(ProposalDecisionStage.Expired, m.state.value.list.items.single().ended)
    }
    // 직접 연 프로필을 받는 중에 뒤로(목록) 가면 그 조회를 취소하고 바로 목록으로, 늦게 온 응답은 무시
    @Test fun backDuringManualProfileLoadCancelsRead() = flowTest {
        val server = ProposalServer(); val m = vm(server); runCurrent()
        val gate = CompletableDeferred<Unit>()
        server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.record) }
        m.open(10); runCurrent(); assertTrue(m.state.value.busy)
        server.onGet = null
        m.refresh(); runCurrent()
        assertEquals(ProposalPageMode.List, m.state.value.page); assertEquals(1, m.state.value.list.items.size)
        gate.complete(Unit); runCurrent()
        assertEquals(ProposalPageMode.List, m.state.value.page); assertNull(m.state.value.review)
    }
    // 수락을 보내는 중엔 새로고침 · 다시 불러오기가 쓰기를 취소하지 않고, 끝나면 writing 이 풀린다
    @Test fun readsDoNotCancelWriteAndWritingFlagClears() = flowTest {
        val server = ProposalServer(); val reply = CompletableDeferred<Unit>()
        server.onWrite = { reply.await(); server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT"); ApiResult.Ok(server.record) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertTrue(m.state.value.writing)
        m.refresh(); m.reloadProfile(); runCurrent()
        reply.complete(Unit); runCurrent()
        assertEquals(1, server.writes.size); assertWaiting(m); assertFalse(m.state.value.writing)
        // 쓰기가 실패로 끝나도 writing 은 풀린다
        server.onWrite = { ApiResult.Fail(AppError.Offline) }
        m.refresh(); runCurrent(); server.record = server.record.copy(status = "PENDING", myDecision = "NONE")
        profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalDecisionStage.Unknown, m.state.value.result?.stage); assertFalse(m.state.value.writing)
    }
    // 당겨서 새로고침 중에 카드를 눌러도 열린다(새로고침을 취소)
    @Test fun openDuringRefreshCancelsRefresh() = flowTest {
        val server = ProposalServer(); val m = vm(server); runCurrent()
        val gate = CompletableDeferred<ApiResult<ProposalPage>>(); server.onList = { gate.await() }
        m.refresh(); runCurrent(); assertTrue(m.state.value.list.refreshing)
        m.open(10); runCurrent()
        assertEquals(ProposalPageMode.Profile, m.state.value.page); assertNotNull(m.state.value.review?.current)
    }
    // 숨겨진 동안 쓰기가 끝나면 프로필 · 사진을 받지 않고, 돌아오면 그때 받는다
    @Test fun writeFinishingWhileHiddenFetchesOnlyAfterResume() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        var profiles = 0; server.onProfile = { profiles++; ApiResult.Ok(server.person) }
        val reply = CompletableDeferred<Unit>()
        server.onWrite = { reply.await(); server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT"); ApiResult.Ok(server.record) }
        val m = vm(server); runCurrent(); m.open(10); runCurrent(); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        val before = profiles
        m.pause(); reply.complete(Unit); runCurrent()
        assertEquals(before, profiles); assertNull(m.state.value.review?.latest)
        m.resume(); runCurrent()
        assertEquals(before + 1, profiles); assertWaiting(m)
    }
    // 기다리던 화면으로 돌아왔는데 확인이 잠깐 실패해도 "확인 중"에서 계속 확인하고, 기다림이면 프로필로
    @Test fun resumeWhileWaitingSurvivesTransientFailure() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onGet = { ApiResult.Fail(AppError.Offline) }
        m.pause(); m.resume(); runCurrent()
        assertEquals(ProposalDecisionStage.Checking, m.state.value.result?.stage); assertNull(m.state.value.error)
        server.onGet = null; server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")
        advanceTimeBy(5_000); runCurrent()
        assertWaiting(m)
    }
    // 수락 기록: 잠깐 못 읽으면 아무것도 쓰지 않고, 깨진 값이면 새로 쓴다. 이미 있으면 다시 쓰지 않는다
    @Test fun acceptedStoreNeverOverwritesOnReadFailure() = runTest {
        val prefs = MemoryPrefs(); val store = AcceptedProposalStore(prefs)
        prefs.values["false:proposal.accepted.v1"] = listOf(AcceptedProposal(5))
        prefs.failure = PrefRead.Unreadable
        store.add(1, 10); store.remove(1, setOf(5))
        prefs.failure = null
        assertEquals(listOf(AcceptedProposal(5)), prefs.values["false:proposal.accepted.v1"])
        prefs.failure = PrefRead.Corrupt
        store.add(1, 10); prefs.failure = null
        assertEquals(listOf(AcceptedProposal(10)), prefs.values["false:proposal.accepted.v1"])
        val writes = prefs.sets
        store.add(1, 10); assertEquals(writes, prefs.sets)   // 이미 있으면 다시 쓰지 않는다(대기 중 5초 확인마다 쓰지 않게)
        assertEquals(listOf(AcceptedProposal(10)), store.read(1))
    }
    @Test fun deletedPhotoIsNotAnExpiredProposal() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        val m = vm(server); profile(m)
        assertEquals(ProposalPhotoState.Removed, m.state.value.review?.current?.profile?.profile?.photoState)
        assertTrue(m.state.value.review!!.canChoose)
    }
    @Test fun expiredPhotoRevokesWholeProfile() = flowTest {
        val server = ProposalServer(); server.person = server.person.copy(hasProfilePhoto = true)
        server.photoError = AppError.Server(410, "PROPOSAL_EXPIRED", "expired")
        val m = vm(server); profile(m)
        assertEquals(ProposalReviewStage.Unavailable, m.state.value.review?.stage); assertNull(m.state.value.review?.latest)
    }
    @Test fun acceptedButRejectedPairShowsNotMatchedOnlyAfterMyChoice() = flowTest {
        val server = ProposalServer(); server.onWrite = { ApiResult.Ok(server.record.copy(status = "REJECTED", myDecision = "ACCEPT")) }
        val m = vm(server); profile(m); assertTrue(m.state.value.review!!.canChoose)
        m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalDecisionStage.NotMatched, m.state.value.result?.stage)
        assertNull(m.state.value.review); assertNull(m.state.value.openChat)
    }
}
