package com.ssafy.e106.features.keyring

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.data.BleDeviceInfo
import com.ssafy.e106.features.keyring.data.BleDeviceStatus
import com.ssafy.e106.features.keyring.data.BleOwnerFlowStatus
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 테스트 APK 전용 호스트. 인증만 테스트 상태이며 BLE 권한/어댑터는 실제 OS를 사용한다. */
class BleProbeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(clazz: Class<T>): T = BleProbeViewModel(
                AndroidKeyringBle(applicationContext), TestSession(OnboardingStatus.KEYRING_REQUIRED)) as T
        })[BleProbeViewModel::class.java]
        setContent { AppTheme { ConnectedBleProbeRoute({ finish() }, model) } }
    }
}

class BleProbeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realPermissionGuardAndRecreatedScreenDoNotPretendConnected() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val availability = context.bleAvailability()
        // 테스트 패키지의 권한을 임의로 바꾸지 않는다. 준비 완료 기기는 이 거절 시나리오 대상이 아니다.
        assumeTrue(availability != BleAvailability.Ready)
        runBlocking {
            val error = runCatching { AndroidKeyringBle(context).connect("unseen") }.exceptionOrNull()
            assertTrue(error is BleException)
            assertTrue((error as BleException).reason in setOf(BleFailure.Unsupported, BleFailure.PermissionRequired,
                BleFailure.BluetoothOff, BleFailure.LocationOff))
        }
        ActivityScenario.launch<BleProbeTestActivity>(Intent(context, BleProbeTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("주변 키링 찾기").assertExists()
            // 권한은 로그인 직후 권한 시트에서 받는다. 이 화면은 요청 버튼 없이 안내만 한다
            if (availability == BleAvailability.PermissionRequired) compose.onNodeWithText("근처 기기 권한이 필요해요", substring = true).assertExists()
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("주변 키링 찾기").assertExists()
            compose.onNodeWithText("연결 끊기").assertDoesNotExist()
        }
    }

    /** 서버 저장 확인 뒤에도 이전 버전의 보드 상태를 현재 준비 완료로 표시하지 않는다. */
    @Test fun matchingReadinessUsesOnlyCurrentBoardVersion() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val serial = "XIAO-test"
        val connected = BleProbeState(connected = true, info = BleDeviceInfo(serial, "0.7.2-owner-flow", supportsStatus = true))
        val reading = mutableStateOf(connected)
        val confirmed = KeyringSyncState(items = listOf(KeyringSyncProgress(4, SyncPhase.Confirmed,
            serial = serial, confirmedTsv3Version = 12L)))
        ActivityScenario.launch<BleProbeTestActivity>(Intent(context, BleProbeTestActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AppTheme {
                        KeyringSyncPanelContent(confirmed, reading.value, OwnedKeyringsState(), onReadStatus = {
                            reading.value = connected.copy(status = BleDeviceStatus(null, null, null, null,
                                BleOwnerFlowStatus(applied = true, timeReady = true, matchingReady = true, storedSettingsVersion = 12L)))
                        })
                    }
                }
            }
            compose.runOnIdle {
                reading.value = connected.copy(status = BleDeviceStatus(null, null, null, null,
                    BleOwnerFlowStatus(applied = true, timeReady = true, matchingReady = true, storedSettingsVersion = 11L)))
            }
            compose.onNodeWithText("매칭 준비 · 미확인").assertExists()
            compose.onNodeWithText("키링 준비 상태 확인").performClick()
            compose.onNodeWithText("설정 적용 · 완료").assertExists()
            compose.onNodeWithText("기기 시각 · 완료").assertExists()
            compose.onNodeWithText("매칭 준비 · 완료").assertExists()
        }
    }
}
