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
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ChatTestServer
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.safety.data.IcebreakerResult
import com.ssafy.e106.features.safety.ui.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** 실제 부가 기능 화면에서 재진입 없이 복구하는지 검사하는 테스트 전용 호스트. */
class ChatExtrasRecoveryTestActivity : ComponentActivity() {
    internal val chats = ChatTestServer().apply { roomFailure = AppError.Offline }
    internal val api = ExtraServer().apply {
        result = ApiResult.Ok(IcebreakerResult("SUCCEEDED",listOf("하나","둘","셋"),"GENERAL_FALLBACK","v1"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatExtrasViewModel(api,chats,TestSession(OnboardingStatus.READY)) as T
        })
        setContent { AppTheme { ConnectedChatExtras(20,true,true,{},provider[ChatExtrasViewModel::class.java]) } }
    }
}

class ChatExtrasRecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun offlineQuestionsShowRetryAndRecoverWithoutLeavingScreen() {
        ActivityScenario.launch<ChatExtrasRecoveryTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,ChatExtrasRecoveryTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("대화 시작 질문").assertExists()
            compose.onNodeWithText("연결을 확인해 주세요").assertExists()
            compose.onNodeWithText("다시 시도").assertIsEnabled()
            scenario.onActivity { it.chats.roomFailure = null }
            compose.onNodeWithText("다시 시도").performClick()
            compose.onNodeWithText("하나").assertExists()
            compose.onNodeWithText("둘").assertExists()
            compose.onNodeWithText("셋").assertExists()
            compose.onNodeWithText("다시 시도").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.api.requests) }
        }
    }
}
