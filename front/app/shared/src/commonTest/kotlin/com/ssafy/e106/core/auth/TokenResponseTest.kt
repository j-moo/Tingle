package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.storage.StoredTokens
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 서버 JwtTokenProvider 모양: header.payload.signature, base64url 패딩 없음 */
private fun jwt(payload: String) = "eyJhbGciOiJIUzI1NiJ9." +
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(payload.encodeToByteArray()) + ".sig"

class TokenResponseTest {
    @Test
    fun subIsUserId() = assertEquals(42L, jwtSubject(jwt("""{"sub":"42","sid":"abc","iat":1,"exp":2}""")))

    @Test
    fun subNeedsNoPadding() = assertEquals(7L, jwtSubject(jwt("""{"sub":"7"}""")))   // 길이가 4 배수가 아닌 payload

    @Test
    fun brokenTokenIsNull() {
        assertNull(jwtSubject("not-a-jwt"))
        assertNull(jwtSubject(jwt("""{"sid":"abc"}""")))
        assertNull(jwtSubject(jwt("""{"sub":"me"}""")))
    }

    @Test
    fun urlSafeAlphabet() {
        val token = jwt("""{"sub":"5","x":"???>>>"}""")
        assertTrue('-' in token || '_' in token)                     // 일반 Base64 로 바꾸면 깨지는 문자가 실제로 들어 있다
        assertEquals(5L, jwtSubject(token))
    }

    private val access = jwt("""{"sub":"42"}""")
    private val response = TokenResponse(access, "r", "Bearer", Instant.parse("2026-10-29T01:00:00Z"), Instant.parse("2026-11-12T00:30:00Z"), OnboardingStatus.AGREEMENT_REQUIRED)

    @Test
    fun loginResponseToStored() {
        val body = """{"accessToken":"$access","refreshToken":"r","tokenType":"Bearer",
            "accessTokenExpiresAt":"2026-10-29T01:00:00Z","refreshTokenExpiresAt":"2026-11-12T00:30:00Z","onboardingStatus":"AGREEMENT_REQUIRED"}"""
        assertEquals(response, ApiJson.decodeFromString(TokenResponse.serializer(), body))
        assertEquals(   // 필드가 자리를 바꿔 들어가면(access ↔ refresh 등) 여기서 걸린다
            StoredTokens(access, "r", "Bearer", Instant.parse("2026-10-29T01:00:00Z"), Instant.parse("2026-11-12T00:30:00Z"), OnboardingStatus.AGREEMENT_REQUIRED, 42L, true),
            response.toStored(signupCompleted = true),
        )
    }

    @Test
    fun noSubIsNotStored() = assertNull(response.copy(accessToken = jwt("""{"sid":"abc"}""")).toStored(signupCompleted = false))

    @Test
    fun toStringHidesTokens() {
        val text = response.toString() + response.toStored(signupCompleted = false).toString()
        assertFalse(access in text || "refreshToken=r" in text)
    }

    @Test
    fun storedRoundTrip() {
        val t = StoredTokens("a", "r", "Bearer", Instant.parse("2026-10-29T01:00:00Z"), Instant.parse("2026-11-12T00:30:00Z"), OnboardingStatus.READY, 42L, true)
        assertEquals(t, ApiJson.decodeFromString(StoredTokens.serializer(), ApiJson.encodeToString(StoredTokens.serializer(), t)))
    }
}
