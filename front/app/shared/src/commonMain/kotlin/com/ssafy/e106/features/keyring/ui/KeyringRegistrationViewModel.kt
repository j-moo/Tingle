package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.PrefRead
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class RegistrationIssue { Storage, ExpiredChallenge, InvalidResponse, Device, NotInOwnedList, ResetPending }
data class LiveRegistrationState(val owner: Long? = null, val loaded: Boolean = false, val busy: Boolean = false,
    val pending: PendingKeyringRegistration? = null, val confirmed: OwnedKeyring? = null,
    val issue: RegistrationIssue? = null, val error: AppError? = null, val canRestart: Boolean = false)

/**
 * 등록 HTTP와 기기 통신을 조정한다. BLE 연결은 별도 모델 소유이며, 이 모델은 응답 유실 복구 기록을 관리한다.
 * 화면 중지/회전은 작업을 취소하지만 이미 저장한 요청은 지우지 않는다. 복귀 후 사용자가 같은 요청을 재시도한다.
 */
@OptIn(ExperimentalUuidApi::class)
class KeyringRegistrationViewModel(private val api: KeyringRegistrationRepository,
    private val owned: OwnedKeyringRepository, private val store: KeyringRegistrationStore,
    private val sessions: SessionStore, private val syncStore: KeyringSyncStore,
    private val resets: KeyringResetStore,
    private val now: () -> Instant = { Clock.System.now() }) : ViewModel() {
    private val mutable = MutableStateFlow(LiveRegistrationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessions.state.map { (it as? Session.SignedIn)?.userId }.distinctUntilChanged().collect { owner ->
            pause(); mutable.value = LiveRegistrationState(owner = owner)
            restore()
        }
    } }

    /** 계정이 같아도 앱 재진입 시 durable 기록부터 읽는다. 복구가 안 되면 새 POST를 허용하지 않는다. */
    fun restore() {
        if (mutable.value.busy || mutable.value.loaded) return
        runAction { operation ->
            when (val saved = store.read(operation.owner)) {
                is PrefRead.Found -> { operation.ensure(); mutable.update { it.copy(loaded = true, pending = saved.value,
                    canRestart = saved.value != null && saved.value.body == null) } }
                PrefRead.OtherUser -> throw CancellationException()
                else -> throw RegistrationProblem(RegistrationIssue.Storage)
            }
        }
    }

    /** 호출부는 사용자 귀속 확인 후에만 호출한다. 이미 미확인 요청이 있으면 새 키로 덮어쓰지 않는다. */
    fun start(serial: String, proof: suspend (KeyringChallenge) -> RegisterKeyringBody) {
        if (!mutable.value.loaded || mutable.value.pending != null || mutable.value.confirmed != null || !KeyringRegistrationProtocol.serial(serial)) return
        runAction { operation ->
            operation.requireResetFinished(serial)
            // 한 회원의 여러 키링 등록을 허용한다. 기존 소유 목록이 있다는 이유로 막거나
            // 기존 관계를 해제하지 않으며, 이번 기기의 등록 자격은 서버가 검증한다.
            val pending = PendingKeyringRegistration(operation.owner, serial, Uuid.random().toString(), Uuid.random().toString())
            operation.save(pending)
            prepareAndSend(operation, pending, proof)
        }
    }

    /** 증명이 저장된 요청은 BLE 재연결 없이 같은 키·본문으로 복구한다. 만료 후에도 서버 replay를 먼저 확인한다. */
    fun retry(proof: suspend (KeyringChallenge) -> RegisterKeyringBody) {
        val pending = mutable.value.pending ?: return
        if (!mutable.value.loaded) return
        runAction { operation ->
            if (pending.owner != operation.owner) throw CancellationException()
            operation.requireResetFinished(pending.serial)
            if (pending.receipt != null) checkOwned(operation, pending)
            else if (pending.body != null) send(operation, pending)
            else {
                prepareAndSend(operation, pending, proof)
            }
        }
    }

    private suspend fun prepareAndSend(operation: Operation, pending: PendingKeyringRegistration,
        proof: suspend (KeyringChallenge) -> RegisterKeyringBody) {
        val challenge = operation.unwrap(api.challenge(pending.challengeKey))
        if (!KeyringRegistrationProtocol.validChallenge(challenge)) throw RegistrationProblem(RegistrationIssue.InvalidResponse)
        if (Instant.parse(challenge.expiresAt) <= now()) throw RegistrationProblem(RegistrationIssue.ExpiredChallenge)
        val body = try { proof(challenge) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw RegistrationProblem(RegistrationIssue.Device) }
        operation.ensure()
        // BLE와 복구 저장소의 경계에서도 서버 challenge의 버전을 유지한다. v2를 v1 증명으로 대체하지 않는다.
        if (body.deviceSerial != pending.serial || body.challengeId != challenge.challengeId ||
            body.proof.protocolVersion != challenge.proofProtocolVersion || !KeyringRegistrationProtocol.validBody(body))
            throw RegistrationProblem(RegistrationIssue.InvalidResponse)
        if (Instant.parse(challenge.expiresAt) <= now()) throw RegistrationProblem(RegistrationIssue.ExpiredChallenge)
        // 등록 POST보다 먼저 디스크에 본문과 키를 기록한다. 이 지점부터는 새 challenge를 발급하지 않는다.
        val ready = pending.copy(body = body)
        operation.save(ready)
        send(operation, ready)
    }

    private suspend fun send(operation: Operation, pending: PendingKeyringRegistration) {
        operation.requireResetFinished(pending.serial)
        val response = api.register(pending.registerKey, requireNotNull(pending.body))
        operation.ensure()
        if (response is ApiResult.Fail) {
            // 입력/증명·기기 귀속의 명시적 거절은 사용자가 기록을 정리하고 다른 보드를 고를 수 있다.
            // 모든 409를 거절로 취급하지 않는다. 멱등 충돌·5xx·통신 단절은 기존 요청을 보존한다.
            val code = (response.error as? AppError.Server)?.code
            mutable.update { it.copy(canRestart = code in setOf("INVALID_REQUEST", "INVALID_DEVICE_PROOF", "DEVICE_ALREADY_BOUND")) }
        }
        val receipt = operation.unwrap(response)
        if (!KeyringRegistrationProtocol.validReceipt(receipt)) throw RegistrationProblem(RegistrationIssue.InvalidResponse)
        val accepted = pending.copy(receipt = receipt)
        operation.save(accepted)
        checkOwned(operation, accepted)
    }

    private suspend fun checkOwned(operation: Operation, pending: PendingKeyringRegistration) {
        operation.requireResetFinished(pending.serial)
        val receipt = requireNotNull(pending.receipt)
        val match = operation.unwrap(owned.list()).find { it.deviceId == receipt.deviceId }
            ?: throw RegistrationProblem(RegistrationIssue.NotInOwnedList)
        // 등록 복구 기록을 지우기 전에 동기화 대상을 보존한다. 프로필/설문을 마친 후에도
        // 같은 기기 번호로 자동 재개하며, 저장 실패 시 등록 API를 새로 호출하지 않는다.
        syncStore.rememberTarget(operation.owner, receipt.deviceId, pending.serial)
        operation.ensure()
        operation.save(null)
        operation.ensure()
        mutable.update { it.copy(confirmed = match, pending = null, canRestart = false) }
        operation.unwrap(sessions.refreshOnboarding())
    }

    /** 서버 요청 전 준비 기록, 또는 서버가 확실히 거절한 요청만 사용자 선택으로 지울 수 있다. */
    fun restart() {
        if (!mutable.value.loaded || !mutable.value.canRestart || mutable.value.pending?.receipt != null) return
        runAction { operation -> operation.save(null) }
    }

    fun continueOnboarding() {
        if (mutable.value.confirmed == null) return
        runAction { operation -> operation.unwrap(sessions.refreshOnboarding()) }
    }

    private inner class Operation(val owner: Long, val stamp: Long) {
        /** 초기화 완료 응답 유실 상태에서 재등록하면 옛 대상 정리가 새 등록까지 지울 수 있다.
         * 같은 실물의 기존 실행을 먼저 복구하게 한다. 저장소 손상도 등록 허용으로 바꾸지 않는다. */
        suspend fun requireResetFinished(serial: String) {
            val entries = try { resets.read(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw RegistrationProblem(RegistrationIssue.Storage) }
            ensure()
            if (entries.any { it.serial == serial && it.run?.state != "COMPLETED" })
                throw RegistrationProblem(RegistrationIssue.ResetPending)
        }
        fun ensure() {
            if (stamp != generation || (sessions.state.value as? Session.SignedIn)?.userId != owner) throw CancellationException()
        }
        fun <T> unwrap(result: ApiResult<T>): T {
            ensure()
            return when (result) { is ApiResult.Ok -> result.value; is ApiResult.Fail -> throw RegistrationApiProblem(result.error) }
        }
        suspend fun save(pending: PendingKeyringRegistration?) {
            ensure()
            val saved = store.save(owner, pending)
            ensure()
            if (!saved) throw RegistrationProblem(RegistrationIssue.Storage)
            mutable.update { it.copy(pending = pending, canRestart = pending != null && pending.body == null) }
        }
    }

    private fun runAction(action: suspend (Operation) -> Unit) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.busy) return
        val operation = Operation(owner, ++generation)
        mutable.update { it.copy(busy = true, issue = null, error = null) }
        job = viewModelScope.launch {
            try { operation.ensure(); action(operation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                operation.ensure()
                mutable.update { it.copy(issue = (failure as? RegistrationProblem)?.issue ?: if (failure is RegistrationApiProblem) null else RegistrationIssue.InvalidResponse,
                    error = (failure as? RegistrationApiProblem)?.error) }
            } finally { if (operation.stamp == generation) mutable.update { it.copy(busy = false) } }
        }
    }

    fun pause() {
        generation++; job?.cancel(); job = null
        // Activity가 같은 모델을 재사용해도 이전 성공 안내가 다음 기기 등록을 막지 않게 한다.
        // 완료 안내만 화면 수명에 맞춰 지운다. 미확인 요청은 디스크에 보존해 복귀 시 그대로 복구한다.
        mutable.update { it.copy(busy = false, loaded = false, confirmed = null) }
    }
    override fun onCleared() { pause() }
}
private class RegistrationProblem(val issue: RegistrationIssue) : Exception()
private class RegistrationApiProblem(val error: AppError) : Exception()
