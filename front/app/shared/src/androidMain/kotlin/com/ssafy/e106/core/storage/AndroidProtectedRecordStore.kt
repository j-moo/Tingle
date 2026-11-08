package com.ssafy.e106.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore AES-GCM + noBackupFilesDir의 원자 파일. 서버/운영·개발별 키와 파일이 분리된다.
 * 토큰/캐시 정리가 이 키를 삭제하지 않으므로 탈퇴 직후에도 영수증으로 결과를 조회할 수 있다.
 * 앱 삭제/기기 키 유실은 복구할 수 없으며, 이때 파일을 지워 '미요청'으로 바꾸지 않는다.
 * DI에서 서버당 단일 인스턴스로 제공해 읽기-쓰기와 조건부 삭제를 같은 잠금 안에 둔다.
 */
internal class AndroidProtectedRecordStore(context: Context, apiBase: String) : ProtectedRecordStore {
    internal val directory = File(context.noBackupFilesDir, "tingle.recovery." + hash(apiBase.trimEnd('/')))
    private val alias = "tingle.recovery." + hash(apiBase.trimEnd('/'))
    private val lock = Mutex()

    override suspend fun read(key: String): ProtectedRead = locked { readLocked(key) }

    override suspend fun write(key: String, value: String) = locked {
        check(value.isNotEmpty() && value.length <= 65536) { "Invalid protected record" }
        check(readLocked(key) != ProtectedRead.Unavailable) { "Protected record unavailable" }
        check(directory.isDirectory || directory.mkdirs()) { "Protected storage unavailable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        // 파일명을 인증 부가 데이터로 묶어 다른 실행 파일로 암호문을 옮겨도 해독하지 못하게 한다.
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val file = file(key)
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1) + cipher.iv + encrypted)
            file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
            error("Protected record write failed")
        }
        check((readLocked(key) as? ProtectedRead.Found)?.value == value) { "Protected record verification failed" }
    }

    override suspend fun remove(key: String, expectedValue: String): Boolean = locked {
        if ((readLocked(key) as? ProtectedRead.Found)?.value != expectedValue) return@locked false
        file(key).delete()
        check(readLocked(key) == ProtectedRead.Missing) { "Protected record deletion failed" }
        true
    }

    private fun readLocked(key: String): ProtectedRead {
        val file = file(key)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return ProtectedRead.Missing
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            check(bytes.size in 30..262200 && bytes[0] == 1.toByte())
            val storedKey = keyStore().getKey(alias, null) as? SecretKey ?: error("Key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, storedKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                updateAAD(key.toByteArray(Charsets.UTF_8))
            }
            ProtectedRead.Found(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString(throwOnInvalidSequence = true))
        } catch (_: Exception) { ProtectedRead.Unavailable }
    }

    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[a-zA-Z0-9._-]{1,120}")))
        return AtomicFile(File(directory, hash(key)))
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encryptionKey(): SecretKey {
        val keys = keyStore()
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
