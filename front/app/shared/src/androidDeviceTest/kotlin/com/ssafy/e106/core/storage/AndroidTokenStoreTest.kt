package com.ssafy.e106.core.storage

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ssafy.e106.core.auth.OnboardingStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 실제 Android Keystore 로 암호화 · 복호화. ./gradlew :shared:connectedAndroidDeviceTest */
@RunWith(AndroidJUnit4::class)
class AndroidTokenStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val apiBase = "https://test.invalid/api/"
    private val store = AndroidTokenStore(context, apiBase)
    private val tokens = StoredTokens(
        "access", "refresh", "Bearer", Instant.parse("2026-10-29T01:00:00Z"), Instant.parse("2026-11-12T00:30:00Z"),
        OnboardingStatus.PROFILE_REQUIRED, 42L, signupCompleted = false,
    )

    private fun prefs() = context.getSharedPreferences(store.alias, Context.MODE_PRIVATE)

    @After
    fun tearDown() = runTest { store.clear(); store.pendingLogouts().forEach { store.removePendingLogout(it) } }

    @Test
    fun saveLoadClear() = runTest {
        assertNull(store.load())
        store.save(tokens)
        assertEquals(tokens, AndroidTokenStore(context, apiBase).load())   // 새 인스턴스(앱 재시작)에서도 읽힌다
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun storedEncrypted() = runTest {
        store.save(tokens.copy(accessToken = "ACCESS-SENTINEL", refreshToken = "REFRESH-SENTINEL"))
        val saved = assertNotNull(prefs().getString("tokens", null))
        assertEquals(12, Base64.decode(saved.substringBefore('.'), Base64.NO_WRAP).size)   // "iv.body", GCM IV 12바이트
        val raw = saved + context.getSharedPreferencesFiles().joinToString { it.readText() }
        assertFalse("SENTINEL" in raw || "refreshToken" in raw)                          // 토큰 값도 JSON 도 평문으로 없다
    }

    @Test
    fun rotationOverwritesWithFreshIv() = runTest {
        store.save(tokens)
        val first = prefs().getString("tokens", null)
        store.save(tokens)
        assertNotEquals(first?.substringBefore('.'), prefs().getString("tokens", null)?.substringBefore('.'))   // 같은 평문도 IV 는 매번 새로
        store.save(tokens.copy(refreshToken = "r2"))
        assertEquals("r2", AndroidTokenStore(context, apiBase).load()?.refreshToken)
    }

    @Test
    fun keyRemovedClearsValueAndRecovers() = runTest {
        store.save(tokens)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(store.alias)   // 재설치 · 백업 복원: 값은 있는데 키가 없다
        assertNull(AndroidTokenStore(context, apiBase).load())
        assertNull(prefs().getString("tokens", null))
        store.save(tokens)                                                                   // 새 키로 다시 쓰고 읽힌다
        assertEquals(tokens, AndroidTokenStore(context, apiBase).load())
    }

    @Test
    fun otherServerSeparated() = runTest {
        store.save(tokens)
        assertNull(AndroidTokenStore(context, "https://other.invalid/api/").load())
    }

    @Test
    fun brokenValueCleared() = runTest {
        store.save(tokens)
        prefs().edit().putString("tokens", "AAAA.BBBB").commit()
        assertNull(store.load())
        assertNull(prefs().getString("tokens", null))
    }

    @Test
    fun onlyBrokenValuesAreCleared() {
        assertTrue(AndroidTokenStore.isBroken(AEADBadTagException()))                 // 키가 바뀜
        assertTrue(AndroidTokenStore.isBroken(SerializationException("shape")))      // StoredTokens 모양이 바뀜
        assertFalse(AndroidTokenStore.isBroken(KeyStoreException("busy")))           // 부팅 직후 등 일시적 오류: 토큰 유지
        assertFalse(AndroidTokenStore.isBroken(ProviderException("busy")))
    }

    @Test
    fun pendingLogoutsEncryptedAndSurviveClear() = runTest {
        store.save(tokens)
        store.addPendingLogout("PENDING-A")
        store.addPendingLogout("PENDING-B")
        store.clear()                                                                       // 로그아웃: 토큰은 지워도 보낼 로그아웃은 남는다
        assertNull(store.load())
        assertEquals(listOf("PENDING-A", "PENDING-B"), AndroidTokenStore(context, apiBase).pendingLogouts())
        assertFalse("PENDING" in context.getSharedPreferencesFiles().joinToString { it.readText() })
        store.removePendingLogout("PENDING-A")
        assertEquals(listOf("PENDING-B"), store.pendingLogouts(), "보낸 것만 뺀다")
    }

    @Test
    fun brokenPendingLogoutDroppedWithoutLosingTokens() = runTest {
        store.save(tokens)
        prefs().edit().putString("pendingLogout", "AAAA.BBBB").commit()
        assertEquals(emptyList(), store.pendingLogouts())
        assertNull(prefs().getString("pendingLogout", null))
        assertEquals(tokens, store.load(), "로그아웃 값이 깨져도 Keystore 키를 지우지 않아 토큰은 그대로")
    }

    @Test
    fun pendingLogoutsCappedNewestKeptNoDuplicates() = runTest {
        (0..6).forEach { store.addPendingLogout("r$it") }
        store.addPendingLogout("r3")                                                        // 이미 있는 것을 다시 — 맨 뒤로
        assertEquals(listOf("r2", "r4", "r5", "r6", "r3"), AndroidTokenStore(context, apiBase).pendingLogouts())
    }

    @Test
    fun transientKeystoreErrorDoesNotWipePendingLogouts() = runTest {
        store.addPendingLogout("rA")
        store.addPendingLogout("rB")
        var fail = true
        val flaky = AndroidTokenStore(context, apiBase, beforeDecrypt = { if (fail) throw KeyStoreException("busy") })   // 부팅 직후 같은 일시 오류
        assertTrue(runCatching { flaky.removePendingLogout("rA") }.isFailure, "읽지 못했으면 쓰지 않는다")
        assertTrue(runCatching { flaky.addPendingLogout("rC") }.isFailure)
        assertEquals(emptyList(), flaky.pendingLogouts(), "조회는 빈 목록으로 보이지만")
        fail = false
        assertEquals(listOf("rA", "rB"), store.pendingLogouts(), "디스크의 목록은 그대로")
    }
}

private fun Context.getSharedPreferencesFiles() = java.io.File(applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().toList()
