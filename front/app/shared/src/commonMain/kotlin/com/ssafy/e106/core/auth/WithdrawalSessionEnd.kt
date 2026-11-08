package com.ssafy.e106.core.auth

/** 실행 직전 잡은 계정·세션 세대. 토큰이나 영수증을 포함하지 않는다. */
class WithdrawalSessionHandle internal constructor(internal val userId: Long, internal val generation: Long)

/** 서버의 COMPLETED 확인 이후 호출한다. 늦은 이전 계정 결과가 새 로그인을 끝내지 않도록 세대를 대조한다. */
interface WithdrawalSessionEnd {
    suspend fun capture(userId: Long): WithdrawalSessionHandle?
    /** 현재 세션과 일치해 정리했으면 true. 이미 세션이 바뀌었으면 false이며 새 세션을 건드리지 않는다. */
    suspend fun finish(handle: WithdrawalSessionHandle): Boolean
}
