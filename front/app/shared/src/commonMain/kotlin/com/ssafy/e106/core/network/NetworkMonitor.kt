package com.ssafy.e106.core.network

import kotlinx.coroutines.flow.Flow

/** OS가 확인한 기본 인터넷 연결 상태. true도 개별 서버의 응답 성공을 보장하지 않는다.
 * 구독 중에만 감시하고 취소하면 해제한다. 미지원 플랫폼은 신호를 만들지 않고 수동 복구를 유지한다. */
interface NetworkMonitor {
    val availability: Flow<Boolean>
}
