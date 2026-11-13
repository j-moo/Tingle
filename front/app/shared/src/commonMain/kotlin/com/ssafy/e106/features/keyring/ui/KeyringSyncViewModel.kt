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

enum class SyncPhase {
    WaitingStage, Issuing, WaitingConnection, Sending, Stored, Confirming, Confirmed, PolicyWaiting, Stale, Failed,
    ApplyChecking, ApplyWaitingConnection, ApplyConfirming, ApplyRetry, ApplyFailed, ApplyFirmwareRequired, ApplyConfirmed,
}
data class KeyringSyncProgress(val deviceId: Long, val phase: SyncPhase, val error: AppError? = null,
    val applied: Boolean? = null, val timeReady: Boolean? = null, val serial: String? = null,
    val confirmedTsv3Version: Long? = null, val confirmedSyncRunId: String? = null, val alreadyLatest: Boolean = false)
data class KeyringSyncState(val owner: Long? = null, val busy: Boolean = false,
    val items: List<KeyringSyncProgress> = emptyList(), val recoveryFailed: Boolean = false)

/**
 * 등록 후 가입 단계가 준비되면 설정 발급을 자동 시작한다. 화면 회전/백그라운드는 작업만 취소하고
 * 이미 저장한 발급 본문·ACK 멱등키와 증명은 보존한다. 발급 응답 유실은 같은 키/본문으로 먼저 확인하고,
 * 새 연결에서는 그 결과를 BLE에 쓰지 않고 새 challenge로 재서명한다. 무한 자동 재시도하지 않는다.
 * BLE 연결은 진단 모델이 소유한다. 기기 일련번호가 일치한 연결만 잠시 빌려 사용한다.
 */
