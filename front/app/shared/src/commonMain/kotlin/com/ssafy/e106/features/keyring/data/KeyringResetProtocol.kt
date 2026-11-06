package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.network.ApiJson
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** 암호학적 검증은 기기/BE 소유다. FE는 다른 실행의 증명·명령이 섞이지 않도록 모든 결속 필드를 대조한다. */
internal object KeyringResetProtocol {
    const val VERSION = "tingle-keyring-reset-v1"
    const val SCOPE = "OWNER_DATA_V1"
    private fun encoded(value: String, size: Int) = runCatching {
        val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        Regex("[A-Za-z0-9_-]+").matches(value) && codec.decode(value).size == size && codec.encode(codec.decode(value)) == value
    }.getOrDefault(false)
    fun signature(value: String) = encoded(value, 64)
    fun challenge(c: ResetDeviceChallenge) = c.resetProtocolVersion == VERSION &&
        KeyringRegistrationProtocol.serial(c.keyId) && KeyringRegistrationProtocol.serial(c.deviceSerial) &&
        c.ownershipEpoch in 0 until Long.MAX_VALUE &&
        (c.localOwnerGeneration == "NONE" || KeyringRegistrationProtocol.uuid(c.localOwnerGeneration)) &&
        KeyringRegistrationProtocol.uuid(c.challengeId) && encoded(c.deviceNonce, 32) && c.expiresInSeconds == 60 && signature(c.signature)
    fun valid(run: KeyringResetRun) = run.deviceId > 0 && KeyringRegistrationProtocol.uuid(run.resetRunId) &&
        KeyringRegistrationProtocol.uuid(run.ownerGeneration) && run.fromOwnershipEpoch in 0 until Long.MAX_VALUE &&
        run.toOwnershipEpoch == run.fromOwnershipEpoch + 1 && encoded(run.resetNonce, 32) &&
        runCatching { Instant.parse(run.createdAt); Instant.parse(run.commandExpiresAt) }.isSuccess &&
        when (run.state) {
            "PENDING_DEVICE" -> run.completedAt == null && !run.deviceCommand.isNullOrBlank()
            "COMPLETED" -> run.deviceCommand == null && run.completedAt != null && runCatching { Instant.parse(run.completedAt) }.isSuccess
            else -> false
        }

    fun challengeRequest(id: String) = request("GET_RESET_CHALLENGE", id)
    fun resultRequest(id: String, run: KeyringResetRun): ByteArray {
        require(valid(run))
        return request("GET_RESET_RESULT", id, run)
    }
    private fun request(type: String, id: String, run: KeyringResetRun? = null): ByteArray {
        require(KeyringRegistrationProtocol.uuid(id))
        return (buildJsonObject {
            put("protocolVersion", 1); put("type", type); put("requestId", id)
            run?.let { put("resetRunId", it.resetRunId); put("resetNonce", it.resetNonce) }
        }.toString() + "\n").encodeToByteArray()
    }
    fun challengeResponse(bytes: ByteArray, id: String, serial: String): ResetDeviceChallenge {
        val c = ApiJson.decodeFromJsonElement<ResetDeviceChallenge>(BleProbeProtocol.response(bytes, id, "RESET_CHALLENGE"))
        require(challenge(c) && c.deviceSerial == serial)
        return c
    }

    /** JSON을 검사하되 다시 직렬화하지 않는다. BE가 발급한 마지막 LF까지 원래 바이트를 전송한다. */
    fun command(run: KeyringResetRun, serial: String, keyId: String): ByteArray {
        require(valid(run) && run.state == "PENDING_DEVICE")
        val raw = requireNotNull(run.deviceCommand)
        val bytes = raw.encodeToByteArray()
        require(bytes.size <= 4096 && raw.endsWith("\n") && '\n' !in raw.dropLast(1) && '\r' !in raw)
        val v = Json.parseToJsonElement(raw).jsonObject
        require(v["protocolVersion"] == JsonPrimitive(1) && v["type"] == JsonPrimitive("RESET_DEVICE") &&
            v["requestId"] == JsonPrimitive(run.resetRunId) && v["resetProtocolVersion"] == JsonPrimitive(VERSION))
        binding(v, run, serial)
        require(v["keyId"] == JsonPrimitive(keyId) && signature(v.string("serverSignature")) &&
            KeyringRegistrationProtocol.serial(v.string("serverKeyId")) &&
            KeyringRegistrationProtocol.uuid(v.string("challengeId")) && encoded(v.string("deviceNonce"), 32))
        return bytes
    }
    /** 진행 중은 null로 구분한다. 시간 초과나 잘못된 응답을 완료 증명으로 바꾸지 않는다. */
    fun result(bytes: ByteArray, id: String, run: KeyringResetRun, serial: String, keyId: String): ResetAckBody? {
        val v = BleProbeProtocol.response(bytes, id, "RESET_RESULT")
        require(v["resetRunId"] == JsonPrimitive(run.resetRunId))
        if (v["state"] == JsonPrimitive("RESETTING") && v["result"] == JsonPrimitive("PENDING")) return null
        require(v["state"] == JsonPrimitive("RESET_DONE") && v["result"] == JsonPrimitive("CLEARED") &&
            v["dataErasure"] == JsonPrimitive("LOGICAL"))
        binding(v, run, serial)
        val proof = ApiJson.decodeFromJsonElement<KeyringProof>(v.getValue("proof"))
        require(proof.protocolVersion == VERSION && proof.keyId == keyId && signature(proof.signature))
        return ResetAckBody(proof.signature)
    }
    private fun binding(v: JsonObject, r: KeyringResetRun, serial: String) {
        require(v["deviceSerial"] == JsonPrimitive(serial) && v["deviceId"] == JsonPrimitive(r.deviceId) &&
            v["ownerGeneration"] == JsonPrimitive(r.ownerGeneration) && v["resetRunId"] == JsonPrimitive(r.resetRunId) &&
            v["fromOwnershipEpoch"] == JsonPrimitive(r.fromOwnershipEpoch) && v["toOwnershipEpoch"] == JsonPrimitive(r.toOwnershipEpoch) &&
            v["resetNonce"] == JsonPrimitive(r.resetNonce) && v["deletionScope"] == JsonPrimitive(SCOPE) &&
            v["recordPolicy"] == JsonPrimitive(if (r.discardUnsentConfirmed) "DISCARD_WITH_CONSENT" else "REQUIRE_SYNCED"))
    }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid reset field")
}
