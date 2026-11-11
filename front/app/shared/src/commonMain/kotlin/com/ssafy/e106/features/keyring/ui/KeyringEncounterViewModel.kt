package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.getOrElse
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class EncounterTransferState {
    Idle, Sending, Empty, LegacyOnly, ConfirmedStored, ReviewStored, Stored, Retryable, PolicyWaiting, NoReceipt, Failed,
}

/** 전경 호스트와 수동 버튼이 같은 작업을 쓴다. 화면 이동 중에도 동일 연결의 전송을 이어간다. */
internal class KeyringEncounterViewModel(
    private val sessions: SessionStore,
    private val syncStore: KeyringSyncStore,
    private val authorizer: KeyringOwnerAuthorizer,
    private val api: KeyringEncounterRepository,
    private val journal: KeyringEncounterJournal,
) : ViewModel() {
    data class Target(val owner: Long, val serial: String, val generation: Long)

    private val mutable = MutableStateFlow(EncounterTransferState.Idle)
    val state = mutable.asStateFlow()
    private var target: Target? = null
    private var work: Job? = null
    private var syncBusy = false
    private var confirmedRun: String? = null
    private var attempted = false
    private var attemptedRun: String? = null
    // 접수할 수 없는 원본은 다음 자동 신호에서 반복 제출하지 않는다. 연결이 바뀌거나 사용자가 재시도하면 다시 확인한다.
    private val held = mutableMapOf<String, EncounterTransferState>()

    fun update(next: Target?, syncing: Boolean, confirmed: String?, ble: BleProbeViewModel) {
        if (next != target) {
            cancel()
            target = next
        }
        syncBusy = syncing
        if (confirmed != null) confirmedRun = confirmed
        start(ble)
    }

    fun retry(ble: BleProbeViewModel) {
        if (syncBusy || work?.isActive == true || target == null) return
        held.clear()
        attempted = false
        start(ble)
    }

    private fun start(ble: BleProbeViewModel) {
        val current = target ?: return
        if (syncBusy || work?.isActive == true || (attempted && attemptedRun == confirmedRun)) return
        attempted = true
        attemptedRun = confirmedRun
        mutable.value = EncounterTransferState.Sending
        // 작업이 즉시 완료되어도 finally에서 현재 작업을 정확히 해제할 수 있게 먼저 저장한다.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            fun ensureCurrent() {
                coroutineContext.ensureActive()
                val link = ble.state.value
                if (target != current || !link.connected || link.connectionGeneration != current.generation ||
                    link.info?.serial != current.serial || (sessions.state.value as? Session.SignedIn)?.userId != current.owner
                ) throw CancellationException()
            }
            try {
                ensureCurrent()
                val ticket = syncStore.read(current.owner).singleOrNull { it.serial == current.serial && it.complete }
                ensureCurrent()
                val identity = ticket?.identity
                mutable.value = if (identity?.ownershipEpoch == null) EncounterTransferState.Idle
                else transfer(current, identity, ble, ::ensureCurrent)
            } catch (cancelled: CancellationException) {
                if (coroutineContext[Job] === work) mutable.value = EncounterTransferState.Idle
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                if (coroutineContext[Job] === work) mutable.value = EncounterTransferState.Failed
            } finally {
                // 같은 연결에서 새 설정 확인이 끝났다면 지금 작업을 마친 뒤 한 번만 이어서 확인한다.
                if (coroutineContext[Job] === work && coroutineContext[Job]?.isActive == true) {
                    work = null
                    start(ble)
                }
            }
        }
        work = job
        job.start()
    }

    private suspend fun transfer(current: Target, identity: SyncIdentity, ble: BleProbeViewModel,
        ensureCurrent: () -> Unit): EncounterTransferState {
        val owner = current.owner
        val serial = current.serial
        val epoch = requireNotNull(identity.ownershipEpoch)
        val pending = journal.read(owner, identity.deviceId)
        ensureCurrent()
        var stored = EncounterTransferState.Empty
        if (pending != null) {
            if (pending.ownershipEpoch < epoch) {
                // 이전 소유 세대의 접수증을 새 소유 세대로 보내지 않는다.
                journal.remove(owner, pending)
            } else {
                finish(owner, identity, serial, ble, pending, ensureCurrent)
                stored = EncounterTransferState.Stored
            }
            ensureCurrent()
        }

        val scan = ble.withOwnerSession(serial, identity, KeyringOwnerPermission.Read, authorizer) {
            KeyringEncounterProtocol.scanSendable(it)
        }
        ensureCurrent()
        var deferred: EncounterTransferState? = null
        for (header in scan.headers) {
            ensureCurrent()
            check(header.deviceId == identity.deviceId && header.ownerGeneration == identity.ownerGeneration &&
                header.ownershipEpoch == epoch)
            val hold = held[header.envelopeHash]
            if (hold != null) {
                deferred = deferred.prefer(hold)
                continue
            }
            val body = ble.withOwnerSession(serial, identity, KeyringOwnerPermission.Read, authorizer) { exchange ->
                KeyringEncounterProtocol.uploadBody(header, exchange)
            }
            ensureCurrent()
            val response = api.upload(body).getOrElse { error("Encounter upload unavailable") }
            ensureCurrent()
            val result = KeyringEncounterProtocol.resultFor(header, response)
            val proof = KeyringEncounterProtocol.proofFor(header, result)
            if (proof == null) {
                val reason = when {
                    result.retryable -> EncounterTransferState.Retryable
                    result.errorCode == "POLICY_UNRESOLVED" -> EncounterTransferState.PolicyWaiting
                    else -> EncounterTransferState.NoReceipt
                }
                if (!result.retryable) held[header.envelopeHash] = reason
                deferred = deferred.prefer(reason)
                // 접수증 없이 원본을 정리할 수 없다. 다음 순번을 계속 처리한다.
                continue
            }
            journal.save(owner, proof)
            ensureCurrent()
            finish(owner, identity, serial, ble, proof, ensureCurrent)
            if (header.kind == "CONFIRMED") stored = EncounterTransferState.ConfirmedStored
            else if (stored == EncounterTransferState.Empty) stored = EncounterTransferState.ReviewStored
        }
        return deferred ?: if (stored == EncounterTransferState.Empty && scan.legacyCount > 0) {
            EncounterTransferState.LegacyOnly
        } else stored
    }

    private fun EncounterTransferState?.prefer(other: EncounterTransferState) = when {
        this == EncounterTransferState.Retryable || other == EncounterTransferState.Retryable -> EncounterTransferState.Retryable
        this == EncounterTransferState.PolicyWaiting || other == EncounterTransferState.PolicyWaiting -> EncounterTransferState.PolicyWaiting
        else -> other
    }

    private suspend fun finish(owner: Long, identity: SyncIdentity, serial: String, ble: BleProbeViewModel,
        proof: KeyringEncounterProof, ensureCurrent: () -> Unit) {
        check(proof.deviceId == identity.deviceId && proof.ownerGeneration == identity.ownerGeneration &&
            proof.ownershipEpoch == identity.ownershipEpoch)
        // 키링이 정리를 확인할 때까지 서명 접수증을 보호 저장소에 보존한다.
        ble.withOwnerSession(serial, identity, KeyringOwnerPermission.Ack, authorizer) { exchange ->
            KeyringEncounterProtocol.acknowledge(proof, exchange)
        }
        ensureCurrent()
        journal.remove(owner, proof)
        ensureCurrent()
    }

    /** 전경 종료·초기화 진입·연결 변경에서 진행만 취소한다. 보호 저널로 다음 연결에서 복구한다. */
    fun cancel() {
        target = null
        work?.cancel()
        work = null
        attempted = false
        attemptedRun = null
        confirmedRun = null
        held.clear()
        mutable.value = EncounterTransferState.Idle
    }
}
