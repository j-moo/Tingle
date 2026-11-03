package com.ssafy.e106.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** 네이버 OIDC 의 일회용 값(PKCE · state · nonce). 순수 계산이라 JVM 호스트 테스트로 확인한다(RFC 7636 부록 B) */
internal object NaverPkce {
    private val random = SecureRandom()
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** 바이트 수만큼의 난수를 base64url(패딩 없음)로. 64바이트면 86자 — PKCE verifier 규격(43~128자, [A-Za-z0-9-._~]) 안 */
    fun randomToken(bytes: Int): String = base64Url.encode(ByteArray(bytes).also(random::nextBytes))

    /** code_challenge = base64url(SHA-256(ASCII(verifier))), 패딩 없음 */
    fun s256(verifier: String): String = base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newAttempt(): NaverOidc.Attempt {
        val verifier = randomToken(64)
        return NaverOidc.Attempt(state = randomToken(32), nonce = randomToken(32), codeVerifier = verifier, codeChallenge = s256(verifier))
    }
}
