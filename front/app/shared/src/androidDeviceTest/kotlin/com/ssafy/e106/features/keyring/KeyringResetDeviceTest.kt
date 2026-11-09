package com.ssafy.e106.features.keyring

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ble.AndroidKeyringBle
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinApplication
import org.koin.dsl.module

/** 테스트 APK만 사용하는 실제 Route 호스트. BLE 명령·서버 요청은 외부로 보내지 않는다. */
class KeyringResetTestActivity : ComponentActivity() {
    companion object { internal var fixture = ResetFixture() }
    internal lateinit var managedSync: KeyringSyncViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = fixture
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(clazz: Class<T>): T = when (clazz) {
                KeyringResetViewModel::class.java -> KeyringResetViewModel(f.model(), f.server, f.session, f.store)
                else -> BleProbeViewModel(AndroidKeyringBle(applicationContext), f.session)
            } as T
        }
        val model = ViewModelProvider(this, factory)[KeyringResetViewModel::class.java]
        val ble = ViewModelProvider(this, factory)[BleProbeViewModel::class.java]
        // 관리형 화면 진입이 호스트의 초기화 중지 결정을 뒤집는지 관찰하는 시험 전용 모델이다.
        managedSync = KeyringSyncViewModel(object : KeyringSyncRepository {
            override suspend fun create(deviceId: Long, key: String, body: SyncChallengeBody) = ApiResult.Fail(AppError.Offline)
            override suspend fun get(deviceId: Long, runId: String) = ApiResult.Fail(AppError.Offline)
            override suspend fun ack(deviceId: Long, runId: String, key: String, body: KeyringSyncAck) = ApiResult.Fail(AppError.Offline)
            override suspend fun applied(deviceId: Long, runId: String, key: String, body: KeyringSyncAck) = ApiResult.Fail(AppError.Offline)
        }, f.sync, f.server, f.session)
        viewModelStore.put("managed-sync", managedSync)
        setContent { AppTheme {
            when (intent.getStringExtra("mode")) {
                "managed" -> KoinApplication(application = {
                    // 실제 화면의 소유 목록 모델만 시험 서버로 주입한다. 제품 Koin이나 로그인 계정은 사용하지 않는다.
                    modules(module { factory { OwnedKeyringsViewModel(f.server, f.session) } })
                }) { ConnectedBleProbeRoute({}, ble, sync = managedSync, managedConnection = true) }
                "blocked" -> {
                    val state by model.state.collectAsStateWithLifecycle()
                    // 실제 화면/모델의 두 동의와 시작을 시험 기기에만 연결한다. 외부 기기/서버는 사용하지 않는다.
                    LaunchedEffect(Unit) { model.load(resetRun.deviceId, true, resetSerial) }
                    KeyringResetScreen(state, model::accepted, {
                        when (it) {
                            KeyringResetAction.StartReset -> model.start(f.board)
                            KeyringResetAction.Cancel -> model.cancel()
                            else -> Unit
                        }
                    }, onRecordChoice = model::recordChoice, onRecordDeletionAccepted = model::recordDeletionAccepted)
                }
                "links" -> KeyringResetRecoveryLinks(1, {}, store = f.store)
                else -> ConnectedKeyringResetRoute(resetRun.deviceId, {}, {}, model, ble)
            }
        } }
    }
}

class KeyringResetDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(mode: String = "recovery") = ActivityScenario.launch<KeyringResetTestActivity>(
        Intent(InstrumentationRegistry.getInstrumentation().context, KeyringResetTestActivity::class.java).putExtra("mode", mode))

    @Test fun enteringManagedBleScreenDoesNotResumeHostSuspendedSync() {
        val f = ResetFixture(); runBlocking { f.sync.rememberTarget(1, resetRun.deviceId, resetSerial) }
        KeyringResetTestActivity.fixture = f
        launch("managed").use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { assertEquals(0, it.managedSync.state.value.items.size) }
            scenario.recreate(); compose.waitForIdle()
            scenario.onActivity { assertEquals(0, it.managedSync.state.value.items.size) }
        }
    }

    @Test fun storedProofIsFinishedAutomaticallyOnceWithoutResendingToBoard() {
        val f = ResetFixture(); runBlocking { f.seed(ack = resetProof) }; KeyringResetTestActivity.fixture = f
        launch().use { scenario ->
            // 결과 확인 화면 없이 저장된 기기 증명으로 서버 해제를 자동으로 마저 한다
            compose.waitUntil(5_000) { f.server.acknowledgements.size == 1 }
            compose.onNodeWithText("초기화되었어요!").assertExists()
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("초기화되었어요!").assertExists()
            assertEquals(1, f.server.acknowledgements.size)
            assertEquals(0, f.board.sends)
        }
    }
    @Test fun unknownInventoryRequiresBothCheckboxesBeforeExplicitStart() {
        val f = ResetFixture(); f.server.run = resetRun.copy(discardUnsentConfirmed = true); KeyringResetTestActivity.fixture = f
        launch("blocked").use {
            compose.onNodeWithText("위 내용을 확인했어요.").performScrollTo().performClick()
            compose.onNodeWithText("키링 초기화").assertIsNotEnabled()
            assertEquals(0, f.server.starts.size)
            compose.onNodeWithText("개수를 확인할 수 없는 미전송 만남 기록의 삭제에 동의해요.").performScrollTo().performClick()
            compose.onNodeWithText("키링 초기화").assertIsEnabled()
            assertEquals(0, f.server.starts.size)
            compose.onNodeWithText("키링 초기화").performClick()
            compose.onNodeWithText("초기화되었어요!").assertExists()
            assertEquals(1, f.server.starts.size)
            assertEquals(true, f.server.starts.single().second.discardUnsentConfirmed)
        }
    }
    @Test fun consentIsNotReusedAfterActivityRecreation() {
        val f = ResetFixture(); KeyringResetTestActivity.fixture = f
        launch("blocked").use { scenario ->
            compose.onNodeWithText("위 내용을 확인했어요.").performScrollTo().performClick()
            compose.onNodeWithText("개수를 확인할 수 없는 미전송 만남 기록의 삭제에 동의해요.").performScrollTo().performClick()
            compose.onNodeWithText("키링 초기화").assertIsEnabled()
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("키링 초기화").assertIsNotEnabled()
            assertEquals(0, f.server.starts.size)
        }
    }
    @Test fun recoveryEntrySurvivesAbsenceFromOwnedList() {
        val f = ResetFixture(); runBlocking { f.seed() }; KeyringResetTestActivity.fixture = f
        launch("links").use { scenario ->
            compose.onNodeWithText("초기화 중인 키링").assertExists()
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("초기화 중인 키링").assertExists()
            assertEquals(0, f.server.ownershipReads)
        }
    }
}
