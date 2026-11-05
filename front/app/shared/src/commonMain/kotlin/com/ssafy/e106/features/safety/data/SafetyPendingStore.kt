package com.ssafy.e106.features.safety.data

import com.ssafy.e106.core.storage.*
import kotlinx.serialization.Serializable

/** null도 완료 표식으로 보존한다. 이전 캐시 정리가 실패해도 옛 요청을 다시 복구하지 않는다. */
@Serializable
internal data class SafetyRecoveryRecord(val request: PendingSafety?)

/** 방·작업별 미확인 요청은 캐시가 아닌 durable 저장소에서 읽기 결과를 구분한다. */
internal class SafetyPendingStore(private val prefs: AppPrefs) {
    private fun key(room: Long, type: String) = PrefKey("safety.recovery.$room.$type", SafetyRecoveryRecord.serializer(), durable = true)
    private fun legacy(room: Long, type: String) = PrefKey("safety.pending.$room.$type", PendingSafety.serializer())

    suspend fun read(room: Long, type: String, owner: Long): PrefRead<PendingSafety> {
        return when (val record = prefs.read(key(room, type), owner)) {
            is PrefRead.Found -> {
                val checked = checked(record.value.request, room, type, owner)
                if (checked != PrefRead.Corrupt) prefs.set(legacy(room, type), null, owner)
                checked
            }
            PrefRead.Missing -> when (val old = prefs.read(legacy(room, type), owner)) {
                is PrefRead.Found -> {
                    val checked = checked(old.value, room, type, owner)
                    if (checked !is PrefRead.Found) checked else migrate(room, type, owner, old.value)
                }
                PrefRead.Missing -> migrate(room, type, owner, null)
                else -> old.failure()
            }
            else -> record.failure()
        }
    }

    private fun checked(request: PendingSafety?, room: Long, type: String, owner: Long): PrefRead<PendingSafety> = when {
        request == null -> PrefRead.Missing
        request.owner != owner || request.room != room || request.operation != type ||
            type !in setOf("BLOCK", "LEAVE") || request.key.isBlank() -> PrefRead.Corrupt
        else -> PrefRead.Found(request)
    }

    private suspend fun migrate(room: Long, type: String, owner: Long, request: PendingSafety?): PrefRead<PendingSafety> {
        // 먼저 같은 요청 키를 복사·검증하고 나서만 기존 캐시를 지운다. 읽기 실패는 빈 값으로 이관하지 않는다.
        val saved = save(room, type, owner, request)
        if (saved !is PrefRead.Found) return saved.failure()
        prefs.set(legacy(room, type), null, owner)
        return checked(request, room, type, owner)
    }

    suspend fun save(room: Long, type: String, owner: Long, request: PendingSafety?): PrefRead<Unit> {
        val record = SafetyRecoveryRecord(request)
        prefs.set(key(room, type), record, owner)
        // 기반 set은 실패를 던지지 않으므로 저장 결과를 다시 읽어 비교해야 서버 전송을 허용할 수 있다.
        return when (val saved = prefs.read(key(room, type), owner)) {
            is PrefRead.Found -> if (saved.value == record) PrefRead.Found(Unit) else PrefRead.Unreadable
            PrefRead.Missing -> PrefRead.Unreadable
            else -> saved.failure()
        }
    }
}

private fun PrefRead<*>.failure(): PrefRead<Nothing> = when (this) {
    PrefRead.Missing -> PrefRead.Missing
    PrefRead.Unreadable -> PrefRead.Unreadable
    PrefRead.Corrupt -> PrefRead.Corrupt
    PrefRead.OtherUser -> PrefRead.OtherUser
    is PrefRead.Found -> error("Expected storage failure")
}
