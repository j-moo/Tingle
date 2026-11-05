package com.ssafy.e106.core.auth

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val REDIRECT = "https://k15e106.p.ssafy.io/auth/naver/callback"
private val attempt = NaverOidc.Attempt(state = "st-1", nonce = "nn-1", codeVerifier = "verifier-1", codeChallenge = "challenge-1")

class NaverOidcTest {
    @Test
    fun authorizeUrlAsksOpenIdWithPkceAndNonce() {
        val url = Url(NaverOidc.authorizeUrl("client-1", REDIRECT, attempt))
        assertEquals("https://nid.naver.com/oauth2/authorize", url.toString().substringBefore('?'), "OIDC 는 새 주소(oauth2.0 아님)")
        assertEquals("code", url.parameters["response_type"])
        assertEquals("client-1", url.parameters["client_id"])
        assertEquals(REDIRECT, url.parameters["redirect_uri"])
        assertEquals("openid", url.parameters["scope"])
        assertEquals("st-1", url.parameters["state"])
        assertEquals("nn-1", url.parameters["nonce"])
        assertEquals("challenge-1", url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
    }

    @Test
    fun callbackWithMatchingStateGivesCode() =
        assertEquals(NaverOidc.Callback.Code("abc"), NaverOidc.parseCallback("$REDIRECT?code=abc&state=st-1", REDIRECT, "st-1"))

    @Test
    fun callbackFromOtherAttemptOrPlaceIsRejected() {
        val other = listOf(
            "$REDIRECT?code=abc&state=other",                                   // 다른 시도의 응답 · 위조
            "$REDIRECT?code=abc",
            "https://evil.example/auth/naver/callback?code=abc&state=st-1",     // 다른 host
            "http://k15e106.p.ssafy.io/auth/naver/callback?code=abc&state=st-1", // scheme
            "https://k15e106.p.ssafy.io/auth/naver/callbackX?code=abc&state=st-1",
            "https://k15e106.p.ssafy.io/auth/naver/callback/?code=abc&state=st-1",
            "https://k15e106.p.ssafy.io:8443/auth/naver/callback?code=abc&state=st-1",
            "$REDIRECT?state=st-1",                                             // code 없음
            "::not a url",
        )
        for (uri in other) assertEquals(NaverOidc.Callback.Failed, NaverOidc.parseCallback(uri, REDIRECT, "st-1"), uri)
    }

    @Test
    fun deniedConsentIsCancelledOtherErrorsFail() {
        assertEquals(NaverOidc.Callback.Cancelled, NaverOidc.parseCallback("$REDIRECT?state=st-1&error=access_denied", REDIRECT, "st-1"))
        assertEquals(NaverOidc.Callback.Failed, NaverOidc.parseCallback("$REDIRECT?state=st-1&error=server_error", REDIRECT, "st-1"))
    }

    // --- 로그인 전체(login): 같은 시도의 값이 서버로 넘어가는지 ---

    private class FakeNaver(val browser: (String) -> NaverOidc.BrowserResult) {
        val opened = mutableListOf<String>()
        suspend fun login() = NaverOidc.login("client-1", REDIRECT, attempt, openBrowser = { url -> opened += url; browser(url) })
    }

    /** 네이버가 받은 인가 주소의 state 를 그대로 붙여 콜백으로 돌려준다 */
    private fun naverReturns(code: String) = { url: String -> NaverOidc.BrowserResult.Callback("$REDIRECT?code=$code&state=${Url(url).parameters["state"]}") }

    @Test
    fun loginHandsCodeWithTheSameAttemptToServer() = runTest {
        val n = FakeNaver(naverReturns("c1"))
        assertEquals(SocialToken.AuthCode(code = "c1", codeVerifier = "verifier-1", redirectUri = REDIRECT, nonce = "nn-1"), n.login(),
            "인가에 보낸 challenge 의 짝 verifier · 같은 redirect_uri · nonce 를 서버가 교환 · 검증에 쓴다")
        assertEquals("challenge-1", Url(n.opened.single()).parameters["code_challenge"])
    }

    @Test
    fun cancelledOrFailedBrowserGivesNoCode() = runTest {
        assertEquals(SocialToken.Cancelled, FakeNaver({ NaverOidc.BrowserResult.Cancelled }).login())
        assertEquals(SocialToken.Failed, FakeNaver({ NaverOidc.BrowserResult.Failed }).login(), "브라우저가 없으면 안내가 뜨게 실패")
        assertEquals(SocialToken.Cancelled, FakeNaver({ NaverOidc.BrowserResult.Callback("$REDIRECT?state=st-1&error=access_denied") }).login())
    }

    @Test
    fun wrongStateNeverHandsCodeToServer() = runTest {
        assertEquals(SocialToken.Failed, FakeNaver({ NaverOidc.BrowserResult.Callback("$REDIRECT?code=stolen&state=other") }).login(),
            "state 가 다르면 code 를 넘기지 않는다")
    }

    @Test
    fun authCodeDoesNotPrintSecrets() {
        val text = SocialToken.AuthCode("c1", "verifier-1", REDIRECT, "nn-1").toString()
        assertTrue("c1" !in text && "verifier-1" !in text && "nn-1" !in text, text)
    }
}
