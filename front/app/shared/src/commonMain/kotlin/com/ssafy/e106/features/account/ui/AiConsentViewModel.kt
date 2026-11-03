package com.ssafy.e106.features.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.chatCode
import com.ssafy.e106.features.chat.data.chatInvalid
import com.ssafy.e106.features.onboarding.data.OnboardingRepository
import com.ssafy.e106.features.safety.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConnectedAiConsent(val owner: Session.SignedIn? = null, val ui: AiConsentState = AiConsentState(), val error: AppError? = null)

/** 동의 변경 응답이 유실되면 조회로 확인한다. 사용자의 재확인 없이 PUT을 반복하지 않는다. */
class AiConsentViewModel(private val api: ChatExtrasRepository, private val agreements: OnboardingRepository,
    private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedAiConsent())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); mutable.value = ConnectedAiConsent(owner = it as? Session.SignedIn)
    } } }
    private fun valid(stamp: Long, owner: Session.SignedIn) = generation == stamp && sessions.state.value == owner
    fun load() {
        val owner = mutable.value.owner ?: return
        if(mutable.value.ui.busy) return
        val stamp = ++generation; job?.cancel(); val scope = AiConsentScope(owner.userId, stamp)
        mutable.update { it.copy(ui = AiConsentState().begin(scope), error = null) }
        job = viewModelScope.launch {
            try {
                when(val result = api.consent(owner.userId)) {
                    is ApiResult.Fail -> if(valid(stamp, owner)) fail(scope, result.error)
                    is ApiResult.Ok -> {
                        val response = agreements.agreements()
                        if(!valid(stamp, owner)) return@launch
                        // 고지 조회 실패를 '게시된 고지 없음'으로 바꾸지 않는다. 기존 오류 화면에서 다시 조회할 수 있게 한다.
                        // 재조회는 동의를 저장하지 않으며, 성공 후에도 전문 확인·체크를 새로 거쳐야 한다.
                        val documents = when (response) {
                            is ApiResult.Fail -> { fail(scope, response.error); return@launch }
                            is ApiResult.Ok -> response.value.agreements
                        }
                        val document = documents.singleOrNull { it.type == "AI_RISK_DETECTION" }
                        val notice = document?.reviewUrl?.let { AiConsentNotice(document.version, "", it) }
                        mutable.update { it.copy(ui = it.ui.loaded(scope, result.value.snapshot(), notice)) }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) fail(scope, chatInvalid()) }
        }
    }
    fun check(value: Boolean) { mutable.update { it.copy(ui = it.ui.check(value)) } }
    fun reviewed() { mutable.update { it.copy(ui = it.ui.reviewed()) } }
    fun withdraw() { mutable.update { it.copy(ui = it.ui.requestWithdrawal()) } }
    fun cancel() { mutable.update { it.copy(ui = it.ui.cancelWithdrawal()) } }
    fun save(enabled: Boolean) {
        val owner = mutable.value.owner ?: return
        val next = if(enabled) mutable.value.ui.submitGrant() else mutable.value.ui.confirmWithdrawal()
        if(!next.busy || mutable.value.ui.busy) return
        val scope = next.scope ?: return; val stamp = generation
        val version = next.snapshot?.noticeVersion ?: return
        mutable.update { it.copy(ui = next, error = null) }
        job = viewModelScope.launch {
            try {
                val result = api.consent(owner.userId, RiskConsentBody(enabled, version))
                if(!valid(stamp, owner)) return@launch
                when(result) {
                    is ApiResult.Ok -> mutable.update { it.copy(ui = it.ui.saved(scope, result.value.snapshot())) }
                    is ApiResult.Fail -> fail(scope, result.error)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) fail(scope, chatInvalid()) }
        }
    }
    private fun fail(scope: AiConsentScope, error: AppError) { mutable.update { it.copy(error = error, ui = it.ui.failed(scope, error.chatCode())) } }
    private fun RiskConsentRecord.snapshot() = AiConsentSnapshot(enabled, noticeVersion, grantedAt)
}
