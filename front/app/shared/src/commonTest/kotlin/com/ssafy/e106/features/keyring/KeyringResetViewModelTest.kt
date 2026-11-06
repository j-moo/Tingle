package com.ssafy.e106.features.keyring

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
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
