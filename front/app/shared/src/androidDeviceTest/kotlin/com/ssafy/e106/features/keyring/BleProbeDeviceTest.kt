package com.ssafy.e106.features.keyring

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.ble.*
import com.ssafy.e106.core.ui.theme.AppTheme
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
            compose.onNodeWithText("키링 BLE 연결 확인 · 개발용").assertExists()
            compose.onNodeWithText("기기 정보 수신까지 확인했어요.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("기기 상태 읽기").assertDoesNotExist()
            if (availability == BleAvailability.PermissionRequired) compose.onNodeWithText("권한 요청하기").assertExists()
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("키링 BLE 연결 확인 · 개발용").assertExists()
            compose.onNodeWithText("연결 종료").assertDoesNotExist()
        }
    }
}
