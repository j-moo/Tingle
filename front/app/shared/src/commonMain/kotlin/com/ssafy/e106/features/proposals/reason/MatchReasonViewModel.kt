package com.ssafy.e106.features.proposals.reason

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.proposals.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.*

data class ConnectedReasonState(val context: ProposalReviewContext? = null, val view: MatchReasonState = MatchReasonState(),
    val error: AppError? = null, val accessLost: Boolean = false)

/** 설명 작업 실패는 제안 수락 권한과 별개다. 실제 404/410만 접근 상실로 전달한다. */
@OptIn(ExperimentalUuidApi::class)
class MatchReasonViewModel(private val repository: MatchReasonRepository, private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedReasonState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var serial = 0L
    private var key: String? = null
    private var task: ReasonTask? = null
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect { bind(null) } } }
    fun bind(context: ProposalReviewContext?) {
        if (mutable.value.context == context) return
        serial++; job?.cancel(); key = null; task = null
        mutable.value = ConnectedReasonState(context)
    }
    private fun valid(stamp: Long, c: ProposalReviewContext) = stamp == serial && mutable.value.context == c &&
        (sessions.state.value as? Session.SignedIn)?.userId == c.viewerId
    fun check(retry: Boolean = false) {
        val c = mutable.value.context ?: return
        if (mutable.value.view.busy || !valid(serial, c) || mutable.value.accessLost) return
        if (retry && !mutable.value.view.canRetry) return
        if (retry) { key = null; task = null }
        val stamp = serial
        mutable.update { it.copy(error = null, view = it.view.copy(busy = true)) }
        job = viewModelScope.launch {
            try {
                if (task == null) {
                    val requestKey = key ?: Uuid.random().toString().also { key = it }
                    when (val response = repository.request(c.proposalId, requestKey)) {
                        is ApiResult.Fail -> { fail(response.error, stamp, c); return@launch }
                        is ApiResult.Ok -> {
                            if (!valid(stamp, c)) return@launch
                            val t = response.value
                            if (t.proposalId != c.proposalId || t.kind != "MATCH_EXPLANATION" ||
                                t.statusUrl != "/api/match-proposals/${c.proposalId}/explanation" ||
                                t.status !in setOf("PENDING", "SUCCEEDED", "FAILED") ||
                                t.pollAfterSeconds != if (t.status == "PENDING") 1L else 0L) {
                                fail(AppError.Unknown(IllegalStateException("Invalid reason task")), stamp, c); return@launch
                            }
                            task = t
                            mutable.update { it.copy(view = MatchReasonState.fromTask(MatchReasonTask(t.status, t.retryable, t.pollAfterSeconds)).copy(busy = true)) }
                            if (t.status == "FAILED") return@launch
                        }
                    }
                }
                // 짧은 자동 조회 뒤에는 사용자가 다시 확인한다. 화면 이탈/계정 변경 시 취소한다.
                repeat(30) {
                    if (task?.status == "PENDING") delay(task!!.pollAfterSeconds * 1000)
                    if (!valid(stamp, c)) return@launch
                    when (val response = repository.get(c.proposalId)) {
                        is ApiResult.Fail -> { fail(response.error, stamp, c); return@launch }
                        is ApiResult.Ok -> {
                            if (!valid(stamp, c)) return@launch
                            val value = response.value
                            val view = MatchReasonState.fromResult(MatchReasonResult(value.status, value.source, value.text))
                            mutable.update { it.copy(view = view.copy(busy = value.status == "PENDING")) }
                            if (value.status != "PENDING") return@launch
                            // GET에는 재생성 허용 여부가 없다. 임의 retryable=true로 만들지 않는다.
                            if (task?.status != "PENDING") return@launch
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(AppError.Unknown(IllegalStateException("Reason unavailable")), stamp, c) }
            finally { if (valid(stamp, c)) mutable.update { it.copy(view = it.view.copy(busy = false)) } }
        }
    }
    private fun fail(error: AppError, stamp: Long, c: ProposalReviewContext) {
        if (!valid(stamp, c)) return
        val lost = error is AppError.Server && (error.status == 404 || error.code == "PROPOSAL_EXPIRED")
        mutable.update { it.copy(error = error, accessLost = lost, view = MatchReasonState(accessible = !lost)) }
    }
}
