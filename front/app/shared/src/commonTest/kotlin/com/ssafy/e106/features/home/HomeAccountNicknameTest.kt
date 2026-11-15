package com.ssafy.e106.features.home

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.ui.AccountMenuLoad
import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import com.ssafy.e106.features.keyring.data.OwnedKeyringRepository
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.HttpOnboardingRepository
import com.ssafy.e106.features.profile.data.ProfileRepository
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 마이를 다시 열 때 받아 둔 닉네임을 지우고 뼈대로 돌아가지 않는다. 다시 받기 실패도 실패 화면으로 바꾸지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAccountNicknameTest {
    @Test fun reopeningKeepsShownNicknameWhileRefreshing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine { error("unused") })
        try {
            var reply = CompletableDeferred<ApiResult<ProfileResponse>>()
            val profiles = object : ProfileRepository {
                override suspend fun get(userId: Long) = reply.await()
                override suspend fun nickname(value: String) = error("unused")
                override suspend fun save(userId: Long, fields: kotlinx.serialization.json.JsonObject) = error("unused")
                override suspend fun complete(userId: Long) = error("unused")
            }
            // 마이 닉네임 조회에는 쓰이지 않는 연결들. 불리면 실패한다
            val settings = object : MatchingSettingsRepository {
                override suspend fun get(userId: Long, kind: SettingsKind) = error("unused")
                override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String) = error("unused")
            }
            val auth = object : AuthRepository {
                override fun available(provider: Provider) = error("unused")
                override suspend fun login(provider: Provider) = error("unused")
                override suspend fun logout() = error("unused")
            }
            val owned = object : OwnedKeyringRepository { override suspend fun list() = error("unused") }
            val vm = HomeSessionViewModel(profiles, settings, HttpOnboardingRepository(client),
                TestSession(OnboardingStatus.READY), auth, owned, prefs = HomeTestPrefs()).also { models.put("home", it) }

            // 처음 열 때만 뼈대
            vm.account(); runCurrent()
            assertEquals(AccountMenuLoad.Loading, vm.state.value.account.load)
            reply.complete(ApiResult.Ok(ProfileResponse(1, "초옹"))); runCurrent()
            assertEquals("초옹", vm.state.value.account.nickname)

            // 다시 열면 받는 동안에도 닉네임이 그대로, 바뀐 값이 오면 갈아 끼운다
            reply = CompletableDeferred()
            vm.account(); runCurrent()
            assertEquals(AccountMenuLoad.Ready, vm.state.value.account.load)
            assertEquals("초옹", vm.state.value.account.nickname)
            reply.complete(ApiResult.Ok(ProfileResponse(1, "새닉네임"))); runCurrent()
            assertEquals("새닉네임", vm.state.value.account.nickname)

            // 다시 받기 실패: 보이던 닉네임 유지
            reply = CompletableDeferred(ApiResult.Fail(AppError.Offline))
            vm.account(); runCurrent()
            assertEquals(AccountMenuLoad.Ready, vm.state.value.account.load)
            assertEquals("새닉네임", vm.state.value.account.nickname)
        } finally {
            models.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
