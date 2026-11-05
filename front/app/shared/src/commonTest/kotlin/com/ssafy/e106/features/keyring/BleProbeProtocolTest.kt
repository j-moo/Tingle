package com.ssafy.e106.features.keyring

import com.ssafy.e106.core.ble.*
import com.ssafy.e106.features.keyring.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BleProbeProtocolTest {
    private fun response(extra: String = "") = """{"protocolVersion":1,"type":"STATUS","requestId":"s"$extra}""".encodeToByteArray()
    @Test fun unknownMetricsAreNotInventedAsZero() {
        val metrics = BleProbeProtocol.status(BleProbeProtocol.response(response(), "s", "STATUS"))
        assertNull(metrics.batteryPercent); assertNull(metrics.charging); assertNull(metrics.pendingRecordCount)
    }
    @Test fun validZeroIsPreservedAndInvalidRangesAreUnknown() {
        val valid = BleProbeProtocol.status(BleProbeProtocol.response(response(",\"batteryPercent\":0,\"pendingRecordCount\":0,\"charging\":false"), "s", "STATUS"))
        assertEquals(0L, valid.batteryPercent); assertEquals(0L, valid.pendingRecordCount); assertEquals(false, valid.charging)
        val invalid = BleProbeProtocol.status(BleProbeProtocol.response(response(",\"batteryPercent\":101,\"batteryMillivolts\":-1,\"charging\":\"true\",\"pendingRecordCount\":-1"), "s", "STATUS"))
        assertNull(invalid.batteryPercent); assertNull(invalid.batteryMillivolts); assertNull(invalid.charging); assertNull(invalid.pendingRecordCount)
    }
    @Test fun wrongRequestTypeOrVersionNeverUpdatesState() {
        assertFailsWith<BleException> { BleProbeProtocol.response(response(), "other", "STATUS") }
        assertFailsWith<BleException> { BleProbeProtocol.response(response(), "s", "DEVICE_INFO") }
        listOf("2", "\"1\"").forEach { version ->
            assertFailsWith<BleException> { BleProbeProtocol.response(response().decodeToString().replace(":1,", ":$version,").encodeToByteArray(), "s", "STATUS") }
        }
    }
    @Test fun deviceErrorIsNotSuccess() {
        val result = """{"protocolVersion":1,"type":"ERROR","requestId":"s","code":"UNSUPPORTED_TYPE"}"""
        assertEquals("UNSUPPORTED_TYPE", assertFailsWith<BleCommandException> { BleProbeProtocol.response(result.encodeToByteArray(), "s", "STATUS") }.code)
    }
    @Test fun requestIsFlatJsonAndHasSingleLf() {
        val frame = BleProbeProtocol.request("GET_DEVICE_INFO", "request-1")
        assertEquals(1, frame.count { it == 10.toByte() })
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement(frame.decodeToString()).jsonObject["protocolVersion"])
        assertFailsWith<IllegalArgumentException> { BleProbeProtocol.request("RESET", "x") }
    }
    @Test fun malformedUtf8AndUnusableSerialAreRejected() {
        assertFailsWith<BleException> { BleProbeProtocol.response(byteArrayOf(-1, 10), "s", "STATUS") }
        assertFailsWith<BleException> { BleProbeProtocol.info(buildJsonObject { put("deviceSerial", "bad serial"); put("firmwareVersion", "1") }) }
    }
}
