package com.ssafy.e106.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

// OkHttp 엔진은 WebSocket ping 을 Ktor 플러그인 설정이 아니라 OkHttpClient 설정으로만 보낸다. pong 이 안 오면 연결을 실패로 끝내
// 한쪽만 살아 있는 연결(NAT 만료 · 와이파이↔LTE 전환)을 알아챈다(core/realtime 이 다시 연결). 서버도 15초마다 ping 을 보낸다
internal actual fun platformEngine(): HttpClientEngine? = OkHttp.create { config { pingInterval(20, TimeUnit.SECONDS) } }
