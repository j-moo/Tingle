package com.ssafy.e106.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Dto(val nickname: String)

@Serializable
private data class WithOptional(val nickname: String, val bio: String?)

private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    createHttpClient(baseUrl = "https://test/api/", httpLog = false, engine = MockEngine(handler))

private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

class CallTest {
    @Test
    fun success() = runTest {
        val r = client { json(HttpStatusCode.OK, """{"nickname":"a"}""") }.call<Dto> { get("users/1/profile") }
        assertEquals(ApiResult.Ok(Dto("a")), r)
    }

    @Test
    fun unknownFieldIgnored() = runTest {
        val r = client { json(HttpStatusCode.OK, """{"nickname":"a","new":1}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Ok(Dto("a")), r)
    }

    @Test
    fun serverErrorKeepsMessage() = runTest {
        val r = client { json(HttpStatusCode.Conflict, """{"code":"NICKNAME_TAKEN","message":"이미 쓰는 닉네임이에요"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(409, "NICKNAME_TAKEN", "이미 쓰는 닉네임이에요")), r)
        assertEquals("이미 쓰는 닉네임이에요", (r as ApiResult.Fail).error.message)
    }

    @Test
    fun blankServerMessageFallsBack() = runTest {
        val r = client { json(HttpStatusCode.InternalServerError, """{"code":"X","message":""}""") }.call<Dto> { get("x") }
        assertEquals(SERVER_FALLBACK_MESSAGE, (r as ApiResult.Fail).error.message)
    }

    @Test
    fun brokenErrorBody() = runTest {
        val r = client { respond("<html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html")) }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(502, "", "")), r)
        assertEquals(SERVER_FALLBACK_MESSAGE, (r as ApiResult.Fail).error.message)
    }

    @Test
    fun sessionExpired() = runTest {
        val r = client { json(HttpStatusCode.Unauthorized, """{"code":"SESSION_REVOKED","message":"m"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.SessionExpired), r)
    }

    @Test
    fun login401IsServerNotSession() = runTest {
        val r = client { json(HttpStatusCode.Unauthorized, """{"code":"INVALID_SOCIAL_TOKEN","message":"m"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(401, "INVALID_SOCIAL_TOKEN", "m")), r)
    }

    @Test
    fun onboardingRequired() = runTest {
        val r = client { json(HttpStatusCode.Forbidden, """{"code":"ONBOARDING_REQUIRED","message":"프로필을 먼저 완성해 주세요"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.OnboardingRequired("프로필을 먼저 완성해 주세요")), r)
        assertEquals("프로필을 먼저 완성해 주세요", (r as ApiResult.Fail).error.message)
    }

    @Test
    fun successParseFailureIsUnknown() = runTest {
        val r = client { json(HttpStatusCode.OK, """{"nickname":1}""") }.call<Dto> { get("x") }
        assertIs<AppError.Unknown>((r as ApiResult.Fail).error)
        assertEquals(UNKNOWN_MESSAGE, r.error.message)
    }

    @Test
    fun ioExceptionIsOffline() = runTest {
        val r = client { throw IOException("boom") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Offline), r)
    }

    @Test
    fun timeoutIsOffline() = runTest {
        val c = client { awaitCancellation() }.config { install(HttpTimeout) { requestTimeoutMillis = 100 } }
        assertEquals(ApiResult.Fail(AppError.Offline), c.call<Dto> { get("x") })
    }

    @Test
    fun cancellationPropagates() = runTest {
        var after = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            client { started.complete(Unit); awaitCancellation() }.call<Dto> { get("x") }
            after = true
        }
        started.await()                                  // 요청이 엔진에 들어간 뒤 취소
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(after, "취소가 Fail 로 삼켜지면 다음 줄이 실행된다")
    }

    @Test
    fun cancellationWhileReadingErrorBodyPropagates() = runTest {
        var after = false
        val job = launch {
            client { json(HttpStatusCode.InternalServerError, """{"code":"X","message":"m"}""") }
                .call<Dto> { get("x").also { currentCoroutineContext().cancel() } }   // 응답을 받은 직후, 오류 본문을 읽기 전에 화면을 떠났다
            after = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(after, "오류 본문을 읽다 취소돼도 Fail 로 삼키지 않는다")
    }

    @Test
    fun urlAndHeaders() = runTest {
        var url = ""
        var lang: String? = null
        val r = client { req ->
            url = req.url.toString()
            lang = req.headers[HttpHeaders.AcceptLanguage]
            json(HttpStatusCode.OK, "{}")
        }.call<Unit> { get("users/1/profile") }
        assertEquals("https://test/api/users/1/profile", url)
        assertEquals("ko", lang)
        assertEquals(ApiResult.Ok(Unit), r)
    }

    @Test
    fun everySessionCodeIsSessionExpired() = runTest {
        // SESSION_CODES 를 그대로 돌면 목록에서 빠진 코드를 못 잡는다. 서버 계약 그대로 적는다
        listOf("AUTH_REQUIRED", "ACCESS_TOKEN_INVALID", "ACCESS_TOKEN_EXPIRED", "SESSION_REVOKED").forEach { code ->
            val r = client { json(HttpStatusCode.Unauthorized, """{"code":"$code","message":"m"}""") }.call<Dto> { get("x") }
            assertEquals(ApiResult.Fail(AppError.SessionExpired), r, code)
        }
        // 재발급 실패는 세션 코드가 아니다(§2 재발급 흐름이 따로 처리)
        val refresh = client { json(HttpStatusCode.Unauthorized, """{"code":"INVALID_REFRESH_TOKEN","message":"m"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(401, "INVALID_REFRESH_TOKEN", "m")), refresh)
    }

    @Test
    fun otherForbiddenIsServer() = runTest {
        // 403 이 전부 온보딩으로 가거나, 세션 코드가 403 으로 와도 로그아웃되면 안 된다
        val forbidden = client { json(HttpStatusCode.Forbidden, """{"code":"FORBIDDEN","message":"m"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(403, "FORBIDDEN", "m")), forbidden)
        val revoked403 = client { json(HttpStatusCode.Forbidden, """{"code":"SESSION_REVOKED","message":"m"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(403, "SESSION_REVOKED", "m")), revoked403)
    }

    @Test
    fun partialErrorBodyKeepsCode() = runTest {
        val r = client { json(HttpStatusCode.Conflict, """{"code":"X"}""") }.call<Dto> { get("x") }
        assertEquals(ApiResult.Fail(AppError.Server(409, "X", "")), r)
    }

    @Test
    fun noContentIsOkUnit() = runTest {
        val r = client { respond("", HttpStatusCode.NoContent) }.call<Unit> { delete("x") }
        assertEquals(ApiResult.Ok(Unit), r)
    }

    @Test
    fun postSendsJson() = runTest {
        var type: ContentType? = null
        var sent = ""
        client { req ->
            type = req.body.contentType
            sent = req.body.toByteArray().decodeToString()
            json(HttpStatusCode.Created, """{"nickname":"a"}""")
        }.call<Dto> { post("x") { setBody(Dto("a")) } }.also { assertEquals(ApiResult.Ok(Dto("a")), it) }
        assertEquals(ContentType.Application.Json, type?.withoutParameters())
        assertEquals("""{"nickname":"a"}""", sent)
    }

    @Test
    fun missingNullableFieldIsNull() = runTest {
        val r = client { json(HttpStatusCode.OK, """{"nickname":"a"}""") }.call<WithOptional> { get("x") }
        assertEquals(ApiResult.Ok(WithOptional("a", null)), r)
    }

    @Test
    fun productionTimeouts() = runTest {
        var connect: Long? = null
        var request: Long? = null
        client { req ->
            val t = req.getCapabilityOrNull(HttpTimeoutCapability)
            connect = t?.connectTimeoutMillis
            request = t?.requestTimeoutMillis
            json(HttpStatusCode.OK, "{}")
        }.call<Unit> { get("x") }
        assertEquals(10_000L, connect)
        assertEquals(15_000L, request)
    }

    @Test
    fun httpLogTogglesLogging() {
        val engine = MockEngine { respond("") }
        assertNull(createHttpClient("https://test/api/", httpLog = false, engine = engine).pluginOrNull(Logging))
        assertNotNull(createHttpClient("https://test/api/", httpLog = true, engine = engine).pluginOrNull(Logging))
    }

    @Test
    fun messages() {
        assertEquals(OFFLINE_MESSAGE, AppError.Offline.message)
        assertEquals(SESSION_EXPIRED_MESSAGE, AppError.SessionExpired.message)
        assertEquals(SERVER_FALLBACK_MESSAGE, AppError.OnboardingRequired("").message)
    }

    @Test
    fun unknownHidesCauseMessage() {
        val e = AppError.Unknown(IllegalStateException("JSON input: {\"accessToken\":\"secret\"}"))
        assertEquals("Unknown(IllegalStateException)", e.toString())
    }

    @Test
    fun resultHelpers() {
        assertEquals(ApiResult.Ok(2), ApiResult.Ok(1).map { it + 1 })
        assertEquals(ApiResult.Fail(AppError.Offline), (ApiResult.Fail(AppError.Offline) as ApiResult<Int>).map { it + 1 })
        assertEquals(1, ApiResult.Ok(1).getOrElse { 0 })
        assertEquals(0, (ApiResult.Fail(AppError.Offline) as ApiResult<Int>).getOrElse { 0 })
        val e = AppError.Server(409, "X", "m")
        assertEquals(ApiResult.Fail(e), (ApiResult.Fail(e) as ApiResult<Int>).map { it + 1 })
        var got: AppError? = null
        assertEquals(0, (ApiResult.Fail(e) as ApiResult<Int>).getOrElse { got = it; 0 })
        assertEquals(e, got)
    }
}
