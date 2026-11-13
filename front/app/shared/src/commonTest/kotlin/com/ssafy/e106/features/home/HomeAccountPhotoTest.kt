package com.ssafy.e106.features.home

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.ui.AccountMenuLoad
import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.HttpOnboardingRepository
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** 내 정보 화면의 프로필 사진: 메뉴를 먼저 보여 주고, 받는 동안 로그아웃을 막지 않으며, 실패해도 메뉴는 그대로다. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAccountPhotoTest {
    private class Photos : OptionalProfileRepository {
        var calls = 0
        var reply = CompletableDeferred<ApiResult<ByteArray>>()
        override suspend fun photo(userId: Long): ApiResult<ByteArray> { calls++; return reply.await() }
        override suspend fun documents() = error("unexpected")
        override suspend fun consents(userId: Long) = error("unexpected")
        override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest) = error("unexpected")
        override suspend fun upload(userId: Long, jpeg: ByteArray) = error("unexpected")
        override suspend fun deletePhoto(userId: Long) = error("unexpected")
    }

    @Test fun menuIsReadyAndLogoutWorksWhilePhotoLoadsAndFailureKeepsMenu() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine { error("unexpected http") })
        try {
            val session = TestSession(OnboardingStatus.READY)
            val user = (session.state.value as Session.SignedIn).userId
            var hasPhoto = true
            var logouts = 0
            val profiles = object : ProfileRepository {
                override suspend fun get(userId: Long) = ApiResult.Ok(ProfileResponse(user, "하늘", hasProfilePhoto = hasPhoto))
                override suspend fun nickname(value: String) = error("unexpected")
                override suspend fun save(userId: Long, fields: JsonObject) = error("unexpected")
                override suspend fun complete(userId: Long) = error("unexpected")
            }
            val auth = object : AuthRepository {
                override fun available(provider: Provider) = error("unexpected")
                override suspend fun login(provider: Provider) = error("unexpected")
                override suspend fun logout() { logouts++ }
            }
            val settings = object : MatchingSettingsRepository {
                override suspend fun get(userId: Long, kind: SettingsKind) = error("unexpected")
                override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String) = error("unexpected")
            }
            val owned = object : OwnedKeyringRepository { override suspend fun list() = error("unexpected") }
            val photos = Photos()
            val vm = HomeSessionViewModel(profiles, settings, HttpOnboardingRepository(client), session, auth, owned, photos = photos, prefs = HomeTestPrefs())
                .also { models.put("home", it) }

            // 사진 응답 전에 메뉴가 준비되고 busy 가 풀려 있어 로그아웃이 무시되지 않는다
            vm.account(); runCurrent()
            assertEquals(1, photos.calls)
            assertEquals(AccountMenuLoad.Ready, vm.state.value.account.load)
            assertEquals("하늘", vm.state.value.account.nickname)
            assertTrue(vm.state.value.account.hasPhoto); assertFalse(vm.state.value.busy)
            vm.logout(); runCurrent(); assertEquals(1, logouts)

            // 사진을 받다가 예외가 나도 메뉴는 그대로(기본 사진), 오류 안내도 띄우지 않는다
            photos.reply.completeExceptionally(IllegalStateException("photo")); runCurrent()
            assertEquals(AccountMenuLoad.Ready, vm.state.value.account.load)
            assertNull(vm.state.value.account.photo); assertNull(vm.state.value.error)

            // 사진이 없는 계정은 사진을 요청하지 않는다
            hasPhoto = false; vm.account(); runCurrent()
            assertEquals(1, photos.calls); assertFalse(vm.state.value.account.hasPhoto)

            // 받는 중에 화면을 떠나면 늦게 온 사진을 반영하지 않는다
            hasPhoto = true; photos.reply = CompletableDeferred(); vm.account(); runCurrent(); assertEquals(2, photos.calls)
            vm.leave(); photos.reply.complete(ApiResult.Fail(AppError.Offline)); runCurrent()
            assertEquals(AccountMenuLoad.Loading, vm.state.value.account.load); assertNull(vm.state.value.account.nickname)
        } finally {
            models.clear(); client.close(); Dispatchers.resetMain()
        }
    }
}
