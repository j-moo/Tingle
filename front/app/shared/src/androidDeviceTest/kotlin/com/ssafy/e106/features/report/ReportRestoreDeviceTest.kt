package com.ssafy.e106.features.report

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ChatTestServer
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** 상태를 직접 주입하지 않고 실제 Route·ViewModel의 중단/복귀와 지연 저장소 읽기를 연결한다. */
class ReportRestoreTestActivity : ComponentActivity() {
    internal val prefs = DelayedReportPrefs()
    internal var requestedRoom by mutableStateOf(20L)
    internal val calls = AtomicInteger()
    internal val lastRequest = AtomicReference<PendingReport?>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runBlocking { prefs.seed() }
        val sessions = TestSession(OnboardingStatus.READY)
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                calls.incrementAndGet(); lastRequest.set(request)
                return ApiResult.Ok(ReportReceipt(77,"RECEIVED","2026-11-03T00:00:00Z"))
            }
        }
        val provider = ViewModelProvider(this,object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReportViewModel(api,ChatTestServer(),sessions,prefs) as T
        })
        setContent { AppTheme { ConnectedReportRoute(sessions.state.value as Session.SignedIn,requestedRoom,{},provider[ReportViewModel::class.java]) } }
    }
}

class ReportRestoreDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun stoppingDuringRestoreThenResumingKeepsOriginalRequest() {
        ActivityScenario.launch<ReportRestoreTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,ReportRestoreTestActivity::class.java)).use { scenario ->
            lateinit var prefs: DelayedReportPrefs
            scenario.onActivity { prefs = it.prefs }
            compose.waitUntil(5000) { prefs.reads.value == 1 }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) { prefs.reads.value >= 2 }
            compose.onNodeWithText("증거 메시지 선택").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.calls.get()); it.prefs.readable.value = true }
            compose.onNodeWithText("접수 결과 확인").assertIsEnabled().performClick()
            compose.onNodeWithText("신고가 접수됐어요").assertExists()
            scenario.onActivity {
                assertEquals(1,it.calls.get())
                assertEquals(prefs.original,it.lastRequest.get())
            }
        }
    }
    // 실제 화면에서 실패 안내·재조회·서버 미전송을 함께 확인한다.
    @Test fun unreadableStorageShowsRetryAndPreservesRequest() {
        ActivityScenario.launch<ReportRestoreTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,ReportRestoreTestActivity::class.java)).use { scenario ->
            scenario.onActivity { it.prefs.failRead = true; it.prefs.readable.value = true }
            compose.onNodeWithText("저장된 신고 요청을 확인하지 못했어요").assertExists()
            compose.onNodeWithText("증거 메시지 선택").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.calls.get()); it.prefs.failRead = false }
            compose.onNodeWithText("다시 확인").performClick()
            compose.onNodeWithText("접수 결과 확인").assertIsEnabled()
            scenario.onActivity { assertEquals(0,it.calls.get()) }
        }
    }
    @Test fun corruptStorageShowsContactNoticeWithoutOpeningNewReport() {
        ActivityScenario.launch<ReportRestoreTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,ReportRestoreTestActivity::class.java)).use { scenario ->
            scenario.onActivity { it.prefs.corruptRead = true; it.prefs.readable.value = true }
            // 상태 화면 제목 + 지우면 안 되는 중복 접수 방지 · 문의 안내
            compose.onNodeWithText("저장된 신고 기록을 확인할 수 없어요").assertExists()
            compose.onNodeWithText("중복 접수를 막기 위해 새 신고를 보낼 수 없어요. 서비스팀에 문의해 주세요.").assertExists()
            compose.onNodeWithText("돌아가기").assertIsEnabled()
            compose.onNodeWithText("증거 메시지 선택").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.calls.get()) }
        }
    }

    @Test fun roomChangeDuringRestoreShowsNewRoomInsteadOfBlankScreen() {
        ActivityScenario.launch<ReportRestoreTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().context,ReportRestoreTestActivity::class.java)).use { scenario ->
            lateinit var prefs: DelayedReportPrefs
            scenario.onActivity { prefs = it.prefs }
            compose.waitUntil(5000) { prefs.reads.value == 1 }
            // 실제 Route의 room 인자만 바꾼다. 모델 상태를 직접 바꾸지 않는다.
            scenario.onActivity { it.requestedRoom = 21L }
            // 확인하는 동안은 뼈대(화면 리더에는 한 문장)
            compose.onNodeWithContentDescription("저장된 신고 요청을 확인하고 있어요.").assertExists()
            scenario.onActivity { it.prefs.readable.value = true }
            compose.onNodeWithText("신고 사유를 선택해 주세요").assertExists()
            compose.onNodeWithText("접수 결과 확인").assertDoesNotExist()
            scenario.onActivity { assertEquals(0,it.calls.get()) }
        }
    }

}
