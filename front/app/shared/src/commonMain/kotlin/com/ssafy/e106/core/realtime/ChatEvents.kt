package com.ssafy.e106.core.realtime

import com.ssafy.e106.core.network.AppError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * 채팅방 실시간 수신(서버 WebSocket `chat-rooms/{id}/events`). 연결 · 토큰 갱신 · 재연결 · 종료는 기반이 한다.
 * 빠진 메시지 복구 · 중복 제거 · 읽음 처리 · 메시지 전송(HTTP)은 채팅 화면 쪽이 한다.
 * ```
 * val MessageKey = ChatMessageResponse.serializer()
 * chatEvents.connect(roomId, lastSequence, MessageKey) { it.sequence }.collect { e ->
 *     when (e) {
 *         is ChatEvent.Connected -> fillGapAfter(e.afterSequence)     // 연결 · 재연결마다. 그 사이 빠진 메시지는 HTTP 로 메운다
 *         is ChatEvent.Message -> append(e.message)                  // 같은 eventId 가 다시 올 수 있다(재연결 경계)
 *         is ChatEvent.Reconnecting -> showBanner(e.error)            // 일시 끊김. 기반이 다시 연결한다
 *         is ChatEvent.Ended -> showError(e.error)                    // 끝. 다시 받으려면 connect 를 다시 부른다
 *     }
 * }
 * ```
 */
interface ChatEvents {
    /**
     * 수집하는 동안만 연결한다(화면을 떠나 수집이 멈추면 끊는다). 앱이 백그라운드면 끊었다가 앞으로 오면 다시 연결한다.
     * 다시 연결할 때는 [sequenceOf] 로 읽은, 마지막으로 내보낸 메시지의 순번부터 받는다. [message] 는 서버 MessageView 를 받을 기능 쪽 DTO
     */
    fun <M> connect(roomId: Long, afterSequence: Long, message: KSerializer<M>, sequenceOf: (M) -> Long): Flow<ChatEvent<M>>
}

sealed interface ChatEvent<out M> {
    /** 구독 시작(연결 · 재연결마다 1회). 서버는 [afterSequence] 다음 메시지부터 보낸다 */
    data class Connected(val afterSequence: Long) : ChatEvent<Nothing>

    /** 새 메시지. [eventId] 는 서버가 메시지마다 주는 값(`chat-message:<id>`) — 중복 거르기에 쓴다 */
    data class Message<M>(val eventId: String, val message: M) : ChatEvent<M>

    /** 연결이 끊겨 다시 연결하는 중. [error] 는 표시용(대개 Offline) */
    data class Reconnecting(val error: AppError) : ChatEvent<Nothing>

    /**
     * 마지막 이벤트 — 이 뒤로 Flow 가 끝난다. 다시 연결할 수 없는 경우: 로그아웃 · 계정 변경 · 세션 만료([AppError.SessionExpired]),
     * 가입 단계 필요 — 연결 중 같은 계정이 약관 재동의 등으로 돌아간 때 포함([AppError.OnboardingRequired]), 닫힌 방(code `CHAT_CLOSED`),
     * 권한 없음 · 없는 방(서버 4xx), 같은 거절이 계속됨(code `CHAT_CONNECT_FAILED`), 서버 메시지 형식 오류가 계속됨(code `CHAT_PROTOCOL_ERROR`),
     * 토큰 저장소 오류([AppError.Unknown])
     */
    data class Ended(val error: AppError) : ChatEvent<Nothing>
}
