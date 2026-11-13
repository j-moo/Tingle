package com.ssafy.e106.features.safety

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
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ChatTestServer
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.ReportDiskPrefs
import com.ssafy.e106.features.safety.data.*
import com.ssafy.e106.features.safety.ui.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** 실제 계정/서버와 분리한 호스트에서 연결 화면의 복구 안내와 사용자 재조회를 검증한다. */
class SafetyRestoreTestActivity : ComponentActivity() {
    internal val prefs = ReportDiskPrefs().apply { fault = PrefRead.Unreadable }
    internal var calls = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = TestSession(OnboardingStatus.READY)
        val api = object : SafetyRepository {
            override suspend fun execute(request: PendingSafety): ApiResult<Unit> {
                calls++
                return ApiResult.Ok(Unit)
            }
        }
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SafetyViewModel(api, ChatTestServer(), sessions, prefs) as T
        })
        setContent { AppTheme {
            ConnectedSafetyRoute(sessions.state.value as Session.SignedIn, 20, SafetyOperation.Block, {}, {}, provider[SafetyViewModel::class.java])
        } }
    }
}

class SafetyRestoreDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun failedRestoreOffersRetryWithoutSendingCommand() {
        ActivityScenario.launch<SafetyRestoreTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context, SafetyRestoreTestActivity::class.java)).use { scenario ->
            // 상태 화면 제목 + 지우면 안 되는 중복 요청 방지 안내
            compose.onNodeWithText("이전 요청을 확인하지 못했어요").assertIsDisplayed()
            compose.onNodeWithText("중복 요청을 막기 위해 새 작업을 시작하지 않았어요.").assertIsDisplayed()
            compose.onNodeWithText("다시 확인").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("돌아가기").assertIsDisplayed().assertIsEnabled()
            scenario.onActivity { assertEquals(0, it.calls); it.prefs.fault = null }
            compose.onNodeWithText("다시 확인").performClick()
            compose.onNodeWithText("다시 확인").assertDoesNotExist()
            scenario.onActivity { assertEquals(0, it.calls) }
        }
    }
}
