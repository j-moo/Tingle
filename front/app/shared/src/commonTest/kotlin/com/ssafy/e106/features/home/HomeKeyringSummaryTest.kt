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
            val vm = HomeSessionViewModel(HttpProfileRepository(client), settings, HttpOnboardingRepository(client),
                session, auth, owned, photos = HttpOptionalProfileRepository(client)).also { models.put("home", it) }

            // 조회 중에도 매칭 설정을 표시하고, 서버의 READY를 현재 BLE 연결 상태로 해석하지 않는다.
            vm.home()
            runCurrent()
            assertEquals(HomeKeyringState.Checking, vm.state.value.home.keyring)
            assertEquals(true, vm.state.value.home.matchingEnabled)
            reply.complete(ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "test"))))
            runCurrent()
            assertEquals(HomeKeyringState.Registered, vm.state.value.home.keyring)

            reply = CompletableDeferred(ApiResult.Ok(emptyList()))
            vm.home()
            runCurrent()
            assertEquals(HomeKeyringState.Missing, vm.state.value.home.keyring)

            // 이전에 확인된 등록 상태나 빈 목록을 조회 실패 결과로 재사용하지 않는다.
            reply = CompletableDeferred(ApiResult.Fail(AppError.Offline))
            vm.home()
            runCurrent()
            assertEquals(HomeKeyringState.Unknown, vm.state.value.home.keyring)
            assertEquals(HomeLoadState.Ready, vm.state.value.home.load)
            assertEquals(true, vm.state.value.home.matchingEnabled)
        } finally {
            models.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
