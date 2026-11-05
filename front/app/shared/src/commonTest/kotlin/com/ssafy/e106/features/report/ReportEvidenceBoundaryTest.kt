package com.ssafy.e106.features.report

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 메시지 요청 전 권한이 유효했더라도 늦은 응답 직전의 종료를 확인해야 한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportEvidenceBoundaryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun evidenceArrivingAfterRoomClosureIsNotPublished() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val chats = ChatTestServer().apply {
            onMessages = { _, _ -> gate.await(); ApiResult.Ok(ChatMessagesPage(listOf(message(19)), null, now)) }
        }
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> = error("신규 신고 금지")
        }
        val model = ReportViewModel(api, chats, TestSession(OnboardingStatus.READY), ChatMemoryPrefs())
        store.put("r", model)
        model.bind(20); runCurrent()
        chats.roomRecord = chats.roomRecord.copy(status = "CLOSED", canRead = false)
        gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.ui.evidence.isEmpty())
        assertFalse(model.state.value.ui.canReport)
    }

    @Test fun accessRecheckFailureHidesEvidenceUntilSuccessfulRetry() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val chats = ChatTestServer().apply {
            onMessages = { _, _ -> gate.await(); ApiResult.Ok(ChatMessagesPage(listOf(message(19)), null, now)) }
        }
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> = error("신규 신고 금지")
        }
        val model = ReportViewModel(api, chats, TestSession(OnboardingStatus.READY), ChatMemoryPrefs())
        store.put("r", model)
        model.bind(20); runCurrent()
        chats.roomFailure = AppError.Offline
        gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.ui.evidence.isEmpty())
        assertFalse(model.state.value.ui.canReport)
        chats.roomFailure = null
        model.evidence(); runCurrent()
        assertEquals(listOf(19L), model.state.value.ui.evidence.map { it.id })
        assertTrue(model.state.value.ui.canReport)
    }
}
