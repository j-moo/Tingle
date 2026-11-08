package com.ssafy.e106.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** 서버가 필드를 늘려도 안 깨진다. 반대로 서버는 모르는 필드를 400 으로 거부하므로 요청 DTO 에 여분 필드를 넣지 않는다 */
val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * 앱에 하나뿐인 HttpClient. Koin `coreModule` 이 세션과 같이 만들고 각 기능 Api 가 `get()` 으로 받는다.
 * 주소 앞부분(baseUrl, / 로 끝남)과 로그 여부(httpLog)는 앱 빌드 타입이 정해 넘긴다. JSON · 타임아웃 · Bearer 가 여기 있고, Api 는 "users/…" 상대 경로만 쓴다.
 * [session] 이 없으면 Bearer 없이 만든다(테스트용).
 */
internal fun createHttpClient(
    baseUrl: String,
    httpLog: Boolean,
    engine: HttpClientEngine? = null,
    session: SessionAuth? = null,
    refreshRetryDelays: List<Long> = REFRESH_RETRY_DELAYS,
    followRedirects: Boolean = true,
): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false                            // 4xx·5xx 도 예외 대신 응답으로 받아 call 이 AppError 로 바꾼다
        this.followRedirects = followRedirects
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
        install(WebSockets)                              // 채팅 실시간 수신(core/realtime). ping 은 엔진 설정([platformEngine])
        if (httpLog) install(Logging) {                  // debug 빌드만. release 는 URL 도 남기지 않는다
            logger = Logger.SIMPLE
            level = LogLevel.INFO                        // URL·상태만. 본문 로그 금지(로그인·재발급 본문에 토큰이 있다)
            // 상세 로그 수준을 바꾸더라도 탈퇴 결과 영수증이 출력되지 않도록 함께 가린다.
            sanitizeHeader { it.equals(HttpHeaders.Authorization, true) || it.equals("X-Withdrawal-Receipt", true) }
        }
        defaultRequest {
            url(baseUrl)                                 // 반드시 / 로 끝난다
            header(HttpHeaders.AcceptLanguage, "ko")
            contentType(ContentType.Application.Json)
        }
        if (session != null) installSessionAuth(baseUrl, session, refreshRetryDelays)
    }
    // 엔진을 안 넘기면 플랫폼 엔진(Android 는 WebSocket ping 을 켠 OkHttp), 없으면 Ktor 가 의존성에서 고른다
    val chosen = engine ?: platformEngine()
    return if (chosen != null) HttpClient(chosen, config) else HttpClient(config)
}

/** 플랫폼 HTTP 엔진. null 이면 Ktor 가 의존성에 있는 엔진을 고른다 */
internal expect fun platformEngine(): HttpClientEngine?

/** 세션이 폐기된 뒤에도 쓰는 결과 조회 전용. Bearer/refresh/세션 만료 콜백·HTTP 로그가 없고 영수증 리다이렉트도 금지한다. */
internal fun createReceiptHttpClient(baseUrl: String, engine: HttpClientEngine? = null): HttpClient =
    createHttpClient(baseUrl, httpLog = false, engine = engine, session = null, followRedirects = false)
