package com.ssafy.e106.features.report

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.report.ui.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** 만료 상태의 실제 화면 분기와 버튼 이벤트를 확인한다. 서버 신고 없이 테스트 APK에서만 사용한다. */
class ReportRecoveryTestActivity : ComponentActivity() {
    internal var report by mutableStateOf(ReportState(roomKey = "20", canReport = true,
        rules = ReportRules(1000), step = ReportStep.Unknown))
    internal var lastAction: ReportAction? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ReportScreen(report, {}, {}, { _, _ -> }, { lastAction = it }) } }
    }
}

class ReportRecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch() = ActivityScenario.launch<ReportRecoveryTestActivity>(Intent(
        InstrumentationRegistry.getInstrumentation().context, ReportRecoveryTestActivity::class.java))

    @Test fun expiryKeepsResultCheckAndThenReceiptVisible() {
        launch().use { scenario ->
            compose.onNodeWithText("접수 결과 확인").assertIsEnabled()
            scenario.onActivity { it.report = it.report.copy(canReport = false) }
            compose.onNodeWithText("접수 결과를 확인하지 못했어요").assertExists()
            compose.onNodeWithText("접수 결과 확인").assertIsEnabled().performClick()
            scenario.onActivity {
                assertEquals(ReportAction.CheckResult, it.lastAction)
                it.report = it.report.copy(step = ReportStep.Received)
            }
            compose.onNodeWithText("신고가 접수됐어요").assertExists()
            compose.onNodeWithText("접수 결과 확인").assertDoesNotExist()
            compose.onNodeWithText("지금은 신고 화면을 이용할 수 없어요").assertDoesNotExist()
            compose.onNodeWithText("돌아가기").assertIsEnabled()
        }
    }

    @Test fun expiryStillBlocksUnsubmittedForm() {
        launch().use { scenario ->
            scenario.onActivity { it.report = it.report.copy(canReport = false, step = ReportStep.Form) }
            compose.onNodeWithText("지금은 신고 화면을 이용할 수 없어요").assertExists()
            compose.onNodeWithText("증거 메시지 선택").assertDoesNotExist()
            compose.onNodeWithText("접수 결과 확인").assertDoesNotExist()
            compose.onNodeWithText("돌아가기").assertIsEnabled()
        }
    }
}
