package com.ssafy.e106.features.home

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.home.ui.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.HttpOnboardingRepository
import com.ssafy.e106.features.profile.data.HttpOptionalProfileRepository
import com.ssafy.e106.features.profile.data.HttpProfileRepository
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeKeyringSummaryTest {
    @Test fun registrationAndLookupFailureStaySeparateFromMatchingSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine {
            error("홈 요약은 프로필과 약관을 조회하지 않는다")
        })
        try {
            val session = TestSession(OnboardingStatus.READY)
            val auth = object : AuthRepository {
                override fun available(provider: Provider) = error("unexpected social login lookup")
                override suspend fun login(provider: Provider) = error("unexpected login")
                override suspend fun logout() = error("unexpected logout")
            }
            val settings = object : MatchingSettingsRepository {
                override suspend fun get(userId: Long, kind: SettingsKind) =
                    ApiResult.Ok(SettingsDocument(ConflictValues.Matching(true, false), null))
                override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String) =
                    error("unexpected save")
            }
            var reply = CompletableDeferred<ApiResult<List<OwnedKeyring>>>()
            val owned = object : OwnedKeyringRepository {
                override suspend fun list() = reply.await()
            }
            val prefs = HomeTestPrefs()
            val vm = HomeSessionViewModel(HttpProfileRepository(client), settings, HttpOnboardingRepository(client),
                session, auth, owned, prefs = prefs).also { models.put("home", it) }

            // 키링 목록을 받기 전에는 매칭 원을 보여 주지 않는다(키링이 없는 계정에 ON 이 잠깐 보였다 바뀌지 않게).
            // 다 받으면 한 번에 보여 주고, 서버의 READY를 현재 BLE 연결 상태로 해석하지 않는다.
            vm.home()
            runCurrent()
            assertEquals(HomeLoadState.Loading, vm.state.value.home.load)
            assertNull(vm.state.value.home.matchingEnabled)
            reply.complete(ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "test"), OwnedKeyring(7, "READY", "test"))))
            runCurrent()
            assertEquals(HomeLoadState.Ready, vm.state.value.home.load)
            assertEquals(true, vm.state.value.home.matchingEnabled)
            assertEquals(HomeKeyringState.Registered, vm.state.value.home.keyring)
            assertTrue(vm.saveKeyringAlias(4, " 재준키링 "))
            assertFalse(vm.saveKeyringAlias(999, "내 기기 아님"))
            prefs.refuseWrites = true
            assertFalse(vm.saveKeyringAlias(4, "저장 실패"))
            prefs.refuseWrites = false
            // 목록 순서가 바뀌어도 별명은 기기 ID에 남고, 기본 이름으로 돌아가면 저장값도 지워진다.
            reply = CompletableDeferred(ApiResult.Ok(listOf(OwnedKeyring(7, "READY", "test"), OwnedKeyring(4, "READY", "test"))))
            vm.home()
            runCurrent()
            assertEquals(mapOf(4L to "재준키링"), vm.state.value.home.keyringAliases)
            assertTrue(vm.saveKeyringAlias(4, ""))
            vm.home()
            runCurrent()
            assertTrue(vm.state.value.home.keyringAliases.isEmpty())

            reply = CompletableDeferred(ApiResult.Ok(emptyList()))
            vm.home()
            runCurrent()
            assertEquals(HomeKeyringState.Missing, vm.state.value.home.keyring)

            // 받아 둔 홈을 다시 열 때(조용한 갱신) 목록 조회가 실패하면 보이던 상태를 두고 오류만 남긴다.
            reply = CompletableDeferred(ApiResult.Fail(AppError.Offline))
            vm.home()
            runCurrent()
            assertEquals(HomeKeyringState.Missing, vm.state.value.home.keyring)
            assertEquals(AppError.Offline, vm.state.value.error)
            assertEquals(HomeLoadState.Ready, vm.state.value.home.load)
            assertEquals(true, vm.state.value.home.matchingEnabled)
        } finally {
            models.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
