package com.ssafy.e106.features.chat

import com.ssafy.e106.features.chat.ui.isSameKstDay
import com.ssafy.e106.features.safety.ui.remainingQuestions
import kotlin.test.*
import kotlin.time.Instant

class ChatListHelpersTest {
    @Test fun sameKstDayUsesKoreanDateAndYear() {
        val now = Instant.parse("2026-11-15T15:30:00Z")   // 한국 11-16 00:30
        assertTrue(isSameKstDay("2026-11-15T15:05:00Z", now))    // 한국 11-16 00:05
        assertFalse(isSameKstDay("2026-11-15T14:55:00Z", now))   // 한국 11-15 23:55
        assertFalse(isSameKstDay("2025-11-15T15:05:00Z", now))   // 1년 전 같은 날
        assertFalse(isSameKstDay("broken", now))
    }
    @Test fun usedQuestionsAreRemovedIgnoringSpaces() {
        val qs = listOf("주말엔 뭐 하세요?", "좋아하는 음식은?", "여행 가고 싶은 곳은?")
        assertEquals(qs.drop(1), remainingQuestions(qs, setOf("주말엔 뭐 하세요?")))
        assertEquals(listOf(qs[2]), remainingQuestions(listOf(" " + qs[0], qs[1], qs[2]), setOf(qs[0], qs[1])))
        assertTrue(remainingQuestions(qs, qs.toSet()).isEmpty())
    }
}
