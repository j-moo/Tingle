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
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); mutable.value = HomeSessionState(session = it as? Session.SignedIn)
    } } }
    private fun valid(stamp: Long) = generation == stamp && mutable.value.session == sessions.state.value
    private fun read(block: suspend (Long, Long) -> Unit) {
        val user = mutable.value.session ?: return
        job?.cancel(); val stamp = ++generation
        mutable.update { it.copy(error = null, busy = true) }
        job = viewModelScope.launch {
            try { block(user.userId, stamp) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (valid(stamp)) mutable.update { it.copy(error = AppError.Unknown(IllegalStateException("Home unavailable")),
                home = HomeState(HomeLoadState.Failed), account = AccountMenuState(AccountMenuLoad.Failed)) } }
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
        val keepCurrent = refresh && mutable.value.home.load == HomeLoadState.Ready
        // 당겨서 갱신하는 동안 목록을 비우면 살아 있는 BLE 연결이 잠깐 빨간색으로 바뀐다.
        mutable.update { it.copy(home = if (keepCurrent) it.home.copy(refreshing = true) else HomeState(refreshing = refresh)) }
        if (!keepCurrent) matching = null
        try {
            saving?.join() // 저장 중에 홈을 다시 열었으면 저장이 끝난 뒤의 값을 받는다.
            val settingsResult = settings.get(user, SettingsKind.MATCHING)
            if (!valid(stamp)) return@read
            when (settingsResult) {
                is ApiResult.Ok -> {
                    matching = settingsResult.value
                    mutable.update { it.copy(home = it.home.copy(load = HomeLoadState.Ready,
                        matchingEnabled = (settingsResult.value.values as? ConflictValues.Matching)?.enabled,
                        keyring = if (keepCurrent) it.home.keyring else HomeKeyringState.Checking)) }
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
                    if (valid(stamp)) mutable.update { it.copy(home = it.home.copy(keyring = keyring,
                        keyrings = devices, keyringAliases = aliases)) }
                }
                is ApiResult.Fail -> if (valid(stamp)) mutable.update {
                    if (keepCurrent) it.copy(error = list.error) else it.copy(home = it.home.copy(keyring = HomeKeyringState.Unknown))
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
     * - 다른 곳에서 먼저 바꿨으면(412 등) 덮어쓰지 않고 실패로 알린 뒤 서버의 최신 값을 보여 준다.
     * - 화면 반영만 generation 으로 거른다. 저장 중에 홈을 다시 열면 [home] 이 저장이 끝날 때까지 기다렸다 조회한다(이전 값 고정 방지).
     */
    fun setMatching(enabled: Boolean) {
        val user = mutable.value.session ?: return
        val doc = matching ?: return
        val values = doc.values as? ConflictValues.Matching ?: return
        val etag = doc.etag?.takeIf(::validSettingsEtag) ?: return
        if (mutable.value.busy || mutable.value.home.savingMatching || values.enabled == enabled) return
        val stamp = generation
        val target = values.copy(enabled = enabled)
        fun sameAccount() = (sessions.state.value as? Session.SignedIn)?.userId == user.userId
        mutable.update { it.copy(home = it.home.copy(savingMatching = true, matchingSaveFailed = false)) }
        saving = viewModelScope.launch {
            val result = settings.save(user.userId, SettingsKind.MATCHING, target, etag)
            val saved = (result as? ApiResult.Ok)?.value?.takeIf { it.values == target && validSettingsEtag(it.etag) }
            // 실패 · 응답 유실이면 서버의 지금 값을 다시 받는다. 우리 저장이 실제로 됐는지, 다른 곳에서 바뀌었는지 여기서만 알 수 있다
            val latest = if (saved == null && sameAccount()) (settings.get(user.userId, SettingsKind.MATCHING) as? ApiResult.Ok)?.value else null
            val now = saved ?: latest?.takeIf { it.values == target && validSettingsEtag(it.etag) }
            val changed = now != null || (latest != null && (latest.values != doc.values || latest.settingsVersion != doc.settingsVersion))
            if (changed && sameAccount()) changes.saved(user.userId)
            if (!valid(stamp)) return@launch
            val shown = now ?: latest
            if (shown != null) matching = shown
            mutable.update { it.copy(home = it.home.copy(savingMatching = false, matchingSaveFailed = now == null,
                matchingEnabled = (shown?.values as? ConflictValues.Matching)?.enabled ?: it.home.matchingEnabled)) }
        }
    }
    fun account() = read { user, stamp ->
        // 같은 계정에서 이미 받은 닉네임은 그대로 보여 주고 뒤에서 새로 받는다(계정이 바뀌면 상태 전체가 초기화된다).
        // 다시 받기가 실패해도 보이던 닉네임을 실패 화면으로 바꾸지 않는다
        val shown = mutable.value.account.load == AccountMenuLoad.Ready
        if (!shown) mutable.update { it.copy(account = AccountMenuState()) }
        when (val result = profiles.get(user)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val mine = result.value.userId == user
                // 마이에는 사진을 두지 않는다(사진은 프로필 보기에서). 닉네임만 받는다
                mutable.update { it.copy(busy = false, account = if (mine) AccountMenuState(AccountMenuLoad.Ready, result.value.nickname)
                    else AccountMenuState(AccountMenuLoad.Failed)) }
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
    fun leave() {
        generation++; job?.cancel()
        mutable.update { HomeSessionState(session = it.session) }
    }
    fun logout() { if (!mutable.value.busy) viewModelScope.launch { auth.logout() } }
}

internal const val KEYRING_ALIAS_LIMIT = 20

// 캐시와 다른 파일에 저장해 앱 재실행 뒤에도 남긴다. 로그아웃·계정 전환의 공통 정리 정책은 따른다.
private fun aliasKey(deviceId: Long) = PrefKey("home.keyring.alias.$deviceId", String.serializer(), durable = true)
