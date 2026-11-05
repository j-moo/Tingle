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
        return BleDeviceInfo(serial, version, "GET_STATUS" in functions, "GET_REGISTRATION_PROOF" in functions)
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
data class BleDeviceInfo(val serial: String, val firmware: String, val supportsStatus: Boolean, val supportsRegistration: Boolean = false)
data class BleDeviceStatus(val batteryPercent: Long?, val batteryMillivolts: Long?, val charging: Boolean?, val pendingRecordCount: Long?)
