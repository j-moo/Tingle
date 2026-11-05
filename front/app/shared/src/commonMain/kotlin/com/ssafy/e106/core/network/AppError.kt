package com.ssafy.e106.core.network

// 서버가 문구를 줄 수 없는 경우에만 쓰는 기본 문구. 다국어로 갈 때 이 넷만 리소스로 옮긴다
const val OFFLINE_MESSAGE = "연결을 확인해 주세요"
const val SERVER_FALLBACK_MESSAGE = "잠시 후 다시 시도해 주세요"
const val SESSION_EXPIRED_MESSAGE = "다른 기기에서 로그인했거나 세션이 끝났어요. 다시 로그인해 주세요"
const val UNKNOWN_MESSAGE = "문제가 생겼어요"

/**
 * 화면이 받는 오류는 이 하나뿐. 연결 실패·HTTP 상태·서버 `{code, message}` 를 [call] 이 전부 여기로 바꾼다.
 * 화면은 [message] 만 보여준다. [SessionExpired]·[OnboardingRequired] 는 NavHost 가 세션 상태로 처리하므로 화면이 따로 할 일은 없다.
 */
sealed interface AppError {
    val message: String
        get() = when (this) {
            Offline -> OFFLINE_MESSAGE
            is Server -> serverMessage.ifBlank { SERVER_FALLBACK_MESSAGE }
            SessionExpired -> SESSION_EXPIRED_MESSAGE
            is OnboardingRequired -> serverMessage.ifBlank { SERVER_FALLBACK_MESSAGE }
            is Unknown -> UNKNOWN_MESSAGE
        }

    /** 연결 실패 · 타임아웃 */
    data object Offline : AppError

    /** 서버가 준 오류 응답. [serverMessage] 는 서버 문구 그대로 */
    data class Server(val status: Int, val code: String, val serverMessage: String) : AppError

    /** 재로그인 필요 (AUTH_REQUIRED · ACCESS_TOKEN_INVALID · SESSION_REVOKED · 재발급 실패) */
    data object SessionExpired : AppError

    /** 403 ONBOARDING_REQUIRED. 단계가 안 바뀌는 경우도 있어 화면은 보통 오류처럼 [message] 를 보여준다 */
    data class OnboardingRequired(val serverMessage: String) : AppError

    /** 성공 응답 파싱 실패 등 예상 못 한 것. [cause] 메시지에 응답 본문(토큰)이 섞일 수 있어 로그·리포터로 그대로 넘기지 않는다 */
    data class Unknown(val cause: Throwable) : AppError {
        override fun toString() = "Unknown(${cause::class.simpleName})"
    }
}
