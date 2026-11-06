package com.ssafy.e106.features.auth.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.LoginResult
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Provider
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.network.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocialLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(auth: ControlledAuth, prefs: DataStore<Preferences>? = null): SocialLoginViewModel =
        SocialLoginViewModel(auth, prefs).also { store.put("login", it) }

    @Test fun savedLastProviderIsShownOnOpen() = runTest(dispatcher) {
        val vm = viewModel(ControlledAuth(), MemoryPrefs(preferencesOf(LAST_PROVIDER to "Kakao")))
        runCurrent()
        assertEquals(LoginProvider.Kakao, vm.state.value.lastProvider)
    }

    @Test fun successSavesLastProviderButCancelDoesNot() = runTest(dispatcher) {
        val prefs = MemoryPrefs()
        val auth = ControlledAuth()
        val vm = viewModel(auth, prefs)
        vm.login(LoginProvider.Kakao)
        auth.answer.complete(LoginResult.Cancelled)
        runCurrent()
        assertNull(prefs.value[LAST_PROVIDER])
        auth.answer = CompletableDeferred()
        vm.login(LoginProvider.Google)
        auth.answer.complete(LoginResult.Success(Session.SignedIn(1, OnboardingStatus.READY, true)))
        runCurrent()
        assertEquals("Google", prefs.value[LAST_PROVIDER])
        assertEquals(LoginProvider.Google, vm.state.value.lastProvider)
    }

    @Test fun unavailableProviderIsHiddenAndCannotStart() = runTest(dispatcher) {
        val auth = ControlledAuth(setOf(Provider.GOOGLE))
        val vm = viewModel(auth)
        assertEquals(setOf(LoginProvider.Google), vm.state.value.availableProviders)
        vm.login(LoginProvider.Naver)
        assertTrue(auth.calls.isEmpty())
        assertNull(vm.state.value.busyProvider)
    }

    @Test fun rapidClicksIncludingOtherProviderStartOnlyOneRequest() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Kakao)
        vm.login(LoginProvider.Kakao)
        vm.login(LoginProvider.Google)
        runCurrent()
        assertEquals(listOf(Provider.KAKAO), auth.calls)
        assertEquals(LoginProvider.Kakao, vm.state.value.busyProvider)
    }

    @Test fun userCancellationClearsLoadingWithoutErrorAndAllowsRetry() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Google)
        auth.answer.complete(LoginResult.Cancelled)
        runCurrent()
        assertNull(vm.state.value.busyProvider)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.completed)
        auth.answer = CompletableDeferred()
        vm.login(LoginProvider.Kakao)
        assertEquals(listOf(Provider.GOOGLE, Provider.KAKAO), auth.calls)
    }

    @Test fun failureShowsRepositoryMessageAndRetryClearsIt() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Kakao)
        auth.answer.complete(LoginResult.Failed(AppError.Server(502, "SOCIAL_PROVIDER_ERROR", "다시 시도해 주세요")))
        runCurrent()
        assertNull(vm.state.value.busyProvider)
        assertEquals("다시 시도해 주세요", vm.state.value.errorMessage)
        auth.answer = CompletableDeferred()
        vm.login(LoginProvider.Google)
        assertNull(vm.state.value.errorMessage)
        assertEquals(LoginProvider.Google, vm.state.value.busyProvider)
    }

    @Test fun coroutineCancellationIsNotDisplayedAsLoginFailure() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Google)
        vm.viewModelScope.coroutineContext.cancelChildren()
        runCurrent()
        assertTrue(auth.cancelled)
        assertNull(vm.state.value.busyProvider)
        assertNull(vm.state.value.errorMessage)
        vm.login(LoginProvider.Kakao)
        assertEquals(2, auth.calls.size)
    }

    @Test fun clearingViewModelCancelsThePendingLogin() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Google)
        store.clear()
        runCurrent()
        assertTrue(auth.cancelled)
        assertNull(vm.state.value.busyProvider)
    }

    @Test fun successBlocksAnotherLoginUntilSessionScreenTakesOver() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Kakao)
        auth.answer.complete(LoginResult.Success(Session.SignedIn(1, OnboardingStatus.AGREEMENT_REQUIRED, false)))
        runCurrent()
        assertTrue(vm.state.value.completed)
        assertNull(vm.state.value.busyProvider)
        vm.login(LoginProvider.Google)
        assertEquals(1, auth.calls.size)
        vm.prepareForLogin() // 세션 만료 후 같은 Activity에서 로그인 화면 재진입
        auth.answer = CompletableDeferred()
        vm.login(LoginProvider.Google)
        assertEquals(2, auth.calls.size)
    }

    @Test fun reenteringDuringRotationDoesNotUnlockPendingRequest() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Google)
        vm.prepareForLogin()
        vm.login(LoginProvider.Kakao)
        assertEquals(LoginProvider.Google, vm.state.value.busyProvider)
        assertEquals(1, auth.calls.size)
    }

    @Test fun acknowledgingOldErrorDoesNotEraseNewFailure() = runTest(dispatcher) {
        val auth = ControlledAuth()
        val vm = viewModel(auth)
        vm.login(LoginProvider.Google)
        auth.answer.complete(LoginResult.Failed(AppError.Offline))
        runCurrent()
        val oldId = vm.state.value.errorId
        auth.answer = CompletableDeferred()
        vm.login(LoginProvider.Google)
        auth.answer.complete(LoginResult.Failed(AppError.Offline))
        runCurrent()
        vm.dismissError(oldId)
        assertEquals(AppError.Offline.message, vm.state.value.errorMessage)
        vm.dismissError(vm.state.value.errorId)
        assertNull(vm.state.value.errorMessage)
    }
}

/** 테스트 소스에서만 사용하는 제어용 인증 객체. 앱에는 등록하지 않는다. */
/** 메모리 DataStore — 디스크 없이 저장 · 읽기만 흉내 */
private class MemoryPrefs(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val flow = MutableStateFlow(initial)
    val value get() = flow.value
    override val data: Flow<Preferences> = flow
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(flow.value).also { flow.value = it }
}

private class ControlledAuth(private val providers: Set<Provider> = setOf(Provider.GOOGLE, Provider.KAKAO)) : AuthRepository {
    val calls = mutableListOf<Provider>()
    var answer = CompletableDeferred<LoginResult>()
    var cancelled = false
    override fun available(provider: Provider) = provider in providers
    override suspend fun login(provider: Provider): LoginResult {
        calls += provider
        return try { answer.await() } catch (e: CancellationException) { cancelled = true; throw e }
    }
    override suspend fun logout() = Unit
}
