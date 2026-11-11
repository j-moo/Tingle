package com.ssafy.e106.features.chat

import com.ssafy.e106.features.chat.ui.ChatTimeParts
import com.ssafy.e106.features.chat.ui.chatTimeParts
import com.ssafy.e106.features.chat.ui.dayStarts
import com.ssafy.e106.features.chat.ui.initial
import kotlin.test.*

class ChatTimeTest {
    // 서버 시각(UTC)을 한국 시간으로: 날짜 넘김 · 오전/오후 · 12시 · 윤년 · 연말
    @Test fun partsInKoreanTime() {
        assertEquals(ChatTimeParts(11, 11, 15, 12), chatTimeParts("2026-11-11T06:12:00Z"))
        assertEquals(ChatTimeParts(11, 12, 0, 5), chatTimeParts("2026-11-11T15:05:30.123456Z"))   // 자정 넘김
        assertEquals(ChatTimeParts(1, 1, 8, 59), chatTimeParts("2026-12-31T23:59:59Z"))           // 해 넘김
        assertEquals(ChatTimeParts(2, 29, 12, 0), chatTimeParts("2028-02-29T03:00:00Z"))          // 윤년
        val noon = chatTimeParts("2026-11-11T03:00:00Z")!!; assertTrue(noon.afternoon); assertEquals(12, noon.hour12)
        val midnight = chatTimeParts("2026-11-11T15:00:00Z")!!; assertFalse(midnight.afternoon); assertEquals(12, midnight.hour12)
        assertEquals(3, chatTimeParts("2026-11-11T06:00:00Z")!!.hour12)
        assertNull(chatTimeParts("오후 3:12")); assertNull(chatTimeParts(null))
    }

    // 날짜 구분은 그날의 첫 메시지 위에만. 이전 메시지가 더 남아 있으면 맨 앞 메시지 위엔 두지 않고, 시각을 못 읽는 메시지는 건너뛴다
    @Test fun dayDividers() {
        val messages = listOf("1" to "2026-11-10T13:01:00Z", "2" to "2026-11-10T14:00:00Z", "3" to "2026-11-11T06:05:00Z", "pending" to null)
        assertEquals(mapOf("1" to (11 to 10), "3" to (11 to 11)), dayStarts(messages, complete = true))
        assertEquals(mapOf("3" to (11 to 11)), dayStarts(messages, complete = false))
        assertTrue(dayStarts(listOf("1" to "오후 3:01"), complete = true).isEmpty())
    }

    @Test fun initials() {
        assertEquals("하", "하늘".initial()); assertEquals("", null.initial()); assertEquals("", "  ".initial())
        val emoji = charArrayOf('\uD83D', '\uDE00').concatToString()
        assertEquals(emoji, (emoji + "민트").initial())   // 이모지는 통째로
    }
}
