package com.ssafy.e106.features.proposals.data

import com.ssafy.e106.core.storage.*
import kotlinx.serialization.Serializable

// null도 완료 표식으로 저장한다. 이전 캐시 삭제 실패 뒤 옛 요청이 다시 살아나는 것을 막는다.
@Serializable
internal data class ProposalRecoveryRecord(val request: PendingDecision?)

internal class ProposalPendingStore(private val prefs: AppPrefs) {
    private fun key() = PrefKey("proposal.recovery.v2", ProposalRecoveryRecord.serializer(), durable = true)
    private fun legacy() = PrefKey("proposal.pending-decision.v1", PendingDecision.serializer())

    suspend fun read(owner: Long): PrefRead<PendingDecision> {
        return when (val stored = prefs.read(key(), owner)) {
            is PrefRead.Found -> {
                val result = checked(stored.value.request, owner)
                // 복사 확인 뒤 앱이 종료됐어도 다음 진입에서 이전 파일 정리를 재시도한다.
                if(result != PrefRead.Corrupt) prefs.set(legacy(), null, owner)
                result
            }
            PrefRead.Missing -> when (val old = prefs.read(legacy(), owner)) {
                is PrefRead.Found -> {
                    val valid = checked(old.value, owner)
                    if (valid !is PrefRead.Found) valid
                    else migrate(owner, old.value)
                }
                PrefRead.Missing -> migrate(owner, null)
                else -> old.failure() // 읽기 실패·손상은 빈 기록으로 바꾸거나 덮어쓰지 않는다.
            }
            else -> stored.failure()
        }
    }

    private fun checked(value: PendingDecision?, owner: Long): PrefRead<PendingDecision> = when {
        value == null -> PrefRead.Missing
        value.owner != owner || value.proposalId <= 0 || value.key.isBlank() ||
            value.choice !in setOf("ACCEPT", "DECLINE") ||
            value.choice == "ACCEPT" && value.profileVersion.isNullOrBlank() -> PrefRead.Corrupt
        else -> PrefRead.Found(value)
    }

    private suspend fun migrate(owner: Long, request: PendingDecision?): PrefRead<PendingDecision> {
        // 같은 요청 번호와 본문을 복사하고, 새 파일에서 읽어 확인하기 전에는 이전 기록을 삭제하지 않는다.
        val saved = save(owner, request)
        if (saved !is PrefRead.Found) return saved.failure()
        prefs.set(legacy(), null, owner)
        return checked(request, owner)
    }

    suspend fun save(owner: Long, request: PendingDecision?): PrefRead<Unit> {
        val record = ProposalRecoveryRecord(request)
        prefs.set(key(), record, owner)
        return when (val stored = prefs.read(key(), owner)) {
            is PrefRead.Found -> if (stored.value == record) PrefRead.Found(Unit) else PrefRead.Unreadable
            PrefRead.Missing -> PrefRead.Unreadable // set은 실패를 던지지 않으므로 다시 읽어 일치 여부를 검사한다.
            else -> stored.failure()
        }
    }
}

// 성공 값의 타입은 보존하고 실패 상태만 전달한다.
private fun PrefRead<*>.failure(): PrefRead<Nothing> = when(this) {
    PrefRead.Missing -> PrefRead.Missing
    PrefRead.Unreadable -> PrefRead.Unreadable
    PrefRead.Corrupt -> PrefRead.Corrupt
    PrefRead.OtherUser -> PrefRead.OtherUser
    is PrefRead.Found -> error("Expected storage failure")
}
