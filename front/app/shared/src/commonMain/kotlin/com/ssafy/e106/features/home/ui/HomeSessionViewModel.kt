package com.ssafy.e106.features.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.storage.PrefKey
import com.ssafy.e106.core.storage.PrefRead
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.keyring.data.KeyringSettingsChanges
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.features.keyring.data.OwnedKeyringRepository
import com.ssafy.e106.features.onboarding.data.OnboardingRepository
import com.ssafy.e106.features.profile.data.ProfileRepository
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.serializer

data class HomeSessionState(val session: Session.SignedIn? = null, val home: HomeState = HomeState(),
    val account: AccountMenuState = AccountMenuState(), val guide: HelpGuideState? = null,
    val error: AppError? = null, val busy: Boolean = false)

/** 홈은 조회를 조합하고, 매칭 켜기·끄기와 이 휴대폰의 키링 별명을 저장한다. */
class HomeSessionViewModel(private val profiles: ProfileRepository, private val settings: MatchingSettingsRepository,
    private val agreements: OnboardingRepository, private val sessions: SessionStore, private val auth: AuthRepository,
    private val ownedKeyrings: OwnedKeyringRepository, private val changes: KeyringSettingsChanges = KeyringSettingsChanges(),
    private val prefs: AppPrefs) : ViewModel() {
    // 마지막으로 받은 매칭 설정(값 + ETag). 켜기 · 끄기 저장은 이 버전에 맞춰서만 보낸다
    private var matching: SettingsDocument? = null
    // 진행 중인 켜기 · 끄기 저장. 화면 이동(leave)과 상관없이 끝까지 가고, 홈 재조회는 이게 끝난 뒤에 한다
    private var saving: Job? = null
    private val mutable = MutableStateFlow(HomeSessionState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var nicknameCache: Job? = null
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); nicknameCache?.cancel()
        val session = it as? Session.SignedIn
        mutable.value = HomeSessionState(session = session)
        // 마이 닉네임은 세션이 정해질 때 미리 읽어 둔다. 마이에 들어갈 때 읽으면 한두 프레임 뼈대가 보인다.
        // 따로 읽어서 다음 세션 변화(로그아웃 · 계정 전환) 처리를 늦추지 않는다
        if (session != null) nicknameCache = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val cached = (prefs.read(NicknameKey, session.userId) as? PrefRead.Found)?.value ?: return@launch
            mutable.update { state -> if (state.session != session || state.account.load != AccountMenuLoad.Loading) state
                else state.copy(account = AccountMenuState(AccountMenuLoad.Ready, cached)) }
        }
    } } }
    private fun valid(stamp: Long) = generation == stamp && mutable.value.session == sessions.state.value
    private fun read(block: suspend (Long, Long) -> Unit) {
        val user = mutable.value.session ?: return
        job?.cancel(); val stamp = ++generation
        mutable.update { it.copy(error = null, busy = true) }
        job = viewModelScope.launch {
            try { block(user.userId, stamp) }
            catch (e: CancellationException) { throw e }
            // 받아 둔 홈 · 마이는 실패 화면으로 바꾸지 않는다(조용한 갱신 · 당겨서 새로고침 중 예외)
            catch (_: Exception) { if (valid(stamp)) mutable.update { it.copy(error = AppError.Unknown(IllegalStateException("Home unavailable")),
                home = if (it.home.load == HomeLoadState.Ready) it.home else HomeState(HomeLoadState.Failed),
                account = if (it.account.load == AccountMenuLoad.Ready) it.account else AccountMenuState(AccountMenuLoad.Failed)) } }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun home() = loadHome(refresh = false)

    fun refreshHome() {
        val current = mutable.value
        if (current.busy || current.home.savingMatching || current.session == null) return
        loadHome(refresh = true)
    }

    private fun loadHome(refresh: Boolean) = read { user, stamp ->
        // 받아 둔 홈이 있으면(탭 · 앱 복귀, 당겨서 갱신) 보이던 화면을 두고 뒤에서 다시 받는다. 뼈대는 처음 열 때만, 위쪽 로딩은 당겼을 때만.
        // 갱신하는 동안 목록을 비우면 살아 있는 BLE 연결이 잠깐 빨간색으로 바뀐다.
        val keepCurrent = mutable.value.home.load == HomeLoadState.Ready
        mutable.update { it.copy(home = if (keepCurrent) it.home.copy(refreshing = refresh) else HomeState(refreshing = refresh)) }
        if (!keepCurrent) matching = null
        try {
            saving?.join() // 저장 중에 홈을 다시 열었으면 저장이 끝난 뒤의 값을 받는다.
            val settingsResult = settings.get(user, SettingsKind.MATCHING)
            if (!valid(stamp)) return@read
            // 처음 여는 홈은 매칭 설정과 키링 목록을 다 받은 뒤 한 번에 보여 준다. 설정만 먼저 보여 주면
            // 키링이 없는 계정에 ON 원이 잠깐 보였다가 "키링을 연결해 주세요"로 바뀐다
            var settled: (HomeState) -> HomeState = { it }
            when (settingsResult) {
                is ApiResult.Ok -> {
                    matching = settingsResult.value
                    val enabled = (settingsResult.value.values as? ConflictValues.Matching)?.enabled
                    if (keepCurrent) mutable.update { it.copy(home = it.home.copy(load = HomeLoadState.Ready, matchingEnabled = enabled)) }
                    else settled = { it.copy(load = HomeLoadState.Ready, matchingEnabled = enabled) }
                }
                is ApiResult.Fail -> {
                    mutable.update { it.copy(home = if (keepCurrent) it.home else HomeState(HomeLoadState.Failed),
                        error = settingsResult.error) }
                    if (!keepCurrent) return@read
                }
            }
            // 서버 목록으로 등록 기기를 갱신한다. 현재 연결은 화면의 BLE 모델에서 확인한다.
            when (val list = ownedKeyrings.list()) {
                is ApiResult.Ok -> {
                    val devices = list.value
                    val aliases = readKeyringAliases(user, devices)
                    val keyring = if (devices.isEmpty()) HomeKeyringState.Missing else HomeKeyringState.Registered
                    if (valid(stamp)) mutable.update { it.copy(home = settled(it.home).copy(keyring = keyring,
                        keyrings = devices, keyringAliases = aliases)) }
                }
                is ApiResult.Fail -> if (valid(stamp)) mutable.update {
                    if (keepCurrent) it.copy(error = list.error) else it.copy(home = settled(it.home).copy(keyring = HomeKeyringState.Unknown))
                }
            }
        } finally {
            // 계정 전환과 화면 이탈 뒤 도착한 응답은 현재 화면에 반영하지 않는다.
            if (valid(stamp)) mutable.update { it.copy(home = it.home.copy(refreshing = false)) }
        }
    }

    /** 홈과 동기화 목록에서 이 계정의 휴대폰 별명을 같은 방식으로 읽는다. */
    internal suspend fun readKeyringAliases(user: Long, devices: List<OwnedKeyring>): Map<Long, String> =
        devices.mapNotNull { device ->
            (prefs.read(aliasKey(device.deviceId), user) as? PrefRead.Found)?.value?.let { device.deviceId to it }
        }.toMap()

    /** 별명은 계정과 등록 기기 번호로 구분한다. 서버나 블루투스로 보내지 않는다. */
    suspend fun saveKeyringAlias(deviceId: Long, alias: String): Boolean {
        val user = mutable.value.session?.userId ?: return false
        if (mutable.value.home.keyrings.none { it.deviceId == deviceId }) return false
        val value = alias.trim().takeIf { it.isNotEmpty() }
        if (value != null && value.length > KEYRING_ALIAS_LIMIT) return false
        val key = aliasKey(deviceId)
        prefs.set(key, value, user)
        // 공통 저장소는 쓰기 오류를 숨기므로 다시 읽어서 화면에 성공을 표시할 수 있는지 확인한다.
        val saved = when (val result = prefs.read(key, user)) {
            is PrefRead.Found -> result.value == value
            PrefRead.Missing -> value == null
            else -> false
        }
        if (!saved || (sessions.state.value as? Session.SignedIn)?.userId != user) return false
        mutable.update { state ->
            if (state.session?.userId != user || state.home.keyrings.none { it.deviceId == deviceId }) state
            else state.copy(home = state.home.copy(keyringAliases = if (value == null) state.home.keyringAliases - deviceId
                else state.home.keyringAliases + (deviceId to value)))
        }
        return true
    }
    /**
     * 홈 큰 원: 새 매칭 켜기 · 끄기를 바로 저장한다. 사진 조건은 받은 값 그대로 두고 켜짐만 바꾼다.
     * - 서버 저장이 확정되거나(응답) 응답을 잃은 뒤 다시 받아 보니 설정이 바뀌어 있으면, 화면을 떠났어도 같은 계정인 한 키링 동기화에 알린다.
     *   안 알리면 이미 연결 · 동기화를 마친 키링이 예전 설정으로 계속 동작한다.
     * - 원은 누르는 즉시 바뀐 값으로 보여 준다. 실패하면 서버의 최신 값(412 = 다른 곳에서 먼저 바꿈 포함)을, 그것도 못 받으면 누르기 전 값을 보여 준다.
     * - 화면 반영은 같은 계정이면 화면을 떠났어도 한다(stamp 와 상관없이). 안 그러면 탭을 옮겼다 온 뒤 저장 중 표시가 남아 원이 안 눌린다.
     *   저장 중에 홈을 다시 열면 [home] 이 저장이 끝날 때까지 기다렸다 조회한다(이전 값 고정 방지).
     */
    fun setMatching(enabled: Boolean) {
        val user = mutable.value.session ?: return
        val doc = matching ?: return
        val values = doc.values as? ConflictValues.Matching ?: return
        val etag = doc.etag?.takeIf(::validSettingsEtag) ?: return
        if (mutable.value.busy || mutable.value.home.savingMatching || values.enabled == enabled) return
        val target = values.copy(enabled = enabled)
        fun sameAccount() = (sessions.state.value as? Session.SignedIn)?.userId == user.userId
        mutable.update { it.copy(home = it.home.copy(savingMatching = true, matchingSaveFailed = false, matchingEnabled = enabled)) }
        saving = viewModelScope.launch {
            val result = settings.save(user.userId, SettingsKind.MATCHING, target, etag)
            val saved = (result as? ApiResult.Ok)?.value?.takeIf { it.values == target && validSettingsEtag(it.etag) }
            // 실패 · 응답 유실이면 서버의 지금 값을 다시 받는다. 우리 저장이 실제로 됐는지, 다른 곳에서 바뀌었는지 여기서만 알 수 있다
            val latest = if (saved == null && sameAccount()) (settings.get(user.userId, SettingsKind.MATCHING) as? ApiResult.Ok)?.value else null
            val now = saved ?: latest?.takeIf { it.values == target && validSettingsEtag(it.etag) }
            val changed = now != null || (latest != null && (latest.values != doc.values || latest.settingsVersion != doc.settingsVersion))
            if (changed && sameAccount()) changes.saved(user.userId)
            if (!sameAccount() || mutable.value.session?.userId != user.userId) return@launch
            val shown = now ?: latest
            if (shown != null) matching = shown
            mutable.update { it.copy(home = it.home.copy(savingMatching = false, matchingSaveFailed = now == null,
                matchingEnabled = (shown?.values as? ConflictValues.Matching)?.enabled ?: values.enabled)) }
        }
    }
    fun account() = read { user, stamp ->
        // 같은 계정에서 이미 받은 닉네임은 그대로 보여 주고 뒤에서 새로 받는다(계정이 바뀌면 상태 전체가 초기화된다).
        // 다시 받기가 실패해도 보이던 닉네임을 실패 화면으로 바꾸지 않는다
        val shown = mutable.value.account.load == AccountMenuLoad.Ready
        if (!shown) mutable.update { it.copy(account = AccountMenuState()) }
        // 공용 실패 처리(홈까지 실패로 바꿈) 대신 여기서 받아 보이던 닉네임을 지킨다
        val result = try { profiles.get(user) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ApiResult.Fail(AppError.Unknown(IllegalStateException("Account unavailable"))) }
        when (result) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val mine = result.value.userId == user
                // 마이에는 사진을 두지 않는다(사진은 프로필 보기에서). 닉네임만 받는다
                mutable.update { it.copy(busy = false, account = if (mine) AccountMenuState(AccountMenuLoad.Ready, result.value.nickname)
                    else AccountMenuState(AccountMenuLoad.Failed)) }
                // 앱을 새로 켠 직후에도 바로 보이게 저장한다
                if (mine) prefs.set(NicknameKey, result.value.nickname, user)
            }
            is ApiResult.Fail -> if (valid(stamp) && !shown) mutable.update { it.copy(account = AccountMenuState(AccountMenuLoad.Failed), error = result.error) }
        }
    }
    fun guide(kind: HelpGuideKind) = read { _, stamp ->
        mutable.update { it.copy(guide = HelpGuideState(kind, HelpGuideStage.Loading)) }
        // 수집·이용 동의(PRIVACY)를 개인정보처리방침의 URL로 대신 쓰지 않는다.
        if (kind != HelpGuideKind.Terms) {
            mutable.update { it.copy(guide = HelpGuideState(kind)) }; return@read
        }
        when (val result = agreements.agreements()) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val item = result.value.agreements.firstOrNull { it.type == "TERMS" }
                mutable.update { it.copy(guide = HelpGuideState(kind, if (item?.reviewUrl != null) HelpGuideStage.Ready else HelpGuideStage.Unavailable,
                    destinationUrl = item?.reviewUrl, documentTitle = item?.title, version = item?.version)) }
            }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(guide = HelpGuideState(kind, HelpGuideStage.LoadFailed), error = result.error) }
        }
    }
    fun guideOpenFailed() { mutable.update { it.copy(guide = it.guide?.copy(stage = HelpGuideStage.OpenFailed)) } }
    /**
     * 진행 중인 조회만 멈추고 받아 둔 홈 · 마이는 남긴다(돌아오면 바로 보이고 뒤에서 다시 받는다).
     * 멈춘 조회의 정리는 valid(stamp) 일 때만 돌아 건너뛰어지므로 진행 표시는 여기서 내린다. 안 내리면 새로고침과 원이 막힌다
     */
    fun leave() {
        generation++; job?.cancel()
        mutable.update { it.copy(busy = false, home = it.home.copy(refreshing = false), guide = null, error = null) }
    }
    fun logout() { if (!mutable.value.busy) viewModelScope.launch { auth.logout() } }
}

internal const val KEYRING_ALIAS_LIMIT = 20

// 마이 닉네임 캐시. 비-durable 이라 로그아웃 · 계정 전환 때 지워진다
private val NicknameKey = PrefKey("account.nickname", String.serializer())

// 캐시와 다른 파일에 저장해 앱 재실행 뒤에도 남긴다. 로그아웃·계정 전환의 공통 정리 정책은 따른다.
private fun aliasKey(deviceId: Long) = PrefKey("home.keyring.alias.$deviceId", String.serializer(), durable = true)
