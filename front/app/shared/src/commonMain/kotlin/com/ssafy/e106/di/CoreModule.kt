package com.ssafy.e106.di

import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.DefaultAuthRepository
import com.ssafy.e106.core.auth.DefaultSessionStore
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.auth.SocialKeys
import com.ssafy.e106.core.auth.SocialLogin
import com.ssafy.e106.core.ble.KeyringBle
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.network.NetworkMonitor
import com.ssafy.e106.core.push.DefaultPushEvents
import com.ssafy.e106.core.push.PushDevice
import com.ssafy.e106.core.push.PushEvents
import com.ssafy.e106.core.push.PushRegistrar
import com.ssafy.e106.core.realtime.ChatEvents
import com.ssafy.e106.core.realtime.DefaultChatEvents
import com.ssafy.e106.core.realtime.HttpChatRoomProbe
import com.ssafy.e106.core.realtime.KtorChatSocket
import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.DataStoreAppPrefs
import com.ssafy.e106.core.storage.TokenStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * 기반 부품 등록. 기능 모듈(profileModule 등)은 `get()` 으로 여기 것을 받는다.
 * 주소·로그·소셜 키는 앱이 넘긴다. Android 는 TingleApplication 이 `startKoin { androidContext(this); modules(coreModule(BuildConfig.API_BASE_URL, BuildConfig.HTTP_LOG, SocialKeys(…)), …) }`.
 * §5 구현이 올라올 때마다 한 줄씩 더한다.
 */
fun coreModule(baseUrl: String, httpLog: Boolean, socialKeys: SocialKeys) = module {
    // 인터넷 복원 신호는 공통으로 제공하고 재시도 대상/횟수는 기능이 결정한다.
    single<NetworkMonitor> { platformNetworkMonitor() }
    // 권한 요청은 화면의 사용자 동작에서만 한다. 플랫폼 인스턴스 생성은 무선 작업을 시작하지 않는다.
    single<KeyringBle> { platformKeyringBle() }
    single<TokenStore> { platformTokenStore(baseUrl) }
    // 탈퇴 영수증은 로그아웃 캐시와 독립적으로 유지한다. 플랫폼 미지원 시 평문으로 우회하지 않는다.
    single<com.ssafy.e106.core.storage.ProtectedRecordStore> { platformProtectedRecordStore(baseUrl) }
    single<HttpClient>(named(RECEIPT_HTTP_CLIENT)) { com.ssafy.e106.core.network.createReceiptHttpClient(baseUrl) }
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
    single<com.ssafy.e106.core.auth.WithdrawalSessionEnd> { get<DefaultSessionStore>() }
    single<HttpClient> { get<DefaultSessionStore>().client }
    single<AuthRepository> { DefaultAuthRepository(get(), get()) }
    single<PushDevice> { platformPushDevice() }
    single { DefaultPushEvents(get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }   // 메인 스레드 전용
    single<PushEvents> { get<DefaultPushEvents>() }
    single { com.ssafy.e106.core.push.NotificationInbox(get()) }
    // 세션 상태를 지켜보며 계정 데이터를 지우므로 앱 시작부터 만든다
    single<DataStore<Preferences>>(named(CACHE_PREFS_FILE)) { platformPrefsStore(CACHE_PREFS_FILE, resetOnCorruption = true) }
    single<DataStore<Preferences>>(named(DURABLE_PREFS_FILE)) { platformPrefsStore(DURABLE_PREFS_FILE, resetOnCorruption = false) }
    single<AppPrefs>(createdAtStart = true) {
        DataStoreAppPrefs(get(named(CACHE_PREFS_FILE)), get(named(DURABLE_PREFS_FILE)), get<SessionStore>().state, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    // 채팅 실시간 수신. 앞/뒤 상태는 첫 Activity 를 놓치지 않게 앱 시작부터 센다
    single<StateFlow<Boolean>>(named(APP_FOREGROUND), createdAtStart = true) { platformForeground() }
    single<ChatEvents> {
        val session = get<DefaultSessionStore>()
        DefaultChatEvents(session, { session.accessTokens()?.accessToken }, get(named(APP_FOREGROUND)), KtorChatSocket(session.client, baseUrl), HttpChatRoomProbe(session.client))
    }
}

internal const val APP_FOREGROUND = "appForeground"
internal const val RECEIPT_HTTP_CLIENT = "withdrawalReceiptHttpClient"

internal expect fun Scope.platformProtectedRecordStore(baseUrl: String): com.ssafy.e106.core.storage.ProtectedRecordStore

/** 토큰 저장소는 플랫폼마다. 서버 주소별로 따로 저장한다 */
internal expect fun Scope.platformTokenStore(baseUrl: String): TokenStore

/**
 * AppPrefs 파일(DataStore). 파일 하나에 인스턴스 하나여야 해서 coreModule 의 이름 붙은 single 안에서만 부른다.
 * [resetOnCorruption] = 깨지면 비우고 시작(캐시). false 면 깨진 파일을 그대로 두고 읽기에서 CorruptionException(서버에서 다시 받을 수 없는 기록)
 */
internal expect fun Scope.platformPrefsStore(fileName: String, resetOnCorruption: Boolean): DataStore<Preferences>

internal const val CACHE_PREFS_FILE = "app_prefs.preferences_pb"           // 기존 파일 이름 그대로(업데이트해도 캐시 유지)
internal const val DURABLE_PREFS_FILE = "app_prefs_durable.preferences_pb"

/** 소셜 SDK 래퍼는 플랫폼마다 */
internal expect fun Scope.platformSocialLogin(keys: SocialKeys): SocialLogin

/** 푸시 토큰 · 설치 ID 는 플랫폼마다 */
internal expect fun Scope.platformPushDevice(): PushDevice

/** 앱 화면이 하나라도 보이는지. 채팅 실시간 연결을 백그라운드에서 끊는 데 쓴다 */
internal expect fun Scope.platformForeground(): StateFlow<Boolean>

/** Android NUS 기반. iOS는 지원 준비 전 명시적으로 Unsupported를 반환한다. */
internal expect fun Scope.platformKeyringBle(): KeyringBle

/** Android 기본 경로 검증 상태. iOS는 구현 전까지 복원 신호를 제공하지 않는다. */
internal expect fun Scope.platformNetworkMonitor(): NetworkMonitor
