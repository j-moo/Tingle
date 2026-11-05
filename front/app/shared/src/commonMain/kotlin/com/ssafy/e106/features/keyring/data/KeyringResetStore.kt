package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.storage.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** 최초 요청 본문·키와 마지막 실행/증명을 함께 보존한다. 개인키나 로그인 토큰을 담지 않는다. */
@Serializable data class ResetTicket(val deviceId: Long, val serial: String, val requestKey: String,
    val start: ResetStartBody, val run: KeyringResetRun? = null, val ack: ResetAckBody? = null,
    val commandAttempted: Boolean = false) {
    override fun toString() = "ResetTicket(REDACTED)"
}
@Serializable private data class ResetJournal(val tickets: List<ResetTicket>)

/** 기기별 실행을 유지한다. 손상/읽기 실패는 빈 기록과 구분하며 이전 요청을 덮어쓰지 않는다. */
class KeyringResetStore(private val prefs: AppPrefs) {
    private val key = PrefKey("keyring.reset.v1", ResetJournal.serializer(), durable = true)
    private val lock = Mutex()
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    suspend fun read(owner: Long): List<ResetTicket> = when (val result = prefs.read(key, owner)) {
        is PrefRead.Found -> result.value.tickets.also { list ->
            check(list.map { it.deviceId }.distinct().size == list.size && list.all(::valid))
        }
        PrefRead.Missing -> emptyList()
        else -> error("Reset journal unavailable")
    }
    /** 새로운 작업은 동일 기기에 복구 기록이 없는 경우만 시작한다. 완료 기록 삭제/재시작은 별도 정책이다. */
    suspend fun insert(owner: Long, ticket: ResetTicket) = lock.withLock {
        val list = read(owner); check(list.none { it.deviceId == ticket.deviceId })
        write(owner, list + ticket)
    }
    suspend fun save(owner: Long, ticket: ResetTicket) = lock.withLock {
        val list = read(owner); val old = list.single { it.deviceId == ticket.deviceId }
        check(old.serial == ticket.serial && old.requestKey == ticket.requestKey && old.start == ticket.start)
        check(old.run == null || old.run.identity() == ticket.run?.identity())
        check(old.ack == null || old.ack == ticket.ack)
        check(!old.commandAttempted || ticket.commandAttempted)
        check(old.run?.state != "COMPLETED" || ticket.run?.state == "COMPLETED")
        write(owner, list.filterNot { it.deviceId == ticket.deviceId } + ticket)
    }
    private suspend fun write(owner: Long, tickets: List<ResetTicket>) {
        check(tickets.all(::valid))
        val journal = ResetJournal(tickets)
        prefs.set(key, journal, owner)
        check((prefs.read(key, owner) as? PrefRead.Found)?.value == journal) { "Reset journal not persisted" }
        changed.value++
    }
    private fun valid(t: ResetTicket): Boolean = t.deviceId > 0 && KeyringRegistrationProtocol.serial(t.serial) &&
        KeyringRegistrationProtocol.uuid(t.requestKey) && KeyringResetProtocol.challenge(t.start.deviceChallenge) &&
        t.serial == t.start.deviceChallenge.deviceSerial &&
        (t.run == null || (KeyringResetProtocol.valid(t.run) && t.run.deviceId == t.deviceId &&
            t.run.discardUnsentConfirmed == t.start.discardUnsentConfirmed &&
            t.run.fromOwnershipEpoch == t.start.deviceChallenge.ownershipEpoch &&
            (t.start.deviceChallenge.localOwnerGeneration == "NONE" || t.start.deviceChallenge.localOwnerGeneration == t.run.ownerGeneration))) &&
        (t.ack == null || (t.run != null && KeyringResetProtocol.signature(t.ack.proof))) &&
        (!t.commandAttempted || t.run != null)
}
