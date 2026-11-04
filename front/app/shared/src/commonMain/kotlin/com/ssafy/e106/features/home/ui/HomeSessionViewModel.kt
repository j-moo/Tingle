package com.ssafy.e106.features.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.data.OnboardingRepository
import com.ssafy.e106.features.profile.data.ProfileRepository
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.ConflictValues
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeSessionState(val session: Session.SignedIn? = null, val home: HomeState = HomeState(),
    val account: AccountMenuState = AccountMenuState(), val guide: HelpGuideState? = null,
    val error: AppError? = null, val busy: Boolean = false)

/** 홈에서는 조회만 조합한다. 변경은 기존 설정/프로필 화면의 검증·버전 충돌 흐름으로 보낸다. */
class HomeSessionViewModel(private val profiles: ProfileRepository, private val settings: MatchingSettingsRepository,
    private val agreements: OnboardingRepository, private val sessions: SessionStore, private val auth: AuthRepository) : ViewModel() {
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
        when (val result = settings.get(user, SettingsKind.MATCHING)) {
            is ApiResult.Ok -> if (valid(stamp)) mutable.update { it.copy(home = HomeState(HomeLoadState.Ready,
                matchingEnabled = (result.value.values as? ConflictValues.Matching)?.enabled)) }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(home = HomeState(HomeLoadState.Failed), error = result.error) }
        }
        // 목록에는 전체 건수가 없고 BLE 상태도 아직 없으므로 null/Unknown을 그대로 둔다.
    }
    fun account() = read { user, stamp ->
        mutable.update { it.copy(account = AccountMenuState()) }
        when (val result = profiles.get(user)) {
            is ApiResult.Ok -> if (valid(stamp)) mutable.update { it.copy(account =
                if (result.value.userId == user) AccountMenuState(AccountMenuLoad.Ready, result.value.nickname)
                else AccountMenuState(AccountMenuLoad.Failed)) }
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
