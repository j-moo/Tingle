package com.ssafy.e106.features.home

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.ui.AccountMenuLoad
import com.ssafy.e106.features.home.ui.HomeLoadState
import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import com.ssafy.e106.features.keyring.data.*
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

/** 탭을 오가도 받아 둔 홈 · 마이를 지우지 않고, 진행 표시만 내린다. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSessionKeepTest {
    private class FakeSettings : MatchingSettingsRepository {
        var current = SettingsDocument(ConflictValues.Matching(true, false), "\"v1\"")
        val saves = mutableListOf<ConflictValues>()
        var getGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var getFails: Exception? = null
        var getOffline = false
        var saveFails = false
        override suspend fun get(userId: Long, kind: SettingsKind): ApiResult<SettingsDocument> {
            getGate?.await()
            getFails?.let { throw it }
            return if (getOffline) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(current)
        }
        override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> {
            saves += values
            saveGate?.await()
            if (saveFails) return ApiResult.Fail(AppError.Server(412, "PRECONDITION_FAILED", "changed"))
            current = SettingsDocument(values, "\"v2\""); return ApiResult.Ok(current)
        }
    }

    private suspend fun TestScope.withModel(settings: FakeSettings, prefs: HomeTestPrefs = HomeTestPrefs(),
        block: suspend TestScope.(HomeSessionViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val client = createHttpClient("https://test/api/", httpLog = false, engine = MockEngine { error("unused") })
        try {
            val profiles = object : ProfileRepository {
                override suspend fun get(userId: Long) = ApiResult.Ok(ProfileResponse(1, "초옹"))
                override suspend fun nickname(value: String) = error("unused")
                override suspend fun save(userId: Long, fields: kotlinx.serialization.json.JsonObject) = error("unused")
                override suspend fun complete(userId: Long) = error("unused")
                override suspend fun contactEmail(userId: Long) = error("unused")
            }
            val auth = object : AuthRepository {
                override fun available(provider: Provider) = error("unused")
                override suspend fun login(provider: Provider) = error("unused")
                override suspend fun logout() = error("unused")
            }
            val owned = object : OwnedKeyringRepository { override suspend fun list() = ApiResult.Ok(listOf(OwnedKeyring(4, "READY", "1.0"))) }
            val vm = HomeSessionViewModel(profiles, settings, HttpOnboardingRepository(client),
                TestSession(OnboardingStatus.READY), auth, owned, prefs = prefs).also { models.put("home", it) }
            block(vm)
        } finally {
            models.clear(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test fun leaveKeepsHomeAndAccountButClearsProgress() = runTest {
        val settings = FakeSettings()
        withModel(settings) { vm ->
            vm.home(); runCurrent()
            vm.account(); runCurrent()
            settings.getGate = CompletableDeferred()
            vm.refreshHome(); runCurrent()
            assertTrue(vm.state.value.busy)
            assertTrue(vm.state.value.home.refreshing)

            vm.leave()
            val state = vm.state.value
            assertEquals(HomeLoadState.Ready, state.home.load)
            assertEquals(true, state.home.matchingEnabled)
            assertEquals(1, state.home.keyrings.size)
            assertEquals(AccountMenuLoad.Ready, state.account.load)
            assertEquals("초옹", state.account.nickname)
            assertFalse(state.busy)
            assertFalse(state.home.refreshing)
            assertNull(state.guide)
            assertNull(state.error)
        }
    }

    // 다시 들어오면 뼈대 · 위쪽 로딩 없이 보이던 홈을 두고 뒤에서 받는다. 받는 동안 원은 막힌다(busy)
    @Test fun reenteringHomeRefreshesSilently() = runTest {
        val settings = FakeSettings()
        withModel(settings) { vm ->
            vm.home(); runCurrent()
            vm.leave()
            settings.getGate = CompletableDeferred()
            vm.home(); runCurrent()
            assertEquals(HomeLoadState.Ready, vm.state.value.home.load)
            assertFalse(vm.state.value.home.refreshing)
            assertTrue(vm.state.value.busy)
            settings.current = SettingsDocument(ConflictValues.Matching(false, false), "\"v2\"")
            settings.getGate!!.complete(Unit); runCurrent()
            assertEquals(false, vm.state.value.home.matchingEnabled)
            assertFalse(vm.state.value.busy)
        }
    }

    @Test fun silentRefreshExceptionKeepsShownHome() = runTest {
        val settings = FakeSettings()
        withModel(settings) { vm ->
            vm.home(); runCurrent()
            vm.account(); runCurrent()
            settings.getFails = IllegalStateException("broken")
            vm.home(); runCurrent()
            assertEquals(HomeLoadState.Ready, vm.state.value.home.load)
            assertEquals(true, vm.state.value.home.matchingEnabled)
            assertEquals(AccountMenuLoad.Ready, vm.state.value.account.load)
            assertFalse(vm.state.value.busy)
        }
    }

    // 저장 중 탭을 옮겼다가(leave) 저장이 실패해도, 다시 홈에 오면 서버 값이고 원을 다시 누를 수 있다
    @Test fun failedSaveAfterLeaveLeavesCircleUsable() = runTest {
        val settings = FakeSettings()
        withModel(settings) { vm ->
            vm.home(); runCurrent()
            settings.saveGate = CompletableDeferred(); settings.saveFails = true
            vm.setMatching(false); runCurrent()
            vm.leave()
            settings.saveGate!!.complete(Unit); runCurrent()
            assertFalse(vm.state.value.home.savingMatching)

            vm.home(); runCurrent()
            assertEquals(true, vm.state.value.home.matchingEnabled)
            assertFalse(vm.state.value.busy)
            settings.saveGate = null; settings.saveFails = false
            vm.setMatching(false); runCurrent()
            assertEquals(2, settings.saves.size)
            assertEquals(false, vm.state.value.home.matchingEnabled)
        }
    }

    // 원은 누르는 즉시 바뀐다. 실패하면 서버 최신 값을, 그것도 못 받으면 누르기 전 값을 보여 준다
    @Test fun optimisticToggleRevertsToLatestOrPrevious() = runTest {
        val settings = FakeSettings()
        withModel(settings) { vm ->
            vm.home(); runCurrent()
            settings.saveGate = CompletableDeferred(); settings.saveFails = true
            vm.setMatching(false); runCurrent()
            assertEquals(false, vm.state.value.home.matchingEnabled)
            assertTrue(vm.state.value.home.savingMatching)

            // 다른 곳에서 먼저 바뀜: 누른 값(꺼짐)이 아니라 서버 최신 값(켜짐, 사진 조건 바뀜)을 보여 준다
            settings.current = SettingsDocument(ConflictValues.Matching(true, true), "\"v3\"")
            settings.saveGate!!.complete(Unit); runCurrent()
            assertTrue(vm.state.value.home.matchingSaveFailed)
            assertEquals(true, vm.state.value.home.matchingEnabled)

            // 최신 값도 못 받음: 누르기 전 값(켜짐)으로 돌아간다. 저장은 받아 둔 최신 값(사진 조건 켜짐)에 맞춰 보냈다
            settings.saveGate = CompletableDeferred(); settings.getOffline = true
            vm.setMatching(false); runCurrent()
            assertEquals(false, vm.state.value.home.matchingEnabled)
            assertEquals(ConflictValues.Matching(false, true), settings.saves.last())
            settings.saveGate!!.complete(Unit); runCurrent()
            assertTrue(vm.state.value.home.matchingSaveFailed)
            assertEquals(true, vm.state.value.home.matchingEnabled)
            assertFalse(vm.state.value.home.savingMatching)
        }
    }
}
