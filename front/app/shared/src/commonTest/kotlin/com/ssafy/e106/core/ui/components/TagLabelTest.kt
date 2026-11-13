package com.ssafy.e106.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** 꼬리표(필수 · 선택)만 박스로 떼고, 가운뎃점이 든 긴 이름은 건드리지 않는다 */
class TagLabelTest {
    @Test fun shortSuffixIsTag() {
        assertEquals("닉네임" to "필수", splitTag("닉네임 · 필수"))
        assertEquals("프로필 사진" to "선택", splitTag("프로필 사진 · 선택"))
    }

    @Test fun longNamesStayWhole() {
        assertEquals("희망 나이 · 매칭 설정" to null, splitTag("희망 나이 · 매칭 설정"))
        assertEquals("닉네임" to null, splitTag("닉네임"))
        assertEquals("자기소개", "자기소개 · 선택".withoutTag())
    }
}
