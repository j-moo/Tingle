package com.ssafy.e106.core.auth

import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.network.SessionAuth
import com.ssafy.e106.core.network.call
import com.ssafy.e106.core.network.catchingNonCancel
import com.ssafy.e106.core.storage.StoredTokens
import com.ssafy.e106.core.storage.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/** GET users/{id}/onboarding 응답 중 쓰는 두 필드(B/user/dto/OnboardingDetailResponse.java) */
@Serializable
internal class OnboardingDetail(val nextStep: OnboardingStatus, val signupCompleted: Boolean)

/**
 * 세션 상태와 그 변화를 한 곳에서. HttpClient 의 Bearer · 재발급([SessionAuth])과 같은 객체라 세션 만료 처리가 여기 하나뿐이다.
 * [client] 는 만든 직후 coreModule 이 넣는다(서로를 참조한다). [restore] 로 시작한다.
 * 토큰 저장소 쓰기와 상태 전이는 [lock] 안에서만 한다. 재발급 응답 · 단계 조회 결과가 로그아웃 · 만료 · 다른 저장과 엇갈려 지운 토큰을 되살리거나 새 토큰을 덮지 않게.
 */
internal class DefaultSessionStore(
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) : SessionStore, SessionAuth {
    lateinit var client: HttpClient

    private val _state = MutableStateFlow<Session>(Session.Restoring)
    override val state: StateFlow<Session> = _state.asStateFlow()

    private val _events = Channel<SessionEvent>(Channel.BUFFERED)
    override val events: Flow<SessionEvent> = _events.receiveAsFlow()

    private val lock = Mutex()
    private var onboardingSent = 0L                      // 단계 조회 순번. 늦게 온 옛 조회가 더 최근 결과를 덮지 않게
    private var onboardingApplied = 0L
    private var announceStep = false
    @Volatile private var generation = 0L                          // 세션 세대. 로그인 · 로그아웃(만료 포함)마다 오른다. 재발급은 같은 세션이라 그대로                     // 403 을 받아 단계를 다시 읽는 중. 그동안 단계가 바뀌면 OnboardingRequired 1회

    private val onboardingLock = Mutex()
    private var onboarding: Deferred<ApiResult<Unit>>? = null

    /** 앱 시작 때 한 번. 오프라인 · 서버 오류면 리프레시 토큰이 살아 있는 동안 마지막 단계로 들어간다(기반_인터페이스.md §2) */
    fun restore() = scope.launch {
        flushPendingLogoutLater()                        // 복원과 따로(복원 Job 에 묶지 않는다). 이전 세션 정리가 첫 화면을 늦추지 않게
        val saved = tokenStore.load()
        if (saved == null) {
            _state.compareAndSet(Session.Restoring, Session.SignedOut)
            return@launch
        }
        when (val r = fetchAndApply(saved.userId)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Fail -> lock.withLock {
                when {
                    _state.value != Session.Restoring -> Unit   // 그 사이 expire() 등이 이미 정했다
                    saved.refreshTokenExpiresAt > now() -> _state.value = Session.SignedIn(saved.userId, saved.onboardingStatus, saved.signupCompleted)
                    else -> signOutLocked(announce = false)      // 리프레시도 만료. 처음부터 로그인 화면이라 안내는 없다
                }
            }
        }
    }

    override suspend fun refreshOnboarding(): ApiResult<Unit> {
        // 동시에 불려도 요청은 하나. 부른 화면이 사라져도 진행 중인 조회는 끝까지 간다(scope.async)
        val job = onboardingLock.withLock {
            onboarding?.takeIf { it.isActive } ?: scope.async { reloadOnboarding() }.also { onboarding = it }
        }
        return job.await()
    }

    private suspend fun reloadOnboarding(): ApiResult<Unit> {
        val userId = (_state.value as? Session.SignedIn)?.userId ?: return ApiResult.Fail(AppError.SessionExpired)
        return fetchAndApply(userId)
    }

    /** GET users/{id}/onboarding 후 반영. 보낼 때 순번을 받아, 그보다 나중에 보낸 조회가 이미 반영됐으면 이 응답은 버린다 */
    private suspend fun fetchAndApply(userId: Long): ApiResult<Unit> {
        val seq = lock.withLock { ++onboardingSent }
        return when (val r = client.call<OnboardingDetail> { get("users/$userId/onboarding") }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit).also { apply(userId, r.value, seq) }
            is ApiResult.Fail -> r
        }
    }

    /** 저장된 토큰이 아직 그 사용자일 때만 반영(그 사이 로그아웃 · 계정 전환이면 버림). 다음 실행의 오프라인 복원을 위해 저장도 한다 */
    private suspend fun apply(userId: Long, detail: OnboardingDetail, seq: Long) = lock.withLock {
        if (seq < onboardingApplied) return@withLock     // 더 나중에 보낸 조회가 이미 반영됨
        val saved = tokenStore.load()?.takeIf { it.userId == userId } ?: return@withLock
        val before = _state.value
        if (before != Session.Restoring && (before as? Session.SignedIn)?.userId != userId) return@withLock
        onboardingApplied = seq
        _state.value = Session.SignedIn(userId, detail.nextStep, detail.signupCompleted)
        catchingNonCancel { tokenStore.save(saved.copy(onboardingStatus = detail.nextStep, signupCompleted = detail.signupCompleted)) }   // 캐시라 실패해도 진행
        if (announceStep && before is Session.SignedIn && before.status != detail.nextStep) {
            announceStep = false
            _events.trySend(SessionEvent.OnboardingRequired(detail.nextStep))   // 잠금 안이라 기다리지 않는다(버퍼가 차 있으면 안내 하나는 버린다)
        }
    }

    override suspend fun accessTokens(): BearerTokens? = tokenStore.load()?.let { BearerTokens(it.accessToken, it.refreshToken) }

    override suspend fun saveRefreshed(res: TokenResponse, sentRefreshToken: String): BearerTokens? = lock.withLock {
        val cur = tokenStore.load()
        // 재발급을 보낸 뒤 로그아웃 · 만료 · 다른 로그인이 있었으면 이 응답은 버린다(지운 토큰을 되살리지 않는다)
        if (cur == null || cur.refreshToken != sentRefreshToken) return@withLock null
        val stored = res.toStored(signupCompleted = cur.signupCompleted)?.takeIf { it.userId == cur.userId }
            ?: return@withLock null.also { signOutLocked(announce = true) }   // sub 가 없거나 다른 사용자 — 쓸 수 없는 응답
        tokenStore.save(stored)                          // 회전: 새 refreshToken 으로 통째로 교체
        val s = _state.value
        if (s is Session.SignedIn && s.status != res.onboardingStatus) scope.launch { refreshOnboarding() }   // signupCompleted 는 이 응답에 없다
        BearerTokens(stored.accessToken, stored.refreshToken)
    }

    /** 세션을 바로 끝내고 안내한다(쓸 수 없는 재발급 응답 등). HTTP 401 은 [expireIfCurrent] 로 온다 */
    suspend fun expire() = lock.withLock { signOutLocked(announce = true) }

    /**
     * 로그인 응답 저장 → 단계 조회 → SignedIn. 단계 조회가 실패하면 로그인 응답의 단계로 들어간다(signupCompleted 는 모름 → false).
     * 단계 조회 중 401 이나 로그아웃으로 토큰이 바뀌었으면 null
     */
    suspend fun signIn(res: TokenResponse): Session.SignedIn? {
        val stored = res.toStored(signupCompleted = false) ?: return null   // sub 없는 토큰으로는 어떤 API 도 못 부른다
        val seq = lock.withLock {
            tokenStore.save(stored)
            generation++
            client.authProvider<BearerAuthProvider>()?.clearToken()   // 메모리에 남은 이전 계정 토큰 대신 새 토큰을 읽게
            announceStep = false
            ++onboardingSent
        }
        val detail = (client.call<OnboardingDetail> { get("users/${stored.userId}/onboarding") } as? ApiResult.Ok)?.value
        return lock.withLock {
            if (tokenStore.load()?.refreshToken != stored.refreshToken) return@withLock null
            val signedIn = Session.SignedIn(stored.userId, detail?.nextStep ?: res.onboardingStatus, detail?.signupCompleted ?: false)
            onboardingApplied = seq
            _state.value = signedIn
            if (detail != null) catchingNonCancel { tokenStore.save(stored.copy(onboardingStatus = detail.nextStep, signupCompleted = detail.signupCompleted)) }
            signedIn
        }
    }

    /** 지금 세션 세대. 로그아웃은 잠금을 기다리기 전에 이것부터 잡는다 — 취소 정리 때도 "로그아웃을 누른 그 세션"인지 비교할 수 있게 */
    val currentGeneration: Long get() = generation

    /** 로그아웃 시작: 지금 토큰(서버 로그아웃 요청용). 잠금을 기다리는 사이 [startedAt] 세대가 끝났으면(만료 · 다른 로그인) null — 할 일이 없다 */
    suspend fun beginSignOut(startedAt: Long): StoredTokens? = lock.withLock { if (generation == startedAt) tokenStore.load() else null }

    /**
     * 사용자가 직접 로그아웃: 토큰 · 메모리 토큰 삭제 → SignedOut. 안내(Expired)는 내지 않는다. 부른 쪽이 취소돼도 끝까지 한다.
     * [startedAt] 세대가 지금과 다르면(잠금 대기 · 로그아웃 응답 대기 사이 만료 · 다른 로그인) 새 세션은 보관도 삭제도 하지 않는다.
     * [unsent] 는 토큰을 읽기 전 · 읽는 중 취소 — 서버에 로그아웃을 못 보냈으니 지우기 전에 그 refreshToken 을 재전송 목록에 보관한다
     */
    suspend fun signOut(startedAt: Long, unsent: Boolean) = withContext(NonCancellable) {
        lock.withLock {
            if (startedAt != generation) return@withLock
            if (unsent) catchingNonCancel { tokenStore.load() }.getOrNull()?.let { keepPendingLogout(it.refreshToken) }
            signOutLocked(announce = false)
        }
    }

    override suspend fun expireIfCurrent(sentAccessToken: String) = lock.withLock {
        val cur = tokenStore.load()
        // 그 사이 다시 로그인했거나 재발급돼 다른 토큰이면 이 401 은 지난 토큰 것. 새 세션을 끝내지 않는다(그 토큰이 죽었으면 다음 요청이 알려준다)
        if (cur != null && cur.accessToken != sentAccessToken) return@withLock
        signOutLocked(announce = true)
    }

    /** 토큰 · 메모리 토큰을 지우고 SignedOut. 이미 SignedOut 이어도 지우기는 한다(남은 토큰 정리). 안내 이벤트는 처음 한 번만 */
    private suspend fun signOutLocked(announce: Boolean) {
        val before = _state.getAndUpdate { Session.SignedOut }
        generation++
        announceStep = false
        // 부른 쪽이 취소돼도(화면 전환) 끝까지 지운다. 안 그러면 SignedOut 인데 저장 토큰이 남는다
        withContext(NonCancellable) {
            catchingNonCancel { tokenStore.clear() }
            client.authProvider<BearerAuthProvider>()?.clearToken()   // 메모리에 남은 이전 토큰이 다음 요청에 나가지 않게
        }
        // 잠금을 잡은 채 기다리지 않는다. 버퍼(64)가 찰 만큼 아무도 안 받았으면 안내 하나는 버려도 된다
        if (announce && before != Session.SignedOut) _events.trySend(SessionEvent.Expired)
    }

    private val logoutLock = Mutex()

    /** 로그아웃 요청을 못 보냈을 때(연결 실패 · 5xx · 도중 취소) 보관한다. 서버 로그아웃은 멱등이라 두 번 가도 된다 */
    suspend fun keepPendingLogout(refreshToken: String) = withContext(NonCancellable) {
        catchingNonCancel { tokenStore.addPendingLogout(refreshToken) }
    }

    /**
     * 보관한 로그아웃을 다시 보낸다(앱 시작 · 로그인 때, 기다리지 않고). 서버가 받았거나(2xx) 그 토큰을 거절했으면(400 · 401) 그 항목만 빼고, 그 밖은 남겨 둔다.
     * 만료 판단은 서버에 맡긴다(기기 시계를 믿지 않는다 — 서버는 만료된 토큰의 로그아웃도 받는다). 잠금은 같은 항목을 두 번 보내지 않으려는 것
     */
    suspend fun flushPendingLogout() = logoutLock.withLock {
        for (token in catchingNonCancel { tokenStore.pendingLogouts() }.getOrDefault(emptyList())) {
            val r = client.call<Unit> { post("auth/logout") { setBody(LogoutRequest(token)) } }
            if (!r.shouldRetryLogout()) catchingNonCancel { tokenStore.removePendingLogout(token) }
        }
    }

    /** 마지막으로 띄운 재전송. 끝나기를 기다려야 하는 쪽(푸시 등록 S15P31E106-195, 테스트)이 join 한다 */
    var pendingLogoutFlush: Job? = null
        private set

    /** 로그인 화면을 기다리게 하지 않고 보낸다. 계정마다 서버 세션(family)이 따로라 로그인과 순서를 맞출 필요가 없다 */
    fun flushPendingLogoutLater() = scope.launch { flushPendingLogout() }.also { pendingLogoutFlush = it }

    override fun onboardingRequired() {
        scope.launch {
            lock.withLock {
                if (_state.value !is Session.SignedIn) return@launch
                announceStep = true
            }
            // 진행 중인 조회에 합치지 않고 새로 읽는다. 403 전에 보낸 조회는 바뀌기 전 단계를 돌려줄 수 있다
            reloadOnboarding()
            lock.withLock { announceStep = false }       // 단계가 그대로면 그 화면이 오류 문구로 보여준다(§4)
        }
    }
}
