package com.ssafy.e106.core.network

import io.ktor.client.engine.HttpClientEngine

// iOS 엔진(Darwin)은 S15P31E106-197. 그때 WebSocket ping 도 정한다
internal actual fun platformEngine(): HttpClientEngine? = null
