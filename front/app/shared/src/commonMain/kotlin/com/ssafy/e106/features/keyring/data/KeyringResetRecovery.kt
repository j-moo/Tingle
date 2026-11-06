package com.ssafy.e106.features.keyring.data

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 실제 BLE 연결이 구현할 경계다. 반환 전에는 KeyringResetProtocol로 요청/기기/실행을 대조해야 한다. */
interface ResetDevicePort {
    suspend fun challenge(serial: String): ResetDeviceChallenge
    suspend fun result(ticket: ResetTicket): ResetAckBody?
    suspend fun send(ticket: ResetTicket): ResetAckBody?
}
enum class ResetRecoveryPhase { WaitingDevice, DevicePending, ServerPending, Completed }
data class ResetRecoveryResult(val phase: ResetRecoveryPhase, val ticket: ResetTicket) {
    override fun toString() = "ResetRecoveryResult($phase, REDACTED)"
}
class ResetApiException(val error: AppError) : Exception("Reset API failed")

/**
 * 한 번의 사용자 조작에 한 단계 복구를 수행하며 자동 무한 재시도를 하지 않는다.
 * 호출 코루틴의 취소/계정 변경은 후속 I/O를 중단한다. 이미 저장한 요청은 앱 재시작 후 같은 계정에서 이어간다.
 * 건수 미확인은 0으로 바꾸지 않는다. 별도 삭제 동의가 있을 때만 DISCARD_WITH_CONSENT로 요청한다.
 */
