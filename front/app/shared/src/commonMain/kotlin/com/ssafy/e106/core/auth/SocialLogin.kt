package com.ssafy.e106.core.auth

/** 소셜 SDK 키. 앱 빌드가 넘긴다(Android 는 BuildConfig). 비어 있으면 그 공급자는 쓸 수 없다 */
data class SocialKeys(
    val googleWebClientId: String = "",
    val kakaoNativeAppKey: String = "",
    val naverClientId: String = "",
    /** 네이버 OIDC 콜백(App Link). 개발자센터 Callback URL 과 같아야 한다. 비밀값(client_secret)은 앱에 두지 않는다(PKCE) */
    val naverRedirectUri: String = "",
)

/** 소셜 SDK 래퍼(순철 내부용). 구글은 ID 토큰, 카카오는 액세스 토큰, 네이버는 인가 코드(서버가 교환 · 검증)를 돌려준다(로그인_인증_흐름.md §1) */
internal interface SocialLogin {
    fun configured(provider: Provider): Boolean

    /** SDK 로그인 창을 띄워 토큰을 받는다. 예외를 던지지 않는다(코루틴 취소 제외) */
    suspend fun token(provider: Provider): SocialToken

    /** SDK 쪽 로그인 상태 정리. 실패해도 무시한다(서비스 세션은 이미 끝났다) */
    suspend fun clear()
}

internal sealed interface SocialToken {
    data class Ok(val token: String) : SocialToken

    /**
     * 네이버 OIDC 인가 코드. 네이버가 client_secret 없는 교환을 거절해(invalid_client, 11-02 실측) 서버가 비밀값으로 교환하고 id_token 을 검증한다.
     * codeVerifier(PKCE) 가 있어야 교환되므로 코드를 가로채도 쓸 수 없다. nonce 는 서버가 id_token 의 nonce 와 대조한다
     */
    data class AuthCode(val code: String, val codeVerifier: String, val redirectUri: String, val nonce: String) : SocialToken {
        override fun toString() = "AuthCode(code=***, codeVerifier=***, redirectUri=$redirectUri, nonce=***)"
    }
    data object Cancelled : SocialToken
    data object Failed : SocialToken
}
