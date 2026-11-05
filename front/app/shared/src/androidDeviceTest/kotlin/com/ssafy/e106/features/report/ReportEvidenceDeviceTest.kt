package com.ssafy.e106.features.report

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
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** 메시지를 받은 직후 권한 조회만 실패하게 해 실제 화면 버튼으로 복구하는지 검사한다. */
class ReportEvidenceTestActivity : ComponentActivity() {
    internal val chats = ChatTestServer().apply {
        onMessages = { _, _ ->
            roomFailure = AppError.Offline
            ApiResult.Ok(ChatMessagesPage(listOf(message(19)), null, now))
        }
    }
    internal var submissions = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = TestSession(OnboardingStatus.READY)
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                submissions++
                return ApiResult.Fail(AppError.Offline)
            }
        }
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReportViewModel(api, chats, sessions, ChatMemoryPrefs()) as T
        })
        setContent { AppTheme { ConnectedReportRoute(sessions.state.value as Session.SignedIn,20,{},provider[ReportViewModel::class.java]) } }
    }
}

class ReportEvidenceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun deniedEvidenceCanBeRetriedThroughVisibleScreenButton() {
        ActivityScenario.launch<ReportEvidenceTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context, ReportEvidenceTestActivity::class.java)).use { scenario ->
            compose.onNodeWithText("메시지 다시 불러오기").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("증거 메시지 선택").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.submissions); it.chats.roomFailure = null; it.chats.onMessages = null }
            compose.onNodeWithText("메시지 다시 불러오기").performClick()
            compose.onNodeWithText("신고 사유를 선택해 주세요").assertIsDisplayed()
            scenario.onActivity { assertEquals(0,it.submissions) }
        }
    }
}
