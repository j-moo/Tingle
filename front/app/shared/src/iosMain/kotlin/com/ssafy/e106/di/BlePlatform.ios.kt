package com.ssafy.e106.di

import com.ssafy.e106.core.ble.*
import kotlinx.coroutines.flow.flow
import org.koin.core.scope.Scope

/** iOS 연동 미구현을 실제 연결/가상 성공으로 대체하지 않는다. */
internal actual fun Scope.platformKeyringBle(): KeyringBle = object : KeyringBle {
    override fun scan() = flow<List<BleCandidate>> { throw BleException(BleFailure.Unsupported) }
    override suspend fun connect(candidateId: String): BleConnection = throw BleException(BleFailure.Unsupported)
}
