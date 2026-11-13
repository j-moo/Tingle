package com.ssafy.e106.di

import com.ssafy.e106.core.network.NetworkMonitor
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.scope.Scope

/** iOS 감지 기반은 아직 미구현이다. 가짜 온라인 신호 없이 기존 수동 복구 경로를 유지한다. */
internal actual fun Scope.platformNetworkMonitor(): NetworkMonitor = object : NetworkMonitor {
    override val availability = emptyFlow<Boolean>()
}
