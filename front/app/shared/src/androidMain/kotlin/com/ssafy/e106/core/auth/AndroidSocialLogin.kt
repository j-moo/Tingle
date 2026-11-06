package com.ssafy.e106.core.auth

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.user.UserApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * 구글(Credential Manager) · 카카오 SDK · 네이버 OIDC(Custom Tab, [NaverOidc]). 179 브랜치 AndroidSocialLogin 을 가져와 결과를 [SocialToken] 으로 바꿨다.
 * SDK 창은 Activity 가 있어야 띄울 수 있어, 지금 앞에 있는 Activity 를 기억한다(앱 시작 때 만들어져야 해서 Koin 에서 createdAtStart).
 */
internal class AndroidSocialLogin(app: Application, private val keys: SocialKeys) : SocialLogin {
    private var current = WeakReference<Activity>(null)
    private val credentials = CredentialManager.create(app)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
            override fun onActivityDestroyed(activity: Activity) { if (current.get() === activity) current = WeakReference(null) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        if (keys.kakaoNativeAppKey.isNotBlank()) KakaoSdk.init(app, keys.kakaoNativeAppKey)
    }

    override fun configured(provider: Provider) = when (provider) {
        Provider.GOOGLE -> keys.googleWebClientId.isNotBlank()
        Provider.KAKAO -> keys.kakaoNativeAppKey.isNotBlank()
        Provider.NAVER -> keys.naverClientId.isNotBlank() && keys.naverRedirectUri.isNotBlank()
    }

    override suspend fun token(provider: Provider): SocialToken = withContext(Dispatchers.Main.immediate) {
        // 끝나는 중인 화면으로는 SDK 창을 띄우지 않는다
        val activity = current.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@withContext SocialToken.Failed
        try {
            when (provider) {
                Provider.GOOGLE -> google(activity)
                Provider.KAKAO -> kakao(activity)
                Provider.NAVER -> naver(activity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SocialToken.Failed                           // 원인(SDK 메시지)은 화면에 보이지 않는다
        }
    }

    private suspend fun google(activity: Activity): SocialToken = try {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(keys.googleWebClientId).build())
            .build()
        val credential = credentials.getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            SocialToken.Ok(GoogleIdTokenCredential.createFrom(credential.data).idToken)   // 서버가 aud 를 GOOGLE_WEB_CLIENT_ID 와 대조한다
        } else SocialToken.Failed
    } catch (_: GetCredentialCancellationException) {
        SocialToken.Cancelled
    }

    /** 카카오톡이 있으면 카카오톡으로(입력 없이 동의만), 없거나 카카오톡 쪽이 실패하면 카카오계정(웹). 사용자가 취소하면 웹으로 넘기지 않는다. 토큰은 둘 다 같은 access token */
    private suspend fun kakao(activity: Activity): SocialToken {
        if (UserApiClient.instance.isKakaoTalkLoginAvailable(activity)) {
            val talk = kakaoLogin { UserApiClient.instance.loginWithKakaoTalk(activity, callback = it) }
            if (talk != SocialToken.Failed) return talk
        }
        return kakaoLogin { UserApiClient.instance.loginWithKakaoAccount(activity, callback = it) }
    }

    private suspend fun kakaoLogin(start: ((OAuthToken?, Throwable?) -> Unit) -> Unit): SocialToken = suspendCancellableCoroutine { cont ->
        start { token, error ->
            if (!cont.isActive) return@start
            cont.resume(
                when {
                    error is ClientError && error.reason == ClientErrorCause.Cancelled -> SocialToken.Cancelled
                    error != null || token == null -> SocialToken.Failed
                    else -> SocialToken.Ok(token.accessToken)
                },
            )
        }
    }

    /** 네이버 OIDC: Custom Tab 인가 → 콜백 code(state 확인) → 서버로 code · verifier · nonce. 교환 · id_token 검증은 서버가 한다([NaverOidc]) */
    private suspend fun naver(activity: Activity): SocialToken {
        val attempt = NaverPkce.newAttempt()
        return NaverOidc.login(
            clientId = keys.naverClientId,
            redirectUri = keys.naverRedirectUri,
            attempt = attempt,
            openBrowser = { url -> NaverAuthActivity.authorize(activity, url, keys.naverRedirectUri, attempt.state) },
        )
    }

    override suspend fun clear() {
        // 각 SDK 의 로그인 상태만 지운다. 서비스 세션(refresh family)은 이미 서버 로그아웃으로 끝났다
        try { credentials.clearCredentialState(ClearCredentialStateRequest()) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        if (configured(Provider.KAKAO)) UserApiClient.instance.logout { }
        // 네이버는 앱에 남는 로그인 상태가 없다(토큰을 저장하지 않는다). 브라우저의 네이버 로그인은 사용자 것이라 건드리지 않는다
    }
}
