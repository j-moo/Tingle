package com.ssafy.e106.features.report

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.ChatTestServer
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.data.*
import com.ssafy.e106.features.report.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

// 캐시/복구 파일을 구분하고 실제 기반처럼 flow 오류는 null, read 오류는 상태로 돌려준다.
internal class ReportDiskPrefs : AppPrefs {
    val values = mutableMapOf<Pair<Boolean,String>, Any>()
    var fault: PrefRead<Nothing>? = null
    var legacyFault: PrefRead<Nothing>? = null
    var refuseWrites = false
    var refuseLegacyDelete = false
    var failAfterWrite = false
    var writes = 0
    @Suppress("UNCHECKED_CAST") override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(null)
    @Suppress("UNCHECKED_CAST") override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> {
        (if(key.durable) fault else legacyFault)?.let { return it }
        return values[key.durable to key.name]?.let { PrefRead.Found(it as T) } ?: PrefRead.Missing
    }
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        writes++
        if(refuseWrites || !key.durable && value == null && refuseLegacyDelete) return
        if(value == null) values.remove(key.durable to key.name) else values[key.durable to key.name] = value
        if(failAfterWrite && key.durable) fault = PrefRead.Unreadable
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportPendingStoreTest {
    private val original = PendingReport(1,"00000000-0000-0000-0000-000000000174",ReportBody(20,"ETC",emptyList(),"기존 요청"))
    private val oldKey = false to "report.pending.20"
    @Test fun unreadableOrCorruptDoesNotReadAsMissingOrOverwrite() = runTest {
        for(fault in listOf(PrefRead.Unreadable,PrefRead.Corrupt,PrefRead.OtherUser)) {
            val prefs = ReportDiskPrefs().apply { this.fault = fault; values[oldKey] = original }
            assertEquals(fault,ReportPendingStore(prefs).read(20,1))
            assertEquals(0,prefs.writes); assertEquals(original,prefs.values[oldKey])
        }
    }
    @Test fun legacyReadFailureDoesNotCreateEmptyMigrationMarker() = runTest {
        val prefs = ReportDiskPrefs().apply { legacyFault = PrefRead.Unreadable }
        assertEquals(PrefRead.Unreadable,ReportPendingStore(prefs).read(20,1)); assertEquals(0,prefs.writes)
    }
    @Test fun migrationPreservesExactRequestAndClearedMarkerPreventsResurrection() = runTest {
        val prefs = ReportDiskPrefs().apply { values[oldKey] = original; refuseLegacyDelete = true }
        val store = ReportPendingStore(prefs)
        assertEquals(PrefRead.Found(original),store.read(20,1))
        assertEquals(PrefRead.Found(Unit),store.save(20,1,null))
        assertEquals(original,prefs.values[oldKey]) // 이전 파일 삭제 실패를 흉내 내도 완료 표식이 우선한다.
        assertEquals(PrefRead.Missing,ReportPendingStore(prefs).read(20,1))
    }
    @Test fun failedMigrationVerificationPreservesLegacyAndCanRetry() = runTest {
        val prefs = ReportDiskPrefs().apply { values[oldKey] = original; refuseWrites = true }
        assertEquals(PrefRead.Unreadable,ReportPendingStore(prefs).read(20,1))
        assertEquals(original,prefs.values[oldKey])
        prefs.refuseWrites = false
        assertEquals(PrefRead.Found(original),ReportPendingStore(prefs).read(20,1))
        assertFalse(prefs.values.containsKey(oldKey))
    }
    @Test fun invalidOwnerRoomOrReasonIsCorruptNotMissing() = runTest {
        for(request in listOf(original.copy(owner=2), original.copy(body=original.body.copy(chatRoomId=21)),original.copy(body=original.body.copy(reason="UNKNOWN")))) {
            val prefs = ReportDiskPrefs().apply { values[oldKey] = request }
            assertEquals(PrefRead.Corrupt,ReportPendingStore(prefs).read(20,1)); assertEquals(0,prefs.writes)
        }
    }
    @Test fun storageFailureStopsNetworkAndRetryUsesSameRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val prefs = ReportDiskPrefs()
            val calls = mutableListOf<PendingReport>()
            val api = object : ReportRepository {
                override suspend fun submit(request: PendingReport): ApiResult<ReportReceipt> {
                    calls += request; return ApiResult.Fail(AppError.Offline)
                }
            }
            val m = ReportViewModel(api,ChatTestServer(),TestSession(OnboardingStatus.READY),prefs)
            models.put("r",m); m.bind(20); runCurrent()
            assertTrue(m.state.value.recoveryComplete)
            prefs.failAfterWrite = true
            m.reason(ReportReason.ETC); m.action(ReportAction.NextEvidence); m.action(ReportAction.Review); m.action(ReportAction.Submit); runCurrent()
            assertTrue(calls.isEmpty()); assertEquals(ReportStorageIssue.Unreadable,m.state.value.storageIssue)
            val pending = m.state.value.pending
            prefs.fault = null; prefs.failAfterWrite = false
            m.action(ReportAction.CheckResult); runCurrent()
            assertEquals(listOf(pending),calls)
        } finally { models.clear(); Dispatchers.resetMain() }
    }
}
