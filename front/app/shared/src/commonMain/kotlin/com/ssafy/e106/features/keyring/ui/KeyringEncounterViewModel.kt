package com.ssafy.e106.features.keyring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.getOrElse
import com.ssafy.e106.features.keyring.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class EncounterTransferState { Idle, Sending, Empty, LegacyOnly, ConfirmedStored, ReviewStored, Stored, NoReceipt, Failed }

/** 한 번에 기록 한 건을 처리한다. 복구 접수증은 새 기록을 읽기 전에 먼저 확인한다. */
internal class KeyringEncounterViewModel(
    private val sessions: SessionStore,
    private val syncStore: KeyringSyncStore,
    private val authorizer: KeyringOwnerAuthorizer,
    private val api: KeyringEncounterRepository,
    private val journal: KeyringEncounterJournal,
) : ViewModel() {
    private val mutable = MutableStateFlow(EncounterTransferState.Idle)
    val state = mutable.asStateFlow()
    private var work: Job? = null

    fun send(serial: String, ble: BleProbeViewModel) {
        if (work?.isActive == true) return
        val owner = (sessions.state.value as? Session.SignedIn)?.userId ?: return
        mutable.value = EncounterTransferState.Sending
        work = viewModelScope.launch {
            fun ensureOwner() {
                coroutineContext.ensureActive()
                check((sessions.state.value as? Session.SignedIn)?.userId == owner)
            }
            try {
                val ticket = syncStore.read(owner).single { it.serial == serial && it.complete }
                ensureOwner()
                val identity = requireNotNull(ticket.identity)
                val epoch = requireNotNull(identity.ownershipEpoch)
                val pending = journal.read(owner, ticket.deviceId)
                ensureOwner()
                if (pending != null) {
                    if (pending.ownershipEpoch < epoch) {
                        // 새 소유 세대의 동기화를 이미 확인했다. 이전 소유의 접수증은 재전송할 수 없다.
                        journal.remove(owner, pending)
                        ensureOwner()
                    } else {
                        finish(owner, identity, serial, ble, pending, ::ensureOwner)
                        mutable.value = EncounterTransferState.Stored
                        return@launch
                    }
                }

                val (scan, body) = ble.withOwnerSession(serial, identity, KeyringOwnerPermission.Read, authorizer) { exchange ->
                    val scan = KeyringEncounterProtocol.findSendable(exchange)
                    val body = scan.header?.let { header ->
                        check(header.deviceId == identity.deviceId && header.ownerGeneration == identity.ownerGeneration &&
                            header.ownershipEpoch == identity.ownershipEpoch)
                        KeyringEncounterProtocol.uploadBody(header, exchange)
                    }
                    scan to body
                }
                ensureOwner()
                val header = scan.header
                if (header == null || body == null) {
                    mutable.value = if (scan.legacyCount > 0) EncounterTransferState.LegacyOnly else EncounterTransferState.Empty
                    return@launch
                }
                val receipt = api.upload(body).getOrElse { error("Encounter upload unavailable") }
                ensureOwner()
                val proof = KeyringEncounterProtocol.proofFor(header, receipt)
                if (proof == null) {
                    mutable.value = EncounterTransferState.NoReceipt
                    return@launch
                }
                journal.save(owner, proof)
                ensureOwner()
                finish(owner, identity, serial, ble, proof, ::ensureOwner)
                mutable.value = if (header.kind == "REVIEW") EncounterTransferState.ReviewStored else EncounterTransferState.ConfirmedStored
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                mutable.value = EncounterTransferState.Failed
            }
        }
    }

    private suspend fun finish(owner: Long, identity: SyncIdentity, serial: String, ble: BleProbeViewModel,
        proof: KeyringEncounterProof, ensureOwner: () -> Unit) {
        check(proof.deviceId == identity.deviceId && proof.ownerGeneration == identity.ownerGeneration &&
            proof.ownershipEpoch == identity.ownershipEpoch)
        // 서버가 서명한 접수증만 ACK 권한으로 전송한다. 키링이 완료를 돌려줄 때까지 저널을 보존한다.
        ble.withOwnerSession(serial, identity, KeyringOwnerPermission.Ack, authorizer) { exchange ->
            KeyringEncounterProtocol.acknowledge(proof, exchange)
        }
        ensureOwner()
        journal.remove(owner, proof)
        ensureOwner()
    }

    /** 화면 이탈·BLE 연결 변경은 진행만 중단한다. 보호 저널은 다음 시도에서 이어간다. */
    fun cancel() {
        work?.cancel()
        mutable.value = EncounterTransferState.Idle
    }
}
