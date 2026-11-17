package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.BleException
import com.ssafy.e106.core.ble.BleFailure
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
    // 동의한 실물과 실행 직전 연결이 달라지지 않도록 serial을 화면 수명 동안만 유지한다.
    private var connectedSerial: String? = null
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessions.state.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().collect { pause(); mutable.value = KeyringResetState() }
    } }

    fun load(deviceId: Long, connected: Boolean, serial: String? = null) {
        if (work?.isActive == true || deviceId <= 0) return
        target = deviceId
        this.connected = connected
        connectedSerial = serial
        execute(KeyringResetStage.Loading) { owner, stamp ->
            val ticket = recovery.inspect(owner, deviceId); ensure(owner, stamp)
            pending = ticket
            if (ticket != null) showTicket(owner, stamp, ticket)
            else {
                val result = owned.list(); ensure(owner, stamp)
                if (result is ApiResult.Fail) throw ResetApiException(result.error)
                val belongs = (result as ApiResult.Ok).value.any { it.deviceId == deviceId }
                // 안내와 확인을 한 화면에서 한다 — 바로 확인 단계(체크 · 초기화 버튼)로 시작한다
                mutable.value = KeyringResetState(stage = KeyringResetStage.Confirm,
                    deviceLabel = listOfNotNull("#$deviceId", connectedSerial).joinToString(" · "),
                    ownershipConfirmed = belongs, connected = this.connected)
            }
        }
    }
    fun accepted(value: Boolean) { if (!state.value.busy) mutable.update { it.copy(accepted = value) } }
    fun cancel() { if (!state.value.busy && pending == null) mutable.update { it.copy(stage = KeyringResetStage.Confirm,
        accepted = false, recordChoice = null, recordDeletionAccepted = false) } }
    /** 연결 대상 변경/끊김은 두 동의를 취소한다. 진행 중 실행의 내구 기록이나 서버 결과는 지우지 않는다. */
    fun connectionChanged(isConnected: Boolean, serial: String?) {
        val changed = connected != isConnected || connectedSerial != serial
        connected = isConnected; connectedSerial = serial
        mutable.update { it.copy(connected = isConnected, accepted = if (changed) false else it.accepted,
            recordDeletionAccepted = if (changed) false else it.recordDeletionAccepted,
            deviceLabel = if (pending == null && target != null) listOfNotNull("#$target", serial).joinToString(" · ") else it.deviceLabel) }
    }
    fun recordChoice(value: ResetRecordChoice) { if (!state.value.busy && pending == null) mutable.update {
        it.copy(recordChoice = value, recordDeletionAccepted = if (it.recordChoice == value) it.recordDeletionAccepted else false)
    } }
    fun recordDeletionAccepted(value: Boolean) { if (!state.value.busy && pending == null) mutable.update { it.copy(recordDeletionAccepted = value) } }

    /** 사용자 두 동의 후에만 시작한다. 서버 소유 확인·기기 challenge 검증은 recovery에서 다시 수행한다. */
    fun start(device: ResetDevicePort) {
        val id = target ?: return
        val serial = connectedSerial?.takeIf(KeyringRegistrationProtocol::serial) ?: return
        val confirmed = state.value
        if (work?.isActive == true || pending != null || !confirmed.canConfirm) return
        execute(KeyringResetStage.Preparing) { owner, stamp ->
            val result = recovery.start(owner, id, serial, confirmed.accepted, confirmed.pendingRecordCount?.toLong(),
                confirmed.recordChoice == ResetRecordChoice.Delete && confirmed.recordDeletionAccepted, device)
            ensure(owner, stamp); pending = result.ticket; showTicket(owner, stamp, result.ticket)
        }
    }
    fun recover(device: ResetDevicePort?) {
        val id = target ?: return
        if (work?.isActive == true) return
        // 아직 실행 기록이 없는 조회 실패는 같은 실물 연결로 다시 읽는다. serial을 누락하면
        // 연결 이벤트가 다시 오지 않는 동안 활성화된 시작 버튼이 아무 동작도 하지 않게 된다.
        if (pending == null) { load(id, connected, connectedSerial); return }
        connected = device != null
        // 결과를 기다리는 중에 다시 조회할 때는 "결과를 확인하고 있어요"를 그대로 둔다(진행 · 실패 문구로 깜빡이지 않게)
        val stage = if (state.value.stage == KeyringResetStage.Waiting) KeyringResetStage.Waiting else KeyringResetStage.Recovering
        execute(stage) { owner, stamp ->
            val result = recovery.resume(owner, id, device); ensure(owner, stamp)
            pending = result.ticket
            showTicket(owner, stamp, result.ticket)
        }
    }
    /**
     * 결과 대기를 멈추고 실패로 보여 준다(재연결 시간 초과 · 블루투스 꺼짐 · 권한 없음처럼 기다려도 낫지 않는 경우).
     * 실행 기록은 그대로라 "다시 시도"나 다시 열기로 같은 실행을 이어 간다.
     */
    fun stopWaiting(message: String?) {
        if (state.value.stage != KeyringResetStage.Waiting) return
        // 진행 중인 결과 조회도 멈춘다. 늦게 끝난 응답이 실패 표시를 덮지 않게 세대를 올린다(증명 · 실행 기록은 저장소에 남는다)
        // 취소한 작업이 증명을 이미 저장했을 수 있어 메모리 기록은 버린다. 다음 시도는 저장소에서 다시 판정한다(증명 있으면 서버만 마저)
        generation++; work?.cancel(); work = null; pending = null
        mutable.update { it.copy(stage = KeyringResetStage.Unknown, resumeSerial = null, errorMessage = message) }
    }
    /** 실패로 멈춘 실행을 "다시 시도"하면 같은 키링을 다시 찾아 결과부터 읽는다(화면이 재연결을 맡는다). */
    fun waitForDevice() {
        val ticket = pending ?: return
        if (work?.isActive == true || ticket.run == null || ticket.ack != null) return
        mutable.update { it.copy(stage = KeyringResetStage.Waiting, errorMessage = null, resumeSerial = ticket.serial,
            waitTick = it.waitTick + 1) }
    }
    private suspend fun showTicket(owner: Long, stamp: Long, ticket: ResetTicket) {
        val completed = ticket.run?.state == "COMPLETED"
        // 소유 해제 직후의 정리는 recovery에서 한 번만 한다. 과거 완료 화면 재조회가 새 등록을 지우면 안 된다.
        ensure(owner, stamp)
        // 실행은 있는데 키링 증명이 아직 없으면(연결 끊김 · 키링이 아직 지우는 중) 실패가 아니라 결과 대기다
        val waiting = !completed && ticket.ack == null && ticket.run != null
        val tick = state.value.waitTick
        mutable.value = KeyringResetState(stage = when {
                completed -> KeyringResetStage.Complete
                ticket.ack != null -> KeyringResetStage.Partial
                waiting -> KeyringResetStage.Waiting
                else -> KeyringResetStage.Unknown
            }, deviceLabel = "#${ticket.deviceId}",
            connected = connected, deviceResetConfirmed = completed || ticket.ack != null, ownershipReleased = completed,
            resumeSerial = ticket.serial.takeIf { waiting }, waitTick = if (waiting) tick + 1 else tick, runCreated = ticket.run != null)
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
                // 기록을 읽지 못하면 서버가 실행을 만들었을 수 있다. 실패가 아니라 결과 미확인으로 보인다
                var unreadable = false
                try { pending = journal.read(owner).singleOrNull { it.deviceId == target }; ensure(owner, stamp) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ensure(owner, stamp); unreadable = true }
                // 저장 실패/시간 초과는 결과 불명이다. 실패를 초기화 취소로 표현하지 않는다.
                // 실행을 만든 뒤 블루투스가 끊긴 것(키링이 지우면서 재시작 · 페어링 정리)은 실패가 아니라 재연결 대기다
                val current = pending
                val waiting = current?.ack == null && current?.run != null &&
                    (failure as? BleException)?.reason in RECONNECTABLE
                // 저장된 요청이 없거나(확정 거절로 지운 경우 포함 → 다시 시도는 확인 화면부터), 실행이 생기기 전 서버가
                // 시작 요청을 4xx로 거절했으면 실패다(남은 요청은 다시 시도에서 같은 키 · 본문으로 다시 보낸다).
                // 그 밖에는 결과 미확인이다(실패 · 원상복구로 표시하지 않는다)
                val rejected = ((failure as? ResetApiException)?.error as? AppError.Server)?.status in 400..499
                mutable.update { it.copy(stage = when {
                        current?.ack != null -> KeyringResetStage.Partial
                        waiting -> KeyringResetStage.Waiting
                        !unreadable && (current == null || (current.run == null && rejected)) -> KeyringResetStage.Failed
                        else -> KeyringResetStage.Unknown
                    }, runCreated = current?.run != null,
                    deviceResetConfirmed = current?.ack != null,
                    resumeSerial = current?.serial?.takeIf { waiting }, waitTick = if (waiting) it.waitTick + 1 else it.waitTick,
                    errorMessage = if (waiting) null else (failure as? ResetApiException)?.error?.message) }
            }
        }
    }
    private suspend fun ensure(owner: Long, stamp: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != stamp || (sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
    }
    fun pause() { generation++; work?.cancel(); work = null; pending = null; connectedSerial = null; mutable.value = KeyringResetState() }
    override fun onCleared() { pause() }
}

/** 다시 연결하면 나을 수 있는 블루투스 실패. 그 밖의 실패(프레임 오류 · 보안 연결 필요 등)는 기다리지 않고 실패로 보여 준다 */
private val RECONNECTABLE = setOf(BleFailure.Disconnected, BleFailure.Timeout, BleFailure.ConnectFailed,
    BleFailure.WriteFailed, BleFailure.NotFound, BleFailure.ServiceMissing, BleFailure.SubscribeFailed)
