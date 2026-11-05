package com.ssafy.e106.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * 키는 각 기능이 자기 파일에 선언한다. core 는 기능 DTO 를 모른다.
 * [durable] = 서버에서 다시 받을 수 없는 기록(미확인 요청의 Idempotency-Key 등). 캐시와 다른 파일에 두고, 파일이 깨져도 자동으로 비우지 않는다.
 * 깨진 파일은 [AppPrefs.read] 가 [PrefRead.Corrupt] 로 알린다. 읽을 때는 [AppPrefs.flow] 가 아니라 [AppPrefs.read] 를 쓴다
 */
class PrefKey<T>(val name: String, val serializer: KSerializer<T>, val durable: Boolean = false)

/**
 * 프로필 · 설정 캐시(DataStore). 키는 내부에서 "<userId>:<name>" 로 계정별 분리. 로그아웃 · 세션 만료 · 계정 전환 때 이전 계정 데이터는 기반이 지운다.
 * **암호화하지 않는 평문 파일이다. 토큰 · 소셜 토큰 · 비밀번호류는 넣지 않는다**(토큰은 TokenStore 만).
 */
interface AppPrefs {
    /** 현재 로그인 userId 기준. SignedOut 이면 null 만 흐른다 */
    fun <T> flow(key: PrefKey<T>): Flow<T?>

    /** [forUser] 가 현재 로그인 userId 와 다르면 무시(늦게 온 이전 계정 응답 차단). 요청 시작 때의 userId 를 넘긴다 */
    suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long)

    /**
     * 한 번 읽기. [flow] 와 달리 못 읽은 것을 '없음'으로 숨기지 않는다.
     * 서버에서 다시 받을 수 없는 값(미확인 요청의 Idempotency-Key 등)처럼 '없음'과 '못 읽음'을 구분해야 할 때 쓴다.
     * [forUser] 는 요청 시작 때의 userId. 읽기 전이나 읽는 사이 로그아웃 · 계정 전환이 있었으면 [PrefRead.OtherUser]
     */
    suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T>
}

/** [AppPrefs.read] 결과 */
sealed interface PrefRead<out T> {
    /** 읽기 성공 · 기록 있음 */
    data class Found<T>(val value: T) : PrefRead<T>

    /** 읽기 성공 · 기록 없음 */
    data object Missing : PrefRead<Nothing>

    /** 저장소를 읽지 못함(디스크 오류 등). 기록이 있는지 모른다 — 다시 시도한다 */
    data object Unreadable : PrefRead<Nothing>

    /** 파일이 깨졌거나(durable 키) 값을 해석하지 못함(앱 업데이트로 모양이 바뀐 값 등). 기록이 있었는지 모르고, 다시 읽어도 같다. 기반은 지우지 않는다 */
    data object Corrupt : PrefRead<Nothing>

    /** [AppPrefs.read] 의 forUser 가 현재 로그인 계정이 아니다(로그아웃 · 계정 전환). 결과를 버린다 */
    data object OtherUser : PrefRead<Nothing>
}
