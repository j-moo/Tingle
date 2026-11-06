package com.ssafy.e106.core.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

/** 실제 Android 콜백 등록/해제를 검증한다. 서버·계정·기기 연결이나 네트워크 설정은 바꾸지 않는다. */
class NetworkMonitorDeviceTest {
    @Test fun repeatedForegroundSubscriptionsReleaseAndroidCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        assumeTrue("검증된 인터넷 경로가 있는 에뮬레이터에서 실행",
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val monitor = AndroidNetworkMonitor(context)
        // first()가 구독을 취소한 뒤 OS 콜백도 해제돼야 한다. 해제가 누락되면 UID당 콜백 제한에 걸린다.
        // 실패를 false로 내리는 방어 경로까지 포함해, 매번 실제 온라인 신호가 나오는지 검사한다.
        repeat(110) { index ->
            assertTrue(withTimeout(5_000) { monitor.availability.first { it } }, "구독 ${index + 1}")
        }
    }
}
