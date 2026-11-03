package com.ssafy.e106.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PKCE 계산(JVM). commonTest 는 iOS 로도 컴파일돼 java.security 를 못 써서 여기에 둔다 */
class NaverPkceTest {
    @Test
    fun s256MatchesRfc7636Example() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", NaverPkce.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test
    fun verifierFitsSpec() {
        val v = NaverPkce.randomToken(64)
        assertEquals(86, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "base64url, 패딩 없음: $v")
    }

    @Test
    fun attemptValuesAreFreshAndLinked() {
        val a = NaverPkce.newAttempt()
        val b = NaverPkce.newAttempt()
        assertEquals(NaverPkce.s256(a.codeVerifier), a.codeChallenge)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.state, a.nonce)
    }
}
