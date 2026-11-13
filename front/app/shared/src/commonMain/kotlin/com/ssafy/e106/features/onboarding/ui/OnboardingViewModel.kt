package com.ssafy.e106.features.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OnboardingState(
    val session: Session.SignedIn? = null,
    val busy: Boolean = false,
    val agreements: List<AgreementItem> = emptyList(),
    val checked: Set<String> = emptySet(),
    val submitted: Boolean = false,
    val error: AppError? = null,
    val purchaseUrl: String? = null,
    val allowUnpublishedAgreements: Boolean = false,
) {
    val requiredAgreements get() = agreements.filter(::isRequired)
    /** 가입 전에만 시트에 함께 보여 주는 선택 동의(종교). 체크하지 않아도 가입할 수 있다. 전문이 없어 체크할 수 없으면 줄을 두지 않는다(프로필에서 다시 받음) */
    val optionalAgreements get() = agreements.filter { session?.signupCompleted == false && it.type in SignupOptionalConsents && !isRequired(it) &&
        (it.canReview || allowUnpublishedAgreements) }
    fun canCheck(item: AgreementItem) = (isRequired(item) || item in optionalAgreements) && (item.canReview || allowUnpublishedAgreements)
    // 가입 전에만 사진 · 자기소개 동의를 필수로 묶는다. 약관 개정으로 다시 동의하는 기존 회원은 계정 메뉴에서 따로 관리하므로 건드리지 않는다.
    // 문서가 없어 체크할 수 없는 항목은 묶지 않는다(묶으면 가입이 막힌다). 그때는 프로필 화면에서 동의 칸을 보여 받는다
    private fun isRequired(item: AgreementItem) = item.required ||
        (session?.signupCompleted == false && item.type in SignupRequiredConsents && (item.canReview || allowUnpublishedAgreements))
    val canSubmit get() = !busy && !submitted && requiredAgreements.isNotEmpty() &&
        requiredAgreements.all { canCheck(it) && it.type in checked }
}

/** 프로필 사진 · 자기소개 동의는 서버에선 선택 항목이지만 가입 때 약관과 함께 필수로 받는다(프로필 화면엔 동의 칸이 없다) */
internal val SignupRequiredConsents = setOf("PROFILE_PHOTO", "PROFILE_INTRODUCTION")
/** 종교는 민감정보라 가입 시트에서 따로, 선택으로 받는다. 거부해도 가입되고 상대에게는 "종교 정보 비공개"로 보인다(프로필에서 나중에 동의 가능) */
internal val SignupOptionalConsents = setOf("PROFILE_RELIGION")
/** 프로필 동의 전문은 랜딩 정적 페이지(front/web/public). 서버가 주소를 안 줘도 운영 · 개발이 같게 보이도록 앱이 채운다 */
// 전문이 없으면 동의 칸이 잠겨(시트 · 프로필 관리 모두) 고를 수 없으므로 서버 주소가 없을 때 랜딩 페이지로 채운다
private val ProfileDocumentPaths = mapOf("PROFILE_PHOTO" to "profile-photo/", "PROFILE_INTRODUCTION" to "profile-introduction/",
    "PROFILE_RELIGION" to "profile-religion/")
internal fun AgreementItem.withProfileDocument(siteBase: String?): AgreementItem {
    val path = ProfileDocumentPaths[type] ?: return this
    return if (reviewUrl != null || siteBase == null) this else copy(contentUrl = siteBase + path)
}
internal fun List<AgreementItem>.withProfileDocuments(siteBase: String?) = map { it.withProfileDocument(siteBase) }
/** 서버 제목은 "(선택) 프로필 사진 …"이라 앞의 "(선택)"을 떼고 시트가 뒤에 (필수)/(선택)을 붙인다 */
internal val AgreementItem.signupTitle get() = if (type in SignupRequiredConsents || type in SignupOptionalConsents) title.removePrefix("(선택)").trim() else title

