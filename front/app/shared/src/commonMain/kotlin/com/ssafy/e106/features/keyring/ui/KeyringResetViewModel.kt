package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 읽기 진입/사용자 복구를 분리한다. 화면 이탈·회전에는 작업을 취소하고 실행 기록은 지우지 않는다. */
class KeyringResetViewModel(private val recovery: KeyringResetRecovery, private val owned: OwnedKeyringRepository,
    private val sessions: SessionStore, private val journal: KeyringResetStore) : ViewModel() {
    private val mutable = MutableStateFlow(KeyringResetState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var pending: ResetTicket? = null
    private var target: Long? = null
    private var connected = false
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessions.state.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().collect { pause(); mutable.value = KeyringResetState() }
    } }

    fun load(deviceId: Long, connected: Boolean) {
        if (work?.isActive == true || deviceId <= 0) return
        target = deviceId
        this.connected = connected
        execute(KeyringResetStage.Loading) { owner, stamp ->
            val ticket = recovery.inspect(owner, deviceId); ensure(owner, stamp)
            pending = ticket
            if (ticket != null) showTicket(owner, stamp, ticket)
            else {
                val result = owned.list(); ensure(owner, stamp)
                if (result is ApiResult.Fail) throw ResetApiException(result.error)
                val belongs = (result as ApiResult.Ok).value.any { it.deviceId == deviceId }
                mutable.value = KeyringResetState(stage = KeyringResetStage.Ready, deviceLabel = "#$deviceId",
                    ownershipConfirmed = belongs, connected = connected)
            }
        }
    }
    fun confirm() { if (pending == null && state.value.displayStage == KeyringResetStage.Ready) mutable.update { it.copy(stage = KeyringResetStage.Confirm) } }
    fun accepted(value: Boolean) { if (!state.value.busy) mutable.update { it.copy(accepted = value) } }
    fun cancel() { if (!state.value.busy && pending == null) mutable.update { it.copy(stage = KeyringResetStage.Ready, accepted = false) } }
    // 미전송 조회·정책 확정 전에는 start API 진입 자체를 제공하지 않는다. Preview 값도 이 모델에 주입하지 않는다.
    fun recover(device: ResetDevicePort?) {
        val id = target ?: return
        if (work?.isActive == true) return
        if (pending == null) { load(id, connected); return }
        connected = device != null
        execute(KeyringResetStage.Recovering) { owner, stamp ->
            val result = recovery.resume(owner, id, device); ensure(owner, stamp)
            pending = result.ticket
            showTicket(owner, stamp, result.ticket)
        }
    }
    private suspend fun showTicket(owner: Long, stamp: Long, ticket: ResetTicket) {
        val completed = ticket.run?.state == "COMPLETED"
        // 소유 해제 직후의 정리는 recovery에서 한 번만 한다. 과거 완료 화면 재조회가 새 등록을 지우면 안 된다.
        ensure(owner, stamp)
        mutable.value = KeyringResetState(stage = if (completed) KeyringResetStage.Complete else if (ticket.ack != null)
            KeyringResetStage.Partial else KeyringResetStage.Unknown, deviceLabel = "#${ticket.deviceId}",
            connected = connected, deviceResetConfirmed = completed || ticket.ack != null, ownershipReleased = completed)
    }
    private fun execute(stage: KeyringResetStage, action: suspend (Long, Long) -> Unit) {
        val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: return
        val stamp = ++generation
        mutable.update { it.copy(stage = stage, errorMessage = null) }
        work = viewModelScope.launch {
            try { ensure(owner, stamp); action(owner, stamp) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensure(owner, stamp)
                // ACK HTTP 응답이 유실돼도 이미 내구 저장한 기기 증명은 부분 완료 안내에 반영한다.
                try { pending = journal.read(owner).singleOrNull { it.deviceId == target }; ensure(owner, stamp) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ensure(owner, stamp) }
                // 저장 실패/시간 초과는 결과 불명이다. 실패를 초기화 취소로 표현하지 않는다.
                mutable.update { it.copy(stage = if (pending?.ack != null) KeyringResetStage.Partial else KeyringResetStage.Unknown,
                    deviceResetConfirmed = pending?.ack != null,
                    errorMessage = (failure as? ResetApiException)?.error?.message) }
            }
        }
    }
    private suspend fun ensure(owner: Long, stamp: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != stamp || (sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
    }
    fun pause() { generation++; work?.cancel(); work = null; pending = null; mutable.value = KeyringResetState() }
    override fun onCleared() { pause() }
}
