package com.ssafy.e106.core.ble

import com.ssafy.e106.features.keyring.data.BleProbeProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** test_product.c가 생성한 실제 STATUS 바이트를 전송·파싱한다. 실물 BLE 검사는 별도다. */
class KeyringStatusTransportTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/keyring/status-0.7.json")).use { it.readBytes() }

    private class Link(private val response: ByteArray) : BlePacketLink {
        override val state = MutableStateFlow(BleLinkState.Ready)
        override val packets = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun write(bytes: ByteArray) {
            if (bytes.last() == 10.toByte()) {
                response.asList().chunked(20).forEach { packets.send(it.toByteArray()) }
            }
        }
        override fun close() {
            state.value = BleLinkState.Closed
            packets.close(BleException(BleFailure.Disconnected))
        }
    }

    @Test fun fullFirmwareStatusFitsTheDiagnosticLimitAndKeepsConnection() = runTest {
        val expected = fixture()
        assertTrue(expected.size > 512)
        assertTrue(expected.size <= BleProbeProtocol.STATUS_RESPONSE_MAX_BYTES)
        val link = Link(expected)
        val response = NusBleConnection(link).exchange(BleProbeProtocol.request("GET_STATUS", "test"),
            maxResponseBytes = BleProbeProtocol.STATUS_RESPONSE_MAX_BYTES)
        assertContentEquals(expected, response)
        val status = BleProbeProtocol.status(BleProbeProtocol.response(response, "test", "STATUS"))
        assertEquals(3750L, status.batteryMillivolts)
        assertEquals(50L, status.batteryPercent)
        assertNull(status.charging)
        assertEquals(0L, status.pendingRecordCount)
        assertEquals(BleLinkState.Ready, link.state.value)
    }

    @Test fun theSameFirmwareResponseExceedsTheOld512ByteLimit() = runTest {
        val link = Link(fixture())
        val error = assertFailsWith<BleException> {
            NusBleConnection(link).exchange(BleProbeProtocol.request("GET_STATUS", "test"), maxResponseBytes = 512)
        }
        assertEquals(BleFailure.Overflow, error.reason)
        assertEquals(BleLinkState.Closed, link.state.value)
    }
}
