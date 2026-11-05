package com.ssafy.e106.core.auth

import org.koin.core.qualifier.named
import kotlinx.coroutines.flow.StateFlow
import com.ssafy.e106.di.APP_FOREGROUND
import com.ssafy.e106.di.CACHE_PREFS_FILE
import com.ssafy.e106.di.DURABLE_PREFS_FILE
import com.ssafy.e106.core.realtime.DefaultChatEvents
import com.ssafy.e106.core.realtime.ChatEvents
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.REFRESH_RETRY_DELAYS
import com.ssafy.e106.core.network.REFRESH_TIMEOUT_MILLIS
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.push.DefaultPushEvents
import com.ssafy.e106.core.push.PushBinding
import com.ssafy.e106.core.push.PushDevice
import com.ssafy.e106.core.push.PushEvents
import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.DataStoreAppPrefs
import com.ssafy.e106.core.storage.StoredTokens
import com.ssafy.e106.core.storage.MAX_PENDING_LOGOUTS
import com.ssafy.e106.core.storage.TokenStore
import com.ssafy.e106.di.coreModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Serializable
private data class Dto(val nickname: String)

private const val BASE = "https://test/api/"
private val FUTURE = Instant.parse("2099-01-01T00:00:00Z")
private val PAST = Instant.parse("2000-01-01T00:00:00Z")

private fun jwt(sub: Long, tag: String) = "h." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"$sub","t":"$tag"}""".encodeToByteArray()) + ".s"

private fun tokens(access: String = jwt(7, "a1"), refresh: String = "r1", refreshExp: Instant = FUTURE, status: OnboardingStatus = OnboardingStatus.READY) =
    StoredTokens(access, refresh, "Bearer", FUTURE, refreshExp, status, 7, signupCompleted = true)

/** 테스트용 메모리 저장소 */
private class MemoryTokenStore(var saved: StoredTokens?) : TokenStore {
    var clearGate: CompletableDeferred<Unit>? = null      // 정하면 삭제가 그 동안 멈춘다(느린 디스크 흉내)
    var clearStarted = false
    override suspend fun load() = saved
    override suspend fun save(tokens: StoredTokens) { saved = tokens }
    val pending = mutableListOf<String>()               // 실제 저장소처럼 매번 멈췄다 간다(취소된 코루틴이면 여기서 끝난다)
    override suspend fun pendingLogouts(): List<String> { yield(); return pending.toList() }
    override suspend fun addPendingLogout(refreshToken: String) { yield(); pending -= refreshToken; pending += refreshToken; while (pending.size > MAX_PENDING_LOGOUTS) pending.removeAt(0) }
    override suspend fun removePendingLogout(refreshToken: String) { yield(); pending -= refreshToken }
    override suspend fun clear() {
        clearStarted = true
        clearGate?.await()
        saved = null
    }
}

private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) = json(status, """{"code":"$code","message":"m"}""")
private fun MockRequestHandleScope.tokenResponse(access: String, refresh: String, status: String = "READY") = json(
    HttpStatusCode.OK,
    """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","accessTokenExpiresAt":"2099-01-01T00:00:00Z",""" +
        """"refreshTokenExpiresAt":"2099-01-01T00:00:00Z","onboardingStatus":"$status"}""",
)
private fun MockRequestHandleScope.onboarding(step: String, signup: Boolean = true) = json(HttpStatusCode.OK, """{"nextStep":"$step","signupCompleted":$signup,"answeredCount":0}""")

private val HttpRequestData.bearer get() = headers[HttpHeaders.Authorization]
private val HttpRequestData.path get() = url.encodedPath

