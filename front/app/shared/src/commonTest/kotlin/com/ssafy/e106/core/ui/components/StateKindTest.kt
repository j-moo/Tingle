package com.ssafy.e106.core.ui.components

import com.ssafy.e106.core.network.AppError
import kotlin.test.Test
import kotlin.test.assertEquals

/** 오류 → 상태 화면 종류(아이콘 · 기본 제목)가 어긋나면 "인터넷 끊김"에 서버 오류 그림이 나오는 식으로 틀어진다 */
class StateKindTest {
    @Test fun eachErrorMapsToItsKind() {
        assertEquals(StateKind.Offline, AppError.Offline.stateKind())
        assertEquals(StateKind.Server, AppError.Server(500, "X", "").stateKind())
        assertEquals(StateKind.Session, AppError.SessionExpired.stateKind())
        assertEquals(StateKind.Locked, AppError.OnboardingRequired("").stateKind())
        assertEquals(StateKind.Unknown, AppError.Unknown(IllegalStateException()).stateKind())
    }

    @Test fun missingErrorIsUnknown() = assertEquals(StateKind.Unknown, (null as AppError?).stateKind())
}
