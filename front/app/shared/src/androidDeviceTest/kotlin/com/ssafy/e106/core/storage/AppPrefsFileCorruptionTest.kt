package com.ssafy.e106.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.di.androidPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
private data class Pending(val idempotencyKey: String)

/**
 * 실제 DataStore 파일을 깨뜨려 본다(앱과 같은 androidPrefsStore). ./gradlew :shared:connectedAndroidDeviceTest
 * 캐시 파일은 깨지면 비우고 시작하지만, 복구용 기록(durable) 파일은 비우지 않고 Corrupt 로 알려야 새 요청으로 덮어쓰지 않는다
 */
@RunWith(AndroidJUnit4::class)
class AppPrefsFileCorruptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheName = "test_cache.preferences_pb"
    private val durableName = "test_durable.preferences_pb"
    private val pendingKey = PrefKey("report.pending.1", Pending.serializer(), durable = true)
    private val cacheKey = PrefKey("profile", Pending.serializer())
    private val session = MutableStateFlow<Session>(Session.SignedIn(7, OnboardingStatus.READY, signupCompleted = true))
    private val scopes = mutableListOf<CoroutineScope>()

    // 같은 파일에 DataStore 는 한 번에 하나라서, 앞 인스턴스의 scope 를 닫고 끝날 때까지 기다린 뒤 새로 연다(앱 재시작과 같다)
    private suspend fun open(): AppPrefs {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }; scopes.clear()
        val io = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreAppPrefs(androidPrefsStore(context, cacheName, resetOnCorruption = true, io), androidPrefsStore(context, durableName, resetOnCorruption = false, io), session, io)
    }

    private val garbage = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x13)
    private fun corrupt(name: String) = context.filesDir.resolve(name).writeBytes(garbage)

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        listOf(cacheName, durableName).forEach { context.filesDir.resolve(it).delete() }
    }

    @Test
    fun corruptDurableFileIsReportedAndKept() = runTest {
        open().set(pendingKey, Pending("req-1"), forUser = 7)
        corrupt(durableName)
        val prefs = open()
        assertEquals(PrefRead.Corrupt, prefs.read(pendingKey, forUser = 7), "깨진 파일을 '기록 없음'(Missing)으로 보이지 않는다")
        prefs.set(pendingKey, Pending("req-2"), forUser = 7)                 // 새 요청으로 덮어쓰기 시도
        assertEquals(PrefRead.Corrupt, prefs.read(pendingKey, forUser = 7), "다시 읽어도 같다")
        assertContentEquals(garbage, context.filesDir.resolve(durableName).readBytes(), "기반이 깨진 파일을 비우거나 덮어쓰지 않는다")
    }

    @Test
    fun corruptCacheFileStillResetsAndDurableRecordSurvives() = runTest {
        open().apply { set(pendingKey, Pending("req-1"), forUser = 7); set(cacheKey, Pending("cache"), forUser = 7) }
        corrupt(cacheName)
        val prefs = open()
        assertNull(prefs.flow(cacheKey).first(), "캐시 파일은 깨지면 비우고 시작(기존 동작)")
        assertEquals(PrefRead.Missing, prefs.read(cacheKey, forUser = 7))
        assertEquals(PrefRead.Found(Pending("req-1")), prefs.read(pendingKey, forUser = 7), "캐시 파일이 깨져도 복구용 기록은 다른 파일이라 남는다")
    }

    @Test
    fun durableRecordSurvivesRestart() = runTest {
        open().set(pendingKey, Pending("req-1"), forUser = 7)
        assertEquals(PrefRead.Found(Pending("req-1")), open().read(pendingKey, forUser = 7))
    }
}
