package com.ssafy.e106.features.chat.ui

import com.ssafy.e106.features.chat.data.ChatRoomRecord
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

internal fun monotonicChatClock(): () -> Duration {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow() }
}

/** 기기 시계 변경의 영향을 받지 않는 경과 시간으로, 이미 확인한 종료 시각을 뒤로 미루지 않는다. */
internal class ChatExpiry(private val now: () -> Duration) {
    private var deadline: Duration? = null
    fun reset() { deadline = null }
    fun observe(room: ChatRoomRecord, requestStarted: Duration) {
        // 응답 도착이 아니라 요청 시작을 기준으로 잡아 네트워크 지연을 남은 수명에 더하지 않는다.
        val candidate = requestStarted + (Instant.parse(room.expiresAt) - Instant.parse(room.serverTime))
        deadline = deadline?.let { minOf(it, candidate) } ?: candidate
    }
    fun remaining(): Long = ((deadline ?: now()) - now()).inWholeMilliseconds.coerceAtLeast(0)
}
