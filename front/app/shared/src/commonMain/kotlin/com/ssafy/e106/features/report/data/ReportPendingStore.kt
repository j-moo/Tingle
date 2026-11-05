package com.ssafy.e106.features.report.data

import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.report.ui.ReportReason
import kotlinx.serialization.Serializable

// null도 완료 표식으로 저장한다. 이전 캐시 삭제 실패 뒤 옛 요청이 다시 살아나는 것을 막는다.
@Serializable
internal data class ReportRecoveryRecord(val request: PendingReport?)

internal class ReportPendingStore(private val prefs: AppPrefs) {
    private fun key(room: Long) = PrefKey("report.recovery.$room", ReportRecoveryRecord.serializer(), durable = true)
    private fun legacy(room: Long) = PrefKey("report.pending.$room", PendingReport.serializer())

    suspend fun read(room: Long, owner: Long): PrefRead<PendingReport> {
        return when (val stored = prefs.read(key(room), owner)) {
            is PrefRead.Found -> {
                val result = checked(stored.value.request, room, owner)
                // 복사 확인 뒤 앱이 종료됐어도 다음 진입에서 이전 파일 정리를 재시도한다.
                if(result != PrefRead.Corrupt) prefs.set(legacy(room), null, owner)
                result
            }
            PrefRead.Missing -> when (val old = prefs.read(legacy(room), owner)) {
                is PrefRead.Found -> {
                    val valid = checked(old.value, room, owner)
                    if (valid !is PrefRead.Found) valid
                    else migrate(room, owner, old.value)
                }
                PrefRead.Missing -> migrate(room, owner, null)
                else -> old.failure() // 읽기 실패·손상은 빈 기록으로 바꾸거나 덮어쓰지 않는다.
            }
            else -> stored.failure()
        }
    }

    private fun checked(value: PendingReport?, room: Long, owner: Long): PrefRead<PendingReport> = when {
        value == null -> PrefRead.Missing
        value.owner != owner || value.body.chatRoomId != room || value.key.isBlank() ||
            ReportReason.entries.none { it.name == value.body.reason } -> PrefRead.Corrupt
        else -> PrefRead.Found(value)
    }

    private suspend fun migrate(room: Long, owner: Long, request: PendingReport?): PrefRead<PendingReport> {
        // 같은 요청 번호와 본문을 복사하고, 새 파일에서 읽어 확인하기 전에는 이전 기록을 삭제하지 않는다.
        val saved = save(room, owner, request)
        if (saved !is PrefRead.Found) return saved.failure()
        prefs.set(legacy(room), null, owner)
        return checked(request, room, owner)
    }

    suspend fun save(room: Long, owner: Long, request: PendingReport?): PrefRead<Unit> {
        val record = ReportRecoveryRecord(request)
        prefs.set(key(room), record, owner)
        return when (val stored = prefs.read(key(room), owner)) {
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
