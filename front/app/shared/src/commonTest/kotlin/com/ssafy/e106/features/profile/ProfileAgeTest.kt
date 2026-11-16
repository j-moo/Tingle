package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.ui.internationalAge
import com.ssafy.e106.features.profile.ui.todayKst
import kotlin.test.*

class ProfileAgeTest {
    @Test fun todayUsesKoreanDate() {
        assertEquals(Triple(1970, 1, 1), todayKst(0))
        // 2026-11-15T15:30Z = 한국 11-16 00:30
        assertEquals(Triple(2026, 11, 16), todayKst(1_794_756_600_000))
        assertEquals(Triple(2024, 2, 29), todayKst(1_709_164_800_000))   // 2024-02-29T00:00Z
    }
    @Test fun ageCountsBirthdayOnlyOnceReached() {
        val today = Triple(2026, 11, 16)
        assertEquals(26, internationalAge("2000-11-16", today))
        assertEquals(25, internationalAge("2000-11-17", today))
        assertEquals(25, internationalAge("2000-12-01", today))
        assertNull(internationalAge("2000/11/16", today))
        assertNull(internationalAge("2030-01-01", today))
    }
}