/** 서버 흉내. 경로별 응답은 각 테스트가 정한다 */
private class Harness(scope: TestScope, saved: StoredTokens?, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    // MockEngine 핸들러는 여러 실제 스레드에서 동시에 돈다. 읽는 쪽(eventually 등)도 안전하게 매번 새 목록으로 바꾼다
    private val requestsFlow = MutableStateFlow(emptyList<HttpRequestData>())
    val requests get() = requestsFlow.value
    val store = MemoryTokenStore(saved)
    val session = DefaultSessionStore(store, scope.backgroundScope, now = { Instant.parse("2026-10-30T00:00:00Z") })
    val client = createHttpClient(
        BASE, httpLog = false, session = session, refreshRetryDelays = listOf(1, 1),
        engine = MockEngine { req -> requestsFlow.update { it + req }; handler(req) },
    ).also { session.client = it }
    val refreshCount get() = requests.count { it.path.endsWith("auth/token/refresh") }
    val onboardingCount get() = requests.count { it.path.endsWith("/onboarding") }

    /** 테스트 시작 상태를 SignedIn 으로 */
    suspend fun signIn(status: OnboardingStatus = OnboardingStatus.READY) {
        session.restore().join()
        assertEquals(Session.SignedIn(7, status, true), session.state.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAuthTest {
    @Test
    fun bearerOnlyToOurHostAndNotAuth() = runTest {
        val h = Harness(this, tokens()) { json(HttpStatusCode.OK, "{}") }
        h.client.call<Unit> { get("users/7/profile") }
        h.client.call<Unit> { post("auth/kakao") }
        h.client.call<Unit> { get("https://terms.example.com/privacy.html") }
        assertEquals("Bearer ${jwt(7, "a1")}", h.requests[0].bearer)
        assertNull(h.requests[1].bearer, "auth/… 에는 Bearer 를 안 붙인다")
        assertNull(h.requests[2].bearer, "외부 호스트로 토큰이 나가면 안 된다")
    }

    @Test
    fun externalHost401DoesNotRefreshOrExpire() = runTest {
        val h = Harness(this, tokens()) { req -> if (req.url.host == "test") onboarding("READY") else error(HttpStatusCode.Unauthorized, "X") }
        h.signIn()
        h.client.call<Unit> { get("https://other.example.com/x") }
        assertEquals(0, h.refreshCount)
        assertEquals(1, h.requests.count { it.url.host == "other.example.com" }, "토큰을 붙여 다시 보내지 않는다")
        assertEquals(Session.SignedIn(7, OnboardingStatus.READY, true), h.session.state.value)
    }

    @Test
    fun expiredAccessRefreshesOnceAndRetries() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> {
                    assertEquals("""{"refreshToken":"r1"}""", req.body.toByteArray().decodeToString())
                    tokenResponse(jwt(7, "a2"), "r2")
                }
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> json(HttpStatusCode.OK, """{"nickname":"a"}""")
            }
        }
        h.signIn()
        assertEquals(ApiResult.Ok(Dto("a")), h.client.call<Dto> { get("users/7/profile") })
        assertEquals(1, h.refreshCount)
        assertNull(h.requests.single { it.path.endsWith("refresh") }.bearer, "재발급 요청엔 Bearer 를 안 붙인다")
        assertEquals("r2", h.store.saved?.refreshToken, "회전된 refreshToken 으로 교체 저장")
        assertEquals(true, h.store.saved?.signupCompleted, "재발급 응답에 없는 signupCompleted 는 유지")
    }

    @Test
    fun concurrentExpiredShareOneRefresh() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> tokenResponse(jwt(7, "a2"), "r2")
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> json(HttpStatusCode.OK, """{"nickname":"a"}""")
            }
        }
        h.signIn()
        val results = List(3) { async { h.client.call<Dto> { get("users/7/profile") } } }.awaitAll()
        assertEquals(List(3) { ApiResult.Ok(Dto("a")) }, results)
        assertEquals(1, h.refreshCount, "동시 요청의 재발급은 한 번")
    }

    @Test
    fun revokedSessionExpiresOnceWithoutRefresh() = runTest {
        val h = Harness(this, tokens()) { req -> if (req.path.endsWith("onboarding")) onboarding("READY") else error(HttpStatusCode.Unauthorized, "SESSION_REVOKED") }
        h.signIn()
        val results = List(3) { async { h.client.call<Dto> { get("users/7/profile") } } }.awaitAll()
        assertEquals(List(3) { ApiResult.Fail(AppError.SessionExpired) }, results)
        assertEquals(0, h.refreshCount)
        assertNull(h.store.saved)
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(SessionEvent.Expired, h.session.events.first())
        advanceUntilIdle()
        assertNull(withTimeoutOrNullEvent(h), "Expired 는 한 번")
    }

    @Test
    fun login401IsNotSession() = runTest {
        val h = Harness(this, null) { error(HttpStatusCode.Unauthorized, "INVALID_SOCIAL_TOKEN") }
        h.session.restore().join()
        assertEquals(ApiResult.Fail(AppError.Server(401, "INVALID_SOCIAL_TOKEN", "m")), h.client.call<Dto> { post("auth/kakao") })
        assertEquals(0, h.refreshCount)
        assertEquals(1, h.requests.size, "토큰을 붙여 다시 보내지 않는다")
        assertNull(withTimeoutOrNullEvent(h))
    }

    @Test
    fun refreshRejectedExpires() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> error(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN")
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        assertEquals(ApiResult.Fail(AppError.SessionExpired), h.client.call<Dto> { get("users/7/profile") })
        assertNull(h.store.saved)
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(SessionEvent.Expired, h.session.events.first())
    }

    @Test
    fun refreshOfflineKeepsTokensAfterThreeTries() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> throw IOException("down")
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        assertEquals(ApiResult.Fail(AppError.Offline), h.client.call<Dto> { get("users/7/profile") })
        assertEquals(3, h.refreshCount, "처음 + 재시도 두 번")
        assertEquals(1, h.requests.filter { it.path.endsWith("refresh") }.map { it.body.toByteArray().decodeToString() }.distinct().size, "같은 refreshToken 으로만")
        assertEquals("r1", h.store.saved?.refreshToken, "토큰 유지")
        assertEquals(Session.SignedIn(7, OnboardingStatus.READY, true), h.session.state.value)
    }

    @Test
    fun refreshServerErrorKeepsTokens() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> error(HttpStatusCode.ServiceUnavailable, "X")
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        assertEquals(ApiResult.Fail(AppError.Server(503, "X", "m")), h.client.call<Dto> { get("users/7/profile") })
        assertEquals(1, h.refreshCount, "5xx 는 다시 보내지 않는다")
        assertEquals("r1", h.store.saved?.refreshToken)
        assertEquals(Session.SignedIn(7, OnboardingStatus.READY, true), h.session.state.value)
    }

    @Test
    fun refreshWithNewStatusReloadsOnboarding() = runTest {
        var step = "READY"
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding(step)
                req.path.endsWith("auth/token/refresh") -> tokenResponse(jwt(7, "a2"), "r2", status = "AGREEMENT_REQUIRED")
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> json(HttpStatusCode.OK, """{"nickname":"a"}""")
            }
        }
        h.signIn()
        step = "AGREEMENT_REQUIRED"                       // 새 약관 버전이 나왔다
        h.client.call<Dto> { get("users/7/profile") }
        h.awaitState(Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, true))
    }

    @Test
    fun restoreWithoutTokensIsSignedOut() = runTest {
        val h = Harness(this, null) { error(HttpStatusCode.InternalServerError, "X") }
        h.session.restore().join()
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(0, h.requests.size)
    }

    @Test
    fun restoreSavesLatestStep() = runTest {
        val h = Harness(this, tokens(status = OnboardingStatus.PROFILE_REQUIRED).copy(signupCompleted = false)) { onboarding("SURVEY_REQUIRED", signup = false) }
        h.session.restore().join()
        assertEquals(Session.SignedIn(7, OnboardingStatus.SURVEY_REQUIRED, false), h.session.state.value)
        assertEquals(OnboardingStatus.SURVEY_REQUIRED, h.store.saved?.onboardingStatus, "다음 오프라인 복원용")
    }

    @Test
    fun restoreOfflineUsesLastStepWhileRefreshValid() = runTest {
        val h = Harness(this, tokens(status = OnboardingStatus.PROFILE_REQUIRED)) { throw IOException("down") }
        h.session.restore().join()
        assertEquals(Session.SignedIn(7, OnboardingStatus.PROFILE_REQUIRED, true), h.session.state.value)
    }

    @Test
    fun restoreOfflineWithExpiredRefreshSignsOut() = runTest {
        val h = Harness(this, tokens(refreshExp = PAST)) { throw IOException("down") }
        h.session.restore().join()
        assertEquals(Session.SignedOut, h.session.state.value)
        assertNull(h.store.saved)
    }

    @Test
    fun refreshOnboardingSharesOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val h = Harness(this, tokens()) { req ->
            if (req.path.endsWith("onboarding")) { if (++calls > 1) gate.await(); onboarding("READY") } else json(HttpStatusCode.OK, "{}")
        }
        h.signIn()
        val results = List(3) { async { h.session.refreshOnboarding() } }
        advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(List(3) { ApiResult.Ok(Unit) }, results.awaitAll())
        assertEquals(2, calls, "시작 1 + 합쳐진 1")
    }

    @Test
    fun sameHostOtherServiceOrSchemeGetsNoBearer() = runTest {
        val h = Harness(this, tokens()) { json(HttpStatusCode.OK, "{}") }
        h.client.call<Unit> { get("https://test/jenkins/job") }
        h.client.call<Unit> { get("http://test/api/users/7/profile") }
        h.client.call<Unit> { get("users/7/auth/x") }      // auth/ 가 경로 중간이면 우리 API — 붙인다
        assertNull(h.requests[0].bearer, "같은 호스트의 다른 서비스")
        assertNull(h.requests[1].bearer, "스킴이 다르면 다른 곳")
        assertEquals("Bearer ${jwt(7, "a1")}", h.requests[2].bearer)
    }

    @Test
    fun logout401WhileSignedInIsNotSession() = runTest {
        val h = Harness(this, tokens()) { req -> if (req.path.endsWith("onboarding")) onboarding("READY") else error(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN") }
        h.signIn()
        assertEquals(ApiResult.Fail(AppError.Server(401, "INVALID_REFRESH_TOKEN", "m")), h.client.call<Unit> { post("auth/logout") })
        assertEquals(0, h.refreshCount)
        assertEquals(Session.SignedIn(7, OnboardingStatus.READY, true), h.session.state.value)
        assertEquals("r1", h.store.saved?.refreshToken)
        assertNull(withTimeoutOrNullEvent(h))
    }

    @Test
    fun nonJson401WithBearerExpires() = runTest {
        val h = Harness(this, tokens()) { req ->
            if (req.path.endsWith("onboarding")) onboarding("READY") else respond("<html>", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        h.signIn()
        h.client.call<Unit> { get("users/7/profile") }
        assertEquals(0, h.refreshCount, "code 를 모르면 재발급하지 않는다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun restoreRevokedSignsOutWithEvent() = runTest {
        val h = Harness(this, tokens()) { error(HttpStatusCode.Unauthorized, "SESSION_REVOKED") }
        h.session.restore().join()
        assertEquals(Session.SignedOut, h.session.state.value)
        assertNull(h.store.saved)
        assertEquals(SessionEvent.Expired, h.awaitEvent())
    }

    @Test
    fun restoreServerErrorUsesLastStep() = runTest {
        val h = Harness(this, tokens(status = OnboardingStatus.SURVEY_REQUIRED)) { error(HttpStatusCode.ServiceUnavailable, "X") }
        h.session.restore().join()
        assertEquals(Session.SignedIn(7, OnboardingStatus.SURVEY_REQUIRED, true), h.session.state.value)
    }

    @Test
    fun restoreDoesNotOverrideExpireInBetween() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, tokens()) { gate.await(); onboarding("READY") }
        val job = h.session.restore()
        eventually { h.onboardingCount == 1 }
        h.session.expire()
        gate.complete(Unit)
        job.join()
        assertEquals(Session.SignedOut, h.session.state.value)
        assertNull(h.store.saved)
    }

    @Test
    fun refreshResponseAfterSignOutIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> { gate.await(); tokenResponse(jwt(7, "a2"), "r2") }
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        val call = async { h.client.call<Unit> { get("users/7/profile") } }
        eventually { h.refreshCount == 1 }
        h.session.expire()                               // 재발급 응답이 오기 전에 세션이 끝났다(193 로그아웃도 같다)
        gate.complete(Unit)
        call.await()
        assertNull(h.store.saved, "지운 토큰을 되살리지 않는다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun refreshResponseAfterReloginDoesNotOverwrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> { gate.await(); tokenResponse(jwt(7, "a2"), "r2") }
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        val call = async { h.client.call<Unit> { get("users/7/profile") } }
        eventually { h.refreshCount == 1 }
        h.store.saved = tokens(access = jwt(7, "b1"), refresh = "s1")   // 그 사이 로그아웃 후 다시 로그인했다(193)
        gate.complete(Unit)
        call.await()
        assertEquals("s1", h.store.saved?.refreshToken, "늦게 온 옛 세션의 재발급 응답이 새 로그인을 덮지 않는다")
    }

    @Test
    fun expireWithout401ClearsMemoryToken() = runTest {
        val h = Harness(this, tokens()) { req -> if (req.path.endsWith("onboarding")) onboarding("READY") else json(HttpStatusCode.OK, "{}") }
        h.signIn()                                       // 첫 요청으로 a1 이 Ktor 메모리에 올라갔다
        h.session.expire()                               // 401 없이 끝남(재발급 응답이 쓸 수 없을 때 · 193 로그아웃)
        h.store.saved = tokens(access = jwt(7, "b1"), refresh = "s1")
        h.client.call<Unit> { get("users/7/profile") }
        assertEquals("Bearer ${jwt(7, "b1")}", h.requests.last().bearer, "이전 토큰이 나가지 않는다")
    }

    @Test
    fun refreshFinishesEvenIfCallerCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> { gate.await(); tokenResponse(jwt(7, "a2"), "r2") }
                else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
            }
        }
        h.signIn()
        val job = launch { h.client.call<Unit> { get("users/7/profile") } }
        eventually { h.refreshCount == 1 }
        job.cancel()                                     // 화면을 떠났다
        gate.complete(Unit)
        eventually { h.store.saved?.refreshToken == "r2" }   // 서버가 회전한 토큰은 저장돼야 30초 뒤 세션 전체 폐기가 없다
    }

    @Test
    fun refreshKeepsSignupFalse() = runTest {
        val h = Harness(this, tokens().copy(signupCompleted = false)) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("PROFILE_REQUIRED", signup = false)
                req.path.endsWith("auth/token/refresh") -> tokenResponse(jwt(7, "a2"), "r2", status = "PROFILE_REQUIRED")
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }
        h.session.restore().join()
        h.client.call<Unit> { get("users/7/profile") }
        assertEquals(false, h.store.saved?.signupCompleted)
        delay300()
        assertEquals(1, h.onboardingCount, "단계가 같으면 다시 읽지 않는다")
    }

    @Test
    fun refreshForOtherOrNoUserExpires() = runTest {
        for (access in listOf("h.e30.s", jwt(8, "x"))) {  // sub 없음 · 다른 사용자
            val h = Harness(this, tokens()) { req ->
                when {
                    req.path.endsWith("onboarding") -> onboarding("READY")
                    req.path.endsWith("auth/token/refresh") -> tokenResponse(access, "r2")
                    else -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                }
            }
            h.signIn()
            assertEquals(ApiResult.Fail(AppError.SessionExpired), h.client.call<Unit> { get("users/7/profile") }, access)
            assertNull(h.store.saved, access)
            assertEquals(Session.SignedOut, h.session.state.value, access)
        }
    }

    @Test
    fun forbiddenElsewhereDoesNotReloadStep() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.url.host == "other.example.com" -> error(HttpStatusCode.Forbidden, "ONBOARDING_REQUIRED")
                else -> error(HttpStatusCode.Forbidden, "FORBIDDEN")
            }
        }
        h.signIn()
        h.client.call<Unit> { get("https://other.example.com/x") }
        h.client.call<Unit> { get("users/8/profile") }
        delay300()
        assertEquals(1, h.onboardingCount)
    }

    @Test
    fun onboardingRequiredEmitsOncePerChange() = runTest {
        var step = "READY"
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val h = Harness(this, tokens()) { req ->
            if (req.path.endsWith("onboarding")) {
                if (gated) gate.await()
                onboarding(step)
            } else error(HttpStatusCode.Forbidden, "ONBOARDING_REQUIRED")
        }
        h.signIn()
        h.client.call<Unit> { get("chat/rooms") }         // 단계 그대로(서비스 조건 403) → 이벤트 없음
        eventually { h.onboardingCount == 2 }
        assertNull(withTimeoutOrNullEvent(h))

        step = "AGREEMENT_REQUIRED"
        gated = true
        List(2) { async { h.client.call<Unit> { get("chat/rooms") } } }.awaitAll()
        eventually { h.onboardingCount == 4 }             // 두 403 의 조회가 모두 걸린 뒤 연다
        gate.complete(Unit)
        assertEquals(SessionEvent.OnboardingRequired(OnboardingStatus.AGREEMENT_REQUIRED), h.awaitEvent())
        assertNull(withTimeoutOrNullEvent(h), "같은 변경은 한 번")

        gated = false
        step = "READY"
        h.session.refreshOnboarding()                     // 동의했다
        step = "AGREEMENT_REQUIRED"                       // 새 약관 버전
        h.client.call<Unit> { get("chat/rooms") }
        assertEquals(SessionEvent.OnboardingRequired(OnboardingStatus.AGREEMENT_REQUIRED), h.awaitEvent(), "같은 단계가 다시 필요해지면 또 알린다")
    }

    @Test
    fun concurrentExpireAnnouncesOnce() = runTest {
        val h = Harness(this, tokens()) { onboarding("READY") }
        h.signIn()
        List(3) { async { h.session.expire() } }.awaitAll()
        assertEquals(SessionEvent.Expired, h.awaitEvent())
        assertNull(withTimeoutOrNullEvent(h))
    }

    @Test
    fun refreshBudgetFitsServerGrace() {
        assertEquals(listOf(1_000L, 3_000L), REFRESH_RETRY_DELAYS)
        assertEquals(5_000L, REFRESH_TIMEOUT_MILLIS)
        assertTrue(REFRESH_TIMEOUT_MILLIS * (REFRESH_RETRY_DELAYS.size + 1) + REFRESH_RETRY_DELAYS.sum() < 25_000, "서버 재사용 유예 30초 안")
    }

    @Test
    fun coreModuleWiresOneSessionAndClient() = runTest {
        val refreshed = MutableSharedFlow<Unit>()
        val noPush = object : PushDevice {
            override val platform = "ANDROID"
            override suspend fun installationId() = "i"
            override suspend fun token(): String? = null
            override val tokenRefreshed = refreshed
            override suspend fun binding(): PushBinding? = null
            override suspend fun saveBinding(binding: PushBinding?) = Unit
        }
        val prefsFile = MemoryPrefsStore().apply { data.value = preferencesOf(stringPreferencesKey("7:profile") to "{}") }   // 지난 실행의 계정 캐시
        val durableFile = MemoryPrefsStore().apply { data.value = preferencesOf(stringPreferencesKey("7:report") to "{}") }   // 지난 실행의 계정 복구용 기록
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))   // 알림 탭 대기열은 메인 스레드에서 돈다. 실패해도 finally 가 되돌린다
        try {
            val koin = koinApplication {
                modules(
                    coreModule("https://test/api/", httpLog = false, SocialKeys()),
                    module {
                        single<TokenStore> { MemoryTokenStore(null) }
                        single<SocialLogin> { NoSocial }                 // 실제 SDK 는 Android Context 가 필요하다
                        single<DataStore<Preferences>>(named(CACHE_PREFS_FILE)) { prefsFile }          // 실제 파일도 Android Context 가 필요하다
                        single<DataStore<Preferences>>(named(DURABLE_PREFS_FILE)) { durableFile }
                        single<PushDevice> { noPush }                    // FCM 도
                        single<StateFlow<Boolean>>(named(APP_FOREGROUND)) { MutableStateFlow(true) }   // 앞/뒤 상태는 Application 이 필요하다
                    },
                )
            }.koin
            try {
                val session = koin.get<DefaultSessionStore>()
                assertTrue(koin.get<SessionStore>() === session)
                assertTrue(koin.get<HttpClient>() === session.client)
                assertTrue(koin.get<AuthRepository>() is DefaultAuthRepository)
                assertTrue(koin.get<ChatEvents>() is DefaultChatEvents)
                assertTrue(koin.get<PushEvents>() === koin.get<DefaultPushEvents>())   // 탭 화면과 화면 쪽이 같은 대기열을 본다
                withContext(Dispatchers.Default) { withTimeout(5_000) { session.state.first { it == Session.SignedOut } } }   // restore 가 시작됐다
                withContext(Dispatchers.Default) { withTimeout(5_000) { refreshed.subscriptionCount.first { it > 0 } } }   // 푸시 등록기도 시작됐다
                // 아무도 AppPrefs 를 받지 않아도(앱 시작만으로) 남은 계정 캐시를 지운다
                withContext(Dispatchers.Default) { withTimeout(5_000) { prefsFile.data.first { it.asMap().isEmpty() } } }
                withContext(Dispatchers.Default) { withTimeout(5_000) { durableFile.data.first { it.asMap().isEmpty() } } }
                assertTrue(koin.get<AppPrefs>() is DataStoreAppPrefs)
            } finally {
                koin.close()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }


    @Test
    fun staleRefreshFailureDoesNotEndNewLogin() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> { gate.await(); error(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN") }
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }
        h.signIn()
        val call = async { h.client.call<Unit> { get("users/7/profile") } }
        eventually { h.refreshCount == 1 }
        h.store.saved = tokens(access = jwt(7, "b1"), refresh = "s1")   // 이전 토큰 재발급을 기다리는 사이 다시 로그인했다(193)
        gate.complete(Unit)
        call.await()
        assertEquals("s1", h.store.saved?.refreshToken, "지난 세션의 재발급 실패가 새 로그인을 지우지 않는다")
        assertEquals(Session.SignedIn(7, OnboardingStatus.READY, true), h.session.state.value)
        assertNull(withTimeoutOrNullEvent(h))
    }

    @Test
    fun revokedAfterRefreshedRetrySignsOutOnce() = runTest {
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                req.path.endsWith("auth/token/refresh") -> tokenResponse(jwt(7, "a2"), "r2")
                req.bearer == "Bearer ${jwt(7, "a1")}" -> error(HttpStatusCode.Unauthorized, "ACCESS_TOKEN_EXPIRED")
                else -> error(HttpStatusCode.Unauthorized, "SESSION_REVOKED")   // 재발급 직후 다른 기기 로그인 등으로 세션이 끝났다
            }
        }
        h.signIn()
        assertEquals(ApiResult.Fail(AppError.SessionExpired), h.client.call<Unit> { get("users/7/profile") })
        assertEquals(1, h.refreshCount, "다시 보낸 요청의 401 로 또 재발급하지 않는다")
        assertNull(h.store.saved)
        assertEquals(Session.SignedOut, h.session.state.value)
        assertEquals(SessionEvent.Expired, h.awaitEvent())
        assertNull(withTimeoutOrNullEvent(h), "Expired 는 한 번")
    }

    @Test
    fun staleOnboardingResponseDoesNotOverwriteNewer() = runTest {
        val slow = CompletableDeferred<Unit>()
        var calls = 0
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> when (++calls) {
                    2 -> { slow.await(); onboarding("READY") }   // 먼저 보냈지만 늦게 오는 옛 조회
                    3 -> onboarding("AGREEMENT_REQUIRED")        // 403 으로 새로 보낸 조회
                    else -> onboarding("READY")
                }
                else -> error(HttpStatusCode.Forbidden, "ONBOARDING_REQUIRED")
            }
        }
        h.signIn()
        val first = async { h.session.refreshOnboarding() }
        eventually { h.onboardingCount == 2 }
        h.client.call<Unit> { get("chat/rooms") }
        h.awaitState(Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, true))
        slow.complete(Unit)
        first.await()
        assertEquals(Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, true), h.session.state.value, "늦게 온 옛 READY 가 덮지 않는다")
        assertEquals(OnboardingStatus.AGREEMENT_REQUIRED, h.store.saved?.onboardingStatus)
    }

    @Test
    fun expireFinishesClearingEvenIfCallerCancelled() = runTest {
        val h = Harness(this, tokens()) { onboarding("READY") }
        h.signIn()
        val gate = CompletableDeferred<Unit>()
        h.store.clearGate = gate
        val job = launch(Dispatchers.Default) { h.session.expire() }
        eventually { h.store.clearStarted }
        job.cancel()                                     // 토큰을 지우는 중에 부른 쪽이 취소됐다(화면 전환)
        gate.complete(Unit)
        job.join()
        assertNull(h.store.saved, "로그아웃 상태인데 저장 토큰이 남으면 안 된다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun fullEventBufferDoesNotBlockSession() = runTest {
        var step = "READY"
        val h = Harness(this, tokens()) { req -> if (req.path.endsWith("onboarding")) onboarding(step) else error(HttpStatusCode.Forbidden, "ONBOARDING_REQUIRED") }
        h.signIn()
        // 아무도 events 를 받지 않는 동안(NavHost 가 아직 없음) 단계 변경 안내가 버퍼(64)를 채운다
        repeat(64) { i ->
            step = if (i % 2 == 0) "AGREEMENT_REQUIRED" else "READY"
            h.client.call<Unit> { get("chat/rooms") }
            h.awaitState(Session.SignedIn(7, OnboardingStatus.valueOf(step), true))
        }
        step = "AGREEMENT_REQUIRED"
        h.client.call<Unit> { get("chat/rooms") }         // 65번째 변경 — 버퍼가 차 있다
        eventually { h.onboardingCount == 66 }
        val done = withContext(Dispatchers.Default) { withTimeoutOrNull(3_000) { h.session.expire(); true } }
        assertEquals(true, done, "안내를 넣지 못해 잠금을 잡고 기다리면 expire() 가 멈춘다")
        assertEquals(Session.SignedOut, h.session.state.value)
    }

    @Test
    fun expiredClearsMemoryToken() = runTest {
        var revoked = true
        val h = Harness(this, tokens()) { req ->
            when {
                req.path.endsWith("onboarding") -> onboarding("READY")
                revoked -> error(HttpStatusCode.Unauthorized, "SESSION_REVOKED")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }
        h.signIn()
        h.client.call<Unit> { get("users/7/profile") }
        revoked = false
        h.store.saved = tokens(access = jwt(7, "b1"), refresh = "s1")   // 다시 로그인했다(193 이 할 일)
        h.client.call<Unit> { get("users/7/profile") }
        assertEquals("Bearer ${jwt(7, "b1")}", h.requests.last().bearer, "이전 계정 토큰이 나가지 않는다")
    }
}

/** MockEngine 응답은 실제 스레드에서 오므로 가상 시간(advanceUntilIdle)으로는 못 기다린다 */
private suspend fun Harness.awaitState(expected: Session) =
    withContext(Dispatchers.Default) { withTimeout(5_000) { session.state.first { it == expected } } }

/** 실제 시간으로 300ms 안에 오는 이벤트. 없으면 null — "이벤트가 없다" 확인용 */
private suspend fun withTimeoutOrNullEvent(h: Harness): SessionEvent? =
    withContext(Dispatchers.Default) { withTimeoutOrNull(300) { h.session.events.first() } }

private suspend fun Harness.awaitEvent(): SessionEvent = withContext(Dispatchers.Default) { withTimeout(5_000) { session.events.first() } }

/** 실제 스레드 쪽 일이 끝나기를 기다린다 */
private suspend fun eventually(check: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (!check()) delay(10) } }

private suspend fun delay300() = withContext(Dispatchers.Default) { delay(300) }

private class MemoryPrefsStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(data.value).also { data.value = it }
}

private object NoSocial : SocialLogin {
    override fun configured(provider: Provider) = false
    override suspend fun token(provider: Provider) = SocialToken.Failed
    override suspend fun clear() = Unit
}
