package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** 서버가 고정한 설정을 불투명하게 중계한다. payload/nonce/증명은 로그·일반 캐시에 남기지 않는다. */
@Serializable data class KeyringSyncRun(
    val syncRunId: String, val settingsSnapshotId: String, val deviceId: Long,
    val ownerGeneration: String, val settingsVersion: Long, val currentSettingsVersion: Long,
    val state: String, val stale: Boolean, val payload: String, val payloadEncoding: String,
    val payloadFormat: String, val payloadHash: String, val nonce: String, val keyId: String,
    val proofProtocolVersion: String, val createdAt: String, val ackedAt: String? = null, val serverTime: String,
    val serverProofProtocolVersion: String? = null, val serverKeyId: String? = null,
    val deviceNonce: String? = null, val serverSignature: String? = null,
    val ownershipEpoch: Long? = null,
) {
    override fun toString() = "KeyringSyncRun(REDACTED)"
    fun identity() = SyncIdentity(syncRunId, deviceId, ownerGeneration, settingsVersion, payloadHash, nonce, keyId, payloadFormat,
        ownershipEpoch, proofProtocolVersion)
}

/** 복구 때 동일 실행의 바이트/소유 세대가 바뀌지 않았는지 비교할 최소 메타데이터. */
@Serializable data class SyncIdentity(val syncRunId: String, val deviceId: Long, val ownerGeneration: String,
    val settingsVersion: Long, val payloadHash: String, val nonce: String, val keyId: String, val payloadFormat: String,
    // 이전 v1 복구 파일은 그대로 읽되 v2 실행은 epoch·증명 버전까지 같아야 같은 작업으로 인정한다.
    val ownershipEpoch: Long? = null, val proofProtocolVersion: String = KeyringRegistrationProtocol.VERSION) {
    override fun toString() = "SyncIdentity(REDACTED)"
}
@Serializable data class KeyringSyncAck(val settingsVersion: Long, val payloadHash: String, val proof: KeyringProof) {
    override fun toString() = "KeyringSyncAck(REDACTED)"
}
data class KeyringStoredResult(val ack: KeyringSyncAck, val applied: Boolean, val timeReady: Boolean)
/** null 형식은 구형 ON/OFF 요청처럼 JSON에서 생략한다. 성향 발급은 BE257의 명시 선택을 사용한다. */
@Serializable data class SyncChallengeBody(val deviceNonce: String, val payloadFormat: String? = null) {
    override fun toString() = "SyncChallengeBody(REDACTED)"
}

interface KeyringSyncRepository {
    suspend fun create(deviceId: Long, key: String, body: SyncChallengeBody): ApiResult<KeyringSyncRun>
    suspend fun get(deviceId: Long, runId: String): ApiResult<KeyringSyncRun>
    suspend fun ack(deviceId: Long, runId: String, key: String, body: KeyringSyncAck): ApiResult<KeyringSyncRun>
}

/** 환경별 접두사는 공통 클라이언트 소유다. 저장해 둔 nonce·형식을 그대로 보내 멱등 재시도 본문을 유지한다. */
class HttpKeyringSyncRepository(private val client: HttpClient) : KeyringSyncRepository {
    override suspend fun create(deviceId: Long, key: String, body: SyncChallengeBody) = client.call<KeyringSyncRun> {
        post("keyrings/$deviceId/sync-runs") { header("Idempotency-Key", key); setBody(body) }
    }
    override suspend fun get(deviceId: Long, runId: String) = client.call<KeyringSyncRun> {
        get("keyrings/$deviceId/sync-runs/$runId") { header(HttpHeaders.CacheControl, "no-store") }
    }
    override suspend fun ack(deviceId: Long, runId: String, key: String, body: KeyringSyncAck) = client.call<KeyringSyncRun> {
        post("keyrings/$deviceId/sync-runs/$runId/ack") { header("Idempotency-Key", key); setBody(body) }
    }
}

