package com.ssafy.e106.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ssafy.e106.core.network.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 토큰을 Android Keystore 의 AES-GCM 키로 암호화해 SharedPreferences 에 둔다. 키는 Keystore 밖으로 나오지 않는다.
 * 서버 주소마다 따로 저장해 dev · prod 토큰이 섞이지 않는다. 백업으로 다른 기기에 옮겨져도 키가 없어 복호화 실패 → 지우고 로그아웃 상태.
 * (179 브랜치 AndroidSessionStore 를 가져와 이름 · 실패 처리를 계약에 맞췄다)
 */
internal class AndroidTokenStore(
    context: Context,
    apiBase: String,
    private val beforeDecrypt: () -> Unit = {},          // 기기 테스트가 일시적인 Keystore 오류를 흉내 낼 자리. 앱은 쓰지 않는다
) : TokenStore {
    /** Keystore 키 이름이자 SharedPreferences 파일 이름 */
    internal val alias = "tingle.tokens." + MessageDigest.getInstance("SHA-256").digest(apiBase.trimEnd('/').toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val prefs = context.applicationContext.getSharedPreferences(alias, Context.MODE_PRIVATE)
    private val lock = Mutex()

    override suspend fun load(): StoredTokens? = io {
        val saved = prefs.getString(KEY, null) ?: return@io null
        runCatching { ApiJson.decodeFromString(StoredTokens.serializer(), decrypt(saved)) }.getOrElse {
            // 일시적인 Keystore 오류면 지우지 않고 다음 실행에 다시 읽는다. 깨졌으면 값과 키를 함께 지워 다음 save 가 새 키로 시작한다
            if (isBroken(it)) {
                write(null)
                runCatching { keyStore().deleteEntry(alias) }
            }
            null
        }
    }

    override suspend fun save(tokens: StoredTokens) = io { write(encrypt(ApiJson.encodeToString(StoredTokens.serializer(), tokens))) }

    override suspend fun clear() = io { write(null) }

    // 같은 키로 암호화한다. 깨졌으면 값만 버린다 — Keystore 키까지 지우면 토큰도 못 읽게 된다
    // 토큰이 깨져 Keystore 키를 지우면(load) 이 목록도 못 읽어 버려진다. 드문 경우라 받아들인다
    override suspend fun pendingLogouts(): List<String> = io { runCatching { readLogouts() }.getOrDefault(emptyList()) }

    // 읽기 · 쓰기를 같은 잠금(io) 안에서 해서 동시에 더하고 빼도 서로 덮지 않는다. 읽기가 일시 오류면 던져서 쓰지 않는다(목록을 빈 것으로 덮지 않게)
    override suspend fun addPendingLogout(refreshToken: String) = io { writeLogouts((readLogouts() - refreshToken + refreshToken).takeLast(MAX_PENDING_LOGOUTS)) }

    override suspend fun removePendingLogout(refreshToken: String) = io { writeLogouts(readLogouts() - refreshToken) }

    /** 깨진 값이면 지우고 빈 목록. 일시적인 Keystore 오류는 그대로 던진다(load 와 같은 원칙: 다음에 다시 읽는다) */
    private fun readLogouts(): List<String> {
        val saved = prefs.getString(LOGOUT_KEY, null) ?: return emptyList()
        return runCatching { ApiJson.decodeFromString(LOGOUTS, decrypt(saved)) }
            .getOrElse { if (isBroken(it)) emptyList<String>().also { write(null, LOGOUT_KEY) } else throw it }
    }

    private fun writeLogouts(tokens: List<String>) = write(tokens.takeIf { it.isNotEmpty() }?.let { encrypt(ApiJson.encodeToString(LOGOUTS, it)) }, LOGOUT_KEY)

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** commit 으로 디스크에 쓴 뒤 돌아온다. 회전된 refreshToken 을 못 쓰고 앱이 죽으면 다음 재발급이 재사용 감지에 걸린다 */
    private fun write(value: String?, key: String = KEY) {
        check(prefs.edit().putString(key, value).commit()) { "토큰 저장 실패" }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return b64(cipher.iv) + "." + b64(cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(saved: String): String {
        beforeDecrypt()
        val (iv, body) = saved.split('.')
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv))) }
        return cipher.doFinal(unb64(body)).decodeToString()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val keys = keyStore()
        // 키 항목이 망가졌으면(OS 업데이트 뒤 등) 지우고 새로 만든다. 안 그러면 다시 로그인해도 save 가 계속 실패한다
        val existing = try {
            keys.getKey(alias, null)
        } catch (e: UnrecoverableKeyException) {
            keys.deleteEntry(alias)
            null
        }
        (existing as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    internal companion object {
        /** 다시 읽어도 소용없는 값: 키가 바뀜(재설치 · 백업 복원) · 키 무효화 · IV 가 깨짐 · base64/JSON 이 깨짐(SerializationException 포함) · "iv.body" 모양이 아님 */
        fun isBroken(e: Throwable) = e is AEADBadTagException || e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException ||
            e is InvalidAlgorithmParameterException || e is IllegalArgumentException || e is IndexOutOfBoundsException

        const val KEY = "tokens"
        const val LOGOUT_KEY = "pendingLogout"
        val LOGOUTS = ListSerializer(String.serializer())
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
