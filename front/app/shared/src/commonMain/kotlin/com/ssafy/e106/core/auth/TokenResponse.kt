package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiJson
import com.ssafy.e106.core.storage.StoredTokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** POST auth/{provider} · auth/token/refresh 응답(B/auth/dto/TokenResponse.java) */
@Serializable
internal data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val accessTokenExpiresAt: Instant,
    val refreshTokenExpiresAt: Instant,
    val onboardingStatus: OnboardingStatus,
) {
    /** 로그 · 크래시 리포트에 찍혀도 토큰이 새지 않게 가린다 */
    override fun toString() = "TokenResponse(accessToken=***, refreshToken=***, tokenType=$tokenType, accessTokenExpiresAt=$accessTokenExpiresAt, " +
        "refreshTokenExpiresAt=$refreshTokenExpiresAt, onboardingStatus=$onboardingStatus)"
}

/** 저장 모양으로 바꾼다. sub 를 못 읽으면 null(경로가 users/{userId}/… 라 userId 없이는 아무 API 도 못 부른다) */
internal fun TokenResponse.toStored(signupCompleted: Boolean): StoredTokens? = jwtSubject(accessToken)?.let { userId ->
    StoredTokens(accessToken, refreshToken, tokenType, accessTokenExpiresAt, refreshTokenExpiresAt, onboardingStatus, userId, signupCompleted)
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** JWT payload 의 sub(회원번호)만 꺼낸다. 서명 검증은 서버 몫이라 하지 않는다 */
internal fun jwtSubject(jwt: String): Long? = runCatching {
    val payload = base64Url.decode(jwt.split('.')[1]).decodeToString()
    ApiJson.parseToJsonElement(payload).jsonObject["sub"]?.jsonPrimitive?.content?.toLongOrNull()
}.getOrNull()
