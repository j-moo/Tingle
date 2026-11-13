package com.ssafy.e106.features.account.data

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.storage.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 요청 전 키와 최종 실행 의도를 영수증과 같은 보호 파일에 보존한다. 로그/화면 상태로 전달하지 않는다. */
@Serializable
internal data class WithdrawalTicket(val owner: Long, val key: String, val operation: WithdrawalOperation? = null,
    val receipt: String? = null, val attempted: Boolean = false, val autoResume: Boolean = false,
    // 취소 응답이 유실돼도 같은 준비 ID의 취소를 이어간다. 만료 후 새 준비 발급과 구분한다.
    val cancelRequested: Boolean = false) {
    override fun toString() = "WithdrawalTicket(REDACTED)"
}
@Serializable private data class Journal(val tickets: List<WithdrawalTicket>, val lastOwner: Long? = null)

/** 단일 Coordinator의 직렬 작업 안에서만 사용한다. 손상은 빈 목록으로 바꾸지 않는다. */
internal class WithdrawalJournal(private val store: ProtectedRecordStore) {
    private val key = "withdrawal.journal.v1"
    private val lock = Mutex()
    suspend fun all(): List<WithdrawalTicket> = lock.withLock { read().tickets }
    suspend fun lastOwner(): Long? = lock.withLock { read().lastOwner }
    // 다른 계정 로그인 후 로그아웃해도 과거 사용자의 결과가 자동 공개되지 않도록 마지막 계정을 내구 저장한다.
    suspend fun markSession(owner: Long) = lock.withLock { write(read().copy(lastOwner = owner)) }
    private suspend fun read(): Journal = when (val result = store.read(key)) {
        ProtectedRead.Missing -> Journal(emptyList())
        is ProtectedRead.Found -> try {
            ApiJson.decodeFromString(Journal.serializer(), result.value).also { journal ->
                val tickets = journal.tickets
                check(tickets.map { it.owner }.distinct().size == tickets.size)
                check(tickets.all(::valid))
            }
        } catch (_: Exception) { error("Protected withdrawal journal unavailable") }
        else -> error("Protected withdrawal journal unavailable")
    }
    suspend fun save(ticket: WithdrawalTicket) = lock.withLock {
        // 서버의 잘못된 영수증/실행 ID를 디스크에 쓴 뒤 복구 저널 전체가 읽히지 않는 일을 막는다.
        check(valid(ticket))
        val existing = read(); val old = existing.tickets; val current = old.singleOrNull { it.owner == ticket.owner }
        check(current == null || (current.key == ticket.key &&
            (current.operation == null || current.operation.withdrawalId == ticket.operation?.withdrawalId) &&
            (!current.attempted || ticket.attempted)))
        write(existing.copy(tickets = old.filterNot { it.owner == ticket.owner } + ticket))
    }
    suspend fun remove(ticket: WithdrawalTicket) = lock.withLock {
        val existing = read(); val old = existing.tickets; check(old.singleOrNull { it.owner == ticket.owner } == ticket)
        write(existing.copy(tickets = old.filterNot { it.owner == ticket.owner }))
    }
    /** 인증된 서버가 만료 후 발급한 새 준비만 교체한다. 기존 실행 기록을 지운 뒤 쓰는 중간 상태를 만들지 않는다. */
    suspend fun replaceExpired(expected: WithdrawalTicket, prepared: WithdrawalPrepared) = lock.withLock {
        val existing = read()
        check(!expected.cancelRequested && existing.tickets.singleOrNull { it.owner == expected.owner } == expected)
        val next = expected.copy(operation = prepared.operation, receipt = prepared.receiptToken,
            attempted = false, autoResume = false)
        check(valid(next) && prepared.operation.status == "PREPARED" &&
            prepared.operation.withdrawalId != expected.operation?.withdrawalId)
        write(existing.copy(tickets = existing.tickets.filterNot { it.owner == expected.owner } + next))
    }
    private suspend fun write(journal: Journal) {
        store.write(key, ApiJson.encodeToString(Journal.serializer(), journal))
        check(read() == journal) { "Withdrawal journal was not persisted" }
    }
    private fun valid(it: WithdrawalTicket) = it.owner > 0 && withdrawalUuid(it.key) &&
        (!it.cancelRequested || (!it.attempted && it.operation?.status == "PREPARED")) &&
        ((it.operation == null && it.receipt == null && !it.attempted) ||
            (it.operation != null && withdrawalUuid(it.operation.withdrawalId) && !it.receipt.isNullOrBlank() &&
                it.receipt.none { char -> char.isWhitespace() || char.code < 32 }))
}
