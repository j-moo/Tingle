package com.ssafy.e106.features.home

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.HttpOnboardingRepository
import com.ssafy.e106.features.profile.data.HttpProfileRepository
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeMatchingToggleTest {
    private class FakeSettings : MatchingSettingsRepository {
        var current = SettingsDocument(ConflictValues.Matching(true, true), "\"v1\"")
        val saves = mutableListOf<Pair<ConflictValues, String>>()
        var fail: AppError? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun get(userId: Long, kind: SettingsKind) = ApiResult.Ok(current)
        override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> {
            saves += values to etag
            gate?.await()
            fail?.let { return ApiResult.Fail(it) }
            current = SettingsDocument(values, "\"v2\""); return ApiResult.Ok(current)
        }
    }

    // 홈 큰 원: 받은 ETag 로 켜짐만 바꿔 저장하고(사진 조건 유지) 키링 동기화에 알린다. 실패하면 덮어쓰지 않고 최신 값을 다시 보여 준다
    @Test fun toggleSavesWithEtagKeepsPhotoAndRecoversFromConflict() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine { error("unexpected") })
        try {
            val session = TestSession(OnboardingStatus.READY)
            val auth = object : AuthRepository {
                override fun available(provider: Provider) = error("unexpected")
                override suspend fun login(provider: Provider) = error("unexpected")
                override suspend fun logout() = error("unexpected")
            }
            val owned = object : OwnedKeyringRepository { override suspend fun list() = ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "1.0"))) }
            val settings = FakeSettings(); val changes = KeyringSettingsChanges()
            val vm = HomeSessionViewModel(HttpProfileRepository(client), settings, HttpOnboardingRepository(client),
                session, auth, owned, changes).also { models.put("home", it) }
            vm.home(); runCurrent()
            assertEquals(1, vm.state.value.home.keyrings.size)

            vm.setMatching(false); runCurrent()
            assertEquals(ConflictValues.Matching(false, true) to "\"v1\"", settings.saves.single())
            assertEquals(false, vm.state.value.home.matchingEnabled)
            assertFalse(vm.state.value.home.savingMatching)
            assertEquals(1L, changes.state.value[1])

            // 다른 곳에서 먼저 바뀜(412, 사진 조건만 바뀜): 실패로 알리고 서버 최신 값을 보여 준다. 서버 설정이 바뀌었으니 키링에도 알린다
            settings.fail = AppError.Server(412, "PRECONDITION_FAILED", "changed")
            settings.current = SettingsDocument(ConflictValues.Matching(false, false), "\"v3\"")
            vm.setMatching(true); runCurrent()
            assertTrue(vm.state.value.home.matchingSaveFailed)
            assertEquals(false, vm.state.value.home.matchingEnabled)
            assertEquals(2L, changes.state.value[1])

            // 응답 유실: 서버는 켜짐으로 저장했는데 응답이 안 옴 → 다시 받아 보니 켜짐이면 성공으로 보고 키링에 알린다
            settings.fail = AppError.Offline
            settings.current = SettingsDocument(ConflictValues.Matching(true, false), "\"v4\"")
            vm.setMatching(true); runCurrent()
            assertFalse(vm.state.value.home.matchingSaveFailed)
            assertEquals(true, vm.state.value.home.matchingEnabled)
            assertEquals(3L, changes.state.value[1])

            // 저장 응답 전에 탭을 옮겨도(leave) 저장이 확정되면 동기화에 알린다. 화면 상태는 건드리지 않는다
            settings.fail = null
            vm.home(); runCurrent()
            settings.gate = CompletableDeferred()
            vm.setMatching(false); runCurrent()
            vm.leave()
            settings.gate!!.complete(Unit); runCurrent()
            assertEquals(4L, changes.state.value[1])
            assertFalse(vm.state.value.home.savingMatching)

            // 저장 중에 다른 탭에 갔다가 홈으로 돌아오면, 홈은 저장이 끝난 뒤의 값(켜짐)을 보여 준다(이전 값 고정 금지)
            vm.home(); runCurrent()
            settings.gate = CompletableDeferred()
            vm.setMatching(true); runCurrent()
            vm.leave(); vm.home(); runCurrent()
            settings.gate!!.complete(Unit); runCurrent()
            assertEquals(true, vm.state.value.home.matchingEnabled)
        } finally {
            models.clear(); client.close(); Dispatchers.resetMain()
        }
    }
}
