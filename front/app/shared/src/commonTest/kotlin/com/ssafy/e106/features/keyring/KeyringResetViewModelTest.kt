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

    @Test fun ownedConnectedDeviceStillCannotStartWithoutRecordInventory() = runTest(dispatcher) {
        val f = ResetFixture(); val m = model(f)
        m.load(resetRun.deviceId, true); runCurrent(); m.confirm(); m.accepted(true)
        assertEquals(KeyringResetStage.Confirm, m.state.value.displayStage)
        assertNull(m.state.value.pendingRecordCount); assertFalse(m.state.value.canConfirm)
        assertTrue(f.server.starts.isEmpty())
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
