package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

enum class SyncPhase { WaitingStage, Issuing, WaitingConnection, Sending, Stored, Confirming, Confirmed, PolicyWaiting, Stale, Failed }
data class KeyringSyncProgress(val deviceId: Long, val phase: SyncPhase, val error: AppError? = null,
    val applied: Boolean? = null, val timeReady: Boolean? = null)
data class KeyringSyncState(val owner: Long? = null, val busy: Boolean = false,
    val items: List<KeyringSyncProgress> = emptyList(), val recoveryFailed: Boolean = false)

/**
 * 등록 후 가입 단계가 준비되면 설정 발급을 자동 시작한다. 화면 회전/백그라운드는 작업만 취소하고
 * 이미 저장한 멱등키와 증명은 보존한다. 복귀·재연결 때 같은 실행을 이어가며 무한 자동 재시도하지 않는다.
 * BLE 연결은 진단 모델이 소유한다. 기기 일련번호가 일치한 연결만 잠시 빌려 사용한다.
 */
@OptIn(ExperimentalUuidApi::class)
class KeyringSyncViewModel(private val api: KeyringSyncRepository, private val store: KeyringSyncStore,
    private val owned: OwnedKeyringRepository, private val sessions: SessionStore,
    private val now: () -> Instant = { Clock.System.now() }) : ViewModel() {
    // combine의 최초 방출보다 화면 resume이 먼저 올 수 있다. 현재 계정으로 초기화해
    // 첫 방출을 계정 전환으로 오인하거나 같은 발급/ACK를 두 번 시작하지 않는다.
    private val mutable = MutableStateFlow(KeyringSyncState(owner = (sessions.state.value as? Session.SignedIn)?.userId))
    val state = mutable.asStateFlow()
    private var active = false
    private var generation = 0L
    private var job: Job? = null
    private var requested = false
    private val attempted = mutableSetOf<Long>()
    private var boardSerial: String? = null
    private var transmit: (suspend (KeyringSyncRun) -> KeyringStoredResult)? = null
    private var challenge: (suspend () -> String)? = null
    private var observed: Session? = sessions.state.value

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(sessions.state, store.targetsChanged) { session, _ -> session }.collect { session ->
            val owner = (session as? Session.SignedIn)?.userId
            if (owner != mutable.value.owner) {
                cancelWork(); boardSerial = null; transmit = null; challenge = null
                mutable.value = KeyringSyncState(owner)
            }
            if (session != observed) {
                // 같은 계정이라도 약관 재동의 등으로 자격이 내려가면 이전 단계의 전송을 계속하지 않는다.
                if ((session as? Session.SignedIn)?.status !in setOf(OnboardingStatus.SYNC_PENDING, OnboardingStatus.READY)) cancelWork()
                attempted.clear(); observed = session
            }
            drive()
        }
    } }

    fun resume() { if (!active) { active = true; attempted.clear() }; drive() }
    fun pause() { active = false; detach(); cancelWork() }
    fun attach(serial: String, getChallenge: suspend () -> String, send: suspend (KeyringSyncRun) -> KeyringStoredResult) {
        if (boardSerial == serial && transmit != null) return
        boardSerial = serial; transmit = send; challenge = getChallenge; attempted.clear(); drive()
    }
    fun detach() {
        if (transmit != null) { boardSerial = null; transmit = null; challenge = null; cancelWork() }
    }
    private fun cancelWork() {
        generation++; job?.cancel(); job = null; requested = false; attempted.clear()
        mutable.update { it.copy(busy = false) }
    }
    fun retry(deviceId: Long) { attempted.remove(deviceId); drive() }
    fun retryRecovery() { attempted.clear(); drive() }

    /** 이전 APK에서 등록한 기기는 serial이 서버 목록에 없다. 사용자가 연결 기기와 번호를 명시적으로 선택한다.
     * 이 대응은 전송 경로일 뿐 소유 인증이 아니다. 서버의 소유 검사와 보드 keyId/저장 서명이 최종 검증한다. */
    fun useExisting(deviceId: Long, serial: String) {
        if (mutable.value.busy || !active || serial != boardSerial) return
        val owner = mutable.value.owner ?: return
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            try {
                val items = unwrap(owned.list(), owner, stamp)
                check(items.any { it.deviceId == deviceId })
                store.selectTarget(owner, deviceId, serial); ensure(owner, stamp)
                attempted.remove(deviceId)
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (stamp == generation) mutable.update { it.copy(recoveryFailed = true) } }
            finally { if (stamp == generation) { mutable.update { it.copy(busy = false) }; drive() } }
        }
    }

    /** 구버전 또는 서버 확인이 끝난 설정의 재부팅 후 재전송에만 새 발급을 허용한다.
     * 미확인 ACK를 버리지 않으며, HTTP 응답 유실은 기존 키/본문 재시도 경로를 사용한다. */
    fun latest(deviceId: Long) {
        if (mutable.value.busy || mutable.value.items.none { it.deviceId == deviceId && it.phase in setOf(SyncPhase.Stale, SyncPhase.Confirmed) }) return
        val owner = mutable.value.owner ?: return
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            try {
                val previous = store.read(owner).first { it.deviceId == deviceId }; ensure(owner, stamp)
                store.save(owner, SyncTicket(deviceId, previous.serial, Uuid.random().toString())); ensure(owner, stamp)
                attempted.remove(deviceId)
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (stamp == generation) mutable.update { it.copy(recoveryFailed = true) } }
            finally { if (stamp == generation) { mutable.update { it.copy(busy = false) }; drive() } }
        }
    }

    private fun drive() {
        if (!active) return
        val session = sessions.state.value as? Session.SignedIn ?: return
        if (mutable.value.busy) { requested = true; return }
        val stamp = generation
        val owner = session.userId
        mutable.update { it.copy(busy = true, recoveryFailed = false) }
        job = viewModelScope.launch {
            try {
                val tickets = store.read(owner); ensure(owner, stamp)
                // isReady는 재동의 예외를 포함하므로 발급 단계 판정에는 서버 status 자체를 사용한다.
                val allowed = session.status in setOf(OnboardingStatus.SYNC_PENDING, OnboardingStatus.READY)
                for (ticket in tickets) {
                    ensure(owner, stamp)
                    if (!allowed) { show(ticket.deviceId, SyncPhase.WaitingStage); continue }
                    if (!attempted.add(ticket.deviceId)) continue
                    try { process(owner, stamp, ticket) }
                    catch (c: CancellationException) { throw c }
                    catch (failure: Exception) {
                        ensure(owner, stamp)
                        val error = (failure as? SyncApiFailure)?.error
                        val code = (error as? AppError.Server)?.code ?: (failure as? BleCommandException)?.code
                        show(ticket.deviceId, when (code) {
                            "POLICY_UNRESOLVED", "UNKNOWN_COMMAND", "UNSUPPORTED_COMMAND", "UNSUPPORTED_TYPE" -> SyncPhase.PolicyWaiting
                            "SYNC_VERSION_STALE" -> SyncPhase.Stale
                            else -> SyncPhase.Failed
                        }, error)
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (stamp == generation) mutable.update { it.copy(recoveryFailed = true) } }
            finally { if (stamp == generation) {
                mutable.update { it.copy(busy = false) }
                if (requested) { requested = false; drive() }
            } }
        }
    }

    private suspend fun process(owner: Long, stamp: Long, original: SyncTicket) {
        var ticket = original
        val id = ticket.deviceId
        // 저장 증명이 있으면 BLE를 다시 건드리지 않고 GET/동일 ACK로 복구한다.
        // 그 전의 BLE 재전송은 60초·1회용 challenge를 재사용하면 안 된다.
        if (ticket.ack == null && !ticket.complete) {
            val freshChallenge = challenge
            if (transmit == null || freshChallenge == null || boardSerial != ticket.serial) {
                show(id, SyncPhase.WaitingConnection); return
            }
            if (ticket.deviceNonce == null || ticket.bleAttempted || !nonceFresh(ticket)) {
                val nonce = freshChallenge(); ensure(owner, stamp)
                check(KeyringSyncProtocol.validDeviceNonce(nonce))
                ticket = SyncTicket(id, ticket.serial, Uuid.random().toString(), deviceNonce = nonce, nonceReceivedAt = now().toString())
                // 새 본문에는 새 키를 사용한다. POST보다 먼저 둘을 함께 디스크에 고정한다.
                store.save(owner, ticket); ensure(owner, stamp)
            }
        }
        show(id, SyncPhase.Issuing)
        val recoveringAck = ticket.ack != null || ticket.complete
        var run = unwrap(if (recoveringAck) api.get(id, requireNotNull(ticket.identity).syncRunId)
            else api.create(id, ticket.createKey, SyncChallengeBody(requireNotNull(ticket.deviceNonce))), owner, stamp)
        check(KeyringSyncProtocol.valid(run, id))
        check(ticket.identity == null || ticket.identity == run.identity())
        // GET은 최초 발급 별칭의 서명을 돌려줄 수 있다. 전송용 서명은 같은 키/본문 POST에서만 받는다.
        if (!recoveringAck) check(KeyringSyncProtocol.signed(run, requireNotNull(ticket.deviceNonce)))
        ticket = ticket.copy(identity = run.identity())
        store.save(owner, ticket); ensure(owner, stamp)
        if (run.stale) { show(id, SyncPhase.Stale); return }
        if (ticket.complete && run.state == "ACKED") { show(id, SyncPhase.Confirmed); return }
        if (recoveringAck && run.state == "ACKED" && ticket.ack == null) {
            store.save(owner, ticket.copy(complete = true)); ensure(owner, stamp)
            show(id, SyncPhase.Confirmed); return
        }
        var result: KeyringStoredResult? = null
        if (ticket.ack == null) {
            val send = transmit
            if (send == null || boardSerial != ticket.serial) { show(id, SyncPhase.WaitingConnection); return }
            // 서버 응답 대기로 60초를 넘길 수 있다. 5초 전송 여유를 남기고 오래된 서명은 보내지 않는다.
            // 휴대폰 시계가 뒤로 바뀐 경우도 새 challenge를 요구하며 최종 만료 판정은 보드가 한다.
            if (!nonceFresh(ticket)) throw BleCommandException("SYNC_CHALLENGE_REQUIRED")
            // 프레임을 보내기 직전 소비 가능성을 기록한다. 중단·응답 유실 후에는 새 challenge로 재서명한다.
            ticket = ticket.copy(bleAttempted = true)
            store.save(owner, ticket); ensure(owner, stamp)
            show(id, SyncPhase.Sending)
            result = send(run); ensure(owner, stamp)
            check(result.ack.settingsVersion == run.settingsVersion && result.ack.payloadHash == run.payloadHash &&
                result.ack.proof.keyId == run.keyId && result.ack.proof.protocolVersion == run.proofProtocolVersion)
            show(id, SyncPhase.Stored)
            // 전송 전에 내구 기록을 확인한다. 앱이 종료돼도 새 서명/키로 ACK를 바꾸지 않는다.
            ticket = ticket.copy(ack = result.ack, ackKey = Uuid.random().toString())
            store.save(owner, ticket); ensure(owner, stamp)
        }
        show(id, SyncPhase.Confirming)
        run = unwrap(api.ack(id, run.syncRunId, requireNotNull(ticket.ackKey), requireNotNull(ticket.ack)), owner, stamp)
        check(KeyringSyncProtocol.valid(run, id) && run.identity() == ticket.identity)
        if (run.stale) { show(id, SyncPhase.Stale); return }
        check(run.state == "ACKED")
        store.save(owner, ticket.copy(complete = true)); ensure(owner, stamp)
        show(id, SyncPhase.Confirmed, applied = result?.applied, timeReady = result?.timeReady)
        // 서버가 실제로 가입 단계를 갱신했는지 재조회한다. FE에서 READY를 만들어 넣지 않는다.
        sessions.refreshOnboarding(); ensure(owner, stamp)
    }
    private fun ensure(owner: Long, stamp: Long) {
        if (!active || stamp != generation || (sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
    }
    private fun nonceFresh(ticket: SyncTicket): Boolean = runCatching {
        val elapsed = now() - Instant.parse(requireNotNull(ticket.nonceReceivedAt))
        elapsed >= 0.seconds && elapsed < 55.seconds
    }.getOrDefault(false)
    private fun <T> unwrap(result: ApiResult<T>, owner: Long, stamp: Long): T {
        ensure(owner, stamp)
        return when (result) { is ApiResult.Ok -> result.value; is ApiResult.Fail -> throw SyncApiFailure(result.error) }
    }
    private fun show(id: Long, phase: SyncPhase, error: AppError? = null, applied: Boolean? = null, timeReady: Boolean? = null) {
        mutable.update { it.copy(items = it.items.filterNot { item -> item.deviceId == id } + KeyringSyncProgress(id, phase, error, applied, timeReady)) }
    }
    override fun onCleared() { pause() }
}
private class SyncApiFailure(val error: AppError) : Exception()
