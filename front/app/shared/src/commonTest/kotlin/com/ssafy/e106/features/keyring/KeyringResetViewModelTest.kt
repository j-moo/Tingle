package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 회전/이탈은 작업만 취소하고 증명은 보존한다. 화면에서 불명 상태를 성공으로 표시하지 않는지 검사한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyringResetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() { models.clear(); Dispatchers.resetMain() }
    private fun model(f: ResetFixture) = KeyringResetViewModel(f.model(), f.server, f.session, f.store)
        .also { models.put("reset", it) }

    @Test fun unknownInventoryNeedsSeparateDeletionConsentAndCancelClearsBoth() = runTest(dispatcher) {
        val f = ResetFixture(); val m = model(f)
        m.load(resetRun.deviceId, true, resetSerial); runCurrent(); m.accepted(true)
        assertEquals(KeyringResetStage.Confirm, m.state.value.displayStage)
        assertNull(m.state.value.pendingRecordCount); assertFalse(m.state.value.canConfirm)
        m.start(f.board); runCurrent()
        m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
        assertTrue(m.state.value.canConfirm)
        m.cancel()
        assertFalse(m.state.value.canConfirm); assertFalse(m.state.value.recordDeletionAccepted); assertFalse(m.state.value.accepted)
        assertTrue(f.server.starts.isEmpty())
    }
    @Test fun explicitConsentStartsOnceAndOnlyServerCompletionShowsComplete() = runTest(dispatcher) {
        val f = ResetFixture(); f.server.run = resetRun.copy(discardUnsentConfirmed = true)
        f.server.ackAction = { ApiResult.Fail(AppError.Offline) }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
        m.start(f.board); runCurrent(); assertTrue(f.server.starts.isEmpty())
        m.accepted(true); m.start(f.board); m.start(f.board); runCurrent()
        assertEquals(1, f.server.starts.size); assertTrue(f.server.starts.single().second.discardUnsentConfirmed)
        assertEquals(KeyringResetStage.Partial, m.state.value.displayStage)
        assertFalse(m.state.value.ownershipReleased)
        f.server.ackAction = { ApiResult.Ok(f.server.run.copy(state = "COMPLETED", deviceCommand = null,
            completedAt = "2026-11-05T12:00:10Z")) }
        m.recover(null); runCurrent()
        assertEquals(KeyringResetStage.Complete, m.state.value.displayStage)
    }
    /** 첫 조회/시작 직전 재조회가 실패해도 같은 BLE 연결로 재시도해 실제 시작까지 도달해야 한다. */
    @Test fun ownershipReadFailureRetryKeepsConnectedSerial() = runTest(dispatcher) {
        for (failBeforeConfirm in listOf(true, false)) {
            val f = ResetFixture(); f.server.run = resetRun.copy(discardUnsentConfirmed = true)
            val m = model(f)
            if (failBeforeConfirm) f.server.ownershipFailure = AppError.Offline
            m.load(resetRun.deviceId, true, resetSerial); runCurrent()
            if (!failBeforeConfirm) {
                m.accepted(true); m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
                f.server.ownershipFailure = AppError.Offline
                m.start(f.board); runCurrent()
            }
            assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage)
            assertTrue(f.server.starts.isEmpty()); assertTrue(f.store.read(1).isEmpty())
            f.server.ownershipFailure = null
            m.recover(f.board); runCurrent(); m.accepted(true)
            m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
            assertTrue(m.state.value.canConfirm)
            m.start(f.board); runCurrent()
            assertEquals(1, f.server.starts.size)
            assertEquals(KeyringResetStage.Complete, m.state.value.displayStage)
            models.clear()
        }
    }
    @Test fun disconnectOrDifferentBoardInvalidatesConsentAndRecreationDoesNotStart() = runTest(dispatcher) {
        val f = ResetFixture(); val m = model(f)
        m.load(resetRun.deviceId, true, resetSerial); runCurrent(); m.accepted(true)
        m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
        m.connectionChanged(false, null); m.start(f.board); runCurrent()
        assertFalse(m.state.value.canConfirm); assertEquals(KeyringResetStage.Disconnected, m.state.value.displayStage)
        m.connectionChanged(true, "another-board"); assertFalse(m.state.value.accepted); assertFalse(m.state.value.recordDeletionAccepted)
        m.pause(); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        assertFalse(m.state.value.canConfirm); assertTrue(f.server.starts.isEmpty()); assertEquals(0, f.board.challenges)
    }
    @Test fun storedProofIsPartialAfterHttpFailureAndSurvivesModelRecreation() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); f.server.ackAction = { ApiResult.Fail(AppError.Offline) }
        val m = model(f); m.load(resetRun.deviceId, true); runCurrent()
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Partial, m.state.value.displayStage)
        assertTrue(m.state.value.deviceResetConfirmed); assertFalse(m.state.value.ownershipReleased)
        models.clear()
        val restored = model(f); restored.load(resetRun.deviceId, false); runCurrent()
        assertEquals(KeyringResetStage.Partial, restored.state.value.displayStage)
        f.server.ackAction = { ApiResult.Ok(resetDone()) }
        restored.recover(null); runCurrent()
        assertEquals(KeyringResetStage.Complete, restored.state.value.displayStage)
        assertEquals(listOf(resetProof, resetProof), f.server.acknowledgements)
    }
    /** 키링이 지우면서 블루투스가 끊기면 실패가 아니라 결과 대기다. 같은 키링에 다시 붙으면 증명부터 읽어 완료한다. */
    @Test fun disconnectDuringResetWaitsForTheSameBoardAndReadsTheProofFirst() = runTest(dispatcher) {
        val f = ResetFixture(); f.server.run = resetRun.copy(discardUnsentConfirmed = true)
        f.server.ackAction = { ApiResult.Ok(resetDone().copy(discardUnsentConfirmed = true)) }
        f.board.sendAction = { throw BleException(BleFailure.Disconnected) }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        m.accepted(true); m.recordChoice(ResetRecordChoice.Delete); m.recordDeletionAccepted(true)
        m.start(f.board); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage)
        assertEquals(resetSerial, m.state.value.resumeSerial); assertNull(m.state.value.errorMessage)
        assertFalse(m.state.value.busy)   // 기다리는 동안 뒤로 갈 수 있다
        // 다시 연결: 명령을 다시 보내지 않고 결과(증명)부터 읽어 서버 확인까지 끝낸다
        val sends = f.board.sends
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Complete, m.state.value.displayStage)
        assertEquals(sends, f.board.sends); assertEquals(1, f.board.queries); assertEquals(listOf(resetProof), f.server.acknowledgements)
    }
    /** 키링이 아직 지우는 중(PENDING)이면 결과 대기를 유지하고, 다시 조회할 때마다 대기 표시를 새로 한다. */
    @Test fun devicePendingKeepsWaitingAndAppRestartResumesTheSameRun() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); f.server.ackAction = { ApiResult.Ok(resetDone()) }
        f.board.resultAction = { null }
        val m = model(f); m.load(resetRun.deviceId, false); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage)   // 앱을 다시 열어도 같은 실행을 기다린다
        val tick = m.state.value.waitTick
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage); assertTrue(m.state.value.waitTick > tick)
        f.board.resultAction = { resetProof }
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Complete, m.state.value.displayStage)
    }
    /** 블루투스 끊김이 아닌 실제 실패(키링이 명령을 거절 · 서버 오류)는 실패로 보이고 "다시 시도"로 같은 키링을 다시 찾는다. */
    @Test fun realFailureShowsFailureAndRetryWaitsForTheSameBoard() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed()
        f.board.resultAction = { throw IllegalStateException("proof mismatch") }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage); assertNull(m.state.value.resumeSerial)
        m.waitForDevice()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage); assertEquals(resetSerial, m.state.value.resumeSerial)
    }
    /** 기다려도 낫지 않는 실패는 결과 대기로 가리지 않는다: 키링 거절 · 재연결로 낫지 않는 블루투스 오류 · 실행 전 끊김. */
    @Test fun nonTransientFailuresAreNotHiddenAsWaiting() = runTest(dispatcher) {
        for (error in listOf<Exception>(BleCommandException("RESET_REJECTED"), BleException(BleFailure.InvalidFrame))) {
            val f = ResetFixture(); f.seed(); f.board.resultAction = { throw error }
            val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
            m.recover(f.board); runCurrent()
            assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage, error.toString())
            models.clear()
        }
        // 실행이 생기기 전(POST 응답 유실)에 끊기면 기다릴 실행이 없다
        val f = ResetFixture(); f.seed(run = null)
        f.server.startAction = { throw BleException(BleFailure.Disconnected) }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        assertNotEquals(KeyringResetStage.Waiting, m.state.value.displayStage)
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage)
    }
    /** 정해진 시간 · 블루투스 꺼짐으로 대기를 멈추면 원인과 "다시 시도"가 보이고, 다시 시도하면 같은 실행을 다시 기다린다. */
    @Test fun stoppingTheWaitShowsTheCauseAndRetryWaitsAgain() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); f.board.resultAction = { null }
        val m = model(f); m.load(resetRun.deviceId, false); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage)
        m.stopWaiting("블루투스를 켜 주세요")
        assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage); assertEquals("블루투스를 켜 주세요", m.state.value.errorMessage)
        // 화면의 "다시 시도": 메모리 기록은 버렸으므로 저장소에서 다시 판정해 같은 실행을 다시 기다린다
        m.waitForDevice(); m.recover(null); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage); assertNull(m.state.value.errorMessage)
    }
    /** 결과 조회 중에 대기를 멈추면 늦게 온 키링 응답이 실패 표시를 덮지 않는다(증명은 저장소에 남아 다음에 이어 간다). */
    @Test fun stoppingTheWaitIgnoresALateDeviceAnswer() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); val answer = CompletableDeferred<Unit>()
        f.board.resultAction = { withContext(NonCancellable) { answer.await(); null } }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        m.recover(f.board); runCurrent()
        assertEquals(KeyringResetStage.Waiting, m.state.value.displayStage)
        m.stopWaiting("시간 초과"); answer.complete(Unit); runCurrent()
        assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage); assertEquals("시간 초과", m.state.value.errorMessage)
    }
    /** 서버 확인을 기다리는 중에 대기를 멈춰도, 저장된 키링 증명으로 다시 시도는 서버만 마저 한다(키링을 다시 찾지 않는다). */
    @Test fun stoppingTheWaitDuringAckRetriesOnlyTheServer() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); val server = CompletableDeferred<Unit>()
        f.server.ackAction = { withContext(NonCancellable) { server.await() }; ApiResult.Ok(resetDone()) }
        val m = model(f); m.load(resetRun.deviceId, true, resetSerial); runCurrent()
        m.recover(f.board); runCurrent()
        m.stopWaiting(null); server.complete(Unit); runCurrent()
        assertEquals(KeyringResetStage.Unknown, m.state.value.displayStage)
        m.waitForDevice(); assertNotEquals(KeyringResetStage.Waiting, m.state.value.displayStage)
        val queries = f.board.queries
        m.recover(null); runCurrent()   // 화면의 "다시 시도": 저장소에서 다시 판정
        assertEquals(KeyringResetStage.Partial, m.state.value.displayStage); assertEquals(queries, f.board.queries)
        m.recover(null); runCurrent()
        assertEquals(KeyringResetStage.Complete, m.state.value.displayStage)
    }
    @Test fun leavingOrSwitchingAccountDiscardsLateUiButKeepsExecution() = runTest(dispatcher) {
        val f = ResetFixture(); f.seed(); val waiting = CompletableDeferred<Unit>()
        f.board.resultAction = { withContext(NonCancellable) { waiting.await(); resetProof } }
        val m = model(f); m.load(resetRun.deviceId, true); runCurrent(); m.recover(f.board); runCurrent()
        m.pause(); waiting.complete(Unit); runCurrent()
        assertTrue(f.server.acknowledgements.isEmpty()); assertEquals(1, f.store.read(1).size)
        f.session.state.value = Session.SignedIn(2, OnboardingStatus.READY, false); runCurrent()
        assertNull(m.state.value.deviceLabel)
    }
}
