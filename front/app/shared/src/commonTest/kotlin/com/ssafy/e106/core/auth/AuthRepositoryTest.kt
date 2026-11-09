package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.storage.StoredTokens
import com.ssafy.e106.core.storage.MAX_PENDING_LOGOUTS
import com.ssafy.e106.core.storage.TokenStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun jwt(sub: Long, tag: String) = "h." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"$sub","t":"$tag"}""".encodeToByteArray()) + ".s"

private class MemoryStore(var saved: StoredTokens? = null) : TokenStore {
    var failSave = false                                 // Keystore 오류 흉내
    var loadGate: CompletableDeferred<Unit>? = null      // 정하면 다음 읽기 한 번이 그 동안 멈춘다
    var loadStarted = false
    override suspend fun load(): StoredTokens? {
        loadGate?.let { gate -> loadGate = null; loadStarted = true; gate.await() }
        return saved
    }
    var saveGate: CompletableDeferred<Unit>? = null      // 정하면 다음 저장 한 번이 그 동안 멈춘다(세션 잠금을 잡은 채)
    val saveStarted = MutableStateFlow(false)
    override suspend fun save(tokens: StoredTokens) {
        saveGate?.let { gate -> saveGate = null; saveStarted.value = true; gate.await() }
        check(!failSave) { "Keystore 오류" }; saved = tokens
    }
    override suspend fun clear() { saved = null }
    // 보관 목록. 테스트가 다른 스레드에서 기다려도 안전하게 StateFlow 로 둔다. 실제 저장소처럼 매번 멈췄다 간다(취소된 코루틴이면 여기서 끝난다)
    val pendingFlow = MutableStateFlow(emptyList<String>())
    var pending: List<String>
        get() = pendingFlow.value
        set(value) { pendingFlow.value = value }
    // Keystore 일시 오류 흉내. 실제 저장소처럼 조회는 빈 목록, 더하기 · 빼기는 실패
    var failRead = false
    var failAdd = false
    var failRemove = false
    override suspend fun pendingLogouts(): List<String> { yield(); return if (failRead) emptyList() else pending }
    override suspend fun addPendingLogout(refreshToken: String) { yield(); check(!failAdd) { "Keystore 오류" }; pendingFlow.update { (it - refreshToken + refreshToken).takeLast(MAX_PENDING_LOGOUTS) } }
    override suspend fun removePendingLogout(refreshToken: String) { yield(); check(!failRemove) { "Keystore 오류" }; pendingFlow.update { it - refreshToken } }
}

/** 테스트용 SDK. 실제 SDK 창 대신 정해 둔 결과를 돌려준다 */
private class ScriptedSocial(var result: SocialToken = SocialToken.Ok("kakao-token"), private val keys: Set<Provider> = Provider.entries.toSet()) : SocialLogin {
    var cleared = 0
    var asked = 0
    override fun configured(provider: Provider) = provider in keys
    override suspend fun token(provider: Provider): SocialToken { asked++; return result }
    override suspend fun clear() { cleared++ }
}

private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) = json(status, """{"code":"$code","message":"m"}""")
private fun MockRequestHandleScope.loginOk(access: String = jwt(7, "a1"), status: String = "AGREEMENT_REQUIRED", refresh: String = "r1") = json(
    HttpStatusCode.OK,
    """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","accessTokenExpiresAt":"2099-01-01T00:00:00Z",""" +
        """"refreshTokenExpiresAt":"2099-01-01T00:00:00Z","onboardingStatus":"$status"}""",
)
private fun MockRequestHandleScope.onboarding(step: String, signup: Boolean) = json(HttpStatusCode.OK, """{"nextStep":"$step","signupCompleted":$signup}""")

private val HttpRequestData.path get() = url.encodedPath

