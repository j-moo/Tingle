package com.ssafy.e106.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Wi-Fi 접속만으로 온라인이라고 판단하지 않는다. 캡티브 포털 등을 제외한 VALIDATED 기본 경로를 관찰한다.
 * Application Context만 보관하며 전경 구독이 끝나면 콜백도 해제해 Activity와 백그라운드 작업을 남기지 않는다. */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    override val availability = callbackFlow {
        val lock = Any()
        var current: Network? = null
        fun valid(capabilities: NetworkCapabilities?) = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(lock) {
                // 콜백 안에서 동기 조회하지 않는다. 같은 경로의 초기 통지는 복원 이벤트를 중복 생성하지 않는다.
                if (current != network) { current = network; trySend(false) }
                Unit
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                if (current == network) trySend(valid(capabilities))
                Unit
            }
            override fun onLost(network: Network) = synchronized(lock) {
                // 새 기본 경로가 생긴 뒤 도착한 이전 경로의 종료로 온라인 상태를 덮어쓰지 않는다.
                if (current == network) { current = null; trySend(false) }
                Unit
            }
        }
        var registered = false
        try {
            synchronized(lock) {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
                // 등록 직후 스냅샷과 콜백을 직렬화한다. 연결이 처음부터 없어도 false를 전달한다.
                current = manager.activeNetwork
                trySend(valid(current?.let(manager::getNetworkCapabilities)))
            }
        } catch (_: RuntimeException) {
            // 권한/OS 제한으로 감시하지 못해도 온라인을 꾸미거나 로그인·수동 재시도를 막지 않는다.
            if (registered) manager.unregisterNetworkCallback(callback)
            trySend(false); close(); return@callbackFlow
        }
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
