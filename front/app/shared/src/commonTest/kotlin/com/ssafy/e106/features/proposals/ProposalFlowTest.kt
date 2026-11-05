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

// 캐시/영속 키를 분리해 실제 저장소의 읽기 실패 및 이관 실패를 재현한다.
private class MemoryPrefs : AppPrefs {
    val values = mutableMapOf<String, Any?>()
    var failure: PrefRead<Nothing>? = null
    var dropWrites = false
    var keepLegacy = false
    var failAfterWrite = false
    var writeGate: CompletableDeferred<Unit>? = null
    var writeStarted = false
    private fun id(key: PrefKey<*>) = "${key.durable}:${key.name}"
    @Suppress("UNCHECKED_CAST") override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(if(failure != null) null else values[id(key)] as T?)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
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
    override suspend fun list(cursor: String?) = onList?.invoke(cursor) ?: ApiResult.Ok(ProposalPage(listOf(record), null, "2026-11-03T00:00:00Z"))
    override suspend fun get(id: Long): ApiResult<ProposalRecord> { reads++; return onGet?.invoke() ?: ApiResult.Ok(record) }
    override suspend fun profile(id: Long) = onProfile?.invoke() ?: ApiResult.Ok(person)
    override suspend fun photo(id: Long): ApiResult<ByteArray> = ApiResult.Fail(photoError)
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

    // 실제 수락 경로를 거친 뒤 서버 상태만 바꿔 자동 갱신의 결과/수명을 검증한다.
    private fun TestScope.waiting(m: ProposalViewModel) {
        profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalDecisionStage.Waiting, m.state.value.result?.stage)
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
        assertEquals(ProposalDecisionStage.Waiting, m.state.value.result?.stage)
        m.checkResult(); advanceTimeBy(20_000); runCurrent(); assertEquals(before + 1, server.reads)
        gate.complete(ApiResult.Ok(server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT")))
        runCurrent(); assertEquals(ProposalDecisionStage.MatchedPreparing, m.state.value.result?.stage)
        server.onGet = null
        server.record = server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT", chatAvailable = true, chatRoomId = 3)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(ProposalDecisionStage.MatchedReady, m.state.value.result?.stage)
        assertEquals(3L, m.state.value.chatRoomId)
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

    @Test fun automaticFailureStopsAndManualRetryRestartsWaitingPolling() = flowTest {
        val server = ProposalServer(); val m = vm(server); waiting(m)
        server.onGet = { ApiResult.Fail(AppError.Offline) }
        advanceTimeBy(5_000); runCurrent()
        assertEquals(ProposalDecisionStage.Unknown, m.state.value.result?.stage)
        val afterFailure = server.reads
        advanceTimeBy(30_000); runCurrent(); assertEquals(afterFailure, server.reads)
        server.onGet = null; server.record = server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")
        m.checkResult(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(afterFailure + 2, server.reads)
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
    @Test fun changedProfileNeedsNewExplicitChoiceAndNewKey() = flowTest {
        val server = ProposalServer(); server.onWrite = { ApiResult.Fail(AppError.Server(409, "PROFILE_CHANGED", "changed")) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals(ProposalDecisionStage.ProfileChanged, m.state.value.result?.stage)
        assertNull(m.state.value.pending)
        server.person = server.person.copy(profileVersion = "b".repeat(64)); m.reloadProfile(); runCurrent()
        assertEquals(1, server.writes.size); assertNull(m.state.value.review?.confirmation)
        m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        assertEquals("b".repeat(64), server.writes.last().profileVersion)
        assertNotEquals(server.writes.first().key, server.writes.last().key)
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
        server.onWrite = { reply.await(); ApiResult.Ok(server.record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")) }
        val m = vm(server); profile(m); m.ask(ProposalChoice.Accept); m.submit(); runCurrent()
        m.pause(); m.resume(); m.submit(); runCurrent()
        assertEquals(1, server.writes.size); assertTrue(m.state.value.busy)
        reply.complete(Unit); runCurrent(); assertFalse(m.state.value.busy)
        assertEquals(ProposalDecisionStage.Waiting, m.state.value.result?.stage)
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
        assertNull(m.state.value.review); assertNull(m.state.value.chatRoomId)
    }
}
