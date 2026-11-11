package com.ssafy.e106.features.proposals

import com.ssafy.e106.features.proposals.ui.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProposalRemainingTest {
    private val server = "2026-11-11T03:00:00Z"

    // 남은 시간은 서버 시각 기준(마감 − 서버 시각). 지났으면 0, 못 읽으면 표시 안 함
    @Test fun remaining() {
        assertEquals(2.hours, remainingUntil("2026-11-11T05:00:00Z", server))
        assertEquals(Duration.ZERO, remainingUntil("2026-11-11T02:00:00Z", server))
        assertNull(remainingUntil("내일 오후", server))
        assertNull(remainingUntil(null, server))
        assertNull(remainingUntil("2026-11-11T05:00:00Z", null))
        // 서버 Instant 는 소수 초가 붙어 온다
        assertEquals(2.hours, remainingUntil("2026-11-11T05:00:00.123456Z", "2026-11-11T03:00:00.123456Z"))
    }

    // 기기 시계가 몇 시든(여기 서버 시각은 과거 날짜) 서버 기준으로 남은 시간을 잡고, 받은 뒤 흐른 시간만 줄어든다
    @Test fun closesAtIgnoresDeviceClock() {
        val item = ProposalListItem(1, "하늘", 27, closesAt = TimeSource.Monotonic.markNow() + 2.hours)
        val left = assertNotNull(item.remaining())
        assertTrue(left > 2.hours - 1.minutes && left <= 2.hours, "남은 시간 $left")
        assertEquals(Duration.ZERO, ProposalListItem(2, null, null, closesAt = TimeSource.Monotonic.markNow() - 1.hours).remaining())
        assertNull(ProposalListItem(3, null, null).remaining())
    }

    // 표시 경계: 시간은 내림, 1시간 미만은 분(최소 1분), 정각 · 지남은 곧 마감, 3시간 미만은 빨강
    @Test fun labelBoundaries() {
        assertEquals(RemainingLabel.Minutes(59), remainingLabel(59.minutes + 59.seconds))
        assertEquals(RemainingLabel.Hours(1), remainingLabel(1.hours))
        assertEquals(RemainingLabel.Minutes(1), remainingLabel(30.seconds))
        assertEquals(RemainingLabel.Closing, remainingLabel(Duration.ZERO))
        assertEquals(RemainingLabel.Hours(23), remainingLabel(23.hours + 59.minutes))
        assertTrue(2.hours + 59.minutes < ProposalUrgent); assertFalse(3.hours < ProposalUrgent)
    }
}