/** Activity survives rotation; session changes cancel work and erase the previous account's state. */
class OnboardingViewModel(
    private val repository: OnboardingRepository,
    private val sessions: SessionStore,
    private val auth: AuthRepository,
    private val allowUnpublishedAgreements: Boolean = false,
    private val siteBase: String? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingState(allowUnpublishedAgreements = allowUnpublishedAgreements))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { session ->
                work?.cancel()
                generation++
                mutable.value = OnboardingState(session = session as? Session.SignedIn,
                    allowUnpublishedAgreements = allowUnpublishedAgreements)
                if (session is Session.SignedIn && !session.isReady) load()
            }
        }
    }

    private var logoutJob: Job? = null
    private fun launchWork(block: suspend (Int) -> Unit) {
        if (logoutJob?.isActive == true || mutable.value.busy || mutable.value.session == null || mutable.value.session != sessions.state.value) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { block(stamp) }
            finally { if (stamp == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.session == sessions.state.value

    fun load() {
        val session = mutable.value.session ?: return
        if (mutable.value.submitted) { refresh(); return }
        launchWork { stamp ->
            when (session.status) {
                OnboardingStatus.AGREEMENT_REQUIRED -> when (val result = repository.agreements()) {
                    is ApiResult.Ok -> if (valid(stamp)) mutable.update { it.copy(agreements = result.value.agreements.withProfileDocuments(siteBase), checked = emptySet()) }
                    is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
                }
                OnboardingStatus.KEYRING_REQUIRED -> when (val result = repository.purchaseInfo()) {
                    is ApiResult.Ok -> if (valid(stamp)) mutable.update {
                        it.copy(purchaseUrl = result.value.purchaseUrl?.takeIf(::isPublishedHttpsUrl))
                    }
                    is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
                }
                else -> Unit
            }
        }
    }
    fun check(type: String, checked: Boolean) {
        val s = mutable.value
        if (s.busy || s.submitted || s.session != sessions.state.value || s.agreements.none { it.type == type && s.canCheck(it) }) return
        mutable.update { it.copy(checked = if (checked) it.checked + type else it.checked - type) }
    }
    fun submit() {
        val s = mutable.value
        val session = s.session ?: return
        if (!s.canSubmit || session != sessions.state.value) return
        launchWork { stamp ->
            // Omission preserves optional consent. Sending false would withdraw it and delete profile data.
            // 선택 동의는 체크한 것만 보낸다. 안 보내면 동의하지 않은 상태로 남는다(false 를 보내면 철회 · 삭제가 된다)
            val answers = (s.requiredAgreements + s.optionalAgreements.filter { it.type in s.checked }).map { AgreementAnswer(it.type, it.version, true) }
            when (val result = repository.agree(session.userId, answers)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    mutable.update { it.copy(submitted = true) }
                    refreshInside(stamp)
                }
                is ApiResult.Fail -> if (valid(stamp)) {
                    if ((result.error as? AppError.Server)?.code == "AGREEMENT_VERSION_OUTDATED") {
                        // Clear old checks immediately; they do not express consent to the new version.
                        mutable.update { it.copy(agreements = emptyList(), checked = emptySet()) }
                        val latest = repository.agreements()
                        if (valid(stamp) && latest is ApiResult.Ok) mutable.update { it.copy(agreements = latest.value.agreements.withProfileDocuments(siteBase)) }
                    }
                    if (valid(stamp)) mutable.update { it.copy(error = result.error) }
                }
            }
        }
    }
    private suspend fun refreshInside(stamp: Int) {
        when (val result = sessions.refreshOnboarding()) {
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
            is ApiResult.Ok -> Unit // Only SessionStore may advance the route.
        }
    }
    fun refresh() = launchWork { refreshInside(it) }
    // 로딩 중에도 나갈 수 있어야 한다(키링 단계는 들어오자마자 불러오기라 권한 시트 ✕가 무시됐다). 다른 계정으로 바뀐 뒤의 오래된 요청은 막는다
    fun logout() {
        val session = mutable.value.session ?: return
        if (session != sessions.state.value || logoutJob?.isActive == true) return   // 연타해도 서버 로그아웃은 한 번
        work?.cancel()
        logoutJob = viewModelScope.launch { auth.logout() }
    }
}
