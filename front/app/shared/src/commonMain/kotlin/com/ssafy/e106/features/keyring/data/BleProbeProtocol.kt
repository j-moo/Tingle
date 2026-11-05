package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.ble.*
import kotlinx.serialization.json.*

/** 공개 읽기 명령만 진단 화면에 노출한다. 등록 증명·설정 변경·초기화는 별도 업무 계약에서 연결한다. */
internal object BleProbeProtocol {
    fun request(type: String, id: String): ByteArray {
        require(type in setOf("GET_DEVICE_INFO", "GET_STATUS") && id.encodeToByteArray().size in 1..36)
        return (buildJsonObject { put("protocolVersion", 1); put("type", type); put("requestId", id) }.toString() + "\n").encodeToByteArray()
    }

    fun response(bytes: ByteArray, id: String, type: String): JsonObject {
        val value = try { Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject }
        catch (_: Exception) { throw BleException(BleFailure.InvalidFrame) }
        if (value["protocolVersion"] != JsonPrimitive(1) || value["requestId"] != JsonPrimitive(id))
            throw BleException(BleFailure.InvalidFrame)
        if (value["type"] == JsonPrimitive("ERROR")) throw BleCommandException(value.string("code")?.take(80) ?: "UNKNOWN")
        if (value["type"] != JsonPrimitive(type)) throw BleException(BleFailure.InvalidFrame)
        return value
    }

    fun info(value: JsonObject): BleDeviceInfo {
        val serial = value.string("deviceSerial") ?: throw BleException(BleFailure.InvalidFrame)
        val version = value.string("firmwareVersion") ?: throw BleException(BleFailure.InvalidFrame)
        if (!serial.matches(Regex("[A-Za-z0-9._-]{1,64}")) || version.length !in 1..80) throw BleException(BleFailure.InvalidFrame)
        val functions = (value["supportedFunctions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        // 구형 펌웨어의 생략과 신형 필드 손상을 구분한다. 미확인 epoch를 0으로 만들지 않는다.
        val epoch = value["ownershipEpoch"]?.let {
            number(value, "ownershipEpoch")?.takeIf { n -> n >= 0 } ?: throw BleException(BleFailure.InvalidFrame)
        }
        val owner = value["ownerState"]?.let {
            value.string("ownerState")?.takeIf { s -> s in setOf("BOUND", "UNOWNED", "RESETTING", "UNKNOWN") }
                ?: throw BleException(BleFailure.InvalidFrame)
        }
        val versions = value["supportedProofProtocolVersions"]?.let {
            (it as? JsonArray)?.map { p ->
                (p as? JsonPrimitive)?.takeIf { s -> s.isString }?.content ?: throw BleException(BleFailure.InvalidFrame)
            } ?: throw BleException(BleFailure.InvalidFrame)
        } ?: listOf(KeyringRegistrationProtocol.VERSION)
        return BleDeviceInfo(serial, version, "GET_STATUS" in functions, "GET_REGISTRATION_PROOF" in functions,
            epoch, owner, versions)
    }

    fun status(value: JsonObject) = BleDeviceStatus(
        number(value, "batteryPercent")?.takeIf { it in 0..100 },
        number(value, "batteryMillivolts")?.takeIf { it > 0 },
        (value["charging"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
        number(value, "pendingRecordCount")?.takeIf { it >= 0 },
    )
    private fun number(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

class BleCommandException(val code: String) : Exception("BLE command failed")
data class BleDeviceInfo(val serial: String, val firmware: String, val supportsStatus: Boolean, val supportsRegistration: Boolean = false,
    val ownershipEpoch: Long? = null, val ownerState: String? = null,
    val supportedProofProtocolVersions: List<String> = listOf("tingle-device-proof-v1")) {
    /** v2 명령은 펌웨어 이름이 아니라 실제 응답의 지원 규격·소유 순번으로 판단한다. */
    internal fun supportsProof(version: String) = version in supportedProofProtocolVersions &&
        ownerState !in setOf("RESETTING", "UNKNOWN") && when (version) {
            KeyringRegistrationProtocol.VERSION -> ownershipEpoch == null || ownershipEpoch == 0L
            KeyringRegistrationProtocol.VERSION_2 -> ownershipEpoch != null && ownershipEpoch >= 0 &&
                ownerState in setOf("UNOWNED", "BOUND")
            else -> false
        }
}
data class BleDeviceStatus(val batteryPercent: Long?, val batteryMillivolts: Long?, val charging: Boolean?, val pendingRecordCount: Long?)
