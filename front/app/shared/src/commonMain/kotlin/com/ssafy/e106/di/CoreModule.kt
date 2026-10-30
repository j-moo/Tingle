package com.ssafy.e106.di

import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.DefaultAuthRepository
import com.ssafy.e106.core.auth.DefaultSessionStore
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.storage.TokenStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * 기반 부품 등록. 기능 모듈(profileModule 등)은 `get()` 으로 여기 것을 받는다.
 * 주소·로그·소셜 키는 앱이 넘긴다. Android 는 TingleApplication 이 `startKoin { androidContext(this); modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG, SocialKeys(…)), …) }`.
 * §5 구현이 올라올 때마다 한 줄씩 더한다: single<AppPrefs> { … } 등.
 */
fun coreModule(baseUrl: String, httpLog: Boolean, socialKeys: SocialKeys) = module {
    single<TokenStore> { platformTokenStore(baseUrl) }
    single<SocialLogin>(createdAtStart = true) { platformSocialLogin(socialKeys) }   // 앞에 뜬 Activity 를 앱 시작부터 기억해야 한다
    // HttpClient(Bearer · 재발급)와 SessionStore 는 서로를 부른다. 같이 만들고 SessionStore 를 시작한다
    single {
        DefaultSessionStore(get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)).also {
            it.client = createHttpClient(baseUrl, httpLog, session = it)
            it.restore()
        }
    }
    single<SessionStore> { get<DefaultSessionStore>() }
    single<HttpClient> { get<DefaultSessionStore>().client }
    single<AuthRepository> { DefaultAuthRepository(get(), get()) }
}

/** 토큰 저장소는 플랫폼마다. 서버 주소별로 따로 저장한다 */
internal expect fun Scope.platformTokenStore(baseUrl: String): TokenStore

/** 소셜 SDK 래퍼는 플랫폼마다 */
internal expect fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin
