package com.ssafy.e106.features.report

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private class ReportServer : ReportRepository {
    val calls = mutableListOf<PendingReport>()
    var error: AppError? = null
    var beforeResponse: suspend () -> Unit = {}
    override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
        calls += request; beforeResponse()
        return error?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(ReportReceipt(77,"RECEIVED","2026-11-03T00:00:00Z"))
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
class ReportFlowTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { store.clear() } }
    private fun vm(api: ReportServer, chats: ChatTestServer = ChatTestServer(), prefs: ChatMemoryPrefs = ChatMemoryPrefs()) =
        ReportViewModel(api,chats,TestSession(OnboardingStatus.READY),prefs).also { store.put("r",it); it.bind(20) }
    private fun submit(m: ReportViewModel) { m.action(ReportAction.NextEvidence); m.action(ReportAction.Review); m.action(ReportAction.Submit) }
    @Test fun reportUsesOnlyLoadedSelectedMessageIdsAndReceipt() = test {
        val api = ReportServer(); val chats = ChatTestServer(); val m = vm(api,chats); runCurrent()
        m.reason(ReportReason.ABUSE); m.detail("검토 요청"); m.select(2,true); m.select(999,true); submit(m); runCurrent()
        assertEquals(listOf(2L),api.calls.single().body.messageIds); assertEquals("ABUSE",api.calls.single().body.reason)
        assertEquals(ReportStep.Received,m.state.value.ui.step); assertEquals(77,m.state.value.receipt?.reportId)
        assertTrue(chats.writes.isEmpty()); assertTrue(m.state.value.ui.evidence.isEmpty())
    }
    // 사유 시트에서 확인 단계 없이 바로 접수한다. 사유가 없거나 한도를 넘으면 보내지 않고, 연달아 눌러도 한 번만 보낸다.
    @Test fun formSubmitsDirectlyOnceAndOnlyWhenValid() = test {
        val api = ReportServer(); val m = vm(api); runCurrent()
        m.action(ReportAction.Submit); runCurrent(); assertTrue(api.calls.isEmpty())
        m.reason(ReportReason.ABUSE); m.detail("가".repeat(1001)); m.action(ReportAction.Submit); runCurrent(); assertTrue(api.calls.isEmpty())
        m.detail(""); m.select(2,true); m.action(ReportAction.Submit); m.action(ReportAction.Submit); runCurrent()
        assertEquals(listOf(2L),api.calls.single().body.messageIds); assertNull(api.calls.single().body.detail)
        assertEquals(ReportStep.Received,m.state.value.ui.step)
    }
    @Test fun evidenceStepStillRequiresReviewBeforeSubmit() = test {
        val api = ReportServer(); val m = vm(api); runCurrent()
        m.reason(ReportReason.SPAM); m.action(ReportAction.NextEvidence); m.action(ReportAction.Submit); runCurrent()
        assertTrue(api.calls.isEmpty()); assertEquals(ReportStep.Evidence,m.state.value.ui.step)
    }
    @Test fun responseLossRestoresOriginalRequestAfterRoomClosed() = test {
        val api = ReportServer().apply { error = AppError.Offline }; val prefs = ChatMemoryPrefs(); val chats = ChatTestServer()
        var m = vm(api,chats,prefs); runCurrent(); m.reason(ReportReason.ETC); submit(m); runCurrent()
        val first = api.calls.single(); assertEquals(ReportStep.Unknown,m.state.value.ui.step)
        chats.roomRecord = chats.roomRecord.copy(status="CLOSED"); m = vm(api,chats,prefs); runCurrent(); assertEquals(1,api.calls.size)
        api.error = null; m.action(ReportAction.CheckResult); runCurrent()
        assertEquals(first,api.calls.last()); assertEquals(ReportStep.Received,m.state.value.ui.step)
    }
    @Test fun deniedRoomNeverLoadsEvidenceOrSubmits() = test {
        val api = ReportServer(); val chats = ChatTestServer().apply { roomFailure = AppError.OnboardingRequired("준비 필요") }
        val m = vm(api,chats); runCurrent(); m.reason(ReportReason.SPAM); submit(m); runCurrent()
        assertTrue(api.calls.isEmpty()); assertTrue(chats.calls.isEmpty()); assertFalse(m.state.value.ui.canReport)
    }
    @Test fun evidenceIsOptionalButDetailLimitUsesServerUtf16Units() = test {
        val api = ReportServer(); val m = vm(api); runCurrent(); m.reason(ReportReason.SEXUAL)
        m.detail("가".repeat(1001)); submit(m); runCurrent(); assertTrue(api.calls.isEmpty())
        m.detail(""); submit(m); runCurrent(); assertTrue(api.calls.single().body.messageIds.isEmpty()); assertNull(api.calls.single().body.detail)
    }
    @Test fun failedPersistencePreventsNetworkReport() = test {
        val api = ReportServer(); val m = vm(api,prefs=ChatMemoryPrefs().apply { refuseWrites=true }); runCurrent()
        m.reason(ReportReason.ETC); submit(m); runCurrent(); assertTrue(api.calls.isEmpty())
    }
    @Test fun roomExpiryRemovesDisplayedEvidenceWithoutSubmitting() = test {
        val chats = ChatTestServer().apply { roomRecord=roomRecord.copy(expiresAt="2026-11-03T00:00:01Z") }
        val api = ReportServer(); val m = vm(api,chats); runCurrent(); assertTrue(m.state.value.ui.evidence.isNotEmpty())
        advanceTimeBy(1000); runCurrent(); assertFalse(m.state.value.ui.canReport); assertTrue(m.state.value.ui.evidence.isEmpty()); assertTrue(api.calls.isEmpty())
    }
    // 같은 ViewModel을 다른 방에 연결해도 이전 방의 미확인 요청이 입력을 잠그지 않아야 한다.
    @Test fun changingRoomResetsMemoryButKeepsPreviousPendingRequest() = test {
        val api = ReportServer().apply { error = AppError.Offline }; val m = vm(api); runCurrent()
        m.reason(ReportReason.ETC); m.detail("A방 신고"); submit(m); runCurrent()
        val original = api.calls.single()
        m.bind(21); runCurrent(); m.reason(ReportReason.ABUSE)
        assertEquals(21L, m.state.value.room); assertEquals(ReportReason.ABUSE, m.state.value.ui.reason)
        assertNull(m.state.value.pending); assertNull(m.state.value.receipt); assertEquals("", m.state.value.ui.detail)
        m.bind(20); runCurrent()
        assertEquals(original, m.state.value.pending); assertEquals(ReportStep.Unknown, m.state.value.ui.step)
        assertFalse(m.state.value.ui.available); assertTrue(m.state.value.ui.showsSubmissionStatus)
        api.error = null; m.action(ReportAction.CheckResult); runCurrent()
        assertEquals(original, api.calls.last()); assertEquals(ReportStep.Received, m.state.value.ui.step)
    }
    @Test fun changingRoomClearsPreviousReceipt() = test {
        val m = vm(ReportServer()); runCurrent(); m.reason(ReportReason.ETC); submit(m); runCurrent()
        assertNotNull(m.state.value.receipt)
        m.bind(21); runCurrent()
        assertNull(m.state.value.receipt); assertEquals(ReportStep.Form, m.state.value.ui.step)
        m.reason(ReportReason.SPAM); assertEquals(ReportReason.SPAM, m.state.value.ui.reason)
    }
    // 타이머 만료는 증거 접근만 닫고, 동일 키의 접수 결과 복구는 유지한다.
    @Test fun expiredUnknownRequestCanRecoverSameReceipt() = test {
        val api = ReportServer().apply { error = AppError.Offline }
        val chats = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:01Z") }
        val m = vm(api,chats); runCurrent(); m.reason(ReportReason.ETC); submit(m); runCurrent()
        val original = api.calls.single(); advanceTimeBy(1000); runCurrent()
        assertFalse(m.state.value.ui.available); assertTrue(m.state.value.ui.showsSubmissionStatus)
        assertTrue(m.state.value.ui.evidence.isEmpty()); assertEquals(original, m.state.value.pending)
        api.error = null; chats.roomRecord = chats.roomRecord.copy(status = "CLOSED")
        m.action(ReportAction.CheckResult); runCurrent()
        assertEquals(original, api.calls.last()); assertEquals(ReportStep.Received, m.state.value.ui.step)
        assertFalse(m.state.value.ui.available); assertTrue(m.state.value.ui.showsSubmissionStatus)
    }
    @Test fun expiredReceivedReportKeepsCompletionVisible() = test {
        val chats = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:01Z") }
        val m = vm(ReportServer(),chats); runCurrent(); m.reason(ReportReason.ETC); submit(m); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(77L,m.state.value.receipt?.reportId); assertEquals(ReportStep.Received,m.state.value.ui.step)
        assertFalse(m.state.value.ui.available); assertTrue(m.state.value.ui.showsSubmissionStatus)
        assertTrue(m.state.value.ui.evidence.isEmpty())
    }
    @Test fun failureAfterExpiryDoesNotReopenNewReportPermission() = test {
        val gate = CompletableDeferred<Unit>()
        val api = ReportServer().apply { error = AppError.Offline; beforeResponse = { gate.await() } }
        val chats = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:01Z") }
        val m = vm(api,chats); runCurrent(); m.reason(ReportReason.ETC); submit(m); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(ReportStep.Sending,m.state.value.ui.step); assertTrue(m.state.value.ui.showsSubmissionStatus)
        gate.complete(Unit); runCurrent()
        assertFalse(m.state.value.ui.available); assertEquals(ReportStep.Unknown,m.state.value.ui.step)
        assertTrue(m.state.value.ui.showsSubmissionStatus)
    }
}
