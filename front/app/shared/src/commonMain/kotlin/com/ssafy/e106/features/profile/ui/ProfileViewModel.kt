package com.ssafy.e106.features.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.DevelopmentProfileAccess
import com.ssafy.e106.features.profile.normalizeProfileNickname
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class ProfilePage { Basics, Lifestyle, Optional, Confirmation }
enum class ProfileValidation { Nickname, BirthDate, Duplicate, Missing }
data class ProfileFlowState(
    val session: Session.SignedIn? = null,
    val loaded: Boolean = false, val busy: Boolean = false, val completed: Boolean = false,
    val page: ProfilePage = ProfilePage.Basics,
    val basics: ProfileBasicsState = ProfileBasicsState(),
    val lifestyle: ProfileLifestyleState = ProfileLifestyleState(),
    val saved: ProfileResponse? = null,
    val error: AppError? = null,
    val validation: ProfileValidation? = null,
    val underAgeAccountClosed: Boolean = false,
    val underAgeMessage: String? = null,
    val basicsConfirmation: ProfileBasicsState? = null,
)

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val sessions: SessionStore,
    private val auth: AuthRepository,
    private val access: DevelopmentProfileAccess = DevelopmentProfileAccess(),
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileFlowState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { s ->
                work?.cancel(); generation++
                mutable.value = ProfileFlowState(session = s as? Session.SignedIn)
                if (s is Session.SignedIn && access.canEditProfile(s)) load()
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.session == sessions.state.value
    private fun launchWork(block: suspend (Long, Int) -> Unit) {
        val s = mutable.value
        val session = s.session ?: return
        if (s.busy || s.underAgeAccountClosed || session != sessions.state.value || !access.canEditProfile(session)) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null, validation = null) }
        work = viewModelScope.launch {
            try { block(session.userId, stamp) }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun accept(profile: ProfileResponse) {
        mutable.update { s -> s.copy(loaded = true, saved = profile, completed = profile.completed,
            basics = ProfileBasicsState(nickname = profile.nickname.orEmpty(), birthDateDigits = profile.birthDate.orEmpty().replace("-", ""),
                gender = ProfileGender.entries.find { it.code() == profile.gender }),
            lifestyle = ProfileLifestyleState(smoking = ProfileSmoking.entries.find { it.code() == profile.smokingStatus },
                drinking = ProfileDrinking.entries.find { it.code() == profile.drinkingFrequency })) }
    }
    fun load() = launchWork { userId, stamp ->
        when (val result = repository.get(userId)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                accept(result.value)
                if (result.value.completed) refreshInside(stamp)
            }
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
        }
    }
    private fun edit(block: (ProfileFlowState) -> ProfileFlowState) {
        val s = mutable.value
        if (!s.loaded || s.busy || s.completed || s.underAgeAccountClosed || s.session != sessions.state.value) return
        mutable.value = block(s).copy(error = null, validation = null, basicsConfirmation = null)
    }
    fun nickname(value: String) = edit { it.copy(basics = it.basics.copy(nickname = value)) }
    fun birthDate(value: String) = edit { it.copy(basics = it.basics.copy(birthDateDigits = value.filter { c -> c in '0'..'9' }.take(8))) }
    fun gender(value: ProfileGender) = edit { it.copy(basics = it.basics.copy(gender = value)) }
    fun smoking(value: ProfileSmoking) = edit { it.copy(lifestyle = it.lifestyle.copy(smoking = value)) }
    fun drinking(value: ProfileDrinking) = edit { it.copy(lifestyle = it.lifestyle.copy(drinking = value)) }
    fun page(value: ProfilePage) = edit { s ->
        // Internal page navigation never completes server onboarding.
        if (value == ProfilePage.Confirmation && !hasRequiredSaved(s.saved)) s else s.copy(page = value)
    }
    fun optionalFinished(profile: ProfileResponse) = edit { s ->
        if (s.page != ProfilePage.Optional || profile.userId != s.session?.userId || !hasRequiredSaved(profile)) s
        else s.copy(saved = profile, page = ProfilePage.Confirmation)
    }
    fun saveBasics() = saveBasics(confirmed = false)
    fun cancelBasicsConfirmation() { mutable.update { it.copy(basicsConfirmation = null) } }
    fun confirmBasics() {
        val s = mutable.value
        if (s.basicsConfirmation == null || s.basicsConfirmation != s.basics || s.session != sessions.state.value) return
        mutable.update { it.copy(basicsConfirmation = null) }
        saveBasics(confirmed = true)
    }
    private fun saveBasics(confirmed: Boolean) {
        if (!mutable.value.loaded || mutable.value.busy || mutable.value.completed) return
        val b = mutable.value.basics
        val validation = when {
            !validNickname(b.nickname) -> ProfileValidation.Nickname
            !validBirthDate(b.birthDateDigits) -> ProfileValidation.BirthDate
            b.gender == null -> ProfileValidation.Missing
            else -> null
        }
        if (validation != null) { mutable.update { it.copy(validation = validation) }; return }
        // 한국 날짜 기준 나이 판정은 서버 계약을 따른다. 탈퇴를 일으킬 수 있는
        // 최초 입력/정정 오타는 서버 전송 전에 실제 입력값을 다시 확인하도록 한다.
        if (!confirmed) { mutable.update { it.copy(basicsConfirmation = b) }; return }
        launchWork { userId, stamp ->
            when (val result = repository.nickname(normalizeProfileNickname(b.nickname))) {
                is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
                is ApiResult.Ok -> if (valid(stamp)) {
                    if (!result.value.available) mutable.update { it.copy(validation = if (result.value.reason == "INVALID_FORMAT") ProfileValidation.Nickname else ProfileValidation.Duplicate) }
                    else save(userId, stamp, buildJsonObject {
                        put("nickname", result.value.nickname)
                        put("birthDate", isoBirthDate(b.birthDateDigits))
                        put("gender", b.gender!!.code())
                    }, ProfilePage.Lifestyle)
                }
            }
        }
    }
    fun saveLifestyle() {
        if (!mutable.value.loaded || mutable.value.busy || mutable.value.completed) return
        val life = mutable.value.lifestyle
        if (!life.canContinue) return
        launchWork { userId, stamp -> save(userId, stamp, buildJsonObject {
            put("smokingStatus", life.smoking!!.code()); put("drinkingFrequency", life.drinking!!.code())
        }, ProfilePage.Optional) }
    }
    private suspend fun save(userId: Long, stamp: Int, fields: JsonObject, next: ProfilePage) {
        when (val result = repository.save(userId, fields)) {
            is ApiResult.Ok -> if (valid(stamp)) { accept(result.value); mutable.update { it.copy(page = next) } }
            is ApiResult.Fail -> if (valid(stamp)) {
                val error = result.error
                if (error is AppError.Server && error.status == 403 && error.code == "UNDER_AGE") {
                    // 서버에서 이미 탈퇴 처리됨. 초안을 비우고 다시 저장할 수 없게 한다.
                    // UNDER_AGE 안내는 서버 계약의 문구를 우선 보관하고 빈 값일 때만 UI 기본 문구를 쓴다.
                    mutable.value = ProfileFlowState(session = mutable.value.session, underAgeAccountClosed = true,
                        underAgeMessage = error.serverMessage.takeIf { it.isNotBlank() })
                } else mutable.update { it.copy(error = error) }
            }
        }
    }
    fun returnToLogin() {
        val s = mutable.value
        if (!s.underAgeAccountClosed || s.busy || s.session == null || s.session != sessions.state.value) return
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        work = viewModelScope.launch {
            // 기존 기반이 토큰/소셜 세션을 정리하고 SignedOut으로 바꾼다.
            // 실제 화면 전환은 앱의 세션 분기가 담당한다.
            try { auth.logout() }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false) } }
        }
    }
    fun complete() {
        val s = mutable.value
        if (s.completed) { refresh(); return }
        if (s.page != ProfilePage.Confirmation || !hasRequiredSaved(s.saved)) return
        launchWork { userId, stamp ->
            when (val result = repository.complete(userId)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    mutable.update { it.copy(completed = true) }
                    refreshInside(stamp)
                }
                is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
            }
        }
    }
    private suspend fun refreshInside(stamp: Int) {
        val result = sessions.refreshOnboarding()
        if (valid(stamp) && result is ApiResult.Fail) mutable.update { it.copy(error = result.error) }
    }
    fun refresh() = launchWork { _, stamp -> refreshInside(stamp) }
}

internal fun hasRequiredSaved(p: ProfileResponse?) = p != null && !p.nickname.isNullOrBlank() &&
    p.birthDate != null && ProfileGender.entries.any { it.code() == p.gender } &&
    ProfileSmoking.entries.any { it.code() == p.smokingStatus } && ProfileDrinking.entries.any { it.code() == p.drinkingFrequency }
