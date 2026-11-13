package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.storage.ProtectedRead
import com.ssafy.e106.core.storage.ProtectedRecordStore
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 업로드 결과가 불명확하면 보드가 보유한 동일 submissionId와 원본으로 다시 요청한다. */
internal class KeyringEncounterRepository(private val client: HttpClient) {
    suspend fun upload(body: KeyringEncounterUpload) = client.call<KeyringEncounterResponse> {
        post("offline-encounters/sync") { setBody(body) }
    }
}

/** 키링이 원본을 지운 뒤에도 응답이 유실될 수 있다. 그 전부터 서명 접수증을 보호 저장한다. */
internal class KeyringEncounterJournal(private val store: ProtectedRecordStore) {
    private val lock = Mutex()

    suspend fun read(owner: Long, deviceId: Long): KeyringEncounterProof? = lock.withLock {
        val raw = readValue(key(owner, deviceId)) ?: return@withLock null
        ApiJson.decodeFromString(KeyringEncounterProof.serializer(), raw).also {
            check(it.deviceId == deviceId && KeyringEncounterProtocol.valid(it))
        }
    }

    suspend fun save(owner: Long, proof: KeyringEncounterProof) = lock.withLock {
        check(KeyringEncounterProtocol.valid(proof))
        val key = key(owner, proof.deviceId)
        val raw = ApiJson.encodeToString(KeyringEncounterProof.serializer(), proof)
        val old = readValue(key)
        check(old == null || old == raw)
        if (old == null) store.write(key, raw)
    }

    suspend fun remove(owner: Long, proof: KeyringEncounterProof) = lock.withLock {
        val raw = ApiJson.encodeToString(KeyringEncounterProof.serializer(), proof)
        check(store.remove(key(owner, proof.deviceId), raw))
    }

    private fun key(owner: Long, deviceId: Long) = "keyring.encounter.ack.v1.$owner.$deviceId"

    private suspend fun readValue(key: String): String? = when (val result = store.read(key)) {
        ProtectedRead.Missing -> null
        is ProtectedRead.Found -> result.value
        ProtectedRead.Unavailable -> error("Protected encounter journal unavailable")
    }
}
