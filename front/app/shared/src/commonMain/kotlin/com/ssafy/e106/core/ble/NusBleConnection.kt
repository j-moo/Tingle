package com.ssafy.e106.core.ble

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Android GATT와 분리한 바이트 단위 포트. write는 직전 GATT 응답까지 기다려야 한다. */
internal interface BlePacketLink {
    val state: StateFlow<BleLinkState>
    val packets: ReceiveChannel<ByteArray>
    suspend fun write(bytes: ByteArray)
    // 기존 펌웨어/플랫폼은 일반 전송만 지원한다. 보호 요청을 일반 write로 우회하지 않는다.
    suspend fun readProtection(): ByteArray = throw BleException(BleFailure.Unsupported)
    suspend fun writeProtected(bytes: ByteArray) { throw BleException(BleFailure.Unsupported) }
    fun close()
}

/** LF 이전에는 UTF-8을 해석하지 않는다. 한글 한 글자가 여러 Notify로 나뉘어도 손상되지 않는다. */
internal class BleLineBuffer(private val maxBytes: Int) {
    private val data = ByteArray(maxBytes)
    private var size = 0
    fun append(packet: ByteArray): ByteArray? {
        if (packet.isEmpty() || packet.size > 20) throw BleException(BleFailure.InvalidFrame)
        for ((index, value) in packet.withIndex()) {
            if (size == maxBytes) throw BleException(BleFailure.Overflow)
            data[size++] = value
            if (value == 10.toByte()) {
                // 단일 요청/응답 계약에서 뒤따르는 데이터는 다른 응답으로 몰래 넘기지 않는다.
                if (index != packet.lastIndex) throw BleException(BleFailure.InvalidFrame)
                return data.copyOf(size)
            }
        }
        return null
    }
}

internal class NusBleConnection(private val link: BlePacketLink) : BleConnection {
    private val exchangeLock = Mutex()
    override val state = link.state

    override suspend fun prepareProtectedTransport() = exchangeLock.withLock {
        try { verifyProtection() } catch (failure: Throwable) { link.close(); throw failure }
    }

    // 일반/보호 교환이 같은 TX 알림을 공유하므로 하나의 잠금으로 요청 전체를 직렬화한다.
    override suspend fun exchange(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray =
        exchangeFrame(request, maxResponseBytes, responseTimeoutMillis, protected = false)

    override suspend fun exchangeProtected(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long): ByteArray =
        exchangeFrame(request, maxResponseBytes, responseTimeoutMillis, protected = true)

    private suspend fun verifyProtection() {
        if (state.value != BleLinkState.Ready) throw BleException(BleFailure.Disconnected)
        // OS 페어링 UI의 사용자 응답 시간이다. nonce의 60초와 별개이며 이 확인 뒤 nonce를 받는다.
        val probe = withTimeoutOrNull(60_000) { link.readProtection() }
            ?: throw BleException(BleFailure.Timeout)
        if (!probe.contentEquals(byteArrayOf(1))) throw BleException(BleFailure.SecureTransportRequired)
    }

    private suspend fun exchangeFrame(request: ByteArray, maxResponseBytes: Int, responseTimeoutMillis: Long,
        protected: Boolean): ByteArray {
        require(maxResponseBytes in 1..4096 && responseTimeoutMillis > 0)
        require(request.size in 2..4096 && request.last() == 10.toByte() && request.dropLast(1).none { it == 10.toByte() })
        // 호출자의 배열 변경으로 전송 중 본문이 바뀌지 않게 먼저 복사한다.
        val frame = request.copyOf()
        return exchangeLock.withLock {
            try {
                if (state.value != BleLinkState.Ready) throw BleException(BleFailure.Disconnected)
                // 이전 요청의 잔여 데이터/자발 알림은 현재 응답으로 사용할 수 없다.
                if (link.packets.tryReceive().isSuccess) throw BleException(BleFailure.InvalidFrame)
                // 확인에 실패하면 단 한 조각도 전송하지 않는다. 매 교환마다 같은 연결에서 확인한다.
                if (protected) {
                    verifyProtection()
                    // 페어링 대기 중 들어온 다른 응답도 이번 요청의 응답으로 섞지 않는다.
                    if (link.packets.tryReceive().isSuccess) throw BleException(BleFailure.InvalidFrame)
                }
                val written = withTimeoutOrNull(15_000) {
                    var offset = 0
                    while (offset < frame.size) {
                        val end = minOf(offset + 20, frame.size)
                        val chunk = frame.copyOfRange(offset, end)
                        if (protected) link.writeProtected(chunk) else link.write(chunk)
                        offset = end
                    }
                    true
                } ?: false
                if (!written) throw BleException(BleFailure.Timeout)
                // 상태 조회 계약의 10초는 마지막 Write ACK 이후부터 계산한다.
                val response = withTimeoutOrNull(responseTimeoutMillis) {
                    val buffer = BleLineBuffer(maxResponseBytes)
                    var complete: ByteArray? = null
                    while (complete == null) complete = buffer.append(link.packets.receive())
                    complete
                } ?: throw BleException(BleFailure.Timeout)
                response
            } catch (failure: Throwable) {
                // 부분 프레임·취소·타임아웃 후 같은 연결을 재사용하면 다음 명령과 섞일 수 있다.
                link.close()
                throw failure
            }
        }
    }

    override fun close() = link.close()
}
