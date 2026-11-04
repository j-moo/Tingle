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
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 테스트 APK 전용. 실제 계정이나 BLE 기기를 생성하지 않고 화면의 재조회 수명만 검증한다. */
class OwnedKeyringsTestActivity : ComponentActivity() {
    companion object { val reads = AtomicInteger() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(clazz: Class<T>): T = OwnedKeyringsViewModel(
                object : OwnedKeyringRepository {
                    override suspend fun list(): ApiResult<List<OwnedKeyring>> {
                        reads.incrementAndGet()
                        return ApiResult.Ok(listOf(OwnedKeyring(7, "READY", "1.0")))
                    }
                }, TestSession(OnboardingStatus.READY)) as T
        })[OwnedKeyringsViewModel::class.java]
        setContent { AppTheme {
            ConnectedOwnedKeyringsRoute(Session.SignedIn(1, OnboardingStatus.READY, false), {}, model)
        } }
    }
}

class OwnedKeyringsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun oneReadOnEntryThenFreshReadAfterRotationAndBackground() {
        OwnedKeyringsTestActivity.reads.set(0)
        ActivityScenario.launch<OwnedKeyringsTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            OwnedKeyringsTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("서버 상태 · 설정 동기화 완료").assertExists()
            assertEquals(1, OwnedKeyringsTestActivity.reads.get())
            compose.onNodeWithText("현재 블루투스 연결과 배터리 상태는 아직 확인할 수 없어요.", substring = true).assertExists()
            scenario.recreate(); compose.waitForIdle()
            assertEquals(2, OwnedKeyringsTestActivity.reads.get())
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED); compose.waitForIdle()
            assertEquals(3, OwnedKeyringsTestActivity.reads.get())
            compose.onNodeWithText("키링 #7").assertExists()
        }
    }
}
