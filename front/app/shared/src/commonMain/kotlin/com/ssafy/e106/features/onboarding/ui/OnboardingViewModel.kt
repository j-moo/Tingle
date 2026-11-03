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
    val requiredAgreements get() = agreements.filter { it.required }
    fun canCheck(item: AgreementItem) = item.required && (item.canReview || allowUnpublishedAgreements)
    val canSubmit get() = !busy && !submitted && requiredAgreements.isNotEmpty() &&
        requiredAgreements.all { canCheck(it) && it.type in checked }
}

/** Activity survives rotation; session changes cancel work and erase the previous account's state. */
class OnboardingViewModel(
    private val repository: OnboardingRepository,
    private val sessions: SessionStore,
    private val auth: AuthRepository,
    private val allowUnpublishedAgreements: Boolean = false,
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

    private fun launchWork(block: suspend (Int) -> Unit) {
        if (mutable.value.busy || mutable.value.session == null || mutable.value.session != sessions.state.value) return
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
                    is ApiResult.Ok -> if (valid(stamp)) mutable.update { it.copy(agreements = result.value.agreements, checked = emptySet()) }
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
            when (val result = repository.agree(session.userId, s.requiredAgreements.map { AgreementAnswer(it.type, it.version, true) })) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    mutable.update { it.copy(submitted = true) }
                    refreshInside(stamp)
                }
                is ApiResult.Fail -> if (valid(stamp)) {
                    if ((result.error as? AppError.Server)?.code == "AGREEMENT_VERSION_OUTDATED") {
                        // Clear old checks immediately; they do not express consent to the new version.
                        mutable.update { it.copy(agreements = emptyList(), checked = emptySet()) }
                        val latest = repository.agreements()
                        if (valid(stamp) && latest is ApiResult.Ok) mutable.update { it.copy(agreements = latest.value.agreements) }
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
    fun logout() = launchWork { auth.logout() }
}
