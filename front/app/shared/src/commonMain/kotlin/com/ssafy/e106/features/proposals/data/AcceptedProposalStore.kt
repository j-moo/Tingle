package com.ssafy.e106.features.proposals.data

import com.ssafy.e106.core.storage.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// 제안 번호만 둔다. 상대 닉네임 등은 서버가 권한을 거둔 뒤에도 남지 않게 저장하지 않는다
@Serializable internal data class AcceptedProposal(val proposalId: Long)

/**
 * 내가 수락하고 상대 응답을 기다리는 제안. 서버 목록은 끝난 제안(매칭 안 됨 · 기한 지남)을 주지 않으므로,
 * 목록에서 빠지면 이 기록으로 한 번 결과를 물어 "응답 대기"에 한 번 보여 준다. 확인하면 지운다.
 * ponytail: 기기 · 계정별 기록이라 다른 기기나 재설치에선 안 보임. 서버 목록이 확인 안 한 결과를 주면 이 저장소를 없앤다
 */
internal class AcceptedProposalStore(private val prefs: AppPrefs) {
    // 결정 복구(Idempotency-Key)와 달리 안내용이라 일반 캐시에 둔다
    private val key = PrefKey("proposal.accepted.v1", ListSerializer(AcceptedProposal.serializer()))
    // 읽고-쓰기가 겹쳐 지운 기록이 되살아나지 않게
    private val lock = Mutex()

    // 읽기 실패는 빈 기록으로 본다(한 번 보여 주는 안내일 뿐 결정 안전과는 무관)
    suspend fun read(owner: Long): List<AcceptedProposal> = (prefs.read(key, owner) as? PrefRead.Found)?.value.orEmpty()
    suspend fun add(owner: Long, id: Long) = lock.withLock {
        val stored = prefs.read(key, owner)
        // 잠깐 못 읽었으면 덮어써서 기존 기록을 날리지 않는다. 깨진 값(형식 바뀜 등)은 새로 쓴다. 이미 있으면 쓰지 않는다(대기 중 5초 확인마다 쓰지 않게)
        val now = when (stored) { is PrefRead.Found -> stored.value; PrefRead.Missing, PrefRead.Corrupt -> emptyList(); else -> return@withLock }
        if (now.none { it.proposalId == id }) prefs.set(key, (now + AcceptedProposal(id)).takeLast(50), owner)
    }
    suspend fun remove(owner: Long, ids: Set<Long>) = lock.withLock {
        if (ids.isEmpty()) return@withLock
        val now = read(owner)
        if (now.any { it.proposalId in ids }) prefs.set(key, now.filter { it.proposalId !in ids }, owner)
    }
}
