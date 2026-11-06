package com.ssafy.e106.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 화면에는 플랫폼 객체 대신 탐색 세션에서만 유효한 ID를 제공한다. 서버 deviceId/소유 인증이 아니다. */
data class BleCandidate(val id: String, val name: String?, val rssi: Int)
enum class BleLinkState { Ready, Closed }
enum class BleFailure { Unsupported, PermissionRequired, BluetoothOff, LocationOff, Busy, NotFound, ScanFailed,
    ConnectFailed, ServiceMissing, SubscribeFailed, WriteFailed, Disconnected, Timeout, InvalidFrame, Overflow,
    SecureTransportRequired }
class BleException(val reason: BleFailure) : Exception(reason.name)

/**
 * 한 앱에서 한 번의 탐색/연결을 소유하는 플랫폼 인터페이스.
 * scan 수집 취소가 탐색 중지다. connect는 서비스 확인과 Notify 구독까지 완료된 채널만 반환한다.
 * 연결/교환 취소·시간 초과는 연결을 닫는다. 자동 재시도, 서버 등록, 설정 적용은 수행하지 않는다.
 */
interface KeyringBle {
    fun scan(): Flow<List<BleCandidate>>
    suspend fun connect(candidateId: String): BleConnection
    /** 현재 탐색 후보가 이 프로세스에서 마지막으로 연결한 기기인지 플랫폼 안에서만 대조한다.
     * 탐색 ID는 매번 바뀌므로 화면이 예전 ID를 비교하면 안 된다. 주소를 노출/저장하지 않으며 소유 인증을 대신하지 않는다. */
    fun isPreviousDevice(candidateId: String): Boolean = false
}

interface BleConnection {
    val state: StateFlow<BleLinkState>
    /** UTF-8 JSON+LF 하나를 보내고 LF 응답 하나를 받는다. Write ACK 자체는 업무 성공이 아니다. */
    suspend fun exchange(request: ByteArray, maxResponseBytes: Int = 4096, responseTimeoutMillis: Long = 10_000): ByteArray
    /**
     * 개인정보 없는 보호 특성 Read(01)로 현재 연결의 암호화 준비를 확인한다.
     * 유효 시간이 짧은 challenge 발급 전에 호출한다. 기기 신원/소유 인증을 대신하지 않는다.
     * 미지원 플랫폼은 일반 RX로 대체하지 않고 거부한다.
     */
    suspend fun prepareProtectedTransport() { throw BleException(BleFailure.Unsupported) }
    /** 보호 특성을 다시 확인한 뒤 그 특성에만 쓴다. 재연결한 객체는 이전 확인을 승계하지 않는다. */
    suspend fun exchangeProtected(request: ByteArray, maxResponseBytes: Int = 4096,
        responseTimeoutMillis: Long = 10_000): ByteArray = throw BleException(BleFailure.Unsupported)
    fun close()
}
