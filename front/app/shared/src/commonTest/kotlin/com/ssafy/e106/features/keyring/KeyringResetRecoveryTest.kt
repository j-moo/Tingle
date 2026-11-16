package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal const val resetId = "11111111-1111-4111-8111-111111111111"
internal const val resetGeneration = "22222222-2222-4222-8222-222222222222"
internal const val resetSerial = "XIAO-test"
internal val resetChallenge = ResetDeviceChallenge(KeyringResetProtocol.VERSION, "key-1", resetSerial, 9,
    resetGeneration, resetId, "A".repeat(43), 60, "A".repeat(86))
internal val resetRun = KeyringResetRun(resetId, 9007199254740993L, resetGeneration, "PENDING_DEVICE", false,
    "2026-11-05T12:00:00Z", "2026-11-05T12:01:00Z", deviceCommand = "{}\n",
    fromOwnershipEpoch = 9, toOwnershipEpoch = 10, resetNonce = "A".repeat(43))
internal val resetProof = ResetAckBody("A".repeat(86))
internal fun resetDone() = resetRun.copy(state = "COMPLETED", deviceCommand = null, completedAt = "2026-11-05T12:00:10Z")

// 이 저장소/서버/기기는 테스트에만 있다. 제품 실행은 실제 API와 BLE를 사용한다.
internal class ResetPrefs : AppPrefs {
    val values = mutableMapOf<Pair<Long, String>, String>()
    var fault: PrefRead<Nothing>? = null
    var refuseWrites = false
    override fun <T> flow(key: PrefKey<T>) = flowOf<T?>(null)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        assertTrue(key.durable)
        if (!refuseWrites && value != null) values[forUser to key.name] = ApiJson.encodeToString(key.serializer, value)
    }
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        fault?.let { return it }
        return values[forUser to key.name]?.let { PrefRead.Found(ApiJson.decodeFromString(key.serializer, it)) } ?: PrefRead.Missing
    }
}
internal class ResetServer : KeyringResetRepository, OwnedKeyringRepository {
    var run = resetRun
    val starts = mutableListOf<Pair<String, ResetStartBody>>()
    val acknowledgements = mutableListOf<ResetAckBody>()
    var commands = 0; var reads = 0; var ownershipReads = 0
    // 실행 기록 생성 전 소유 조회 실패와 같은 연결에서의 재시도를 재현한다.
    var ownershipFailure: AppError? = null
    var startAction: suspend () -> ApiResult<KeyringResetRun> = { ApiResult.Ok(run) }
    // 요청의 삭제 정책을 보존한 완료를 반환한다. false로 고정하면 별도 동의 계약 검사가 무의미해진다.
    var ackAction: suspend () -> ApiResult<KeyringResetRun> = {
        run = run.copy(state = "COMPLETED", deviceCommand = null, completedAt = "2026-11-05T12:00:10Z"); ApiResult.Ok(run)
    }
    override suspend fun start(deviceId: Long, key: String, body: ResetStartBody): ApiResult<KeyringResetRun> {
        starts += key to body; return startAction()
    }
    override suspend fun get(deviceId: Long, runId: String): ApiResult<KeyringResetRun> { reads++; return ApiResult.Ok(run) }
    override suspend fun command(deviceId: Long, runId: String, body: ResetCommandBody): ApiResult<KeyringResetRun> {
        commands++; assertEquals(resetId, runId); return ApiResult.Ok(run)
    }
    override suspend fun ack(deviceId: Long, runId: String, body: ResetAckBody): ApiResult<KeyringResetRun> {
        acknowledgements += body; return ackAction()
    }
    // 실제 서버처럼 초기화가 끝나면 내 키링 목록에서 빠진다. 같은 키링을 다시 등록한 경우만 다시 보인다
    var registeredAgain = false
    override suspend fun list(): ApiResult<List<OwnedKeyring>> {
        ownershipReads++; ownershipFailure?.let { return ApiResult.Fail(it) }
        return ApiResult.Ok(if (run.state == "COMPLETED" && !registeredAgain) emptyList()
            else listOf(OwnedKeyring(run.deviceId, "REGISTERED", "test")))
    }
}
internal class ResetBoard : ResetDevicePort {
    var challenges = 0; var queries = 0; var sends = 0
    var resultAction: suspend () -> ResetAckBody? = { resetProof }
    var sendAction: suspend () -> ResetAckBody? = { resetProof }
    override suspend fun challenge(serial: String): ResetDeviceChallenge { challenges++; return resetChallenge }
    override suspend fun result(ticket: ResetTicket): ResetAckBody? { queries++; return resultAction() }
    override suspend fun send(ticket: ResetTicket): ResetAckBody? { sends++; return sendAction() }
}
internal class ResetFixture {
    val prefs = ResetPrefs(); val server = ResetServer(); val board = ResetBoard()
    val session = TestSession(OnboardingStatus.READY)
    val store = KeyringResetStore(prefs); val sync = KeyringSyncStore(prefs)
    fun model() = KeyringResetRecovery(server, KeyringResetStore(prefs), server, session, sync)
    suspend fun start() = model().start(1, resetRun.deviceId, resetSerial, true, 0, false, board)
    suspend fun seed(run: KeyringResetRun? = resetRun, ack: ResetAckBody? = null) {
        store.insert(1, ResetTicket(resetRun.deviceId, resetSerial, resetId, ResetStartBody(false, resetChallenge), run, ack))
    }
}

