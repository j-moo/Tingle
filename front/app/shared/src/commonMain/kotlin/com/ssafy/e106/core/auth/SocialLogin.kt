package com.ssafy.e106.core.auth

/** 소셜 SDK 키. 앱 빌드가 넘긴다(Android 는 BuildConfig). 비어 있으면 그 공급자는 쓸 수 없다 */
data class SocialKeys(
    val googleWebClientId: String = "",
    val kakaoNativeAppKey: String = "",
    val naverClientId: String = "",
    val naverClientSecret: String = "",
)

/** 소셜 SDK 래퍼(순철 내부용). 공급자 토큰 문자열만 돌려준다 — 구글은 ID 토큰, 카카오 · 네이버는 액세스 토큰(로그인_인증_흐름.md §1) */
internal interface SocialLogin {
    fun configured(provider: Provider): Boolean

    /** SDK 로그인 창을 띄워 토큰을 받는다. 예외를 던지지 않는다(코루틴 취소 제외) */
    suspend fun token(provider: Provider): SocialToken

    /** SDK 쪽 로그인 상태 정리. 실패해도 무시한다(서비스 세션은 이미 끝났다) */
    suspend fun clear()
}

internal sealed interface SocialToken {
    data class Ok(val token: String) : SocialToken
    data object Cancelled : SocialToken
    data object Failed : SocialToken
}
