package com.ssafy.e106.core.storage

import com.ssafy.e106.core.auth.OnboardingStatus
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * 로그인 상태 그 자체(리프레시 토큰). 잃으면 재로그인, 새면 그 회원 세션 전체가 날아간다.
 * 순철 내부용이라 화면 코드에는 나오지 않는다. 구현은 플랫폼별: Android 는 Keystore(AndroidTokenStore), iOS 는 S15P31E106-197.
 */
internal interface TokenStore {
    /** 없거나 복호화에 실패하면 null. 깨진 값은 지운다 */
    suspend fun load(): StoredTokens?

    /** 재발급 응답마다 새 refreshToken 으로 통째로 바꾼다(회전) */
    suspend fun save(tokens: StoredTokens)

    suspend fun clear()

    /**
     * 오프라인 · 서버 오류로 못 보낸 로그아웃의 refreshToken 들. 안 보내면 서버 세션과 이 기기의 푸시 연결이 남는다(기반_인터페이스.md §6).
     * 계정마다 하나씩 쌓여 서로 덮지 않고, [clear] 와 상관없이 남는다. 깨졌으면 빈 목록(값만 버린다).
     * 그래서 전송이 실패한 동안에는 로그아웃 뒤에도 유효한 refreshToken 이 토큰과 같은 보호(Keystore 암호화)로 기기에 남는다
     */
    suspend fun pendingLogouts(): List<String>

    /** 목록에 더한다. 오래된 것부터 [MAX_PENDING_LOGOUTS] 개만 남긴다 */
    suspend fun addPendingLogout(refreshToken: String)

    /** 보낸 것만 뺀다. 그 사이 더해진 것은 남는다 */
    suspend fun removePendingLogout(refreshToken: String)
}

/** 보관하는 로그아웃 수. 그보다 오래된 세션은 서버 리프레시 만료로 끝난다 */
internal const val MAX_PENDING_LOGOUTS = 5

/** 서버 토큰 응답 6필드 + userId(액세스 토큰 sub) + 마지막으로 본 signupCompleted */
@Serializable
internal data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
    val onboardingStatus: OnboardingStatus,
    val userId: Long,
    /** 토큰 응답에는 없고 GET users/{id}/onboarding 에만 있다. 오프라인으로 앱을 켰을 때 마지막 값으로 복원한다(기반_인터페이스.md §2) */
    val signupCompleted: Boolean = false,
) {
    /** 로그 · 크래시 리포트에 찍혀도 토큰이 새지 않게 가린다 */
    override fun toString() = "StoredTokens(accessToken=***, refreshToken=***, tokenType=$tokenType, accessTokenExpiresAt=$accessTokenExpiresAt, " +
        "refreshTokenExpiresAt=$refreshTokenExpiresAt, onboardingStatus=$onboardingStatus, userId=$userId, signupCompleted=$signupCompleted)"
}
