package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.AppError

enum class Provider(val wire: String) { GOOGLE("google"), KAKAO("kakao"), NAVER("naver") }

sealed interface LoginResult {
    data class Success(val session: Session.SignedIn) : LoginResult

    /** 사용자가 SDK 창을 닫음. 화면은 아무것도 안 한다 */
    data object Cancelled : LoginResult

    /** error.message 를 스낵바로 */
    data class Failed(val error: AppError) : LoginResult
}

interface AuthRepository {
    /** 키가 없는 공급자는 false → 버튼 숨김 */
    fun available(provider: Provider): Boolean

    /** 소셜 SDK 토큰 → POST auth/{provider} → 토큰 저장 → GET users/{id}/onboarding → SignedIn 까지. 예외를 던지지 않는다(코루틴 취소 제외) */
    suspend fun login(provider: Provider): LoginResult

    /** POST auth/logout(실패 무시) → 소셜 SDK 정리 → 토큰 · 캐시 · 푸시 비우기 → SignedOut. 예외를 던지지 않는다 */
    suspend fun logout()
}
