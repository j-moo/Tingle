package com.ssafy.e106.features.safety

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** 고지 조회만 실패한 실제 동의 화면의 재시도 경로를 검증한다. 앱 배포에는 포함되지 않는다. */
class AiConsentRecoveryTestActivity : ComponentActivity() {
    internal val api = ExtraServer().apply { consent = consent.copy(enabled=false) }
    internal val docs = NoticeServer("https://tingle.test/ai-notice").apply { failure = AppError.Offline }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = TestSession(OnboardingStatus.READY)
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AiConsentViewModel(api,docs,session) as T
        })
        setContent { AppTheme { ConnectedAiConsentRoute(session.state.value as Session.SignedIn,{},provider[AiConsentViewModel::class.java]) } }
    }
}

class AiConsentRecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun failedNoticeCanReloadInPlaceWithoutGrantingConsent() {
        ActivityScenario.launch<AiConsentRecoveryTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,AiConsentRecoveryTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("인터넷 연결을 확인해 주세요").assertExists()
            compose.onNodeWithText("다시 시도").assertIsEnabled()
            scenario.onActivity { it.docs.failure = null }
            compose.onNodeWithText("다시 시도").performClick()
            compose.onNodeWithText("현재 유효한 동의가 없어요").assertExists()
            compose.onNodeWithText("분석 안내 전문 보기").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("동의하고 저장하기").performScrollTo().assertIsNotEnabled()
            scenario.onActivity { assertTrue(it.api.consentWrites.isEmpty()) }
        }
    }
}
