package com.ssafy.e106.features.report

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportRoomSwitchTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() { models.clear(); Dispatchers.resetMain() }
    private fun model(prefs: AppPrefs = ChatMemoryPrefs(), chats: ChatTestServer = ChatTestServer(),
        api: ReportRepository = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> = error("Unexpected submit")
        }) = ReportViewModel(api,chats,TestSession(OnboardingStatus.READY),prefs).also { models.put("report",it) }

    @Test fun switchingDuringStorageReadStartsNewRoomImmediately() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val prefs = object : ChatMemoryPrefs() {
            override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
                if(key.name.endsWith(".20")) withContext(NonCancellable) { gate.await() }
                return super.read(key,forUser)
            }
        }
        val m = model(prefs)
        try {
            m.bind(20); runCurrent(); assertTrue(m.state.value.busy)
            m.bind(21); runCurrent()
            assertEquals(21L,m.state.value.room)
            assertTrue(m.state.value.recoveryComplete)
            assertFalse(m.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertEquals(21L,m.state.value.room) // 취소를 무시한 이전 읽기 결과도 새 방을 덮지 않는다.
        } finally { gate.complete(Unit); models.clear() }
    }
    @Test fun switchingDuringMessagesReadRejectsLateOldMessages() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val chats = object : ChatTestServer() {
            override suspend fun messages(id: Long, cursor: String?, after: Long?): ApiResult<ChatMessagesPage> {
                if(id == 20L) withContext(NonCancellable) { gate.await() }
                return ApiResult.Ok(ChatMessagesPage(listOf(message(id)),null,now))
            }
        }
        val m = model(chats=chats)
        try {
            m.bind(20); runCurrent(); m.bind(21); runCurrent()
            assertEquals(listOf(21L),m.state.value.ui.evidence.map { it.id })
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(21L),m.state.value.ui.evidence.map { it.id })
        } finally { gate.complete(Unit); models.clear() }
    }
    @Test fun sendFinishesBeforeOpeningLatestRequestedRoomAndOldRequestIsRecoverable() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<PendingReport>()
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                calls += request; gate.await(); return ApiResult.Fail(AppError.Offline)
            }
        }
        val m = model(api=api)
        try {
            m.bind(20); runCurrent(); m.reason(ReportReason.ETC)
            m.action(ReportAction.NextEvidence); m.action(ReportAction.Review); m.action(ReportAction.Submit); runCurrent()
            m.bind(21); m.bind(22); runCurrent()
            assertEquals(20L,m.state.value.room); assertTrue(m.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertEquals(22L,m.state.value.room); assertFalse(m.state.value.busy)
            m.bind(20); runCurrent()
            assertEquals(calls.single(),m.state.value.pending)
        } finally { gate.complete(Unit); models.clear() }
    }
    @Test fun returningToSendingRoomCancelsQueuedSwitch() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                gate.await(); return ApiResult.Ok(ReportReceipt(1,"RECEIVED","2026-11-04T00:00:00Z"))
            }
        }
        val m = model(api=api)
        try {
            m.bind(20); runCurrent(); m.reason(ReportReason.ETC)
            m.action(ReportAction.NextEvidence); m.action(ReportAction.Review); m.action(ReportAction.Submit); runCurrent()
            m.bind(21); m.bind(20); gate.complete(Unit); runCurrent()
            assertEquals(20L,m.state.value.room); assertEquals(ReportStep.Received,m.state.value.ui.step)
        } finally { gate.complete(Unit); models.clear() }
    }
}