@OptIn(ExperimentalUuidApi::class)
class KeyringSyncViewModel(private val api: KeyringSyncRepository, private val store: KeyringSyncStore,
    private val owned: OwnedKeyringRepository, private val sessions: SessionStore,
    private val now: () -> Instant = { Clock.System.now() },
    private val changes: KeyringSettingsChanges = KeyringSettingsChanges(),
    private val network: NetworkMonitor? = null) : ViewModel() {
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
    private var challenge: (suspend () -> SyncChallengeBody)? = null
    private var readApplied: (suspend (SyncIdentity) -> KeyringSyncAck)? = null
    private var readDeviceInfo: (suspend () -> BleDeviceInfo)? = null
    private val manualChecks = mutableSetOf<Long>()
    private var observed: Session? = sessions.state.value
    // 서버 ACK와 현재 BLE 연결에서의 저장 확인은 다르다. 새 연결은 재부팅 여부를 단정하지 않고
    // 새 challenge로 한 번 다시 인증한다. 같은 연결/변경 횟수에는 반복 전송하지 않는다.
    private var binding = 0L
    // 보드 challenge는 재부팅하면 사라진다. 키/본문의 HTTP 복구와 달리 BLE 쓰기는 원래 연결에만 허용한다.
    // 디스크에는 연결 생존을 기록하지 않는다. 모델 재생성 후에는 결과 확인 뒤 새 인증이 필요하다.
    private val nonceBindings = mutableMapOf<Long, Pair<Long, String>>()
    private val serials = mutableMapOf<Long, String>()
    private val delivered = mutableMapOf<Long, Pair<Long, Long>>()
    private var observedRevision = 0L
    // 인터넷 사용 가능 신호는 전송 성공 보장이 아니다. 복원 전환마다 한 번만 Offline 실패를 깨운다.
    private var networkAvailable: Boolean? = null
    private var networkRevision = 0L
    private val offlineAttempts = mutableMapOf<Long, Long>()
    private var networkJob: Job? = null

    /** 기반의 검증된 인터넷 상태를 메인 스레드에서 전달한다. 최초 true도 보류 작업을 한 번 확인한다.
     * 요청 키/증명을 교체하지 않고 기존 process 경로를 이용하므로 ACK 유실 복구 계약을 유지한다.
     * 회전·화면 이탈로 pause된 모델, 서버 거절·권한 오류·완료 항목은 자동 재시도하지 않는다. */
    fun onNetworkAvailabilityChanged(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        if (available) { networkRevision++; retryOfflineAfterRestore() }
    }

    private fun retryOfflineAfterRestore() {
        if (!active || networkAvailable != true) return
        val eligible = mutable.value.items.filter {
            it.phase in setOf(SyncPhase.Failed, SyncPhase.ApplyRetry) && it.error == AppError.Offline &&
                offlineAttempts[it.deviceId]?.let { revision -> revision < networkRevision } == true
        }.map { it.deviceId }
        if (eligible.isEmpty()) return
        eligible.forEach { attempted.remove(it); offlineAttempts.remove(it) }
        // 전송 중 복원된 경우에는 진행 중 작업을 취소하지 않고 finally 뒤에 직렬로 이어간다.
        drive()
    }

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(sessions.state, store.targetsChanged, changes.state) { session, _, revisions ->
            session to (revisions[(session as? Session.SignedIn)?.userId] ?: 0L)
        }.collect { (session, revision) ->
            val owner = (session as? Session.SignedIn)?.userId
            if (owner != mutable.value.owner) {
                cancelWork(); boardSerial = null; transmit = null; challenge = null; readApplied = null; readDeviceInfo = null
                mutable.value = KeyringSyncState(owner)
                delivered.clear(); nonceBindings.clear(); serials.clear()
            }
            if (revision != observedRevision) { observedRevision = revision; attempted.clear() }
            if (session != observed) {
                // 같은 계정이라도 약관 재동의 등으로 자격이 내려가면 이전 단계의 전송을 계속하지 않는다.
                if ((session as? Session.SignedIn)?.status !in setOf(OnboardingStatus.SYNC_PENDING, OnboardingStatus.READY)) cancelWork()
                attempted.clear(); observed = session
            }
            drive()
        }
    } }

    fun resume() {
        if (!active) {
            active = true; attempted.clear()
            // viewModelScope에서 받아 상태 변경을 메인 스레드로 직렬화한다. 화면 재구성마다 재구독하지 않는다.
            networkJob = network?.let { monitor -> viewModelScope.launch {
                monitor.availability.collect { onNetworkAvailabilityChanged(it) }
            } }
        }
        drive()
    }
    fun pause() {
        active = false
        networkJob?.cancel(); networkJob = null; networkAvailable = null
        detach(); cancelWork()
    }
    /** 기존 nonce 전용 호출은 ON/OFF로 유지한다. 실행 경로는 최신 기기 지원을 포함한 attachRequest를 쓴다. */
    fun attach(serial: String, getChallenge: suspend () -> String, send: suspend (KeyringSyncRun) -> KeyringStoredResult) =
        attachRequest(serial, { SyncChallengeBody(getChallenge()) }, send = send)

    /** 기기 조회와 보호 연결 확인 뒤 만든 본문을 받는다. 재시도 중에는 이 콜백으로 본문을 다시 만들지 않는다. */
    fun attachRequest(serial: String, getChallenge: suspend () -> SyncChallengeBody,
        getApplied: (suspend (SyncIdentity) -> KeyringSyncAck)? = null,
        getDeviceInfo: (suspend () -> BleDeviceInfo)? = null, send: suspend (KeyringSyncRun) -> KeyringStoredResult) {
        if (boardSerial == serial && transmit != null) return
        binding++
        boardSerial = serial; transmit = send; challenge = getChallenge; readApplied = getApplied
        readDeviceInfo = getDeviceInfo; attempted.clear(); drive()
    }
    fun detach() {
        if (transmit != null) {
            val disconnected = boardSerial
            val recoverHttp = mutable.value.items.any { it.serial == disconnected &&
                it.phase in setOf(SyncPhase.ApplyConfirming, SyncPhase.ApplyConfirmed) }
            boardSerial = null; transmit = null; challenge = null; readApplied = null; readDeviceInfo = null; cancelWork()
            mutable.update { state -> state.copy(items = state.items.map {
                if (it.serial == disconnected && it.phase == SyncPhase.ApplyChecking) it.copy(phase = SyncPhase.ApplyWaitingConnection) else it
            }) }
            // HTTP 증명이 내구 저장된 경우에는 BLE 없이도 같은 요청으로 복구할 수 있다.
            if (recoverHttp) drive()
        }
    }
    private fun cancelWork() {
        generation++; job?.cancel(); job = null; requested = false; attempted.clear()
        // 계정 변경·pause·BLE 분리 뒤 이전 작업의 복원 신호를 재사용하지 않는다.
        offlineAttempts.clear()
        manualChecks.clear()
        mutable.update { it.copy(busy = false) }
    }
    fun retry(deviceId: Long) { attempted.remove(deviceId); drive() }
    fun retryRecovery() { attempted.clear(); drive() }

    /** 이전 APK에서 등록한 기기는 serial이 서버 목록에 없다. 사용자가 연결 기기와 번호를 명시적으로 선택한다.
     * 이 대응은 전송 경로일 뿐 소유 인증이 아니다. 서버의 소유 검사와 보드 keyId/저장 서명이 최종 검증한다. */
    fun useExisting(deviceId: Long, serial: String, checkLatest: Boolean = false) {
        if (mutable.value.busy || !active || serial != boardSerial) return
        val owner = mutable.value.owner ?: return
        val stamp = generation
        mutable.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            var ok = false
            try {
                val items = unwrap(owned.list(), owner, stamp)
                // 목록 순서나 사용자가 고른 번호만으로 다른 실물에 대한 동기화 대상을 저장하지 않는다.
                check(items.any { it.deviceId == deviceId && it.deviceSerial == serial })
                store.selectTarget(owner, deviceId, serial); ensure(owner, stamp)
                if (checkLatest) manualChecks.add(deviceId)
                attempted.remove(deviceId)
                ok = true
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (stamp == generation) mutable.update { it.copy(recoveryFailed = true) } }
            // 실패하면 실패 표시(recoveryFailed)를 남긴다. 그 사이 들어온 연결 · 네트워크 복구 요청은 실패 표시를 지우지 않고 이어서 돈다
            finally { if (stamp == generation) { mutable.update { it.copy(busy = false) }
                if (ok) drive() else if (requested) { requested = false; drive(keepFailure = true) } } }
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
            var ok = false
            try {
                val previous = store.read(owner).first { it.deviceId == deviceId }; ensure(owner, stamp)
                store.save(owner, SyncTicket(deviceId, previous.serial, Uuid.random().toString())); ensure(owner, stamp)
                attempted.remove(deviceId)
                ok = true
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (stamp == generation) mutable.update { it.copy(recoveryFailed = true) } }
            // 실패하면 실패 표시(recoveryFailed)를 남긴다. 그 사이 들어온 연결 · 네트워크 복구 요청은 실패 표시를 지우지 않고 이어서 돈다
            finally { if (stamp == generation) { mutable.update { it.copy(busy = false) }
                if (ok) drive() else if (requested) { requested = false; drive(keepFailure = true) } } }
        }
    }

    private fun drive(keepFailure: Boolean = false) {
        if (!active) return
        val session = sessions.state.value as? Session.SignedIn ?: return
        if (mutable.value.busy) { requested = true; return }
        val stamp = generation
        val owner = session.userId
        mutable.update { it.copy(busy = true, recoveryFailed = keepFailure && it.recoveryFailed) }
        job = viewModelScope.launch {
            try {
                val tickets = store.read(owner); ensure(owner, stamp)
                // isReady는 재동의 예외를 포함하므로 발급 단계 판정에는 서버 status 자체를 사용한다.
                val allowed = session.status in setOf(OnboardingStatus.SYNC_PENDING, OnboardingStatus.READY)
                for (ticket in tickets) {
                    ensure(owner, stamp)
                    serials[ticket.deviceId] = ticket.serial              // 진행 표시에 어느 보드용 설정인지 싣는다
                    if (!allowed) { show(ticket.deviceId, SyncPhase.WaitingStage); continue }
                    if (!attempted.add(ticket.deviceId)) continue
                    val startedNetworkRevision = networkRevision
                    offlineAttempts.remove(ticket.deviceId)
                    try { process(owner, stamp, ticket, checkLatest = manualChecks.remove(ticket.deviceId)) }
                    catch (c: CancellationException) { throw c }
                    catch (failure: Exception) {
                        ensure(owner, stamp)
                        val error = (failure as? SyncApiFailure)?.error
                        val code = (error as? AppError.Server)?.code ?: (failure as? BleCommandException)?.code
                        val phase = mutable.value.items.firstOrNull { it.deviceId == ticket.deviceId }?.phase
                        val applying = phase in setOf(SyncPhase.ApplyChecking, SyncPhase.ApplyConfirming, SyncPhase.ApplyConfirmed)
                        show(ticket.deviceId, when {
                            applying && code in setOf("SETTINGS_APPLIED_UNSUPPORTED", "UNKNOWN_COMMAND", "UNSUPPORTED_COMMAND", "UNSUPPORTED_TYPE") ->
                                SyncPhase.ApplyFirmwareRequired
                            applying && code == "SETTINGS_APPLY_FAILED" -> SyncPhase.ApplyFailed
                            applying -> SyncPhase.ApplyRetry
                            code == "SYNC_VERSION_STALE" -> SyncPhase.Stale
                            code in setOf("POLICY_UNRESOLVED", "UNKNOWN_COMMAND", "UNSUPPORTED_COMMAND", "UNSUPPORTED_TYPE") -> SyncPhase.PolicyWaiting
                            else -> SyncPhase.Failed
                        }, error)
                        if (error == AppError.Offline) {
                            offlineAttempts[ticket.deviceId] = startedNetworkRevision
                            // 인터넷이 복원된 직후 이전 요청이 늦게 실패해도 복원 신호를 놓치지 않는다.
                            retryOfflineAfterRestore()
                        }
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

    private suspend fun process(owner: Long, stamp: Long, original: SyncTicket, mayRenew: Boolean = true,
        checkLatest: Boolean = false) {
        var ticket = original
        val id = ticket.deviceId
        val delivery = binding to (changes.state.value[owner] ?: 0L)
        // 결과 불명의 POST는 모델 재생성/만료 뒤에도 먼저 같은 요청으로 조회한다. 그 서명은 새 연결에 쓰지 않는다.
        val unresolvedCreate = ticket.deviceNonce != null && ticket.identity == null && !ticket.bleAttempted
        fun connected() = transmit != null && boardSerial == ticket.serial
        // 한 번의 진행에서는 새 실행 전환을 최대 한 번만 허용한다. 서버가 계속 stale을 반환해도 루프를 만들지 않는다.
        suspend fun renewOrShowStale() {
            if (mayRenew && connected()) {
                val fresh = SyncTicket(id, ticket.serial, Uuid.random().toString())
                store.save(owner, fresh); ensure(owner, stamp)
                process(owner, stamp, fresh, mayRenew = false)
            } else show(id, if (connected()) SyncPhase.Stale else SyncPhase.WaitingConnection)
        }
        // 응답 유실 복구는 GET이나 BLE보다 먼저 동일 /applied 본문을 재전송한다. 이미 처리된 요청은
        // 서버의 가입·설정 상태가 바뀌었더라도 최초 키로 결과를 확인해야 한다.
        if (ticket.complete && ticket.appliedProof != null && ticket.needsAppliedConfirmation()) {
            finishApplied(owner, stamp, ticket, ::renewOrShowStale)
            return
        }
        // 저장 증명이 있으면 BLE를 다시 건드리지 않고 GET/동일 ACK로 복구한다.
        // 그 전의 BLE 재전송은 60초·1회용 challenge를 재사용하면 안 된다.
        // 시간상 유효하더라도 새 연결에서는 보드가 재부팅했을 수 있어 새 challenge를 받는다.
        if (ticket.ack == null && !ticket.complete && !unresolvedCreate) {
            val freshChallenge = challenge
            if (transmit == null || freshChallenge == null || boardSerial != ticket.serial) {
                show(id, SyncPhase.WaitingConnection); return
            }
            if (ticket.deviceNonce == null || ticket.bleAttempted || !nonceFresh(ticket) ||
                nonceBindings[id] != (binding to ticket.createKey)) {
                val body = freshChallenge(); ensure(owner, stamp)
                check(KeyringSyncProtocol.validDeviceNonce(body.deviceNonce) &&
                    KeyringSyncProtocol.supportsIssuanceFormat(body.payloadFormat))
                ticket = SyncTicket(id, ticket.serial, Uuid.random().toString(), deviceNonce = body.deviceNonce,
                    nonceReceivedAt = now().toString(), createBody = body)
                // 새 본문에는 새 키를 사용한다. POST보다 먼저 둘을 함께 디스크에 고정한다.
                store.save(owner, ticket); ensure(owner, stamp)
                // 내구 저장과 현재 계정/작업 확인이 끝난 요청만 이 연결의 HTTP 재시도에 사용한다.
                nonceBindings[id] = binding to ticket.createKey
            }
        }
        show(id, SyncPhase.Issuing)
        val recoveringAck = ticket.ack != null || ticket.complete
        val issued = if (recoveringAck) api.get(id, requireNotNull(ticket.identity).syncRunId)
            // 구버전 내구 기록에는 형식이 없으므로 원래 nonce 단독 요청 그대로 복원한다.
            else api.create(id, ticket.createKey, ticket.createBody ?: SyncChallengeBody(requireNotNull(ticket.deviceNonce)))
        ensure(owner, stamp)
        // BE257은 GET과 동일 발급 재시도에서도 구버전을 409로 거절한다. 네트워크 오류와 달리
        // 이 확정 응답에서만 기존 실행을 교체하며 새 nonce·키로 최대 한 번 갱신한다.
        if (issued.isStaleRejection()) { renewOrShowStale(); return }
        var run = unwrap(issued, owner, stamp)
        check(KeyringSyncProtocol.valid(run, id))
        check(ticket.identity == null || ticket.identity == run.identity())
        // GET은 최초 발급 별칭의 서명을 돌려줄 수 있다. 전송용 서명은 같은 키/본문 POST에서만 받는다.
        if (!recoveringAck) {
            check(KeyringSyncProtocol.signed(run, requireNotNull(ticket.deviceNonce)))
            // 명시한 전체 설정이나 성향 요청에는 같은 형식의 응답만 허용한다.
            check(ticket.createBody?.payloadFormat?.let { it == run.payloadFormat } != false)
        }
        ticket = ticket.copy(identity = run.identity())
        store.save(owner, ticket); ensure(owner, stamp)
        // 기존 ON/OFF 실행은 409 대신 200/stale:true를 반환할 수 있다. 이미 구버전임이
        // 확정됐으므로 이전 BLE/ACK를 반복하지 않는다. 결과 불명의 네트워크 오류는 위에서 기록을 보존한다.
        if (run.stale) { renewOrShowStale(); return }
        // 최신 서버 실행의 저장·적용 확인까지 끝난 경우만 실물 버전과 해시를 비교해 전송을 건너뛴다.
        // 저장/적용 증명이 남은 실행은 같은 요청으로 복구한다. 소유 세대가 다른 보고는 최신으로 표시하지 않는다.
        if ((checkLatest || (readDeviceInfo != null && delivered[id] != delivery)) && connected() &&
            run.state == "ACKED" && ticket.complete && !ticket.needsAppliedConfirmation()) {
            val info = readDeviceInfo?.invoke()
            ensure(owner, stamp)
            if (info != null && info.serial == ticket.serial &&
                (run.ownershipEpoch == null || info.ownershipEpoch == run.ownershipEpoch) &&
                info.storedSettingsVersion == run.settingsVersion && info.storedPayloadHash == run.payloadHash) {
                delivered[id] = delivery
                showConfirmed(run, alreadyLatest = checkLatest)
            } else renewOrShowStale()
            return
        }
        if (!recoveringAck && unresolvedCreate &&
            (nonceBindings[id] != (binding to ticket.createKey) || !nonceFresh(ticket))) {
            // HTTP 결과는 확인됐지만 예전 보드 challenge는 새 연결에서 신뢰할 수 없다.
            renewOrShowStale(); return
        }
        if (run.state == "ACKED" && ticket.complete && ticket.needsAppliedConfirmation()) {
            finishApplied(owner, stamp, ticket, ::renewOrShowStale)
            return
        }
        if (ticket.complete && run.state == "ACKED") {
            if (connected() && delivered[id] != delivery && mayRenew) renewOrShowStale()
            else if (!connected() && delivered[id]?.second != delivery.second) show(id, SyncPhase.WaitingConnection)
            else showConfirmed(run)
            return
        }
        if (recoveringAck && run.state == "ACKED" && ticket.ack == null) {
            ticket = ticket.copy(complete = true)
            store.save(owner, ticket); ensure(owner, stamp)
            if (ticket.needsAppliedConfirmation()) {
                finishApplied(owner, stamp, ticket, ::renewOrShowStale); return
            }
            if (connected() && delivered[id] != delivery && mayRenew) renewOrShowStale()
            else showConfirmed(run)
            return
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
        val ackResponse = api.ack(id, run.syncRunId, requireNotNull(ticket.ackKey), requireNotNull(ticket.ack))
        ensure(owner, stamp)
        // 전송 중 서버 설정이 바뀌면 미완료 구버전 ACK는 409로 확정 거절된다.
        // 이때만 새 challenge/멱등키로 최신 설정을 발급한다. 오프라인·5xx 등 결과 불명은
        // 기존 ACK를 보존해 재시도하며, 같은 진행에서 갱신은 한 번으로 제한한다.
        if (ackResponse.isStaleRejection()) {
            renewOrShowStale(); return
        }
        run = unwrap(ackResponse, owner, stamp)
        check(KeyringSyncProtocol.valid(run, id) && run.identity() == ticket.identity)
        check(run.state == "ACKED")
        ticket = ticket.copy(complete = true)
        store.save(owner, ticket); ensure(owner, stamp)
        if (!run.stale && result != null) delivered[id] = delivery
        if (!run.stale && ticket.needsAppliedConfirmation()) {
            finishApplied(owner, stamp, ticket, ::renewOrShowStale)
            return
        }
        // 기존 ACK 응답 복구가 끝났거나 설정이 바뀌었다면, 그 다음에만 새 연결용 설정을 발급한다.
        if (run.stale || (result == null && connected() && delivered[id] != delivery && mayRenew)) {
            renewOrShowStale(); return
        }
        showConfirmed(run, result)
        // 서버가 실제로 가입 단계를 갱신했는지 재조회한다. FE에서 READY를 만들어 넣지 않는다.
        sessions.refreshOnboarding(); ensure(owner, stamp)
    }

    private fun awaitingSignup() = (sessions.state.value as? Session.SignedIn)?.let {
        it.status == OnboardingStatus.SYNC_PENDING && !it.isReady
    } == true

    // 서버는 가입 단계와 무관하게 현재 TSV3 버전의 적용 증명을 확인해야 기기 동기화를 반영한다.
    private fun SyncTicket.needsAppliedConfirmation() =
        identity?.payloadFormat == KeyringSyncProtocol.FULL_SETTINGS_FORMAT && !appliedOnboardingChecked

    /** 이미 저장된 실행에서 적용 증명만 이어간다. 증명과 HTTP 키가 저장된 뒤에는 보드 없이도 복구한다. */
    private suspend fun finishApplied(owner: Long, stamp: Long, original: SyncTicket, renew: suspend () -> Unit) {
        var ticket = original
        val id = ticket.deviceId
        val identity = requireNotNull(ticket.identity)
        val duringSignup = ticket.appliedDuringSignup || awaitingSignup()
        if (!ticket.appliedConfirmed) {
            if (ticket.appliedProof == null) {
                if (boardSerial != ticket.serial || transmit == null) { show(id, SyncPhase.ApplyWaitingConnection); return }
                show(id, SyncPhase.ApplyChecking)
                val read = readApplied ?: throw BleCommandException("SETTINGS_APPLIED_UNSUPPORTED")
                val proof = try { pollApplied(identity, read) } catch (failure: BleCommandException) {
                    if (failure.code != "SETTINGS_RUN_MISMATCH") throw failure
                    renew(); return
                }
                ensure(owner, stamp)
                check(KeyringSyncProtocol.validApplied(identity, proof))
                ticket = ticket.copy(appliedKey = Uuid.random().toString(), appliedProof = proof, appliedDuringSignup = duringSignup)
                store.save(owner, ticket); ensure(owner, stamp)
            }
            show(id, SyncPhase.ApplyConfirming)
            val response = api.applied(id, identity.syncRunId, requireNotNull(ticket.appliedKey), requireNotNull(ticket.appliedProof))
            ensure(owner, stamp)
            if (response.isStaleRejection()) { renew(); return }
            unwrap(response, owner, stamp)
            // GET 유실은 이미 성공한 /applied를 반복할 이유가 아니다. 상태 조회부터 복구한다.
            ticket = ticket.copy(appliedConfirmed = true)
            store.save(owner, ticket); ensure(owner, stamp)
        }
        show(id, SyncPhase.ApplyConfirmed)
        unwrap(sessions.refreshOnboarding(), owner, stamp)
        val status = (sessions.state.value as? Session.SignedIn)?.status
        // 적용 POST 성공 뒤 서버의 요구 버전이 바뀌면 가입 조회만 반복해서는 대기를 벗어나지 못한다.
        // 결과가 확정된 적용 증명은 다시 보내지 않고, 실행 조회로 구버전이 확인될 때만 기존 갱신 경로를 쓴다.
        if (status == OnboardingStatus.SYNC_PENDING) {
            val current = api.get(id, identity.syncRunId)
            ensure(owner, stamp)
            if (current.isStaleRejection()) { renew(); return }
            val run = unwrap(current, owner, stamp)
            check(KeyringSyncProtocol.valid(run, id) && run.identity() == identity)
            if (run.stale) renew()
            return
        }
        store.save(owner, ticket.copy(appliedOnboardingChecked = true)); ensure(owner, stamp)
        if (duringSignup && status == OnboardingStatus.READY) {
            // 첫 가입 완료에서만 새 설정을 발급한다. 이미 READY인 계정의 적용 확인은 재발급을 반복하지 않는다.
            delivered.remove(id)
            attempted.remove(id)
            drive()
        } else show(id, SyncPhase.Confirmed, confirmedTsv3Version = identity.settingsVersion, confirmedSyncRunId = identity.syncRunId)
    }

    /** 최초 1회와 대기 재조회 2회로 제한한다. 다른 오류나 취소는 즉시 기존 복구 경로로 보낸다. */
    private suspend fun pollApplied(identity: SyncIdentity, read: suspend (SyncIdentity) -> KeyringSyncAck): KeyringSyncAck {
        for (attempt in 1..3) {
            try { return read(identity) } catch (failure: BleCommandException) {
                if (failure.code != "SETTINGS_APPLY_PENDING" || attempt == 3) throw failure
                delay(1_000)
            }
        }
        error("Application proof polling exhausted")
    }
    private fun ensure(owner: Long, stamp: Long) {
        if (!active || stamp != generation || (sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
    }
    private fun nonceFresh(ticket: SyncTicket): Boolean = runCatching {
        val elapsed = now() - Instant.parse(requireNotNull(ticket.nonceReceivedAt))
        elapsed >= 0.seconds && elapsed < 55.seconds
    }.getOrDefault(false)
    /** 상태와 오류 코드가 모두 맞을 때만 실행 교체를 허용한다. 5xx·오프라인·다른409는 복구 자료를 유지한다. */
    private fun ApiResult<*>.isStaleRejection(): Boolean {
        val error = (this as? ApiResult.Fail)?.error as? AppError.Server
        return error?.status == 409 && error.code == "SYNC_VERSION_STALE"
    }
    private fun <T> unwrap(result: ApiResult<T>, owner: Long, stamp: Long): T {
        ensure(owner, stamp)
        return when (result) { is ApiResult.Ok -> result.value; is ApiResult.Fail -> throw SyncApiFailure(result.error) }
    }
    // 서버가 확인한 버전으로 STATUS를 대조하고, 실행 ID로 같은 동기화 완료에 대한 기록 전송을 한 번만 예약한다.
    private fun showConfirmed(run: KeyringSyncRun, result: KeyringStoredResult? = null, alreadyLatest: Boolean = false) {
        val version = run.settingsVersion.takeIf { run.payloadFormat == KeyringSyncProtocol.FULL_SETTINGS_FORMAT }
        show(run.deviceId, SyncPhase.Confirmed, applied = result?.applied, timeReady = result?.timeReady,
            confirmedTsv3Version = version, confirmedSyncRunId = run.syncRunId, alreadyLatest = alreadyLatest)
    }
    private fun show(id: Long, phase: SyncPhase, error: AppError? = null, applied: Boolean? = null, timeReady: Boolean? = null,
        confirmedTsv3Version: Long? = null, confirmedSyncRunId: String? = null, alreadyLatest: Boolean = false) {
        val progress = KeyringSyncProgress(id, phase, error, applied, timeReady, serials[id], confirmedTsv3Version, confirmedSyncRunId, alreadyLatest)
        mutable.update { it.copy(items = it.items.filterNot { item -> item.deviceId == id } + progress) }
    }
    override fun onCleared() { pause() }
}
private class SyncApiFailure(val error: AppError) : Exception()
