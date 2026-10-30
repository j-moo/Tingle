package com.ssafy.e106.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ssafy.e106.core.network.ApiJson
import kotlinx.coroutines.Dispatchers
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
internal class AndroidTokenStore(context: Context, apiBase: String) : TokenStore {
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

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** commit 으로 디스크에 쓴 뒤 돌아온다. 회전된 refreshToken 을 못 쓰고 앱이 죽으면 다음 재발급이 재사용 감지에 걸린다 */
    private fun write(value: String?) {
        check(prefs.edit().putString(KEY, value).commit()) { "토큰 저장 실패" }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return b64(cipher.iv) + "." + b64(cipher.doFinal(plain.toByteArray()))
    }

    private fun decrypt(saved: String): String {
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
