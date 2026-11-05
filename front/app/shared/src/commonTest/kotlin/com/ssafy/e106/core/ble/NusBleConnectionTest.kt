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
    var onWrite: suspend (ByteArray) -> Unit = {}
    override suspend fun write(bytes: ByteArray) { writes.add(bytes); onWrite(bytes) }
    override fun close() { state.value = BleLinkState.Closed; packets.close(BleException(BleFailure.Disconnected)) }
    fun reply(text: String) { text.encodeToByteArray().asList().chunked(20).forEach { packets.trySend(it.toByteArray()) } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NusBleConnectionTest {
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
