package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.core.ble.BleFailure
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class KeyringPublicNameCommand(val wireName: String) {
    Read("GET_PUBLIC_NAME"),
    Set("SET_PUBLIC_NAME"),
    Clear("CLEAR_PUBLIC_NAME"),
}

/** 공개 이름은 광고의 길이 한도를 바이트로 계산하고 저장 결과를 같은 요청에 대응시킨다. */
internal object KeyringPublicNameProtocol {
    const val MaxBytes = 29

    fun validName(name: String, limit: Int = MaxBytes): Boolean {
        if (name.all { it == ' ' } || name.any { it.code < 0x20 || it.code in 0x7f..0x9f }) return false
        val bytes = runCatching { name.encodeToByteArray(throwOnInvalidSequence = true).size }.getOrNull()
        return bytes != null && bytes in 1..limit
    }

    fun request(command: KeyringPublicNameCommand, id: String, name: String? = null): ByteArray {
        require(id.encodeToByteArray().size in 1..36)
        require(if (command == KeyringPublicNameCommand.Set) name != null && validName(name) else name == null)
        return (buildJsonObject {
            put("protocolVersion", 1)
            put("type", command.wireName)
            put("requestId", id)
            if (name != null) put("name", name)
        }.toString() + "\n").encodeToByteArray()
    }

    fun response(bytes: ByteArray, id: String, limit: Int): String? {
        val value = BleProbeProtocol.response(bytes, id, "PUBLIC_NAME_RESULT")
        return when (value["enabled"]) {
            JsonPrimitive(false) -> if (value["name"] == JsonNull) null else throw BleException(BleFailure.InvalidFrame)
            JsonPrimitive(true) -> (value["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { validName(it, limit) } ?: throw BleException(BleFailure.InvalidFrame)
            else -> throw BleException(BleFailure.InvalidFrame)
        }
    }
}
