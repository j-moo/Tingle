package com.ssafy.e106.core.network

import com.ssafy.e106.core.auth.TokenResponse
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.RefreshTokensParams
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.Serializable

/** HttpClient 가 세션 쪽에 묻고 알리는 것. DefaultSessionStore 가 구현한다 */
internal interface SessionAuth {
    suspend fun accessTokens(): BearerTokens?

    /** 재발급 응답 저장. 보낸 뒤 세션이 바뀌었으면 버리고 null, sub 가 없거나 다른 사용자면 세션을 끝내고 null */
    suspend fun saveRefreshed(res: TokenResponse, sentRefreshToken: String): BearerTokens?

    /** 이 액세스 토큰으로 보낸 요청이 401. 그 토큰이 아직 지금 세션 것일 때만 세션 끝(토큰 삭제 · SignedOut · Expired 1회) */
    suspend fun expireIfCurrent(sentAccessToken: String)

    /** 403 ONBOARDING_REQUIRED 를 받았다 */
    fun onboardingRequired()
}

/** 재발급만 5초. 연결 실패·타임아웃이면 1초 · 3초 뒤 두 번 더 — 최악 19초로 서버의 재사용 유예(30초) 안 (기반_인터페이스.md §6) */
internal const val REFRESH_TIMEOUT_MILLIS = 5_000L
internal val REFRESH_RETRY_DELAYS = listOf(1_000L, 3_000L)

@Serializable
private class RefreshRequest(val refreshToken: String)

/** 재발급이 5xx 등으로 실패. 토큰은 그대로 두고 원래 요청만 이 오류로 끝낸다([call] 이 받는다) */
@PublishedApi
internal class RefreshFailed(val error: AppError) : Exception()

/**
 * Bearer 첨부 · 재발급 · 401/403 처리. 화면 · Repository 는 401 을 모른다(로그인_인증_흐름.md §3).
 * Bearer 는 [baseUrl] 아래(스킴 · 호스트 · 포트 · 경로까지 같음)의 auth/… 가 아닌 요청에만 붙인다.
 * 같은 클라이언트로 외부 URL 이나 같은 호스트의 다른 서비스(/jenkins/ 등)를 불러도 토큰이 나가지 않는다.
 */
internal fun HttpClientConfig<*>.installSessionAuth(baseUrl: String, session: SessionAuth, retryDelays: List<Long>) {
    fun ours(url: String) = url.startsWith(baseUrl) && !url.removePrefix(baseUrl).startsWith("auth/")
    // Ktor Auth 는 토큰 없이 보낸 요청이 401 이면 토큰을 붙여 다시 보낸다. 우리 API 가 아니면 Auth 가 아예 손대지 않게 표시한다
    install(createClientPlugin("SessionScope") {
        onRequest { req, _ -> if (!ours(req.url.buildString())) req.attributes.put(AuthCircuitBreaker, Unit) }
    })
    install(Auth) {
        bearer {
            loadTokens { session.accessTokens() }
            sendWithoutRequest { true }                  // 표시 안 된 요청(우리 서버 · auth/… 아님)엔 처음부터 붙인다
            refreshTokens { refresh(session, retryDelays) }   // Ktor 가 동시 요청의 재발급을 1회로 합친다
            // 원래 요청이 취소돼도(화면 이탈 · 15초 타임아웃) 재발급은 끝까지 해 회전된 토큰을 저장한다.
            // 서버가 회전했는데 응답을 버리면 30초 뒤 옛 토큰 재사용으로 그 회원 세션이 전부 폐기된다
            nonCancellableRefresh = true
        }
    }
    // 재발급 · 재시도까지 끝난 최종 응답을 본다(HttpCallValidator 가 Auth 보다 바깥). 재발급 뒤 다시 보낸 요청의 401 도 여기로 온다
    HttpResponseValidator {
        validateResponse { res ->
            if (!ours(res.request.url.toString())) return@validateResponse
            when (res.status.value) {
                // 토큰을 붙여 보낸 요청의 최종 401 = 세션 끝. 그 사이 다시 로그인했으면 지난 세션의 401 이라 무시한다
                401 -> res.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.let { session.expireIfCurrent(it) }
                // 단계 조회 자체는 AGREEMENT_REQUIRED 부터 되므로 403 이 올 일이 없지만, 돌고 돌지 않게 뺀다
                403 -> if (!res.request.url.encodedPath.endsWith("/onboarding") && errorCode(res) == "ONBOARDING_REQUIRED") session.onboardingRequired()
            }
        }
    }
}

/**
 * 우리 서버 요청의 401. ACCESS_TOKEN_EXPIRED 만 재발급한다. null 을 돌려주면 원래 401 이 그대로 나가고,
 * 세션을 끝낼지는 위 401 검사가 정한다(AUTH_REQUIRED · ACCESS_TOKEN_INVALID · SESSION_REVOKED · 재발급 401 INVALID_REFRESH_TOKEN)
 */
private suspend fun RefreshTokensParams.refresh(session: SessionAuth, retryDelays: List<Long>): BearerTokens? {
    val old = oldTokens?.refreshToken ?: return null
    if (errorCode(response) != "ACCESS_TOKEN_EXPIRED") return null
    val res = postRefresh(old, retryDelays)              // 끝내 연결 실패면 IOException → 원래 요청은 Offline, 토큰 유지
    return when {
        res.status.isSuccess() -> session.saveRefreshed(tokenBody(res), old)
        res.status.value == 401 -> null                  // INVALID_REFRESH_TOKEN
        else -> throw RefreshFailed(errorOf(res))        // 5xx 등. 토큰 유지
    }
}

private suspend fun RefreshTokensParams.postRefresh(refreshToken: String, retryDelays: List<Long>): HttpResponse {
    var attempt = 0
    while (true) {
        try {
            return client.post("auth/token/refresh") {
                markAsRefreshTokenRequest()              // 이 요청엔 Bearer 를 붙이지 않고, 이 요청의 401 로 다시 재발급하지 않는다
                timeout { requestTimeoutMillis = REFRESH_TIMEOUT_MILLIS }
                setBody(RefreshRequest(refreshToken))    // 같은 refreshToken 으로만 재시도한다(서버 30초 유예)
            }
        } catch (e: IOException) {
            if (attempt == retryDelays.size) throw e
            delay(retryDelays[attempt++])
        }
    }
}

/** 파싱 예외 메시지에는 응답 본문(토큰) 일부가 들어가므로 원인을 버리고 던진다 */
private suspend fun tokenBody(res: HttpResponse): TokenResponse =
    catchingNonCancel { res.body<TokenResponse>() }.getOrElse { throw RefreshFailed(AppError.Unknown(IllegalStateException("재발급 응답을 읽지 못함"))) }

private suspend fun errorCode(res: HttpResponse) = catchingNonCancel { res.body<ErrorBody>() }.getOrNull()?.code