private class AuthHarness(scope: TestScope, saved: StoredTokens? = null, val social: ScriptedSocial = ScriptedSocial(), handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    // MockEngine 핸들러는 여러 실제 스레드에서 돈다. 읽는 쪽도 안전하게 StateFlow 로
    val requestsFlow = MutableStateFlow(emptyList<HttpRequestData>())
    val requests get() = requestsFlow.value
    val store = MemoryStore(saved)
    val session = DefaultSessionStore(store, scope.backgroundScope)
    init {
        session.client = createHttpClient("https://test/api/", httpLog = false, session = session, engine = MockEngine { req -> requestsFlow.update { it + req }; handler(req) })
    }
    val auth = DefaultAuthRepository(social, session)
    suspend fun noEvent() = withContext(Dispatchers.Default) { withTimeoutOrNull(300) { session.events.first() } }
}

class AuthRepositoryTest {
    /** 로그인 거절 본문에 실린 시각을 보존하고, 차단된 계정의 세션은 만들지 않는다. */
    @Test fun restrictedRejoinPreservesServerAvailabilityWithoutCreatingSession() = runTest {
        val h = AuthHarness(this) { req ->
            assertTrue(req.path.endsWith("auth/kakao"))
            json(HttpStatusCode.Conflict, """{"code":"REJOIN_RESTRICTED","message":"다시 가입할 수 없어요",
                "rejoinAvailableAt":"2026-11-12T03:03:55Z"}""")
        }
        val failed = assertIs<LoginResult.Failed>(h.auth.login(Provider.KAKAO))
        val error = assertIs<AppError.Server>(failed.error)
        assertEquals(409, error.status)
        assertEquals("REJOIN_RESTRICTED", error.code)
        assertEquals(Instant.parse("2026-11-12T03:03:55Z"), error.rejoinAvailableAt)
        assertNull(h.store.saved)
    }

