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
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** 서버가 필드를 늘려도 안 깨진다. 반대로 서버는 모르는 필드를 400 으로 거부하므로 요청 DTO 에 여분 필드를 넣지 않는다 */
val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * 앱에 하나뿐인 HttpClient. Koin `coreModule` 이 만들고 각 기능 Api 가 `get()` 으로 받는다.
 * 주소 앞부분(baseUrl, / 로 끝남)과 로그 여부(httpLog)는 앱 빌드 타입이 정해 넘긴다. JSON · 타임아웃이 여기 있고, Api 는 "users/…" 상대 경로만 쓴다. Bearer 는 §2 에서 여기에 더한다.
 */
fun createHttpClient(baseUrl: String, httpLog: Boolean, engine: HttpClientEngine? = null): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false                            // 4xx·5xx 도 예외 대신 응답으로 받아 call 이 AppError 로 바꾼다
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
        if (httpLog) install(Logging) {                  // debug 빌드만. release 는 URL 도 남기지 않는다
            logger = Logger.SIMPLE
            level = LogLevel.INFO                        // URL·상태만. 본문 로그 금지(로그인·재발급 본문에 토큰이 있다)
            sanitizeHeader { it == HttpHeaders.Authorization }   // 헤더는 Authorization 만 가린다
        }
        defaultRequest {
            url(baseUrl)                                 // 반드시 / 로 끝난다
            header(HttpHeaders.AcceptLanguage, "ko")
            contentType(ContentType.Application.Json)
        }
    }
    // 엔진을 안 넘기면 Ktor 가 의존성에 있는 엔진(androidMain 의 OkHttp)을 고른다
    return if (engine != null) HttpClient(engine, config) else HttpClient(config)
}
