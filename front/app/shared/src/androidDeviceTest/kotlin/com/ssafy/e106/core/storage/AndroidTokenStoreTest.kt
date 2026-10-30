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
    fun tearDown() = runTest { store.clear() }

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
}

private fun Context.getSharedPreferencesFiles() = java.io.File(applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().toList()
