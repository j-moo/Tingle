package com.ssafy.e106.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Profile(val nickname: String)

private val ProfileKey = PrefKey("profile", Profile.serializer())

/** 테스트용 메모리 DataStore. 실제 파일 대신 */
private class MemoryDataStore : DataStore<Preferences> {
    private val lock = Mutex()
    val state = MutableStateFlow(emptyPreferences())
    var broken = false                                   // 디스크 오류 흉내
    var editGate: CompletableDeferred<Unit>? = null      // 정하면 다음 쓰기 한 번이 잠금을 잡은 채 그 동안 멈춘다(느린 디스크)
    override val data = state.map { check(!broken) { "디스크 오류" }; it }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = lock.withLock {
        check(!broken) { "디스크 오류" }
        editGate?.let { gate -> editGate = null; gate.await() }
        transform(state.value).also { state.value = it }
    }
    fun keys() = state.value.asMap().keys.map { it.name }.toSet()
}

private fun signedIn(userId: Long) = Session.SignedIn(userId, OnboardingStatus.READY, signupCompleted = true)

@OptIn(ExperimentalCoroutinesApi::class)
private class PrefsHarness(scope: TestScope, start: Session = signedIn(7)) {
    val session = MutableStateFlow(start)
    val store = MemoryDataStore()
    val prefs = DataStoreAppPrefs(store, session, scope.backgroundScope)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppPrefsTest {
    @Test
    fun valueIsPerUser() = runTest {
        val h = PrefsHarness(this)
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        assertEquals(Profile("a"), h.prefs.flow(ProfileKey).first())
        assertEquals(setOf("7:profile"), h.store.keys())
    }

    @Test
    fun lateResponseForOtherUserIsIgnored() = runTest {
        val h = PrefsHarness(this)
        runCurrent()                                         // 시작 정리가 먼저 끝나게 — 정리가 8 키를 대신 지워 주면 이 검사가 의미 없다
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        h.prefs.set(ProfileKey, Profile("late"), forUser = 8)   // 이전 계정(8)의 늦은 응답
        assertEquals(Profile("a"), h.prefs.flow(ProfileKey).first())
        assertEquals(setOf("7:profile"), h.store.keys())
    }

    @Test
    fun signOutWhileWriteIsQueuedDropsTheWrite() = runTest {
        val h = PrefsHarness(this)
        runCurrent()                                         // 시작 정리 끝
        val gate = CompletableDeferred<Unit>()
        h.store.editGate = gate
        val write = launch { h.prefs.set(ProfileKey, Profile("late"), forUser = 7) }   // 7 로 들어왔지만 디스크 쓰기를 기다린다
        runCurrent()
        h.session.value = Session.SignedOut                  // 그 사이 로그아웃
        runCurrent()
        gate.complete(Unit)
        write.join()
        runCurrent()
        assertTrue(h.store.keys().isEmpty(), "쓰기 직전에 다시 확인해 로그아웃된 계정 값을 남기지 않는다")
    }

    @Test
    fun nullRemoves() = runTest {
        val h = PrefsHarness(this)
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        h.prefs.set(ProfileKey, null, forUser = 7)
        assertNull(h.prefs.flow(ProfileKey).first())
        assertTrue(h.store.keys().isEmpty())
    }

    @Test
    fun signedOutReadsNullAndClearsDisk() = runTest {
        val h = PrefsHarness(this)
        runCurrent()
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        h.store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("device:installationId")] = "x" } }
        h.session.value = Session.SignedOut                  // 로그아웃 · 만료
        assertNull(h.prefs.flow(ProfileKey).first())
        runCurrent()
        assertEquals(setOf("device:installationId"), h.store.keys(), "계정 키만 지우고 계정 무관 키는 남긴다")
        h.prefs.set(ProfileKey, Profile("b"), forUser = 7)   // 로그아웃 뒤 도착한 응답
        assertEquals(setOf("device:installationId"), h.store.keys())
    }

    @Test
    fun switchingAccountClearsPreviousAndHidesIt() = runTest {
        val h = PrefsHarness(this)
        runCurrent()
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        h.session.value = signedIn(8)                        // 로그아웃 없이 다른 계정(앱이 지우기 전에 죽은 뒤 등)
        assertNull(h.prefs.flow(ProfileKey).first(), "다른 계정 값이 보이지 않는다")
        runCurrent()
        assertTrue(h.store.keys().isEmpty())
    }

    @Test
    fun leftoverIsClearedOnStartWhenSignedOut() = runTest {
        val h = PrefsHarness(this, start = Session.Restoring)
        h.store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("7:profile")] = """{"nickname":"a"}""" } }
        runCurrent()
        assertEquals(setOf("7:profile"), h.store.keys(), "복원 중에는 누구인지 몰라 지우지 않는다")
        h.session.value = Session.SignedOut                  // 지우기 전에 앱이 죽었던 경우
        runCurrent()
        assertTrue(h.store.keys().isEmpty())
    }

    @Test
    fun unreadableValueIsNull() = runTest {
        val h = PrefsHarness(this)
        h.store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("7:profile")] = """{"old":1}""" } }
        assertNull(h.prefs.flow(ProfileKey).first(), "앱 업데이트로 모양이 바뀐 값은 없는 것으로")
    }

    @Test
    fun diskErrorIsNotThrownToScreens() = runTest {
        val h = PrefsHarness(this)
        h.store.broken = true
        assertNull(h.prefs.flow(ProfileKey).first(), "못 읽으면 캐시 없음")
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)   // 예외 없이 지나간다
    }

    @Test
    fun collectingScreenFollowsAccountChanges() = runTest {
        val h = PrefsHarness(this)
        val seen = mutableListOf<Profile?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.prefs.flow(ProfileKey).collect { seen += it } }   // 화면이 계속 보고 있다
        runCurrent()
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7); runCurrent()
        h.session.value = signedIn(8); runCurrent()
        h.prefs.set(ProfileKey, Profile("b"), forUser = 8); runCurrent()
        h.session.value = Session.SignedOut; runCurrent()
        assertEquals(listOf(null, Profile("a"), null, Profile("b"), null), seen, "계정이 바뀌면 이전 값이 다시 보이지 않는다")
    }

    @Test
    fun sameUserStepChangeDoesNotFlicker() = runTest {
        val h = PrefsHarness(this, start = Session.SignedIn(7, OnboardingStatus.PROFILE_REQUIRED, signupCompleted = false))
        val seen = mutableListOf<Profile?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.prefs.flow(ProfileKey).collect { seen += it } }
        runCurrent()
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7); runCurrent()
        h.session.value = signedIn(7); runCurrent()          // 같은 계정, 단계만 READY 로
        assertEquals(listOf(null, Profile("a")), seen, "단계가 바뀌어도 캐시가 null 로 깜빡이지 않는다")
        assertEquals(setOf("7:profile"), h.store.keys())
    }

    @Test
    fun readErrorRecoversWithoutResubscribing() = runTest {
        val h = PrefsHarness(this)
        val seen = mutableListOf<Profile?>()
        h.store.broken = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.prefs.flow(ProfileKey).collect { seen += it } }
        runCurrent()
        h.store.broken = false
        h.prefs.set(ProfileKey, Profile("a"), forUser = 7)
        advanceTimeBy(2_000); runCurrent()                   // 잠시 뒤 다시 구독
        assertEquals(Profile("a"), seen.last(), "한 번 못 읽었다고 그 계정 동안 계속 null 이 아니다")
    }
}
