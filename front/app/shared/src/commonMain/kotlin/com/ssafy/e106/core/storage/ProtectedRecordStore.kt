package com.ssafy.e106.core.storage

/** 비밀 복구 기록 전용. 일반 계정 캐시와 독립적이며 로그아웃으로 지우지 않는다. 값/예외를 로그에 남기지 않는다. */
interface ProtectedRecordStore {
    suspend fun read(key: String): ProtectedRead
    /** 손상/읽기 실패가 있는 기존 기록을 새 요청으로 덮어쓰지 않고 실패시킨다. 디스크 반영 후 반환한다. */
    suspend fun write(key: String, value: String)
    /** 확인한 값과 여전히 같을 때만 삭제한다. 늦은 완료가 새 실행을 삭제하는 일을 막는다. */
    suspend fun remove(key: String, expectedValue: String): Boolean
}

sealed interface ProtectedRead {
    class Found(val value: String) : ProtectedRead { override fun toString() = "Found(REDACTED)" }
    data object Missing : ProtectedRead
    /** 손상·키 유실·일시 IO 실패 모두 기록 없음과 구분한다. 자동 삭제하지 않는다. */
    data object Unavailable : ProtectedRead
}
