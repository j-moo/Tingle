package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** payload는 GET으로 다시 받는다. HTTP 유실 복구에 필요한 키·실행 식별자·기기 증명만 계정별로 저장한다. */
@Serializable data class SyncTicket(val deviceId: Long, val serial: String, val createKey: String,
    val identity: SyncIdentity? = null, val ackKey: String? = null, val ack: KeyringSyncAck? = null,
    val complete: Boolean = false, val deviceNonce: String? = null, val bleAttempted: Boolean = false,
    val nonceReceivedAt: String? = null) {
    override fun toString() = "SyncTicket(REDACTED)"
}
@Serializable private data class SyncRecovery(val tickets: List<SyncTicket>)

/** 여러 기기의 복구 기록을 덮어쓰지 않는다. 등록 저장과 동기화 저장의 경쟁은 같은 Mutex로 직렬화한다. */
class KeyringSyncStore(private val prefs: AppPrefs) {
    private val key = PrefKey("keyring.sync.v1", SyncRecovery.serializer(), durable = true)
    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)
    val targetsChanged = changes.asStateFlow()
    suspend fun read(owner: Long): List<SyncTicket> = when (val found = prefs.read(key, owner)) {
        is PrefRead.Found -> found.value.tickets.also { entries ->
            check(entries.map { it.deviceId }.distinct().size == entries.size && entries.all(::valid))
        }
        PrefRead.Missing -> emptyList()
        else -> error("Sync recovery unavailable")
    }
    private fun valid(t: SyncTicket): Boolean = t.deviceId > 0 && KeyringRegistrationProtocol.serial(t.serial) &&
        KeyringRegistrationProtocol.uuid(t.createKey) &&
        // 응답 유실 후 다른 소유 순번이나 증명 버전의 ACK를 재사용하지 않도록 복구 파일도 검사한다.
        (t.identity == null || (t.identity.deviceId == t.deviceId && KeyringRegistrationProtocol.uuid(t.identity.syncRunId) &&
            KeyringRegistrationProtocol.validEpoch(t.identity.proofProtocolVersion, t.identity.ownershipEpoch))) &&
        ((t.ack == null && t.ackKey == null) || (t.identity != null && t.ack != null && t.ackKey != null &&
            KeyringRegistrationProtocol.uuid(t.ackKey) && t.ack.settingsVersion == t.identity.settingsVersion &&
            t.ack.payloadHash == t.identity.payloadHash && t.ack.proof.keyId == t.identity.keyId &&
            t.ack.proof.protocolVersion == t.identity.proofProtocolVersion)) &&
        (!t.complete || t.identity != null) && (t.deviceNonce == null || KeyringSyncProtocol.validDeviceNonce(t.deviceNonce))

    /** 등록 성공 시 호출한다. 기존 실행을 보존하고 새 대상이 생긴 경우에만 자동 진행을 깨운다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun rememberTarget(owner: Long, deviceId: Long, serial: String) = lock.withLock {
        val entries = read(owner)
        val previous = entries.find { it.deviceId == deviceId }
        if (previous != null) { check(previous.serial == serial); return@withLock }
        write(owner, entries + SyncTicket(deviceId, serial, Uuid.random().toString()))
        changes.value++
    }
    suspend fun save(owner: Long, ticket: SyncTicket) = lock.withLock {
        val entries = read(owner)
        check(entries.any { it.deviceId == ticket.deviceId && it.serial == ticket.serial })
        write(owner, entries.filterNot { it.deviceId == ticket.deviceId } + ticket)
    }
    /** 이전 APK 대상 선택을 잘못했을 때 경로만 고친다. 실행·멱등키·이미 받은 증명은 절대 새로 만들지 않는다. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun selectTarget(owner: Long, deviceId: Long, serial: String) = lock.withLock {
        val entries = read(owner)
        val previous = entries.find { it.deviceId == deviceId }
        val target = previous?.copy(serial = serial) ?: SyncTicket(deviceId, serial, Uuid.random().toString())
        write(owner, entries.filterNot { it.deviceId == deviceId } + target)
        changes.value++
    }
    private suspend fun write(owner: Long, tickets: List<SyncTicket>) {
        check(tickets.all(::valid))
        val record = SyncRecovery(tickets)
        prefs.set(key, record, owner)
        check((prefs.read(key, owner) as? PrefRead.Found)?.value == record) { "Sync recovery not persisted" }
    }
}
