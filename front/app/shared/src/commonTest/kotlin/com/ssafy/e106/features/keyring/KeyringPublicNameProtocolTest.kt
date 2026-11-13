package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.features.keyring.data.BleProbeProtocol
import com.ssafy.e106.features.keyring.data.KeyringPublicNameCommand
import com.ssafy.e106.features.keyring.data.KeyringPublicNameProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyringPublicNameProtocolTest {
    @Test fun advertisingLimitUsesBytesAndKeepsValidUnicode() {
        val name = "가".repeat(9) + "AB"
        val frame = KeyringPublicNameProtocol.request(KeyringPublicNameCommand.Set, "name-1", name)
        assertEquals(JsonPrimitive(name), Json.parseToJsonElement(frame.decodeToString()).jsonObject["name"])
        assertFalse(KeyringPublicNameProtocol.validName("가".repeat(10)))
        assertFalse(KeyringPublicNameProtocol.validName("두\n줄"))
    }

    @Test fun unknownCapabilityCannotEnableThePublicNameControls() {
        val device = """{"deviceSerial":"XIAO-1","firmwareVersion":"0.9.6-d31",
            "supportedFunctions":["GET_PUBLIC_NAME","SET_PUBLIC_NAME","CLEAR_PUBLIC_NAME"]"""
        assertFalse(BleProbeProtocol.info(Json.parseToJsonElement(device + "}").jsonObject).supportsPublicName)
        assertTrue(BleProbeProtocol.info(Json.parseToJsonElement(device + ""","maxPublicNameBytes":29}""").jsonObject).supportsPublicName)
    }

    @Test fun publicNameStatusRequiresMatchingRequestAndConsistentEnabledValue() {
        fun response(id: String, enabled: String, name: String) =
            """{"protocolVersion":1,"type":"PUBLIC_NAME_RESULT","requestId":"$id","enabled":$enabled,"name":$name}"""
                .encodeToByteArray()
        assertEquals("팅글", KeyringPublicNameProtocol.response(response("set", "true", "\"팅글\""), "set", 29))
        assertNull(KeyringPublicNameProtocol.response(response("clear", "false", "null"), "clear", 29))
        assertFailsWith<BleException> {
            KeyringPublicNameProtocol.response(response("clear", "false", "\"팅글\""), "clear", 29)
        }
        assertFailsWith<BleException> {
            KeyringPublicNameProtocol.response(response("other", "true", "\"팅글\""), "set", 29)
        }
    }
}
