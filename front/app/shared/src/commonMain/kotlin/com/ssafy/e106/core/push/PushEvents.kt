package com.ssafy.e106.core.push

import kotlinx.coroutines.flow.StateFlow

/** 알림을 눌렀을 때 열 화면. payload 의 type 필드로 결정. 종류·식별자는 BE 와 합의 후 늘린다 */
sealed interface PushTarget {
    data class Chat(val roomId: Long) : PushTarget
    data class Proposal(val proposalId: Long) : PushTarget

    /** 모르는 type 이면 홈 */
    data object Home : PushTarget
}

interface PushEvents {
    /** 알림을 눌러 앱이 열렸거나(콜드 스타트 포함) 앞으로 왔을 때 채워진다. 이동 전까지 들고 있는다. MainScaffold 한 곳에서만 소비한다 */
    val pending: StateFlow<PushTarget?>

    /** 이동한 뒤 비운다. 그 사이 새 알림이 들어왔으면(값이 다르면) 비우지 않는다 */
    fun consume(target: PushTarget)
}
