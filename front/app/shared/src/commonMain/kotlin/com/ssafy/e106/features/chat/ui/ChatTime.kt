package com.ssafy.e106.features.chat.ui

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 서버 시각(ISO-8601)을 화면용으로 나눈다. 못 읽으면 null(호출부가 받은 글자를 그대로 쓴다).
 * ponytail: 한국 시간(UTC+9) 고정 — 공통 코드에 기기 시간대 API 가 없다. 해외 사용을 지원하면 kotlinx-datetime 으로 바꾼다
 */
internal data class ChatTimeParts(val month: Int, val day: Int, val hour: Int, val minute: Int) {
    val afternoon get() = hour >= 12
    val hour12 get() = (hour % 12).let { if (it == 0) 12 else it }
}

internal fun chatTimeParts(iso: String?): ChatTimeParts? {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val seconds = (instant + 9.hours).epochSeconds
    val days = seconds.floorDiv(86_400L); val ofDay = seconds.mod(86_400L).toInt()
    // 1970-01-01 부터의 일수 → 월 · 일 (Howard Hinnant 의 civil_from_days)
    val z = days + 719_468; val era = z.floorDiv(146_097L); val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt(); val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return ChatTimeParts(month, day, ofDay / 3600, ofDay % 3600 / 60)
}

/** 오래된 순서의 (메시지 id, 서버 시각)에서 "그날의 첫 메시지" id → (월, 일). [complete] 가 아니면(이전 메시지가 더 있음) 맨 앞 메시지는 뺀다 */
internal fun dayStarts(messages: List<Pair<String, String?>>, complete: Boolean): Map<String, Pair<Int, Int>> {
    val result = mutableMapOf<String, Pair<Int, Int>>()
    var previous: Pair<Int, Int>? = null
    messages.forEachIndexed { i, (id, time) ->
        val day = chatTimeParts(time)?.let { it.month to it.day }
        if (day != null && day != previous && (i > 0 || complete)) result[id] = day
        if (day != null) previous = day
    }
    return result
}

/** 동그란 자리 표시용 첫 글자. 이모지처럼 두 단위로 된 글자는 통째로 */
internal fun String?.initial(): String {
    val name = this?.trim().orEmpty()
    return when {
        name.isEmpty() -> ""
        name[0].isHighSurrogate() && name.length > 1 -> name.substring(0, 2)
        else -> name.substring(0, 1)
    }
}
