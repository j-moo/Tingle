package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.ui.ProfileDetailsState
import com.ssafy.e106.features.profile.ui.validNickname
import kotlin.test.*

class ProfileTextRulesTest {
    @Test fun nicknameNfcTrimsOnlyEdgeSpacesAndKeepsCaseAndInternalSpaces() {
        assertEquals("가을  Ab9", normalizeProfileNickname("  가을  Ab9  "))
        assertTrue(validNickname("  가을  "))
        assertTrue(validNickname("가".repeat(20)))
        assertFalse(validNickname("가".repeat(21)))
        assertFalse(validNickname("가"))
        assertFalse(validNickname("  "))
        for (invalid in listOf("홍ㄱ", "ㄱㄴ", "가\t나", "\u00A0가을", "가을😀", "가을!", "ＡＢ")) {
            assertFalse(validNickname(invalid), invalid)
        }
    }

    @Test fun combinedEmojiAndDecomposedCharactersAreSingleGraphemes() {
        for (one in listOf("가", "가", "e\u0301", "👨‍👩‍👧‍👦", "👍🏽", "🇰🇷", "1️⃣", "☕️")) {
            assertEquals(1, profileGraphemeCount(one), one)
        }
        assertEquals(0, profileGraphemeCount(""))
        assertEquals(3, profileGraphemeCount("👨‍👩‍👧‍👦👍🏽🇰🇷"))
    }

    @Test fun introductionBoundaryUsesSameCountForCounterAndContinue() {
        val state = ProfileDetailsState(introduction = "가".repeat(199) + "👨‍👩‍👧‍👦", introductionConsent = true)
        assertEquals(200, state.introductionLength); assertTrue(state.canContinue)
        val over = state.copy(introduction = state.introduction + "👍🏽")
        assertEquals(201, over.introductionLength); assertFalse(over.canContinue)
        assertTrue(over.introductionTooLong)
    }

    @Test fun mixedNewlinesAreNormalizedBeforeCountingAndValidation() {
        val state = ProfileDetailsState(introduction = "a\r\nb\rc\nd", introductionConsent = true)
        assertEquals("a\nb\nc\nd", state.normalizedIntroduction)
        assertEquals(7, state.introductionLength); assertEquals(3, state.introductionLineBreaks)
        assertTrue(state.canContinue)
        val over = state.copy(introduction = state.introduction + "\re")
        assertEquals(4, over.introductionLineBreaks); assertTrue(over.introductionTooManyLines)
        assertFalse(over.canContinue)
    }

    @Test fun BlankIntroductionIsAbsentButNonblankWhitespaceIsPreserved() {
        val blank = ProfileDetailsState(introduction = " \t\r\n".repeat(60))
        assertNull(blank.normalizedIntroduction); assertEquals(0, blank.introductionLength)
        assertTrue(blank.canContinue)
        assertEquals("  hi\n ", normalizeProfileIntroduction("  hi\r\n "))
        assertEquals("\u00A0", normalizeProfileIntroduction("\u00A0"))
    }
}
