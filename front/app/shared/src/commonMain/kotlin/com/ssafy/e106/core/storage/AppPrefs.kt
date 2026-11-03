package com.ssafy.e106.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/** 키는 각 기능이 자기 파일에 선언한다. core 는 기능 DTO 를 모른다 */
class PrefKey<T>(val name: String, val serializer: KSerializer<T>)

/**
 * 프로필 · 설정 캐시(DataStore). 키는 내부에서 "<userId>:<name>" 로 계정별 분리. 로그아웃 · 세션 만료 · 계정 전환 때 이전 계정 데이터는 기반이 지운다.
 * **암호화하지 않는 평문 파일이다. 토큰 · 소셜 토큰 · 비밀번호류는 넣지 않는다**(토큰은 TokenStore 만).
 */
interface AppPrefs {
    /** 현재 로그인 userId 기준. SignedOut 이면 null 만 흐른다 */
    fun <T> flow(key: PrefKey<T>): Flow<T?>

    /** [forUser] 가 현재 로그인 userId 와 다르면 무시(늦게 온 이전 계정 응답 차단). 요청 시작 때의 userId 를 넘긴다 */
    suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long)
}
