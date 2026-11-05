package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 서버가 발급한 원문을 보존한다. nonce·서명·등록 본문을 로그에 출력하지 않는다. */
@Serializable data class KeyringChallenge(val challengeId: String, val nonce: String,
    val expiresAt: String, val proofProtocolVersion: String) {
    override fun toString() = "KeyringChallenge(REDACTED)"
}
@Serializable data class KeyringProof(val protocolVersion: String, val keyId: String, val signature: String) {
    override fun toString() = "KeyringProof(REDACTED)"
}
@Serializable data class RegisterKeyringBody(val challengeId: String, val deviceSerial: String, val proof: KeyringProof,
    val ownershipEpoch: Long? = null, val ownerState: String? = null) {
    override fun toString() = "RegisterKeyringBody(REDACTED)"
}

interface KeyringRegistrationRepository {
    suspend fun challenge(key: String): ApiResult<KeyringChallenge>
    suspend fun register(key: String, body: RegisterKeyringBody): ApiResult<OwnedKeyring>
}

/** 인증 갱신은 공통 HttpClient가 처리한다. 재시도 키는 호출자가 보관하며 여기서 새로 만들지 않는다. */
class HttpKeyringRegistrationRepository(private val client: HttpClient) : KeyringRegistrationRepository {
    override suspend fun challenge(key: String) = client.call<KeyringChallenge> {
        post("keyrings/registration-challenges") { header("Idempotency-Key", key) }
    }
    override suspend fun register(key: String, body: RegisterKeyringBody) = client.call<OwnedKeyring> {
        post("keyrings") { header("Idempotency-Key", key); setBody(body) }
    }
}

/** BLE 증명을 선택한 기기·요청과 대조한다. 암호학적 진위·소유권 판단은 서버에만 맡긴다. */
internal object KeyringRegistrationProtocol {
    const val VERSION = "tingle-device-proof-v1"
    const val VERSION_2 = "tingle-device-proof-v2"
    // v1에는 epoch 필드 자체가 없다. 0을 기본값으로 넣으면 이전 요청의 본문과 의미가 바뀐다.
    fun validEpoch(version: String, epoch: Long?) = when (version) {
        VERSION -> epoch == null
        VERSION_2 -> epoch != null && epoch >= 0
        else -> false
    }
    private val identifier = Regex("[A-Za-z0-9._-]{1,64}")
    @OptIn(ExperimentalUuidApi::class)
    fun uuid(value: String) = runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)
    fun serial(value: String) = identifier.matches(value)
    private fun base64(value: String, length: Int) = runCatching {
        Regex("[A-Za-z0-9_-]+").matches(value) &&
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(value).size == length
    }.getOrDefault(false)

    fun validChallenge(value: KeyringChallenge) = uuid(value.challengeId) && base64(value.nonce, 32) &&
        value.proofProtocolVersion in setOf(VERSION, VERSION_2) && runCatching { Instant.parse(value.expiresAt) }.isSuccess
    fun validBody(body: RegisterKeyringBody) = uuid(body.challengeId) && serial(body.deviceSerial) &&
        validEpoch(body.proof.protocolVersion, body.ownershipEpoch) &&
        (if (body.proof.protocolVersion == VERSION_2) body.ownerState == "UNOWNED" else body.ownerState == null) &&
        identifier.matches(body.proof.keyId) && base64(body.proof.signature, 64)
    fun validReceipt(value: OwnedKeyring) = value.deviceId > 0 && value.status.isNotBlank() && value.firmwareVersion.length in 1..100

    fun request(challenge: KeyringChallenge, id: String): ByteArray {
        require(validChallenge(challenge) && uuid(id))
        return (buildJsonObject {
            put("protocolVersion", 1); put("type", "GET_REGISTRATION_PROOF"); put("requestId", id)
            put("challengeId", challenge.challengeId); put("nonce", challenge.nonce)
            put("expiresAt", challenge.expiresAt); put("proofProtocolVersion", challenge.proofProtocolVersion)
        }.toString() + "\n").encodeToByteArray()
    }

    fun response(bytes: ByteArray, id: String, serial: String, challenge: KeyringChallenge): RegisterKeyringBody {
        val value = BleProbeProtocol.response(bytes, id, "REGISTRATION_PROOF")
        val actual = (value["deviceSerial"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(actual == serial) { "Registration device mismatch" }
        val proof = ApiJson.decodeFromJsonElement<KeyringProof>(value["proof"] ?: error("Missing proof"))
        // 응답이 서버가 요구한 버전을 낮추거나 소유 순번의 숫자 정밀도를 잃으면 등록 POST 전에 거절한다.
        require(proof.protocolVersion == challenge.proofProtocolVersion)
        val epoch = value["ownershipEpoch"]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull ?: error("Invalid ownership epoch")
        }
        val ownerState = value["ownerState"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid owner state")
        }
        return RegisterKeyringBody(challenge.challengeId, serial, proof, epoch, ownerState).also { require(validBody(it)) }
    }
}
