package com.ssafy.e106.features.report

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 읽기가 끝나기 전 중단을 재현한다. 실제 파일·서버에는 신고를 저장하지 않는다. */
internal class DelayedReportPrefs : AppPrefs {
    val original = PendingReport(1,"00000000-0000-0000-0000-000000000174",ReportBody(20,"ETC",emptyList(),"기존 신고"))
    val readable = MutableStateFlow(false)
    val reads = MutableStateFlow(0)
    var failRead = false
    var corruptRead = false
    private val memory = ChatMemoryPrefs()
    suspend fun seed() = memory.set(PrefKey("report.pending.20", PendingReport.serializer()), original, 1)
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = flow {
        reads.update { it + 1 }
        readable.first { it }
        if(failRead) error("test read failure")
        emitAll(memory.flow(key))
    }
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        reads.update { it + 1 }
        readable.first { it }
        return if(corruptRead) PrefRead.Corrupt else if(failRead) PrefRead.Unreadable else memory.read(key, forUser)
    }
    override suspend fun <T> set(key: PrefKey<T>,value: T?,forUser: Long) = memory.set(key,value,forUser)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    @Test fun pausedRestoreCannotOverwriteExistingReportWithNewKey() = runTest(dispatcher) {
        val prefs = DelayedReportPrefs(); prefs.seed()
        val calls = mutableListOf<PendingReport>()
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                calls += request
                return ApiResult.Ok(ReportReceipt(77,"RECEIVED","2026-11-03T00:00:00Z"))
            }
        }
        val model = ReportViewModel(api,ChatTestServer(),TestSession(OnboardingStatus.READY),prefs)
        store.put("restore",model)
        try {
            model.bind(20); runCurrent(); assertEquals(1,prefs.reads.value)
            model.pause(); runCurrent()
            // 기존 Route의 복귀 경로를 호출해도 저장된 요청 조회부터 다시 시작해야 한다.
            model.evidence(); runCurrent()
            model.reason(ReportReason.ABUSE)
            model.action(ReportAction.NextEvidence); model.action(ReportAction.Review); model.action(ReportAction.Submit)
            runCurrent(); prefs.readable.value = true; runCurrent()
            assertTrue(calls.isEmpty(),"복구 중 새 신고가 전송되면 안 됨")
            assertEquals(prefs.original,model.state.value.pending)
            model.action(ReportAction.CheckResult); runCurrent()
            assertEquals(listOf(prefs.original),calls)
        } finally { store.clear() }
    }
    @Test fun failedRestoreCanRetryWithoutSubmitting() = runTest(dispatcher) {
        val prefs = DelayedReportPrefs().apply { readable.value = true; failRead = true }; prefs.seed()
        val calls = mutableListOf<PendingReport>()
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                calls += request; return ApiResult.Fail(AppError.Offline)
            }
        }
        val model = ReportViewModel(api,ChatTestServer(),TestSession(OnboardingStatus.READY),prefs)
        store.put("restore",model)
        try {
            model.bind(20); runCurrent()
            assertFalse(model.state.value.recoveryComplete); assertEquals(ReportStorageIssue.Unreadable,model.state.value.storageIssue)
            model.reason(ReportReason.ABUSE); model.action(ReportAction.Submit); runCurrent()
            assertTrue(calls.isEmpty())
            prefs.failRead = false; model.resume(20); runCurrent()
            assertTrue(model.state.value.recoveryComplete); assertEquals(prefs.original,model.state.value.pending)
            assertTrue(calls.isEmpty())
        } finally { store.clear() }
    }
    @Test fun interruptedEmptyRestoreOpensFormOnlyAfterReadCompletes() = runTest(dispatcher) {
        val prefs = DelayedReportPrefs() // 저장된 요청이 없는 경우도 읽기 완료 전까지 제출을 막는다.
        val api = object : ReportRepository {
            override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> = error("Unexpected submit")
        }
        val model = ReportViewModel(api,ChatTestServer(),TestSession(OnboardingStatus.READY),prefs)
        store.put("restore",model)
        try {
            model.bind(20); runCurrent(); model.pause(); model.resume(20); runCurrent()
            assertFalse(model.state.value.recoveryComplete); assertFalse(model.state.value.ui.canReport)
            assertEquals(2,prefs.reads.value)
            prefs.readable.value = true; runCurrent()
            assertTrue(model.state.value.recoveryComplete); assertTrue(model.state.value.ui.canReport)
            assertNull(model.state.value.pending); assertEquals(ReportStep.Form,model.state.value.ui.step)
        } finally { store.clear() }
    }
}
