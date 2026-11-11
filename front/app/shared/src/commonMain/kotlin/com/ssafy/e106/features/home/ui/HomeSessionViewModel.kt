package com.ssafy.e106.features.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.profile.data.OptionalProfileRepository
import com.ssafy.e106.features.proposals.ui.decodeProposalImage
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.keyring.data.KeyringSettingsChanges
import com.ssafy.e106.features.keyring.data.OwnedKeyringRepository
import com.ssafy.e106.features.onboarding.data.OnboardingRepository
import com.ssafy.e106.features.profile.data.ProfileRepository
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeSessionState(val session: Session.SignedIn? = null, val home: HomeState = HomeState(),
    val account: AccountMenuState = AccountMenuState(), val guide: HelpGuideState? = null,
    val error: AppError? = null, val busy: Boolean = false)

/** 홈은 조회를 조합하고, 새 매칭 켜기 · 끄기만 직접 저장한다(그 밖의 변경은 설정/프로필 화면의 검증·버전 충돌 흐름). */
class HomeSessionViewModel(private val profiles: ProfileRepository, private val settings: MatchingSettingsRepository,
    private val agreements: OnboardingRepository, private val sessions: SessionStore, private val auth: AuthRepository,
    private val ownedKeyrings: OwnedKeyringRepository, private val changes: KeyringSettingsChanges = KeyringSettingsChanges(),
    // 사진 조회 연결이 빠진 채 기본 이미지로만 동작하는 구성을 막기 위해 필수로 받는다.
    private val photos: OptionalProfileRepository) : ViewModel() {
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
    fun home() = read { user, stamp ->
        mutable.update { it.copy(home = HomeState()) }
        matching = null
        saving?.join()   // 저장 중에 홈을 다시 열었으면 저장이 끝난 뒤의 값을 받는다
        when (val result = settings.get(user, SettingsKind.MATCHING)) {
            is ApiResult.Ok -> if (valid(stamp)) mutable.update { matching = result.value; it.copy(home = HomeState(HomeLoadState.Ready,
                matchingEnabled = (result.value.values as? ConflictValues.Matching)?.enabled,
                keyring = HomeKeyringState.Checking)) }
            is ApiResult.Fail -> {
                if (valid(stamp)) mutable.update { it.copy(home = HomeState(HomeLoadState.Failed), error = result.error) }
                return@read
            }
        }
        if (!valid(stamp)) return@read
        // 서버 목록으로는 등록 여부만 확인한다. 연결·매칭 준비 상태는 기기에서 직접 확인한다.
        val list = ownedKeyrings.list()
        val keyring = when (list) {
            is ApiResult.Ok -> if (list.value.isEmpty()) HomeKeyringState.Missing else HomeKeyringState.Registered
            is ApiResult.Fail -> HomeKeyringState.Unknown
        }
        // 계정 전환이나 화면 이탈 뒤 도착한 응답은 현재 화면에 반영하지 않는다.
        if (valid(stamp)) mutable.update { it.copy(home = it.home.copy(keyring = keyring, keyrings = (list as? ApiResult.Ok)?.value.orEmpty())) }
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
        mutable.update { it.copy(account = AccountMenuState()) }
        when (val result = profiles.get(user)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val mine = result.value.userId == user
                // 사진은 메뉴를 먼저 보여 준 뒤 받는다. 받는 동안 busy 로 두면 로그아웃이 무시되므로 여기서 푼다
                mutable.update { it.copy(busy = false, account = if (mine) AccountMenuState(AccountMenuLoad.Ready, result.value.nickname, result.value.hasProfilePhoto)
                    else AccountMenuState(AccountMenuLoad.Failed)) }
                // 실패(응답 · 디코드 오류)하면 기본 사진 그대로 둔다. 메뉴를 실패 화면으로 바꾸지 않는다
                if (mine && result.value.hasProfilePhoto) try {
                    (photos.photo(user) as? ApiResult.Ok)?.let { decodeProposalImage(it.value) }?.let { bitmap ->
                        if (valid(stamp)) mutable.update { it.copy(account = it.account.copy(photo = bitmap)) }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(account = AccountMenuState(AccountMenuLoad.Failed), error = result.error) }
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
