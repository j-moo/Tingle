package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.network.catchingNonCancel
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable

/** 소셜 로그인 실패를 화면에 보일 문구. SDK 오류는 서버 문구가 없어 기본 문구로 */
internal const val SOCIAL_LOGIN_FAILED_MESSAGE = "로그인하지 못했어요. 다시 시도해 주세요"

@Serializable
private class LoginRequest(val token: String)

@Serializable
internal class LogoutRequest(val refreshToken: String)

/**
 * 로그아웃이 서버에 닿지 않았을 수 있는 결과. 나중에 다시 보낸다.
 * 서버가 받았다고 확실한 것(2xx, 그 토큰을 거절한 400 · 401)만 끝으로 보고, 그 밖(연결 실패 · 5xx · 게이트웨이 403/404 · 429 · 알 수 없는 오류)은 남긴다.
 * 세션 코드가 붙은 401 은 [errorOf] 가 SessionExpired 로 바꾸므로 그것도 401 로 본다(안 그러면 끝없이 다시 보낸다)
 */
internal fun ApiResult<*>.shouldRetryLogout() = this is ApiResult.Fail && error != AppError.SessionExpired && (error as? AppError.Server)?.status.let { it != 400 && it != 401 }

/**
 * 로그인 · 로그아웃(기반_인터페이스.md §3). 토큰 저장 · 세션 전이는 [session] 이 한다.
 * 네이버는 서버가 토큰의 발급 앱을 확인하지 않아(다른 앱 토큰으로 로그인 가능) 서버가 고칠 때까지 막는다 — 10-30/나중에_할_일.md
 */
internal class DefaultAuthRepository(
    private val social: SocialLogin,
    private val session: DefaultSessionStore,
) : AuthRepository {
    override fun available(provider: Provider) = provider != Provider.NAVER && social.configured(provider)

    override suspend fun login(provider: Provider): LoginResult {
        if (!available(provider)) return LoginResult.Failed(AppError.Server(0, "SOCIAL_NOT_CONFIGURED", SOCIAL_LOGIN_FAILED_MESSAGE))
        session.flushPendingLogoutLater()                // 이전에 못 보낸 로그아웃. 다른 계정으로 로그인해도 이전 세션이 서버에 남지 않게
        val token = when (val t = social.token(provider)) {
            is SocialToken.Ok -> t.token
            SocialToken.Cancelled -> return LoginResult.Cancelled
            SocialToken.Failed -> return LoginResult.Failed(AppError.Server(0, "SOCIAL_LOGIN_FAILED", SOCIAL_LOGIN_FAILED_MESSAGE))
        }
        return when (val r = session.client.call<TokenResponse> { post("auth/${provider.wire}") { setBody(LoginRequest(token)) } }) {
            is ApiResult.Fail -> LoginResult.Failed(r.error)   // 401 INVALID_SOCIAL_TOKEN · 502 SOCIAL_PROVIDER_ERROR 등 서버 문구 그대로
            // 토큰 저장(Keystore) 오류도 예외 대신 결과로. 화면이 로딩을 풀고 문구를 보여야 한다
            is ApiResult.Ok -> catchingNonCancel { session.signIn(r.value) }.fold(
                onSuccess = { signedIn -> signedIn?.let { LoginResult.Success(it) } ?: LoginResult.Failed(AppError.SessionExpired) },
                onFailure = { LoginResult.Failed(AppError.Unknown(it)) },
            )
        }
    }

    override suspend fun logout() {
        // 세대는 잠금 밖에서 먼저 잡는다. 잠금 대기 중 취소되고 그 사이 새 로그인이 끝나도, 정리가 새 세션을 보관 · 삭제하지 않는다
        val startedAt = session.currentGeneration
        var unsent = true                                    // 토큰을 읽기 전 · 읽는 중 취소면 서버에 못 보냈다 → 정리 때 보관
        try {
            val saved = session.beginSignOut(startedAt)       // 이 읽기 중 취소돼도 아래 finally 가 정리한다
            unsent = false
            // 세션 잠금 밖에서 보낸다(느린 네트워크가 다른 세션 처리를 막지 않게). 그 사이 재발급이 토큰을 바꿔도 서버는 family 전체를 폐기하고,
            // 늦게 온 재발급 응답은 저장 직전 비교로 버려진다. 실패해도 기기는 로그아웃하고, 서버에 닿지 않았을 수 있으면 보관했다 다시 보낸다
            if (saved != null) {
                var sent = false
                try {
                    sent = !session.client.call<Unit> { post("auth/logout") { setBody(LogoutRequest(saved.refreshToken)) } }.shouldRetryLogout()
                } finally {
                    if (!sent) session.keepPendingLogout(saved.refreshToken)   // 도중 취소도 보관한다
                }
            }
            catchingNonCancel { social.clear() }
            // 계정 캐시는 AppPrefs 가 SignedOut 을 보고 지운다. TODO(S15P31E106-195): 푸시 대기 비우기
        } finally {
            session.signOut(startedAt, unsent)           // 도중에 화면이 바뀌어 취소돼도 끝낸다. 그 사이 다른 로그인이면 건드리지 않는다
        }
    }
}