/** JSON 숫자를 Long으로 유지한다. 서버 payload를 재인코딩하거나 FE 설정으로 대체하지 않는다. */
internal object KeyringSyncProtocol {
    const val PROFILE_FORMAT = "tingle-profile-storage-v1"
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    /** 형식과 길이만 검사하고 서버 바이트는 그대로 중계한다. 성향 해석·서명 검증은 보드 책임이다. */
    private fun supportedPayload(run: KeyringSyncRun): Boolean = when (run.payloadFormat) {
        "tingle-dev-settings-v1" -> run.payload == "AA" || run.payload == "AQ"
        PROFILE_FORMAT -> encoded(run.payload, 196..196) &&
            codec.decode(run.payload).take(4) == listOf(84, 80, 83, 49).map { it.toByte() }
        else -> false
    }
    private fun encoded(value: String, length: IntRange) = runCatching {
        Regex("[A-Za-z0-9_-]+").matches(value) && codec.decode(value).size in length && codec.encode(codec.decode(value)) == value
    }.getOrDefault(false)
    fun validDeviceNonce(value: String) = encoded(value, 32..32)
    fun challengeRequest(id: String): ByteArray {
        require(KeyringRegistrationProtocol.uuid(id))
        return (buildJsonObject { put("protocolVersion", 1); put("type", "GET_SYNC_CHALLENGE"); put("requestId", id) }.toString() + "\n").encodeToByteArray()
    }
    fun challengeResponse(bytes: ByteArray, id: String): String {
        val value = BleProbeProtocol.response(bytes, id, "SYNC_CHALLENGE")
        return (value["deviceNonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.also { require(validDeviceNonce(it)) } ?: error("Missing device challenge")
    }
    fun signed(run: KeyringSyncRun, deviceNonce: String): Boolean = run.deviceNonce == deviceNonce && validDeviceNonce(deviceNonce) &&
        supportedPayload(run) &&
        KeyringRegistrationProtocol.validEpoch(run.proofProtocolVersion, run.ownershipEpoch) &&
        run.serverProofProtocolVersion == (if (run.proofProtocolVersion == KeyringRegistrationProtocol.VERSION_2)
            "tingle-dev-settings-proof-v2" else "tingle-dev-settings-proof-v1") &&
        run.serverKeyId?.matches(Regex("dev-ed25519-[0-9a-f]{16}")) == true &&
        run.serverSignature?.let { encoded(it, 64..64) } == true && runCatching { Instant.parse(run.serverTime) }.isSuccess
    fun valid(run: KeyringSyncRun, deviceId: Long): Boolean =
        run.deviceId == deviceId && deviceId > 0 && KeyringRegistrationProtocol.uuid(run.syncRunId) &&
        run.settingsSnapshotId == run.syncRunId && KeyringRegistrationProtocol.uuid(run.ownerGeneration) &&
        run.settingsVersion > 0 && run.currentSettingsVersion > 0 && run.stale == (run.settingsVersion != run.currentSettingsVersion) &&
        run.state in setOf("PENDING", "ACKED") && run.payloadEncoding == "base64url" &&
        run.payloadFormat.length in 1..100 && encoded(run.payload, 1..2048) &&
        Regex("[0-9a-f]{64}").matches(run.payloadHash) && encoded(run.nonce, 32..32) &&
        KeyringRegistrationProtocol.serial(run.keyId) && KeyringRegistrationProtocol.validEpoch(run.proofProtocolVersion, run.ownershipEpoch)

    fun request(run: KeyringSyncRun, requestId: String): ByteArray {
        require(valid(run, run.deviceId) && !run.stale && KeyringRegistrationProtocol.uuid(requestId) &&
            run.deviceNonce?.let { signed(run, it) } == true)
        return (buildJsonObject {
            put("protocolVersion", 1); put("type", "SYNC_SETTINGS"); put("requestId", requestId)
            put("syncRunId", run.syncRunId); put("deviceId", run.deviceId); put("ownerGeneration", run.ownerGeneration)
            // BLE 봉투 버전은 1이다. payloadFormat은 서버가 선택한 ON/OFF 또는 성향 형식을 그대로 쓴다.
            run.ownershipEpoch?.let { put("ownershipEpoch", it) }
            put("settingsVersion", run.settingsVersion); put("payload", run.payload); put("payloadEncoding", run.payloadEncoding)
            put("payloadFormat", run.payloadFormat); put("payloadHash", run.payloadHash); put("nonce", run.nonce)
            put("keyId", run.keyId); put("proofProtocolVersion", run.proofProtocolVersion)
            // 서명 원문에 포함된 시각과 필드를 그대로 전달한다. FE 시각이나 GET의 다른 별칭 증명으로 바꾸지 않는다.
            put("serverProofProtocolVersion", run.serverProofProtocolVersion); put("serverKeyId", run.serverKeyId)
            put("deviceNonce", run.deviceNonce); put("serverTime", run.serverTime); put("serverSignature", run.serverSignature)
        }.toString() + "\n").encodeToByteArray().also { require(it.size <= 4096) }
    }

    fun response(bytes: ByteArray, requestId: String, run: KeyringSyncRun): KeyringStoredResult {
        val value = BleProbeProtocol.response(bytes, requestId, "SETTINGS_RESULT")
        require(value["syncRunId"] == JsonPrimitive(run.syncRunId) && value["settingsVersion"] == JsonPrimitive(run.settingsVersion) &&
            value["payloadHash"] == JsonPrimitive(run.payloadHash) && value["stored"] == JsonPrimitive(true))
        val proof = ApiJson.decodeFromJsonElement<KeyringProof>(value["proof"] ?: error("Missing storage proof"))
        require(proof.protocolVersion == run.proofProtocolVersion && proof.keyId == run.keyId && encoded(proof.signature, 64..64))
        // 두 플래그는 서명 대상이 아니다. true 응답만으로 인증된 매칭 준비 완료를 선언하지 않는다.
        fun flag(name: String) = (value[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Missing flag")
        return KeyringStoredResult(KeyringSyncAck(run.settingsVersion, run.payloadHash, proof), flag("applied"), flag("timeReady"))
    }
}