class KeyringResetRecovery(private val api: KeyringResetRepository, private val store: KeyringResetStore,
    private val owned: OwnedKeyringRepository, private val sessions: SessionStore, private val syncStore: KeyringSyncStore) {
    private val lock = Mutex()
    /** 화면 진입은 저장 기록과 GET만 조회한다. 실행 ID가 없는 미확인 POST도 사용자 재시도 전에는 보내지 않는다. */
    suspend fun inspect(owner: Long, deviceId: Long): ResetTicket? = lock.withLock {
        ensure(owner)
        val ticket = store.read(owner).singleOrNull { it.deviceId == deviceId }; ensure(owner)
        if (ticket?.run == null) ticket
        else accept(owner, ticket, unwrap(api.get(deviceId, ticket.run.resetRunId), owner))
    }
    @OptIn(ExperimentalUuidApi::class)
    suspend fun start(owner: Long, deviceId: Long, serial: String, accepted: Boolean,
        pendingRecordCount: Long?, discardAccepted: Boolean, device: ResetDevicePort): ResetRecoveryResult = lock.withLock {
        ensure(owner)
        // 11/5 확정 정책: 조회 미지원인 null도 별도 삭제 동의가 있어야 한다. 잘못된 음수는 계속 거부한다.
        require(accepted && (pendingRecordCount == null || pendingRecordCount >= 0) &&
            (pendingRecordCount == 0L || discardAccepted))
        check(store.read(owner).none { it.deviceId == deviceId }); ensure(owner)
        check(unwrap(owned.list(), owner).any { it.deviceId == deviceId })
        val challenge = device.challenge(serial); ensure(owner)
        require(KeyringResetProtocol.challenge(challenge) && challenge.deviceSerial == serial)
        val ticket = ResetTicket(deviceId, serial, Uuid.random().toString(),
            ResetStartBody(pendingRecordCount != 0L && discardAccepted, challenge))
        store.insert(owner, ticket); ensure(owner)
        // 최초 POST보다 본문·키를 먼저 저장한다. 응답 유실을 새 초기화 실행으로 대체하지 않는다.
        val saved = accept(owner, ticket, unwrap(api.start(deviceId, ticket.requestKey, ticket.start), owner))
        continueRun(owner, saved, device, firstSend = true)
    }

    suspend fun resume(owner: Long, deviceId: Long, device: ResetDevicePort? = null): ResetRecoveryResult = lock.withLock {
        ensure(owner)
        val ticket = store.read(owner).single { it.deviceId == deviceId }; ensure(owner)
        // 실행 ID가 없으면 반드시 최초 키·본문으로 POST replay한다. 원 계정은 소유 해제 후에도 GET이 가능하다.
        val run = unwrap(ticket.run?.let { api.get(deviceId, it.resetRunId) }
            ?: api.start(deviceId, ticket.requestKey, ticket.start), owner)
        continueRun(owner, accept(owner, ticket, run), device, firstSend = false)
    }

    private suspend fun accept(owner: Long, ticket: ResetTicket, run: KeyringResetRun): ResetTicket {
        ensure(owner)
        check(KeyringResetProtocol.valid(run) && run.deviceId == ticket.deviceId &&
            run.discardUnsentConfirmed == ticket.start.discardUnsentConfirmed &&
            run.fromOwnershipEpoch == ticket.start.deviceChallenge.ownershipEpoch &&
            (ticket.run == null || run.identity() == ticket.run.identity()))
        val saved = ticket.copy(run = run)
        // 완료 기록으로 자동 동기화 차단이 풀리기 전에 옛 소유자의 전송 대상을 제거한다.
        if (run.state == "COMPLETED" && ticket.run?.state != "COMPLETED") {
            syncStore.afterReset(owner, run, ticket.serial); ensure(owner)
        }
        store.save(owner, saved); ensure(owner)
        return saved
    }

    private suspend fun continueRun(owner: Long, initial: ResetTicket, device: ResetDevicePort?, firstSend: Boolean): ResetRecoveryResult {
        var ticket = initial
        if (ticket.run!!.state == "COMPLETED") return ResetRecoveryResult(ResetRecoveryPhase.Completed, ticket)
        if (ticket.ack != null) return acknowledge(owner, ticket)
        if (device == null) return ResetRecoveryResult(ResetRecoveryPhase.WaitingDevice, ticket)
        var proof: ResetAckBody?
        if (!firstSend) {
            // 재전송보다 결과 조회가 먼저다. RESET_NOT_FOUND만 명령 재발급 근거로 사용하고 timeout은 그대로 남긴다.
            try {
                proof = device.result(ticket); ensure(owner)
                if (proof == null) return ResetRecoveryResult(ResetRecoveryPhase.DevicePending, ticket)
                return saveProofAndAck(owner, ticket, proof)
            } catch (failure: BleCommandException) {
                ensure(owner)
                if (failure.code != "RESET_NOT_FOUND") throw failure
            }
            val challenge = device.challenge(ticket.serial); ensure(owner)
            val previousRun = requireNotNull(ticket.run)
            check(KeyringResetProtocol.challenge(challenge) && challenge.deviceSerial == ticket.serial &&
                challenge.keyId == ticket.start.deviceChallenge.keyId &&
                challenge.ownershipEpoch == previousRun.fromOwnershipEpoch &&
                (challenge.localOwnerGeneration == "NONE" || challenge.localOwnerGeneration == previousRun.ownerGeneration))
            // 재발급은 같은 resetRunId에 새 challenge를 결속한다. 삭제 동의·소유 순번은 바꾸지 않는다.
            val run = unwrap(api.command(ticket.deviceId, previousRun.resetRunId, ResetCommandBody(challenge)), owner)
            ticket = accept(owner, ticket, run)
            if (run.state == "COMPLETED") return ResetRecoveryResult(ResetRecoveryPhase.Completed, ticket)
        }
        // 기기가 데이터를 지웠지만 응답을 못 받을 수 있다. 전송 전에 그 가능성을 내구 기록에 남긴다.
        ticket = ticket.copy(commandAttempted = true)
        store.save(owner, ticket); ensure(owner)
        proof = device.send(ticket); ensure(owner)
        return if (proof == null) ResetRecoveryResult(ResetRecoveryPhase.DevicePending, ticket)
            else saveProofAndAck(owner, ticket, proof)
    }

    private suspend fun saveProofAndAck(owner: Long, ticket: ResetTicket, proof: ResetAckBody): ResetRecoveryResult {
        check(KeyringResetProtocol.signature(proof.proof))
        val saved = ticket.copy(ack = proof)
        store.save(owner, saved); ensure(owner)
        return acknowledge(owner, saved)
    }
    private suspend fun acknowledge(owner: Long, ticket: ResetTicket): ResetRecoveryResult {
        val response = api.ack(ticket.deviceId, ticket.run!!.resetRunId, requireNotNull(ticket.ack)); ensure(owner)
        if (response is ApiResult.Fail) throw ResetApiException(response.error)
        val saved = accept(owner, ticket, (response as ApiResult.Ok).value)
        // COMPLETED는 BE가 해당 실행의 기기 증명을 검증하고 소유 해제까지 완료한 결과다.
        return ResetRecoveryResult(if (saved.run!!.state == "COMPLETED") ResetRecoveryPhase.Completed else ResetRecoveryPhase.ServerPending, saved)
    }
    private suspend fun ensure(owner: Long) {
        currentCoroutineContext().ensureActive()
        if ((sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
    }
    private suspend fun <T> unwrap(result: ApiResult<T>, owner: Long): T {
        ensure(owner)
        return when (result) { is ApiResult.Ok -> result.value; is ApiResult.Fail -> throw ResetApiException(result.error) }
    }
}