/** 파괴 작업의 복구 순서와 내구 저장을 검사한다. 성공 화면만 검사하는 테스트가 아니다. */
class KeyringResetRecoveryTest {
    @Test fun unknownRecordsRequireBothConsentsAndInvalidCountNeverStarts() = runTest {
        val f = ResetFixture()
        for ((accepted, count, discard) in listOf(Triple(true, null, false), Triple(false, null, true), Triple(true, -1L, true))) {
            assertFailsWith<IllegalArgumentException> {
                f.model().start(1, resetRun.deviceId, resetSerial, accepted, count, discard, f.board)
            }
        }
        assertEquals(0, f.server.ownershipReads); assertEquals(0, f.board.challenges); assertTrue(f.server.starts.isEmpty())
    }
    @Test fun unknownRecordsPersistExplicitDiscardAndReplayItAfterLostStart() = runTest {
        val f = ResetFixture(); f.server.run = resetRun.copy(discardUnsentConfirmed = true)
        f.server.startAction = { ApiResult.Fail(AppError.Offline) }
        assertFailsWith<ResetApiException> {
            f.model().start(1, resetRun.deviceId, resetSerial, true, null, true, f.board)
        }
        val first = f.server.starts.single()
        assertTrue(first.second.discardUnsentConfirmed)
        assertEquals(first.second, f.store.read(1).single().start)
        f.server.startAction = { ApiResult.Ok(f.server.run) }
        assertEquals(ResetRecoveryPhase.Completed, f.model().resume(1, resetRun.deviceId, f.board).phase)
        assertEquals(listOf(first, first), f.server.starts)
        assertTrue(f.store.read(1).single().run!!.discardUnsentConfirmed)
    }
    @Test fun corruptJournalOrUnpersistedRequestStopsBeforePost() = runTest {
        for (fault in listOf(PrefRead.Corrupt, PrefRead.Unreadable, null)) {
            val f = ResetFixture(); f.prefs.fault = fault; f.prefs.refuseWrites = fault == null
            assertFailsWith<IllegalStateException> { f.start() }
            assertTrue(f.server.starts.isEmpty()); assertEquals(0, f.board.sends)
        }
    }
    @Test fun lostStartReplaysExactKeyAndBodyAfterRecreation() = runTest {
        val f = ResetFixture(); f.server.startAction = { ApiResult.Fail(AppError.Offline) }
        assertFailsWith<ResetApiException> { f.start() }
        assertNull(f.store.read(1).single().run)
        val first = f.server.starts.single()
        f.server.startAction = { ApiResult.Ok(resetRun) }
        f.board.resultAction = { throw BleCommandException("RESET_NOT_FOUND") }
        val restored = f.model().resume(1, resetRun.deviceId, f.board)
        assertEquals(ResetRecoveryPhase.Completed, restored.phase)
        assertEquals(listOf(first, first), f.server.starts)
        assertEquals(1, f.server.commands); assertEquals(1, f.board.queries); assertEquals(1, f.board.sends)
    }
    @Test fun lostBleResponseQueriesProofWithoutSendingAgain() = runTest {
        val f = ResetFixture(); f.board.sendAction = { throw IllegalStateException("timeout") }
        assertFailsWith<IllegalStateException> { f.start() }
        assertTrue(f.store.read(1).single().commandAttempted)
        assertEquals(ResetRecoveryPhase.Completed, f.model().resume(1, resetRun.deviceId, f.board).phase)
        assertEquals(1, f.board.sends); assertEquals(1, f.board.queries); assertEquals(0, f.server.commands)
    }
    @Test fun pendingOrTimedOutQueryNeverIssuesNewCommand() = runTest {
        val f = ResetFixture(); f.seed()
        f.board.resultAction = { null }
        assertEquals(ResetRecoveryPhase.DevicePending, f.model().resume(1, resetRun.deviceId, f.board).phase)
        f.board.resultAction = { throw IllegalStateException("timeout") }
        assertFailsWith<IllegalStateException> { f.model().resume(1, resetRun.deviceId, f.board) }
        assertEquals(0, f.server.commands); assertEquals(0, f.board.sends); assertTrue(f.server.acknowledgements.isEmpty())
    }
    @Test fun lostAckPersistsProofAndRetriesSameBodyWithoutBoard() = runTest {
        val f = ResetFixture(); f.server.ackAction = { ApiResult.Fail(AppError.Offline) }
        assertFailsWith<ResetApiException> { f.start() }
        assertEquals(resetProof, f.store.read(1).single().ack)
        f.server.ackAction = { ApiResult.Ok(resetDone()) }
        assertEquals(ResetRecoveryPhase.Completed, f.model().resume(1, resetRun.deviceId).phase)
        assertEquals(listOf(resetProof, resetProof), f.server.acknowledgements); assertEquals(0, f.board.queries)
    }
    @Test fun serverCompletedRecoversWithoutCurrentOwnershipOrProofReplay() = runTest {
        val f = ResetFixture(); f.seed(); f.server.run = resetDone()
        f.sync.rememberTarget(1, resetRun.deviceId, resetSerial); f.sync.rememberTarget(1, 8, "other")
        assertEquals(ResetRecoveryPhase.Completed, f.model().resume(1, resetRun.deviceId).phase)
        assertEquals(listOf(8L), f.sync.read(1).map { it.deviceId })
        assertEquals(0, f.server.ownershipReads); assertTrue(f.server.acknowledgements.isEmpty())
    }
    @Test fun identityChangesOrDowngradesDoNotOverwriteJournal() = runTest {
        val f = ResetFixture(); f.seed()
        f.server.run = resetRun.copy(toOwnershipEpoch = 12)
        assertFailsWith<IllegalStateException> { f.model().inspect(1, resetRun.deviceId) }
        assertEquals(resetRun, f.store.read(1).single().run)
        val ticket = f.store.read(1).single()
        assertFailsWith<IllegalStateException> { f.store.save(1, ticket.copy(requestKey = resetGeneration)) }
        f.store.save(1, ticket.copy(run = resetDone()))
        assertFailsWith<IllegalStateException> { f.store.save(1, ticket) }
    }
    @Test fun oldCompletedLookupDoesNotRemoveNewRegistrationTarget() = runTest {
        val f = ResetFixture(); f.seed(run = resetDone()); f.server.run = resetDone()
        f.sync.rememberTarget(1, resetRun.deviceId, resetSerial)
        assertEquals(ResetRecoveryPhase.Completed, f.model().resume(1, resetRun.deviceId).phase)
        assertEquals(resetRun.deviceId, f.sync.read(1).single().deviceId)
    }
    @Test fun completedResetIsForgottenOnceSameKeyringIsRegisteredAgain() = runTest {
        val f = ResetFixture(); f.seed(run = resetDone()); f.server.run = resetDone(); f.server.registeredAgain = true
        // 다시 등록돼 내 키링 목록에 있으면 지난 완료 기록은 지우고 새 초기화를 시작할 수 있다
        assertNull(f.model().inspect(1, resetRun.deviceId))
        assertTrue(f.store.read(1).isEmpty())
        f.server.run = resetRun; f.start()
        assertEquals(1, f.server.starts.size)
    }
    @Test fun completedResetIsShownWhenOwnedListFails() = runTest {
        val f = ResetFixture(); f.seed(run = resetDone()); f.server.run = resetDone()
        f.server.registeredAgain = true; f.server.ownershipFailure = AppError.Offline
        // 목록을 못 읽으면 지우지 않고 완료를 그대로 보인다(오류 · 부분 완료로 바꾸지 않음)
        assertEquals("COMPLETED", f.model().inspect(1, resetRun.deviceId)?.run?.state)
        assertEquals(1, f.store.read(1).size)
    }
    @Test fun completedResetStaysWhileKeyringIsNotRegistered() = runTest {
        val f = ResetFixture(); f.seed(run = resetDone()); f.server.run = resetDone()
        assertEquals("COMPLETED", f.model().inspect(1, resetRun.deviceId)?.run?.state)
        assertEquals(1, f.store.read(1).size)
    }
    @Test fun accountChangeDuringHttpStopsBeforeBleAndDoesNotSaveLateResponse() = runTest {
        val f = ResetFixture()
        f.server.startAction = { f.session.state.value = Session.SignedIn(2, OnboardingStatus.READY, false); ApiResult.Ok(resetRun) }
        assertFailsWith<CancellationException> { f.start() }
        assertEquals(0, f.board.sends); assertNull(f.store.read(1).single().run); assertTrue(f.store.read(2).isEmpty())
    }
    @Test fun cancellationPreservesPendingStartForExplicitRecovery() = runTest {
        val f = ResetFixture(); val reached = CompletableDeferred<Unit>()
        f.server.startAction = { reached.complete(Unit); awaitCancellation() }
        val job = launch { f.start() }; reached.await(); job.cancelAndJoin()
        assertEquals(1, f.store.read(1).size); assertNull(f.store.read(1).single().run); assertEquals(0, f.board.sends)
    }
    @Test fun noBoardMeansWaitingAndReadOnlyInspectDoesNotPost() = runTest {
        val f = ResetFixture(); f.seed(run = null)
        assertNotNull(f.model().inspect(1, resetRun.deviceId)); assertTrue(f.server.starts.isEmpty())
        assertEquals(ResetRecoveryPhase.WaitingDevice, f.model().resume(1, resetRun.deviceId).phase)
        assertEquals(0, f.board.sends)
    }
}