    @Test
    fun loginSavesTokensAndSignsInWithServerStep() = runTest {
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> {
                    assertEquals("""{"token":"kakao-token"}""", req.body.toByteArray().decodeToString())
                    assertNull(req.headers[HttpHeaders.Authorization], "로그인 요청엔 Bearer 를 안 붙인다")
                    loginOk()
                }
                req.path.endsWith("users/7/onboarding") -> {
                    assertEquals("Bearer ${jwt(7, "a1")}", req.headers[HttpHeaders.Authorization])
                    onboarding("KEYRING_REQUIRED", signup = true)
                }
                else -> error(HttpStatusCode.NotFound, "X")
            }
        }
        h.session.restore().join()                      // 저장 토큰 없음 → SignedOut
        val expected = Session.SignedIn(7, OnboardingStatus.KEYRING_REQUIRED, signupCompleted = true)
        assertEquals(LoginResult.Success(expected), h.auth.login(Provider.KAKAO))
        assertEquals(expected, h.session.state.value)
        assertEquals("r1", h.store.saved?.refreshToken)
        assertEquals(true, h.store.saved?.signupCompleted, "단계 조회의 signupCompleted 를 저장(오프라인 복원용)")
    }

    @Test
    fun loginFallsBackToLoginStepWhenStepLookupFails() = runTest {
        val h = AuthHarness(this) { req -> if (req.path.endsWith("auth/kakao")) loginOk(status = "PROFILE_REQUIRED") else throw IOException("down") }
        h.session.restore().join()
        assertEquals(LoginResult.Success(Session.SignedIn(7, OnboardingStatus.PROFILE_REQUIRED, false)), h.auth.login(Provider.KAKAO))
    }

    @Test
    fun cancelledSdkSendsNothing() = runTest {
        val h = AuthHarness(this, social = ScriptedSocial(SocialToken.Cancelled)) { error(HttpStatusCode.InternalServerError, "X") }
        h.session.restore().join()
        assertEquals(LoginResult.Cancelled, h.auth.login(Provider.KAKAO))
        assertEquals(0, h.requests.size)
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun sdkFailureIsFailedWithMessage() = runTest {
        val h = AuthHarness(this, social = ScriptedSocial(SocialToken.Failed)) { error(HttpStatusCode.InternalServerError, "X") }
        val r = h.auth.login(Provider.GOOGLE)
        assertIs<LoginResult.Failed>(r)
        assertEquals(SOCIAL_LOGIN_FAILED_MESSAGE, r.error.message)
        assertEquals(0, h.requests.size)
    }

    @Test
    fun serverRejectsSocialToken() = runTest {
        val h = AuthHarness(this) { error(HttpStatusCode.Unauthorized, "INVALID_SOCIAL_TOKEN") }
        h.session.restore().join()
        assertEquals(LoginResult.Failed(AppError.Server(401, "INVALID_SOCIAL_TOKEN", "m")), h.auth.login(Provider.KAKAO))
        assertEquals(Session.SignedOut, h.session.state.value)
        assertNull(h.store.saved)
        assertEquals(1, h.requests.size, "토큰을 붙여 다시 보내지 않는다")
        assertNull(h.noEvent(), "로그인 실패는 세션 만료 안내가 아니다")
    }

    @Test
    fun loginReplacesPreviousAccountMemoryToken() = runTest {
        var seen = mutableListOf<String?>()
        val old = StoredTokens(jwt(5, "old"), "r0", "Bearer", kotlin.time.Instant.parse("2099-01-01T00:00:00Z"), kotlin.time.Instant.parse("2099-01-01T00:00:00Z"), OnboardingStatus.READY, 5)
        val h = AuthHarness(this, saved = old) { req ->
            when {
                req.path.endsWith("users/5/onboarding") -> onboarding("READY", signup = true)
                req.path.endsWith("auth/kakao") -> loginOk()
                else -> { seen += req.headers[HttpHeaders.Authorization]; onboarding("READY", signup = true) }
            }
        }
        h.session.restore().join()                      // 5번 계정 토큰이 Ktor 메모리에 올라갔다
        h.store.saved = null                            // (5번 계정이 로그아웃했다고 치고) 다른 계정으로 로그인
        h.auth.login(Provider.KAKAO)
        assertEquals("Bearer ${jwt(7, "a1")}", seen.last(), "새 계정 토큰으로 단계 조회")
    }

    @Test
    fun authCodeIsSentForServerToExchange() = runTest {
        // 네이버는 막혀 있어 다른 공급자로 본문 모양만 확인한다(본문은 공급자와 무관하게 SocialToken 이 정한다)
        val code = SocialToken.AuthCode(code = "c1", codeVerifier = "v1", redirectUri = "https://k15e106.p.ssafy.io/auth/naver/callback", nonce = "n1")
        val h = AuthHarness(this, social = ScriptedSocial(code)) { req ->
            when {
                req.path.endsWith("auth/kakao") -> {
                    assertEquals("""{"code":"c1","codeVerifier":"v1","redirectUri":"https://k15e106.p.ssafy.io/auth/naver/callback","nonce":"n1"}""",
                        req.body.toByteArray().decodeToString(), "비밀값 없이 서버가 교환 · 검증할 값만")
                    loginOk()
                }
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        assertIs<LoginResult.Success>(h.auth.login(Provider.KAKAO))
    }

    @Test
    fun missingKeysHidden() = runTest {
        val h = AuthHarness(this, social = ScriptedSocial(keys = setOf(Provider.GOOGLE, Provider.NAVER))) { error(HttpStatusCode.InternalServerError, "X") }
        assertTrue(h.auth.available(Provider.GOOGLE))
        assertTrue(h.auth.available(Provider.NAVER))   // 서버 OIDC(!148) 뒤로 연다
        assertFalse(h.auth.available(Provider.KAKAO), "키 없음")
        assertIs<LoginResult.Failed>(h.auth.login(Provider.KAKAO))
        assertEquals(0, h.social.asked, "SDK 창을 띄우지 않는다")
    }

    @Test
    fun logoutTellsServerClearsEverythingWithoutExpiredEvent() = runTest {
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> loginOk()
                req.path.endsWith("auth/logout") -> {
                    assertEquals("""{"refreshToken":"r1"}""", req.body.toByteArray().decodeToString())
                    respond("", HttpStatusCode.NoContent)
                }
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.auth.logout()
        assertEquals(1, h.requests.count { it.path.endsWith("auth/logout") })
        assertEquals(1, h.social.cleared)
        assertNull(h.store.saved)
        assertEquals(Session.SignedOut, h.session.state.value)
        assertNull(h.noEvent(), "직접 로그아웃엔 '세션이 끝났어요' 안내를 내지 않는다")
    }

    @Test
    fun slowLogoutDoesNotBlockOtherSessionWork() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> loginOk()
                req.path.endsWith("auth/logout") -> { gate.await(); respond("", HttpStatusCode.NoContent) }
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        val logout = launch { h.auth.logout() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { h.requestsFlow.first { r -> r.any { it.path.endsWith("auth/logout") } } } }
        // 서버 로그아웃 응답을 기다리는 동안에도 세션 잠금을 쓰는 일(단계 다시 읽기)이 끝난다
        val other = withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { h.session.refreshOnboarding() } }
        gate.complete(Unit)
        logout.join()
        assertEquals(ApiResult.Ok(Unit), other, "로그아웃이 세션 잠금을 잡고 있으면 여기서 막힌다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun logoutFinishesLocallyEvenIfCallerCancelled() = runTest {
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> loginOk()
                req.path.endsWith("auth/logout") -> awaitCancellation()   // 서버가 응답하지 않는다
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        val logout = launch { h.auth.logout() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { h.requestsFlow.first { r -> r.any { it.path.endsWith("auth/logout") } } } }
        logout.cancel()                                  // 로그아웃 중 화면이 바뀌었다
        logout.join()
        assertNull(h.store.saved, "취소돼도 기기 로그아웃은 끝난다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun tokenSaveFailureIsFailedNotException() = runTest {
        val h = AuthHarness(this) { req -> if (req.path.endsWith("auth/kakao")) loginOk() else onboarding("READY", signup = true) }
        h.session.restore().join()
        h.store.failSave = true
        val r = h.auth.login(Provider.KAKAO)
        assertIs<LoginResult.Failed>(r, "예외로 나가면 로그인 화면이 로딩을 못 푼다")
        assertIs<AppError.Unknown>(r.error)
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun logoutCancelledWhileReadingTokensStillSignsOut() = runTest {
        val h = AuthHarness(this) { req -> if (req.path.endsWith("auth/kakao")) loginOk() else onboarding("READY", signup = true) }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.store.loadGate = CompletableDeferred()
        // 테스트 스레드에서 첫 중단점까지 즉시 진행한다. 토큰 읽기에서 대기 중인지 확인하고 그 시점에 취소한다.
        val logout = launch(start = CoroutineStart.UNDISPATCHED) { h.auth.logout() }
        assertTrue(h.store.loadStarted, "취소 전에 로그아웃이 토큰을 읽기 시작해야 한다")
        logout.cancel()                                  // 로그아웃을 누르자마자 화면이 바뀌었다
        logout.join()
        assertNull(h.store.saved, "처음 토큰을 읽다 취소돼도 기기 로그아웃은 끝난다")
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(listOf("r1"), h.store.pending, "서버에 보내지 못했으니 재전송 목록에 남는다")
        assertTrue(h.requests.none { it.path.endsWith("auth/logout") }, "보낸 적 없다 — 다음 시작 · 로그인 때 재전송이 보낸다")
    }

    @Test
    fun logoutCancelledWhileWaitingLockLeavesNewLogin() = runTest {
        var logins = 0
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> if (++logins == 1) loginOk() else loginOk(access = jwt(8, "b1"), refresh = "r2")
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)                     // A(7) 로그인
        val gate = CompletableDeferred<Unit>()
        h.store.saveGate = gate
        val b = launch(Dispatchers.Default) { h.auth.login(Provider.KAKAO) }   // B(8) 로그인이 세션 잠금을 잡은 채 저장 중
        withContext(Dispatchers.Default) { withTimeout(5_000) { h.store.saveStarted.first { it } } }
        val logout = launch(start = CoroutineStart.UNDISPATCHED) { h.auth.logout() }   // A 로그아웃: 세대를 잡고 잠금을 기다린다
        logout.cancel()                                  // 기다리다 화면이 바뀌었다 → 정리가 잠금 뒤에 줄 선다
        gate.complete(Unit)
        b.join()
        logout.join()
        assertEquals(8L, (h.session.state.value as? Session.SignedIn)?.userId, "정리가 새 로그인(B)을 끝내지 않는다")
        assertEquals("r2", h.store.saved?.refreshToken)
        assertTrue(h.store.pending.isEmpty(), "B 토큰을 재전송 목록에 넣지 않는다")
        assertTrue(h.requests.none { it.path.endsWith("auth/logout") })
    }

    @Test
    fun lateLogoutDoesNotSignOutNextLogin() = runTest {
        val gate = CompletableDeferred<Unit>()
        var logins = 0
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> if (logins++ == 0) loginOk() else loginOk(access = jwt(8, "b1"), refresh = "s1")
                req.path.endsWith("auth/logout") -> { gate.await(); respond("", HttpStatusCode.NoContent) }
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)                     // A(7) 로그인
        val logout = launch { h.auth.logout() }          // A 로그아웃 — 서버 응답이 늦다
        withContext(Dispatchers.Default) { withTimeout(5_000) { h.requestsFlow.first { r -> r.any { it.path.endsWith("auth/logout") } } } }
        h.session.expire()                               // 그 사이 A 세션이 끝나고
        assertIs<LoginResult.Success>(h.auth.login(Provider.KAKAO))   // B(8) 로 로그인했다
        gate.complete(Unit)
        logout.join()
        assertEquals("s1", h.store.saved?.refreshToken, "늦게 끝난 A 의 로그아웃이 B 를 지우지 않는다")
        assertEquals(Session.SignedIn(8, OnboardingStatus.READY, true), h.session.state.value)
    }

    @Test
    fun logoutWorksOffline() = runTest {
        val h = AuthHarness(this) { req ->
            when {
                req.path.endsWith("auth/kakao") -> loginOk()
                req.path.endsWith("auth/logout") -> throw IOException("down")
                else -> onboarding("READY", signup = true)
            }
        }
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.auth.logout()
        assertNull(h.store.saved, "서버에 못 알려도 기기는 로그아웃")
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(1, h.social.cleared)
    }

    // --- 못 보낸 로그아웃 다시 보내기(S15P31E106-194) ---

    /** auth/logout 응답을 테스트 중간에 바꾼다. 본문의 refreshToken 으로 응답을 고를 수 있다 */
    private class LogoutServer(var reply: suspend MockRequestHandleScope.(token: String) -> HttpResponseData)

    private fun logoutHarness(scope: TestScope, server: LogoutServer, saved: StoredTokens? = null, refresh: String = "r1") = AuthHarness(scope, saved) { req ->
        when {
            req.path.endsWith("auth/kakao") -> loginOk(refresh = refresh)
            req.path.endsWith("auth/logout") -> server.reply(this, Regex(""""refreshToken":"([^"]+)"""").find(req.body.toByteArray().decodeToString())!!.groupValues[1])
            else -> onboarding("READY", signup = true)
        }
    }

    private val AuthHarness.logoutTokens get() = requests.filter { it.path.endsWith("auth/logout") }.size

    private suspend fun AuthHarness.waitLogoutRequests(n: Int) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { requestsFlow.first { r -> r.count { it.path.endsWith("auth/logout") } >= n } } }

    private suspend fun AuthHarness.waitPending(until: (List<String>) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { store.pendingFlow.first(until) } }

    /** 복원이 띄운 재전송까지 끝나게 한다(복원 Job 은 재전송을 기다리지 않는다) */
    private suspend fun restoreAndSettle(h: AuthHarness) {
        h.session.restore().join()
        h.session.pendingLogoutFlush?.join()
    }

    @Test
    fun offlineLogoutIsKeptAndSentOnNextStart() = runTest {
        val server = LogoutServer { throw IOException("down") }
        val h = logoutHarness(this, server)
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.auth.logout()
        assertEquals(listOf("r1"), h.store.pending, "서버에 못 닿았으니 보관")

        server.reply = { respond("", HttpStatusCode.NoContent) }
        val before = h.logoutTokens
        h.session.restore().join()                       // 다음 실행
        h.waitPending { it.isEmpty() }
        assertTrue(h.logoutTokens > before, "다시 보냈고, 보냈으면 뺀다")
    }

    @Test
    fun serverErrorLogoutIsKeptRejectedIsNot() = runTest {
        val server = LogoutServer { error(HttpStatusCode.InternalServerError, "X") }
        val h = logoutHarness(this, server)
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.auth.logout()
        assertEquals(listOf("r1"), h.store.pending, "5xx 는 다시 보낸다")
        server.reply = { error(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN") }
        h.session.flushPendingLogout()
        assertTrue(h.store.pending.isEmpty(), "거절(이미 끝난 세션)이면 더 보낼 필요 없다")
    }

    @Test
    fun keptLogoutsOfTwoAccountsDoNotOverwrite() = runTest {
        val server = LogoutServer { error(HttpStatusCode.ServiceUnavailable, "X") }
        val h = logoutHarness(this, server)
        h.store.pending += "rA"                          // A 로그아웃이 못 갔다
        h.session.restore().join()                       // 시작 때 재전송도 실패
        h.auth.login(Provider.KAKAO)                     // B(r1) 로그인 — 그때 재전송도 실패
        h.auth.logout()                                  // B 로그아웃도 실패
        assertEquals(listOf("rA", "r1"), h.store.pending, "B 가 A 를 덮지 않는다")
        server.reply = { respond("", HttpStatusCode.NoContent) }
        h.session.flushPendingLogout()
        assertTrue(h.store.pending.isEmpty())
    }

    @Test
    fun flushRemovesOnlyWhatItSent() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = LogoutServer { token -> if (token == "rA") { gate.await(); respond("", HttpStatusCode.NoContent) } else error(HttpStatusCode.ServiceUnavailable, "X") }
        val h = logoutHarness(this, server, saved = null)
        h.store.pending += "rA"
        val restore = h.session.restore()                // rA 재전송 — 응답이 늦다
        h.waitLogoutRequests(1)
        h.auth.login(Provider.KAKAO)
        h.auth.logout()                                  // 그 사이 r1 로그아웃 실패 → 보관
        gate.complete(Unit)
        restore.join()
        h.waitPending { "rA" !in it }                    // 재전송은 복원과 따로 끝난다
        assertEquals(listOf("r1"), h.store.pending, "rA 재전송이 끝나며 r1 까지 지우지 않는다")
    }

    @Test
    fun loginSendsPendingLogout() = runTest {
        val h = logoutHarness(this, LogoutServer { respond("", HttpStatusCode.NoContent) })
        restoreAndSettle(h)                              // 시작 때 재전송은 빈 목록으로 끝났다
        assertEquals(0, h.logoutTokens)
        h.store.pending += "rA"                          // 그 뒤에 생긴 보관분
        h.auth.login(Provider.KAKAO)
        h.waitPending { it.isEmpty() }
        assertEquals(1, h.logoutTokens, "로그인이 보관된 로그아웃을 보낸다")
    }

    @Test
    fun loginDoesNotWaitForPendingLogout() = runTest {
        val h = logoutHarness(this, LogoutServer { awaitCancellation() })   // 재전송 응답이 오지 않는다
        restoreAndSettle(h)
        h.store.pending += "rA"
        val r = withContext(Dispatchers.Default) { withTimeout(3_000) { h.auth.login(Provider.KAKAO) } }
        assertIs<LoginResult.Success>(r, "로그인 화면이 재전송을 기다리지 않는다")
        h.waitLogoutRequests(1)                          // 시작 재전송은 이미 끝났으니 이 요청은 로그인이 보낸 것
    }

    @Test
    fun restoreDoesNotWaitForPendingLogout() = runTest {
        val h = logoutHarness(this, LogoutServer { awaitCancellation() })
        h.store.pending += "rA"
        val restore = h.session.restore()
        withContext(Dispatchers.Default) { withTimeout(3_000) { restore.join() } }   // 재전송 응답이 오지 않아도 복원 Job 은 바로 끝난다(요청 타임아웃 15초를 기다리지 않는다)
        assertEquals(Session.SignedOut, h.session.state.value)
        h.waitLogoutRequests(1)
    }

    @Test
    fun cancelledLogoutIsKept() = runTest {
        val h = logoutHarness(this, LogoutServer { awaitCancellation() })
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        val logout = launch { h.auth.logout() }
        h.waitLogoutRequests(1)
        logout.cancel()
        logout.join()
        assertEquals(listOf("r1"), h.store.pending, "닿았는지 모르니 보관 — 취소된 뒤에도 저장이 끝난다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun deliveredLogoutIsNotKept() = runTest {
        val h = logoutHarness(this, LogoutServer { respond("", HttpStatusCode.NoContent) })
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.auth.logout()
        assertTrue(h.store.pending.isEmpty())
    }

    @Test
    fun sessionCode401OnLogoutIsDelivered() = runTest {
        val h = logoutHarness(this, LogoutServer { error(HttpStatusCode.Unauthorized, "SESSION_REVOKED") })   // 게이트웨이 · 필터가 세션 코드로 거절
        h.store.pending += "rA"
        h.session.flushPendingLogout()
        assertTrue(h.store.pending.isEmpty(), "401 이면 서버가 받은 것 — 끝없이 다시 보내지 않는다")
    }

    @Test
    fun onlyDeliveredLogoutsAreDropped() {
        fun fail(e: AppError) = ApiResult.Fail(e)
        // 서버가 받았다고 확실한 것만 끝
        assertFalse(ApiResult.Ok(Unit).shouldRetryLogout())
        assertFalse(fail(AppError.Server(400, "INVALID_REQUEST", "")).shouldRetryLogout())
        assertFalse(fail(AppError.Server(401, "INVALID_REFRESH_TOKEN", "")).shouldRetryLogout())
        assertFalse(fail(AppError.SessionExpired).shouldRetryLogout(), "세션 코드 401 은 errorOf 가 SessionExpired 로 바꾼다")
        // 닿지 않았을 수 있는 것은 다시 보낸다(게이트웨이 · 요청 제한 · 알 수 없는 오류 포함)
        for (status in listOf(403, 404, 408, 429, 499, 500, 503)) assertTrue(fail(AppError.Server(status, "X", "")).shouldRetryLogout(), "$status")
        assertTrue(fail(AppError.Offline).shouldRetryLogout())
        assertTrue(fail(AppError.Unknown(IllegalStateException())).shouldRetryLogout())
    }

    @Test
    fun pendingStoreErrorDoesNotBreakRestore() = runTest {
        val h = logoutHarness(this, LogoutServer { respond("", HttpStatusCode.NoContent) })
        h.store.pending += "rA"
        h.store.failRead = true                          // 일시 오류: 조회가 빈 목록
        restoreAndSettle(h)
        assertEquals(Session.SignedOut, h.session.state.value, "보관 목록을 못 읽어도 복원은 끝난다")
        assertEquals(listOf("rA"), h.store.pending, "못 읽은 목록은 그대로 — 다음에 다시")
    }

    @Test
    fun sentButNotRemovedIsResentLater() = runTest {
        val h = logoutHarness(this, LogoutServer { respond("", HttpStatusCode.NoContent) })
        h.store.pending += "rA"
        h.store.failRemove = true                        // 보냈는데 목록에서 빼기만 실패
        h.session.flushPendingLogout()
        assertEquals(listOf("rA"), h.store.pending)
        h.store.failRemove = false
        h.session.flushPendingLogout()                   // 다음 기회에 다시 보내고(서버 로그아웃은 멱등) 뺀다
        assertTrue(h.store.pending.isEmpty())
        assertEquals(2, h.logoutTokens)
    }

    @Test
    fun pendingStoreErrorDoesNotBreakLogout() = runTest {
        val h = logoutHarness(this, LogoutServer { error(HttpStatusCode.ServiceUnavailable, "X") })
        h.session.restore().join()
        h.auth.login(Provider.KAKAO)
        h.store.failAdd = true
        h.auth.logout()                                  // 보관이 실패해도 예외 없이 로그아웃
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(1, h.social.cleared)
        assertNull(h.store.saved)
    }
}
