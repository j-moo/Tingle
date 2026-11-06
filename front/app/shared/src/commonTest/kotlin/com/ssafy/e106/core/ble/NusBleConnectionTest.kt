package com.ssafy.e106.core.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 전송 알고리즘 테스트용 포트. 제품 코드에는 가상 BLE 구현을 주입하지 않는다. */
private class PacketFixture : BlePacketLink {
    override val state = MutableStateFlow(BleLinkState.Ready)
    override val packets = Channel<ByteArray>(256)
    val writes = mutableListOf<ByteArray>()
    val protectedWrites = mutableListOf<ByteArray>()
    var probes = 0
    var onProbe: suspend () -> ByteArray = { byteArrayOf(1) }
    var onWrite: suspend (ByteArray) -> Unit = {}
    override suspend fun write(bytes: ByteArray) { writes.add(bytes); onWrite(bytes) }
    override suspend fun readProtection(): ByteArray { probes++; return onProbe() }
    override suspend fun writeProtected(bytes: ByteArray) { protectedWrites.add(bytes); onWrite(bytes) }
    override fun close() { state.value = BleLinkState.Closed; packets.close(BleException(BleFailure.Disconnected)) }
    fun reply(text: String) { text.encodeToByteArray().asList().chunked(20).forEach { packets.trySend(it.toByteArray()) } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NusBleConnectionTest {
    // 보호 통로는 일반 RX와 절대 섞이지 않고 Write ACK마다 다음 20B를 보내야 한다.
    @Test fun protectedChunksWaitForAckAndNeverUseOrdinaryRx() = runTest {
        val port = PacketFixture(); val ack = Channel<Unit>(Channel.UNLIMITED)
        port.onWrite = { ack.receive(); if (it.last() == 10.toByte()) port.reply("{}\n") }
        val request = ("한".repeat(20) + "\n").encodeToByteArray()
        val job = async { NusBleConnection(port).exchangeProtected(request) }
        runCurrent(); assertEquals(1, port.probes); assertEquals(1, port.protectedWrites.size)
        repeat(4) { ack.send(Unit); runCurrent() }
        assertEquals("{}\n", job.await().decodeToString())
        assertEquals(listOf(20, 20, 20, 1), port.protectedWrites.map { it.size })
        assertContentEquals(request, port.protectedWrites.flatMap { it.toList() }.toByteArray())
        assertTrue(port.writes.isEmpty())
    }

    @Test fun unverifiedOrUnsupportedProtectionNeverWritesAndCloses() = runTest {
        // ASCII "01"은 계약의 원시 0x01이 아니다. 빈 값/다른 값/복수 바이트도 거절한다.
        for (value in listOf(byteArrayOf(), byteArrayOf(0), "01".encodeToByteArray(), byteArrayOf(1, 1))) {
            val port = PacketFixture(); port.onProbe = { value }
            val error = assertFailsWith<BleException> { NusBleConnection(port).exchangeProtected("{}\n".encodeToByteArray()) }
            assertEquals(BleFailure.SecureTransportRequired, error.reason)
            assertTrue(port.writes.isEmpty() && port.protectedWrites.isEmpty())
            assertEquals(BleLinkState.Closed, port.state.value)
        }
        val port = PacketFixture(); port.onProbe = { throw BleException(BleFailure.Unsupported) }
        assertEquals(BleFailure.Unsupported, assertFailsWith<BleException> {
            NusBleConnection(port).exchangeProtected("{}\n".encodeToByteArray())
        }.reason)
        assertTrue(port.writes.isEmpty() && port.protectedWrites.isEmpty())
    }

    @Test fun preparationDoesNotWriteAndEveryProtectedExchangeRechecksConnection() = runTest {
        val port = PacketFixture(); val link = NusBleConnection(port)
        link.prepareProtectedTransport()
        assertTrue(port.writes.isEmpty() && port.protectedWrites.isEmpty())
        port.onWrite = { if (it.last() == 10.toByte()) port.reply("{}\n") }
        repeat(2) { link.exchangeProtected("{}\n".encodeToByteArray()) }
        assertEquals(3, port.probes)
        // 새 연결에 성공 값을 승계하지 않는다. 이전 연결의 prepare는 새 연결에 효력이 없다.
        val next = PacketFixture(); next.onProbe = { byteArrayOf(0) }
        assertFailsWith<BleException> { NusBleConnection(next).exchangeProtected("{}\n".encodeToByteArray()) }
        assertTrue(next.protectedWrites.isEmpty())
    }

    @Test fun cancelledOrTimedOutProtectionNeverSendsPayload() = runTest {
        val port = PacketFixture(); port.onProbe = { awaitCancellation() }
        val job = launch { NusBleConnection(port).prepareProtectedTransport() }
        runCurrent(); job.cancelAndJoin()
        assertEquals(BleLinkState.Closed, port.state.value)
        val timed = PacketFixture(); timed.onProbe = { awaitCancellation() }
        assertEquals(BleFailure.Timeout, assertFailsWith<BleException> {
            NusBleConnection(timed).exchangeProtected("{}\n".encodeToByteArray())
        }.reason)
        assertTrue(timed.writes.isEmpty() && timed.protectedWrites.isEmpty())
        assertEquals(BleLinkState.Closed, timed.state.value)
    }

    @Test fun ordinaryExchangeDoesNotRequireProtectedFirmware() = runTest {
        val port = PacketFixture(); port.onProbe = { error("legacy must not read protected characteristic") }
        port.onWrite = { port.reply("{}\n") }
        NusBleConnection(port).exchange("{}\n".encodeToByteArray())
        assertEquals(0, port.probes); assertEquals(1, port.writes.size)
    }

    @Test fun protectionAndOrdinaryExchangeCannotInterleave() = runTest {
        val port = PacketFixture(); val gate = CompletableDeferred<ByteArray>()
        port.onProbe = { gate.await() }; val link = NusBleConnection(port)
        val first = async { link.exchangeProtected("{}\n".encodeToByteArray()) }
        runCurrent()
        val second = async { link.exchange("{}\n".encodeToByteArray()) }
        runCurrent(); assertTrue(port.writes.isEmpty() && port.protectedWrites.isEmpty())
        gate.complete(byteArrayOf(1)); runCurrent()
        assertEquals(1, port.protectedWrites.size); assertTrue(port.writes.isEmpty())
        port.reply("{}\n"); runCurrent(); first.await()
        assertEquals(1, port.writes.size)
        port.reply("{}\n"); second.await()
    }

    @Test fun notificationDuringProtectionCheckIsNotAcceptedAsNewResponse() = runTest {
        val port = PacketFixture(); port.onProbe = { port.reply("{}\n"); byteArrayOf(1) }
        assertEquals(BleFailure.InvalidFrame, assertFailsWith<BleException> {
            NusBleConnection(port).exchangeProtected("{}\n".encodeToByteArray())
        }.reason)
        assertTrue(port.protectedWrites.isEmpty())
    }

    @Test fun protectedWriteFailureDoesNotFallbackOrSendRemainingChunks() = runTest {
        val port = PacketFixture(); port.onWrite = { throw BleException(BleFailure.WriteFailed) }
        assertFailsWith<BleException> { NusBleConnection(port).exchangeProtected(("x".repeat(60) + "\n").encodeToByteArray()) }
        assertEquals(1, port.protectedWrites.size); assertTrue(port.writes.isEmpty())
        assertEquals(BleLinkState.Closed, port.state.value)
    }

    @Test fun utf8SplitAtEveryByteIsReassembledBeforeDecoding() = runTest {
        val port = PacketFixture()
        port.onWrite = { if (it.last() == 10.toByte()) "{\"이름\":\"키링\"}\n".encodeToByteArray().forEach { byte -> port.packets.send(byteArrayOf(byte)) } }
        val actual = NusBleConnection(port).exchange("{}\n".encodeToByteArray())
        assertEquals("{\"이름\":\"키링\"}\n", actual.decodeToString())
    }

    @Test fun chunkingWaitsForEachWriteAckAndPreservesBytes() = runTest {
        val port = PacketFixture()
        val ack = Channel<Unit>(Channel.UNLIMITED)
        port.onWrite = { ack.receive(); if (it.last() == 10.toByte()) port.reply("{}\n") }
        val input = ("가".repeat(20) + "\n").encodeToByteArray()
        val job = async { NusBleConnection(port).exchange(input) }
        runCurrent(); assertEquals(1, port.writes.size)
        repeat(4) { ack.send(Unit); runCurrent() }
        assertEquals(listOf(20,20,20,1), port.writes.map { it.size })
        assertContentEquals(input, port.writes.flatMap { it.toList() }.toByteArray())
        assertEquals("{}\n", job.await().decodeToString())
    }

    @Test fun writeAckAloneIsNotSuccessAndResponseTimeoutClosesConnection() = runTest {
        val port = PacketFixture()
        val task = async { runCatching { NusBleConnection(port).exchange("{}\n".encodeToByteArray()) } }
        runCurrent(); advanceTimeBy(9_999); assertFalse(task.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(BleFailure.Timeout, (task.await().exceptionOrNull() as BleException).reason)
        assertEquals(BleLinkState.Closed, port.state.value)
    }

    @Test fun responseBudgetStartsAfterSendingFinishes() = runTest {
        val port = PacketFixture()
        port.onWrite = { delay(3_000) }
        val task = async { runCatching { NusBleConnection(port).exchange("{}\n".encodeToByteArray()) } }
        runCurrent(); advanceTimeBy(12_999); assertFalse(task.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(task.isCompleted)
    }

    @Test fun cancellationClosesPartialConnection() = runTest {
        val port = PacketFixture()
        port.onWrite = { awaitCancellation() }
        val job = launch { NusBleConnection(port).exchange("{}\n".encodeToByteArray()) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(BleLinkState.Closed, port.state.value)
    }

    @Test fun pendingExchangeCannotInterleaveWithNextRequest() = runTest {
        val port = PacketFixture()
        val link = NusBleConnection(port)
        val first = async { link.exchange("{\"n\":1}\n".encodeToByteArray()) }
        runCurrent()
        val second = async { link.exchange("{\"n\":2}\n".encodeToByteArray()) }
        runCurrent(); assertEquals(1, port.writes.size)
        port.reply("{}\n"); runCurrent(); first.await()
        assertEquals(2, port.writes.size)
        port.reply("{}\n"); second.await()
    }

    @Test fun cancelledQueuedRequestDoesNotCloseActiveExchange() = runTest {
        val port = PacketFixture(); val link = NusBleConnection(port)
        val active = async { link.exchange("{}\n".encodeToByteArray()) }
        runCurrent()
        val queued = launch { link.exchange("{}\n".encodeToByteArray()) }
        runCurrent(); queued.cancelAndJoin()
        assertEquals(BleLinkState.Ready, port.state.value)
        port.reply("{}\n"); active.await()
    }

    @Test fun staleBufferedDataIsNotReusedForANewRequest() = runTest {
        val port = PacketFixture(); port.reply("{}\n")
        assertEquals(BleFailure.InvalidFrame, assertFailsWith<BleException> {
            NusBleConnection(port).exchange("{}\n".encodeToByteArray())
        }.reason)
        assertTrue(port.writes.isEmpty())
        assertEquals(BleLinkState.Closed, port.state.value)
    }

    @Test fun exactLimitAcceptedButOversizedStatusRejected() {
        val exact = BleLineBuffer(4)
        assertContentEquals("123\n".encodeToByteArray(), exact.append("123\n".encodeToByteArray()))
        assertEquals(BleFailure.Overflow, assertFailsWith<BleException> { BleLineBuffer(3).append("123\n".encodeToByteArray()) }.reason)
    }

    @Test fun invalidPacketsAndAdditionalFramesAreRejected() {
        listOf(ByteArray(21), byteArrayOf(), "a\nb\n".encodeToByteArray()).forEach {
            assertEquals(BleFailure.InvalidFrame, assertFailsWith<BleException> { BleLineBuffer(512).append(it) }.reason)
        }
    }

    @Test fun totalSendTimeoutClosesHungWrite() = runTest {
        val port = PacketFixture(); port.onWrite = { awaitCancellation() }
        assertEquals(BleFailure.Timeout, assertFailsWith<BleException> { NusBleConnection(port).exchange("{}\n".encodeToByteArray()) }.reason)
        assertEquals(BleLinkState.Closed, port.state.value)
    }

    @Test fun missingOrMultipleLfNeverReachesGatt() = runTest {
        val port = PacketFixture(); val link = NusBleConnection(port)
        listOf("{}", "{}\n{}\n", "\n").forEach { assertFailsWith<IllegalArgumentException> { link.exchange(it.encodeToByteArray()) } }
        assertTrue(port.writes.isEmpty())
    }

    @Test fun disconnectFailsPendingResponseWithoutWaitingForDeadline() = runTest {
        val port = PacketFixture()
        val waiting = async { runCatching { NusBleConnection(port).exchange("{}\n".encodeToByteArray()) } }
        runCurrent(); port.close(); runCurrent()
        assertEquals(0, testScheduler.currentTime)
        assertEquals(BleFailure.Disconnected, (waiting.await().exceptionOrNull() as BleException).reason)
    }

    @Test fun failedWriteDoesNotSendFollowingChunks() = runTest {
        val port = PacketFixture(); port.onWrite = { throw BleException(BleFailure.WriteFailed) }
        assertFailsWith<BleException> { NusBleConnection(port).exchange(("x".repeat(60) + "\n").encodeToByteArray()) }
        assertEquals(1, port.writes.size); assertEquals(BleLinkState.Closed, port.state.value)
    }
}
