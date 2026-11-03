package com.ssafy.e106.di

import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.DefaultAuthRepository
import com.ssafy.e106.core.auth.DefaultSessionStore
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.push.DefaultPushEvents
import com.ssafy.e106.core.push.PushDevice
import com.ssafy.e106.core.push.PushEvents
import com.ssafy.e106.core.push.PushRegistrar
import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.DataStoreAppPrefs
import com.ssafy.e106.core.storage.TokenStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * 기반 부품 등록. 기능 모듈(profileModule 등)은 `get()` 으로 여기 것을 받는다.
 * 주소·로그·소셜 키는 앱이 넘긴다. Android 는 TingleApplication 이 `startKoin { androidContext(this); modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG, SocialKeys(…)), …) }`.
 * §5 구현이 올라올 때마다 한 줄씩 더한다.
 */
fun coreModule(baseUrl: String, httpLog: Boolean, socialKeys: SocialKeys) = module {
    single<TokenStore> { platformTokenStore(baseUrl) }
    single<SocialLogin>(createdAtStart = true) { platformSocialLogin(socialKeys) }   // 앞에 뜬 Activity 를 앱 시작부터 기억해야 한다
    // HttpClient(Bearer · 재발급)와 SessionStore 는 서로를 부른다. 같이 만들고 SessionStore 를 시작한다. 푸시 등록기도 같이 시작한다
    // (AppPrefs 가 앱 시작부터 세션을 보므로 FCM 이 프로세스를 깨울 때도 여기까지 돈다). 등록은 이전 계정의 로그아웃 재전송이 끝난 뒤에 —
    // 서버가 이전 계정에 묶어 둔 설치로 등록하지 않게
    single {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        DefaultSessionStore(get(), scope).also {
            it.client = createHttpClient(baseUrl, httpLog, session = it)
            it.restore()
            PushRegistrar(it, it.client, get(), beforeRegister = { it.pendingLogoutFlush?.join() }).start(scope)
        }
    }
    single<SessionStore> { get<DefaultSessionStore>() }
    single<HttpClient> { get<DefaultSessionStore>().client }
    single<AuthRepository> { DefaultAuthRepository(get(), get()) }
    single<PushDevice> { platformPushDevice() }
    single { DefaultPushEvents(get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }   // 메인 스레드 전용
    single<PushEvents> { get<DefaultPushEvents>() }
    // 세션 상태를 지켜보며 계정 데이터를 지우므로 앱 시작부터 만든다
    single<DataStore<Preferences>> { platformPrefsStore() }
    single<AppPrefs>(createdAtStart = true) { DataStoreAppPrefs(get(), get<SessionStore>().state, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}

/** 토큰 저장소는 플랫폼마다. 서버 주소별로 따로 저장한다 */
internal expect fun Scope.platformTokenStore(baseUrl: String): TokenStore

/** AppPrefs 파일(DataStore). 파일 하나에 인스턴스 하나여야 해서 coreModule 의 single 안에서만 부른다 */
internal expect fun Scope.platformPrefsStore(): DataStore<Preferences>

/** 소셜 SDK 래퍼는 플랫폼마다 */
internal expect fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin

/** 푸시 토큰 · 설치 ID 는 플랫폼마다 */
internal expect fun Scope.platformPushDevice(): PushDevice
