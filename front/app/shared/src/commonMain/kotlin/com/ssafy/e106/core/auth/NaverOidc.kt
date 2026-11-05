package com.ssafy.e106.core.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * 네이버 로그인 OIDC(네이버 개발가이드 3.5). 앱은 브라우저(Custom Tab)로 인가해 콜백 code 까지 받고(state 확인),
 * code · PKCE code_verifier · nonce 를 서버로 넘긴다. 서버가 client_secret 으로 /oauth2/token 을 불러 id_token 을 받고
 * 서명 · iss · aud · exp · nonce 를 검증한다. 네이버는 client_secret 없는 교환을 거절한다(invalid_client, 11-02 실측) — 비밀값은 APK 에 두지 않는다.
 * 네이버 Android SDK 는 id_token 을 주지 않아(5.12.0) 쓰지 않는다. 화면은 플랫폼이 넘겨주고 여기는 순수 로직만
 */
internal object NaverOidc {
    const val AUTHORIZE_URL = "https://nid.naver.com/oauth2/authorize"

    /** 로그인 한 번의 값. 콜백 state · id_token nonce 를 이것과 맞춘다. codeChallenge = base64url(SHA-256(codeVerifier)) */
    class Attempt(val state: String, val nonce: String, val codeVerifier: String, val codeChallenge: String)

    /** 브라우저 단계의 결과: 콜백 주소, 사용자가 탭을 닫음, 열지 못함(브라우저 없음 등) */
    sealed interface BrowserResult {
        data class Callback(val uri: String) : BrowserResult {
            override fun toString() = "Callback(uri=***)"   // code · state 가 들어 있다
        }
        data object Cancelled : BrowserResult
        data object Failed : BrowserResult
    }

    /** 로그인: 인가 주소를 [openBrowser] 로 열고 콜백의 code 를 서버로 넘길 값으로 만든다. 취소 · 실패 · state 불일치면 code 를 넘기지 않는다 */
    suspend fun login(
        clientId: String,
        redirectUri: String,
        attempt: Attempt,
        openBrowser: suspend (authorizeUrl: String) -> BrowserResult,
    ): SocialToken {
        val callback = when (val r = openBrowser(authorizeUrl(clientId, redirectUri, attempt))) {
            is BrowserResult.Callback -> r.uri
            BrowserResult.Cancelled -> return SocialToken.Cancelled
            BrowserResult.Failed -> return SocialToken.Failed
        }
        val code = when (val r = parseCallback(callback, redirectUri, attempt.state)) {
            is Callback.Code -> r.code
            Callback.Cancelled -> return SocialToken.Cancelled
            Callback.Failed -> return SocialToken.Failed
        }
        return SocialToken.AuthCode(code, attempt.codeVerifier, redirectUri, attempt.nonce)
    }

    fun authorizeUrl(clientId: String, redirectUri: String, attempt: Attempt): String = URLBuilder(AUTHORIZE_URL).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("scope", "openid")
        parameters.append("state", attempt.state)
        parameters.append("nonce", attempt.nonce)
        parameters.append("code_challenge", attempt.codeChallenge)
        parameters.append("code_challenge_method", "S256")
    }.buildString()

    /** 우리 콜백 주소(scheme · host · port · path)인가. 아니면 받지 않는다(다른 앱이 보낸 Intent 등) */
    fun isCallback(uri: String, redirectUri: String): Boolean {
        val url = runCatching { Url(uri) }.getOrNull() ?: return false
        val expected = Url(redirectUri)
        return url.protocol == expected.protocol && url.host == expected.host && url.port == expected.port && url.encodedPath == expected.encodedPath
    }

    sealed interface Callback {
        data class Code(val code: String) : Callback {
            override fun toString() = "Code(code=***)"
        }
        data object Cancelled : Callback
        data object Failed : Callback
    }

    /** 콜백 주소를 읽는다. 다른 주소 · state 불일치(위조 · 다른 시도의 응답)는 Failed, 사용자가 동의를 거부하면 Cancelled */
    fun parseCallback(uri: String, redirectUri: String, expectedState: String): Callback {
        if (!isCallback(uri, redirectUri)) return Callback.Failed
        val params = Url(uri).parameters
        if (params["state"] != expectedState) return Callback.Failed
        params["error"]?.let { return if (it == "access_denied") Callback.Cancelled else Callback.Failed }
        return params["code"]?.takeIf { it.isNotBlank() }?.let { Callback.Code(it) } ?: Callback.Failed
    }
}
