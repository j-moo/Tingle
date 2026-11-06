package com.ssafy.e106.features.safety

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.report.ReportDiskPrefs
import com.ssafy.e106.features.safety.data.*
import com.ssafy.e106.features.safety.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 기기/서버를 변경하지 않고 저장소 실패 이후 사용자 명령의 경계를 재현한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyRecoveryBoundaryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun unreadableRequestNeverBecomesPermissionForANewCommand() = runTest(dispatcher) {
        val calls = mutableListOf<PendingSafety>()
        val api = object : SafetyRepository {
            override suspend fun execute(request: PendingSafety): ApiResult<Unit> {
                calls += request
                return ApiResult.Ok(Unit)
            }
        }
        val prefs = object : ChatMemoryPrefs() {
            override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> = PrefRead.Unreadable
        }
        val model = SafetyViewModel(api, ChatTestServer(), TestSession(OnboardingStatus.READY), prefs)
        store.put("s", model)
        model.bind(20, SafetyOperation.Block); runCurrent()
        assertFalse(model.state.value.ui.canRequest)
        model.ask(); model.submit(); runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test fun legacyRequestMigrationAndCompletionSurviveFailedCacheDeletion() = runTest(dispatcher) {
        // 신고 검증에서도 사용하는 저장소 fixture로 캐시/내구성 파일을 실제 기반처럼 구분한다.
        val prefs = ReportDiskPrefs().apply { refuseLegacyDelete = true }
        val request = PendingSafety(1, 20, "BLOCK", "existing-key")
        prefs.values[false to "safety.pending.20.BLOCK"] = request
        val pending = SafetyPendingStore(prefs)
        assertEquals(PrefRead.Found(request), pending.read(20, "BLOCK", 1))
        assertEquals(PrefRead.Found(Unit), pending.save(20, "BLOCK", 1, null))
        assertEquals(request, prefs.values[false to "safety.pending.20.BLOCK"])
        assertEquals(PrefRead.Missing, SafetyPendingStore(prefs).read(20, "BLOCK", 1))
    }

    @Test fun failedMigrationPreservesOriginalRequestAndRejectsMismatchedOwner() = runTest(dispatcher) {
        val request = PendingSafety(1, 20, "LEAVE", "existing-key")
        val prefs = ReportDiskPrefs().apply {
            refuseWrites = true
            values[false to "safety.pending.20.LEAVE"] = request
        }
        assertEquals(PrefRead.Unreadable, SafetyPendingStore(prefs).read(20, "LEAVE", 1))
        assertEquals(request, prefs.values[false to "safety.pending.20.LEAVE"])
        prefs.refuseWrites = false
        assertEquals(PrefRead.Corrupt, SafetyPendingStore(prefs).read(20, "LEAVE", 2))
        assertEquals(PrefRead.Found(request), SafetyPendingStore(prefs).read(20, "LEAVE", 1))
    }

    @Test fun writeFailureStopsNetworkAndRecoveryUsesOriginalKey() = runTest(dispatcher) {
        val prefs = ReportDiskPrefs()
        val calls = mutableListOf<PendingSafety>()
        val api = object : SafetyRepository {
            override suspend fun execute(request: PendingSafety): ApiResult<Unit> {
                calls += request
                return ApiResult.Fail(AppError.Offline)
            }
        }
        val model = SafetyViewModel(api, ChatTestServer(), TestSession(OnboardingStatus.READY), prefs)
        store.put("s", model)
        model.bind(20, SafetyOperation.Leave); runCurrent()
        prefs.refuseWrites = true
        model.ask(); model.submit(); runCurrent()
        val original = assertNotNull(model.state.value.pending)
        assertTrue(calls.isEmpty())
        assertEquals(SafetyPhase.Unknown, model.state.value.ui.phase)
        prefs.refuseWrites = false
        model.recover(); runCurrent()
        assertEquals(listOf(original), calls)
        // 화면을 새로 만들어도 미확인 요청을 자동 전송하지 않고 같은 키로 복원한다.
        val restored = SafetyViewModel(api, ChatTestServer(), TestSession(OnboardingStatus.READY), prefs)
        store.put("s2", restored)
        restored.bind(20, SafetyOperation.Leave); runCurrent()
        assertEquals(original, restored.state.value.pending)
        assertEquals(1, calls.size)
        restored.recover(); runCurrent()
        assertEquals(listOf(original, original), calls)
    }

    @Test fun serverSuccessWithLocalCompletionWriteFailureRemainsRecoverable() = runTest(dispatcher) {
        val prefs = ReportDiskPrefs()
        val calls = mutableListOf<PendingSafety>()
        val api = object : SafetyRepository {
            override suspend fun execute(request: PendingSafety): ApiResult<Unit> {
                calls += request
                prefs.refuseWrites = calls.size == 1
                return ApiResult.Ok(Unit)
            }
        }
        val model = SafetyViewModel(api, ChatTestServer(), TestSession(OnboardingStatus.READY), prefs)
        store.put("s", model)
        model.bind(20, SafetyOperation.Block); runCurrent()
        model.ask(); model.submit(); runCurrent()
        assertEquals(SafetyPhase.Unknown, model.state.value.ui.phase)
        assertNotNull(model.state.value.pending)
        prefs.refuseWrites = false
        model.recover(); runCurrent()
        assertEquals(calls[0], calls[1])
        assertEquals(SafetyPhase.Succeeded, model.state.value.ui.phase)
        assertNull(model.state.value.pending)
    }
}
