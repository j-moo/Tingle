package com.ssafy.e106.features.preferences

import com.ssafy.e106.features.preferences.ui.AgePreferenceMode
import com.ssafy.e106.features.preferences.ui.AgePreferenceState
import kotlin.test.*

class AgePreferenceStateTest {
    private val range = AgePreferenceState(mode = AgePreferenceMode.Range)

    // 체크박스 없이: 막대 최대 손잡이를 끝(60)까지 밀거나 최대 칸을 비우면 제한 없음
    @Test fun sliderEndOrEmptyMaximumMeansNoUpperLimit() {
        val atEnd = range.changeSlider(30, 60)
        assertTrue(atEnd.noUpperLimit); assertEquals("", atEnd.maximumAge); assertTrue(atEnd.canContinue)
        val inRange = atEnd.changeSlider(25, 32)
        assertFalse(inRange.noUpperLimit); assertEquals("32", inRange.maximumAge); assertTrue(inRange.canContinue)
        val cleared = inRange.changeMaximum("")
        assertTrue(cleared.noUpperLimit); assertTrue(cleared.canContinue)
        val typed = cleared.changeMaximum("70")
        assertFalse(typed.noUpperLimit); assertEquals(70, typed.maximumValue); assertTrue(typed.canContinue)
    }

    // 처음 범위를 고르면 보이는 그대로(19세 이상 · 제한 없음) 저장할 수 있어야 한다. 이미 쓴 값은 건드리지 않는다
    @Test fun choosingRangeFillsWhatIsShown() {
        val fresh = AgePreferenceState().selectMode(AgePreferenceMode.Range)
        assertEquals("19", fresh.minimumAge); assertTrue(fresh.noUpperLimit); assertTrue(fresh.canContinue)
        val kept = AgePreferenceState(AgePreferenceMode.Any, "25", "32").selectMode(AgePreferenceMode.Range)
        assertEquals("25", kept.minimumAge); assertEquals("32", kept.maximumAge); assertFalse(kept.noUpperLimit)
        assertTrue(AgePreferenceState(mode = AgePreferenceMode.Range).changeMinimum("27").canContinue)
    }

    @Test fun reversedRangeCannotContinue() {
        val bad = range.changeMinimum("30").changeMaximum("25")
        assertTrue(bad.reversed); assertFalse(bad.canContinue)
    }
}
