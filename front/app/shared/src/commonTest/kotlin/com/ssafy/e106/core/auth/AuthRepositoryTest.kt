package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.storage.StoredTokens
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun jwt(sub: Long, tag: String) = "h." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"$sub","t":"$tag"}""".encodeToByteArray()) + ".s"

private class MemoryStore(var saved: StoredTokens? = null) : TokenStore {
    var failSave = false                                 // Keystore 오류 흉내
    var loadGate: CompletableDeferred<Unit>? = null      // 정하면 다음 읽기 한 번이 그 동안 멈춘다
    var loadStarted = false
    override suspend fun load(): StoredTokens? {
        loadGate?.let { gate -> loadGate = null; loadStarted = true; gate.await() }
        return saved
    }
    override suspend fun save(tokens: StoredTokens) { check(!failSave) { "Keystore 오류" }; saved = tokens }
    override suspend fun clear() { saved = null }
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
    private val lock = Mutex()
    val requests = mutableListOf<HttpRequestData>()
    val store = MemoryStore(saved)
    val session = DefaultSessionStore(store, scope.backgroundScope)
    init {
        session.client = createHttpClient("https://test/api/", httpLog = false, session = session, engine = MockEngine { req -> lock.withLock { requests += req }; handler(req) })
    }
    val auth = DefaultAuthRepository(social, session)
    suspend fun noEvent() = withContext(Dispatchers.Default) { withTimeoutOrNull(300) { session.events.first() } }
}

class AuthRepositoryTest {
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
    fun naverIsBlockedUntilServerVerifiesAppAndMissingKeysHidden() = runTest {
        val h = AuthHarness(this, social = ScriptedSocial(keys = setOf(Provider.GOOGLE, Provider.NAVER))) { error(HttpStatusCode.InternalServerError, "X") }
        assertTrue(h.auth.available(Provider.GOOGLE))
        assertFalse(h.auth.available(Provider.KAKAO), "키 없음")
        assertFalse(h.auth.available(Provider.NAVER), "서버가 네이버 토큰의 발급 앱을 확인할 때까지 막는다")
        assertIs<LoginResult.Failed>(h.auth.login(Provider.NAVER))
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
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (h.requests.none { it.path.endsWith("auth/logout") }) delay(10) } }
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
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (h.requests.none { it.path.endsWith("auth/logout") }) delay(10) } }
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
        val logout = launch(Dispatchers.Default) { h.auth.logout() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!h.store.loadStarted) delay(10) } }
        logout.cancel()                                  // 로그아웃을 누르자마자 화면이 바뀌었다
        logout.join()
        assertNull(h.store.saved, "처음 토큰을 읽다 취소돼도 기기 로그아웃은 끝난다")
        assertEquals(Session.SignedOut, h.session.state.value)
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
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (h.requests.none { it.path.endsWith("auth/logout") }) delay(10) } }
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
}
