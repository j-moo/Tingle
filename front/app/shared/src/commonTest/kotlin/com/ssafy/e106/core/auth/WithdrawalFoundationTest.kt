package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/** 서버/실제 계정을 쓰지 않고 새 로그인 보호와 결과조회 인증 경계를 검사한다. */
class WithdrawalFoundationTest {
    private class Tokens : TokenStore {
        var saved: StoredTokens? = StoredTokens("access", "refresh", "Bearer", Instant.parse("2035-01-01T00:00:00Z"),
            Instant.parse("2035-02-01T00:00:00Z"), OnboardingStatus.READY, 75, true)
        var failClear = false
        var queued = 0
        override suspend fun load() = saved
        override suspend fun save(tokens: StoredTokens) { saved = tokens }
        override suspend fun clear() { check(!failClear); saved = null }
        override suspend fun pendingLogouts() = emptyList<String>()
        override suspend fun addPendingLogout(refreshToken: String) { queued++ }
        override suspend fun removePendingLogout(refreshToken: String) = Unit
    }

    @Test fun matchingSessionIsClearedWithoutLogoutPostOrRetryQueue() = runTest {
        val tokens = Tokens(); val requests = mutableListOf<String>()
        val session = DefaultSessionStore(tokens, backgroundScope)
        val client = createHttpClient("https://test/api/", false, MockEngine {
            requests += it.url.encodedPath
            respond("""{"nextStep":"READY","signupCompleted":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        session.client = client
        try {
            session.restore().join()
            assertNull(session.capture(7))
            val handle = assertNotNull(session.capture(75))
            assertTrue(session.finish(handle))
            assertEquals(Session.SignedOut, session.state.value); assertNull(tokens.saved)
            assertEquals(0, tokens.queued); assertFalse(requests.any { it.contains("logout") })
            assertFalse(session.finish(handle))
        } finally { client.close() }
    }

    @Test fun staleHandleCannotClearAnotherOrNewSession() = runTest {
        val tokens = Tokens(); val session = DefaultSessionStore(tokens, backgroundScope)
        val client = createHttpClient("https://test/api/", false, MockEngine {
            respond("""{"nextStep":"READY","signupCompleted":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        session.client = client
        try {
            session.restore().join()
            val handle = assertNotNull(session.capture(75))
            session.signOut(session.currentGeneration, false)
            // restore는 시작 시 Restoring 전용이다. 로그아웃 뒤에는 실제 재로그인 경로로 새 세대를 만든다.
            assertNotNull(session.signIn(TokenResponse("h.eyJzdWIiOiI3NSJ9.s", "new-refresh", "Bearer",
                Instant.parse("2035-01-01T00:00:00Z"), Instant.parse("2035-02-01T00:00:00Z"), OnboardingStatus.READY)))
            assertFalse(session.finish(handle))
            assertIs<Session.SignedIn>(session.state.value); assertNotNull(tokens.saved)
        } finally { client.close() }
    }

    @Test fun storageFailureDoesNotPretendLocalCleanupCompleted() = runTest {
        val tokens = Tokens(); val session = DefaultSessionStore(tokens, backgroundScope)
        val client = createHttpClient("https://test/api/", false, MockEngine {
            respond("""{"nextStep":"READY","signupCompleted":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        session.client = client
        try {
            session.restore().join(); val handle = assertNotNull(session.capture(75)); tokens.failClear = true
            assertFailsWith<IllegalStateException> { session.finish(handle) }
            assertNotNull(tokens.saved); assertIs<Session.SignedIn>(session.state.value)
        } finally { client.close() }
    }

    @Test fun receiptClientHasNoBearerAndDoesNotForwardReceiptOnRedirect() = runTest {
        var count = 0
        val client = createReceiptHttpClient("https://test/dev/api/", MockEngine {
            count++
            assertNull(it.headers[HttpHeaders.Authorization])
            assertEquals("synthetic", it.headers["X-Withdrawal-Receipt"])
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://outside.invalid/leak"))
        })
        try {
            val response = client.post("withdrawals/11111111-1111-4111-8111-111111111111/result") {
                header("X-Withdrawal-Receipt", "synthetic")
            }
            assertEquals(HttpStatusCode.TemporaryRedirect, response.status); assertEquals(1, count)
        } finally { client.close() }
    }

    @Test fun protectedReadDoesNotPrintSecret() {
        assertFalse(ProtectedRead.Found("secret-sentinel").toString().contains("secret-sentinel"))
    }
}
