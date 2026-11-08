package com.ssafy.e106.features.account.ui

import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.account.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi
import kotlin.time.Instant

internal data class WithdrawalPresentation(val owner: Long? = null, val visible: Boolean = false,
    val ui: WithdrawalState = WithdrawalState(), val lastAccount: Long? = null, val keyringOwner: Long? = null)

/**
 * 앱 수명의 조정자. 화면 이탈/회전/로그아웃으로 실행 중인 HTTP 작업이 취소되지 않는다.
 * 프로세스 종료는 보호 저널로 복구하며, execute는 사용자가 최종 동의한 호출에서만 한다.
 * 새 계정은 이전 계정 응답을 표시하지 않는다. 영수증은 이 클래스와 저널 내부에만 머문다.
 */
internal class WithdrawalCoordinator(private val api: WithdrawalRepository, private val journal: WithdrawalJournal,
    private val sessions: SessionStore, private val sessionEnd: WithdrawalSessionEnd, private val scope: CoroutineScope,
    private val requestKey: () -> String = { newWithdrawalKey() }) {
    private val mutable = MutableStateFlow(WithdrawalPresentation())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var ticket: WithdrawalTicket? = null
    private var handle: WithdrawalSessionHandle? = null
    private var lastUser: Long? = null

    init {
        scope.launch {
            sessions.state.collect { session ->
                if (session == Session.Restoring) return@collect
                try {
                    val signedInOwner = (session as? Session.SignedIn)?.userId
                    if (signedInOwner != null) { lastUser = signedInOwner; journal.markSession(signedInOwner) }
                    else if (lastUser == null) lastUser = journal.lastOwner()
                    mutable.update { it.copy(lastAccount = lastUser,
                        keyringOwner = it.keyringOwner?.takeIf { owner -> owner == signedInOwner }) }
                } catch (_: Exception) { unknown(); return@collect }
                // 로그인 복원이 끝난 뒤에만 복구한다. signedIn이 바뀌어도 기존 작업 자체를 취소하지 않는다.
                if (session != Session.Restoring && !mutable.value.visible && work?.isActive != true) {
                    val owner = (session as? Session.SignedIn)?.userId ?: lastUser
                    try {
                        // 이전 버전에서 닫기로 autoResume=false가 된 미확인 실행도 회수한다.
                        // 사용자가 확인하고 닫은 COMPLETED 기록만 자동 재표시하지 않는다.
                        val pending = journal.all().lastOrNull { (it.attempted || it.cancelRequested || it.autoResume) && it.owner == owner &&
                            (it.autoResume || it.cancelRequested || it.operation?.status != "COMPLETED") }
                        if (pending != null) open(pending.owner, recoveryOnly = true)
                    } catch (_: Exception) {
                        // 손상된 저널을 지우지 않는다. 메뉴 진입 시 미확인 상태로 다시 안내한다.
                    }
                }
            }
        }
    }

    fun open(owner: Long, recoveryOnly: Boolean = false) = launch {
        mutable.update { it.copy(owner = owner, visible = true, ui = WithdrawalState(), keyringOwner = null) }
        handle = null
        ticket = journal.all().singleOrNull { it.owner == owner }
        if (ticket != null) recover()
        else if (!recoveryOnly && currentOwner() == owner) loadEligibility(owner)
        else unknown()
    }
    fun accepted(value: Boolean) { if (work?.isActive != true) mutable.update { it.copy(ui = it.ui.checkAccepted(value)) } }
    fun reload() = launch {
        if (ticket != null) recover() else mutable.value.owner?.let { if (currentOwner() == it) loadEligibility(it) else unknown() }
    }
    fun prepare() = launch {
        val owner = mutable.value.owner ?: return@launch
        if (!mutable.value.ui.canPrepare || currentOwner() != owner) return@launch
        check(journal.all().none { it.owner == owner })
        val pending = WithdrawalTicket(owner, requestKey())
        // 서버에 첫 POST를 보내기 전에 키를 디스크에 확정한다. 실패하면 네트워크를 시작하지 않는다.
        journal.save(pending); ticket = pending
        mutable.update { it.copy(ui = it.ui.preparing()) }
        prepareSameRequest(pending)
    }
    fun execute() = launch {
        val t = ticket ?: return@launch
        if (!mutable.value.ui.canExecute || currentOwner() != t.owner) return@launch
        handle = sessionEnd.capture(t.owner) ?: return@launch unknown()
        mutable.update { it.copy(ui = it.ui.executing()) }
        // 현재 로그인 세션에 준비를 다시 귀속시킨다. 기존 실행 ID가 바뀌면 새 실행을 자동 진행하지 않는다.
        val renewed = api.prepare(t.owner, t.key)
        if (renewed !is ApiResult.Ok ||
            WithdrawalState().prepared(renewed.value.operation).stage != WithdrawalStage.Confirm) return@launch recover()
        if (sessionEnd.capture(t.owner)?.generation != handle?.generation) return@launch recover()
        if (renewed.value.operation.withdrawalId != t.operation?.withdrawalId) {
            // 만료 후 재발급은 이전 동의로 실행하지 않는다. 실행 전 준비였을 때만 새 준비를 저장하고 동의를 다시 받는다.
            if (!t.attempted) adoptExpiredPreparation(t, renewed.value) else recover()
            return@launch
        }
        val attempted = t.copy(operation = renewed.value.operation, receipt = renewed.value.receiptToken,
            attempted = true, autoResume = true)
        journal.save(attempted); ticket = attempted
        // 저장 중 다른 로그인으로 바뀌었으면 새 계정 토큰으로 이전 계정의 실행을 보내지 않는다.
        if (currentOwner() != t.owner || sessionEnd.capture(t.owner)?.generation != handle?.generation) return@launch unknown()
        when (val result = api.execute(t.owner, attempted.operation!!.withdrawalId, attempted.receipt!!)) {
            is ApiResult.Ok -> applyResult(result.value)
            is ApiResult.Fail -> recover() // 401/통신 실패도 무인증 영수증 조회로 먼저 확인한다.
        }
    }
    fun cancel() = launch {
        val t = ticket ?: return@launch
        if (currentOwner() != t.owner || t.attempted) return@launch recover()
        if (t.operation == null) return@launch recover()
        // 취소도 첫 HTTP 전에 의도를 확정한다. 응답이 없어도 새 준비를 발급하지 않는다.
        val cancelling = t.copy(cancelRequested = true, autoResume = true)
        journal.save(cancelling); ticket = cancelling
        cancelSameRequest(cancelling)
    }
    fun close() = launch {
        dismiss()
    }
    fun openKeyringManagement() = launch {
        val owner = mutable.value.owner ?: return@launch
        if (currentOwner() != owner) return@launch unknown()
        // 화면 닫힘과 이동 의도를 같은 상태 변경으로 전달한다. 그 사이 가입 화면이 먼저 구성되지 않게 한다.
        dismiss(keyringOwner = owner)
    }
    fun consumeKeyringNavigation(owner: Long): Boolean {
        if (currentOwner() != owner || mutable.value.keyringOwner != owner) return false
        mutable.update { it.copy(keyringOwner = null) }
        return true
    }
    private suspend fun dismiss(keyringOwner: Long? = null) {
        ticket?.let {
            // 닫기는 결과 확인 포기/영수증 삭제가 아니다. 실행 후 미확인 기록은 로그아웃·재시작 후에도 복구한다.
            val unresolved = it.cancelRequested || (it.attempted && it.operation?.status != "COMPLETED")
            if (it.autoResume != unresolved) journal.save(it.copy(autoResume = unresolved))
        }
        ticket = null; handle = null
        mutable.update { it.copy(owner = null, visible = false, ui = WithdrawalState(), keyringOwner = keyringOwner) }
    }

    private suspend fun loadEligibility(owner: Long) {
        when (val result = api.eligibility(owner)) {
            is ApiResult.Ok -> mutable.update { it.copy(ui = it.ui.checked(result.value)) }
            else -> mutable.update { it.copy(ui = WithdrawalState(stage = WithdrawalStage.Unavailable)) }
        }
    }
    private suspend fun prepareSameRequest(t: WithdrawalTicket) {
        if (currentOwner() != t.owner) return unknown()
        when (val result = api.prepare(t.owner, t.key)) {
            is ApiResult.Ok -> {
                val value = result.value
                check(!value.receiptToken.isBlank() && withdrawalUuid(value.operation.withdrawalId))
                val saved = t.copy(operation = value.operation, receipt = value.receiptToken)
                journal.save(saved); ticket = saved
                mutable.update { it.copy(ui = it.ui.prepared(value.operation)) }
            }
            else -> unknown()
        }
    }
    private suspend fun recover() {
        val t = ticket ?: return unknown()
        // 취소 의도가 있으면 결과404를 만료로 해석하지 않고 같은 ID의 멱등 취소만 재시도한다.
        if (t.cancelRequested) return cancelSameRequest(t)
        val cached = t.operation?.let { WithdrawalState(operation = it).resolved(it) }
        if (cached?.stage == WithdrawalStage.Completed) {
            // 이미 검증·저장한 완료는 네트워크보다 먼저 복원한다. 72시간 영수증 만료도 완료 사실을 뒤집지 않는다.
            val completedOperation = cached.operation ?: return unknown()
            presentResult(cached, t)
            val refreshed = api.result(completedOperation.withdrawalId, t.receipt!!)
            // 외부 파일 정리는 갱신할 수 있지만 PREPARED/오류/잘못된 결과로 완료를 되돌리지 않는다.
            if (refreshed is ApiResult.Ok && cached.resolved(refreshed.value).stage == WithdrawalStage.Completed)
                applyResult(refreshed.value)
            return
        }
        if (t.operation == null) return prepareSameRequest(t) // 동일 키만 재전송하며 새 요청 키를 만들지 않는다.
        when (val result = api.result(t.operation.withdrawalId, t.receipt!!)) {
            is ApiResult.Ok -> applyResult(result.value)
            is ApiResult.Fail -> if ((result.error as? AppError.Server)?.code == "WITHDRAWAL_OPERATION_EXPIRED")
                recoverExpiredPreparation(t)
            else if ((result.error as? AppError.Server)?.code == "RESOURCE_NOT_FOUND") recoverExpiredPreparation(t)
            else unknown()
        }
    }
    private suspend fun cancelSameRequest(t: WithdrawalTicket) {
        val op = t.operation ?: return unknown()
        val checkpoint = sessionEnd.capture(t.owner) ?: return recoverCancellationOutcome(t)
        val result = api.cancel(t.owner, op.withdrawalId)
        if (result !is ApiResult.Ok) return recoverCancellationOutcome(t)
        if (currentOwner() != t.owner || sessionEnd.capture(t.owner)?.generation != checkpoint.generation) return unknown()
        // 서버는 이미 삭제된 같은 준비의 취소도 성공으로 응답한다. 확인 뒤에만 해당 기록을 제거한다.
        journal.remove(t); ticket = null; handle = null
        loadEligibility(t.owner)
    }

    /** 취소 중 인증이 끝났어도 서버 완료 증명은 확인할 수 있다. 404/PREPARED를 취소 성공으로 만들지는 않는다. */
    private suspend fun recoverCancellationOutcome(t: WithdrawalTicket) {
        val op = t.operation ?: return unknown()
        val result = api.result(op.withdrawalId, t.receipt!!)
        if (result !is ApiResult.Ok) return unknown()
        val verified = WithdrawalState(operation = op).resolved(result.value)
        if (verified.stage != WithdrawalStage.Completed) return unknown()
        // 다른 기기의 실행 등으로 실제 탈퇴가 완료됐다면 취소 성공 대신 확인된 완료를 한 번에 보존한다.
        val completed = t.copy(operation = result.value, cancelRequested = false, autoResume = true)
        journal.save(completed); ticket = completed
        presentResult(verified, completed)
    }

    private suspend fun recoverExpiredPreparation(t: WithdrawalTicket) {
        // 기존 영수증 결과가 만료/없음일 때도 계정이 사라졌다고 추측하지 않는다.
        // 같은 회원의 현재 인증 세션과 자격조회200을 확인한 뒤 같은 키로 준비만 요청한다.
        val checkpoint = sessionEnd.capture(t.owner) ?: return unknown()
        val eligibility = api.eligibility(t.owner)
        // 조회 중 계정/로그인 세대가 바뀌면 이전 소유자의 차단 정보나 이동 버튼도 표시하지 않는다.
        if (currentOwner() != t.owner || sessionEnd.capture(t.owner)?.generation != checkpoint.generation) return unknown()
        if (eligibility !is ApiResult.Ok) return unknown()
        val checked = WithdrawalState().checked(eligibility.value)
        if (checked.stage == WithdrawalStage.Blocked) {
            // 만료된 준비 후 키링이 등록됐어도 제한을 해소할 관리 경로는 열어 둔다.
            // 자격 차단은 기존 실행의 취소/실패 확정이 아니다. 저널·키·영수증·실행 여부는 그대로 보존한다.
            handle = null
            mutable.update { it.copy(ui = checked.copy(operation = t.operation,
                canCancelPreparation = false, executionRequested = t.attempted)) }
            return
        }
        if (!checked.canPrepare) return unknown()
        val prepared = api.prepare(t.owner, t.key)
        if (currentOwner() != t.owner || sessionEnd.capture(t.owner)?.generation != checkpoint.generation) return unknown()
        if (prepared !is ApiResult.Ok) return unknown()
        adoptExpiredPreparation(t, prepared.value)
    }
    private suspend fun adoptExpiredPreparation(t: WithdrawalTicket, value: WithdrawalPrepared) {
        val previous = t.operation ?: return unknown()
        val next = value.operation
        // 장치 시각으로 만료를 추측하지 않고 두 서버 응답의 시각을 대조한다. 임의의 ID 변경은 수용하지 않는다.
        val expiredReplacement = runCatching { Instant.parse(next.preparedAt) >= Instant.parse(previous.expiresAt) }.getOrDefault(false)
        if (currentOwner() != t.owner || next.withdrawalId == previous.withdrawalId || !expiredReplacement ||
            WithdrawalState().prepared(next).stage != WithdrawalStage.Confirm) return unknown()
        journal.replaceExpired(t, value)
        ticket = t.copy(operation = next, receipt = value.receiptToken, attempted = false, autoResume = false)
        handle = null
        mutable.update { it.copy(ui = WithdrawalState().prepared(next)) }
    }
    private suspend fun applyResult(op: WithdrawalOperation) {
        val t = ticket ?: return unknown()
        if (t.operation?.withdrawalId != op.withdrawalId) return unknown()
        // 화면 상태 검사도 통과한 결과만 저널/로컬 정리에 사용한다.
        // 실행 응답이 불명확했던 요청에는 준비 취소 버튼을 다시 제공하지 않는다.
        var next = WithdrawalState(operation = t.operation, canCancelPreparation = !t.attempted).resolved(op)
        if (next.stage == WithdrawalStage.Unknown) return unknown()
        if (next.stage == WithdrawalStage.Confirm && t.attempted) {
            // 실행이 롤백돼 PREPARED로 남았어도 키링 제한이 새로 생겼을 수 있다.
            // 현재 로그인에서 자격을 재확인하고, 기존 실행 증거를 보존한 채 관리 경로를 제공한다.
            val checkpoint = sessionEnd.capture(t.owner) ?: return unknown()
            val eligibility = api.eligibility(t.owner)
            if (currentOwner() != t.owner || sessionEnd.capture(t.owner)?.generation != checkpoint.generation ||
                eligibility !is ApiResult.Ok) return unknown()
            val checked = WithdrawalState().checked(eligibility.value)
            if (checked.stage == WithdrawalStage.Blocked) next = checked.copy(operation = op,
                canCancelPreparation = false, executionRequested = true)
            else if (!checked.canPrepare) return unknown()
        }
        val saved = t.copy(operation = op)
        journal.save(saved); ticket = saved
        presentResult(next, saved)
    }
    /** 저장된 완료를 다시 쓰지 않고 표시·로컬 세션 정리만 재개한다. 저장소/서버의 일시 실패로 완료 근거를 잃지 않는다. */
    private suspend fun presentResult(next: WithdrawalState, t: WithdrawalTicket) {
        if (next.stage == WithdrawalStage.Completed) {
            mutable.update { it.copy(ui = next) }
            try {
                val checkpoint = handle ?: sessionEnd.capture(t.owner)
                if (checkpoint != null) sessionEnd.finish(checkpoint)
            } catch (_: Exception) {
                mutable.update { it.copy(ui = next.copy(localCleanupPending = true)) }; return
            }
        }
        mutable.update { it.copy(ui = next) }
    }
    private fun unknown() {
        // 이미 검증한 완료 상태는 외부 정리 재조회/저장 실패로 미확인 상태로 퇴행하지 않는다.
        if (mutable.value.ui.displayStage == WithdrawalStage.Completed) return
        // 같은 요청을 복구하되, execute를 보내지 않은 준비 실패를 '이미 탈퇴됐을 수 있음'으로 안내하지 않는다.
        // 저널을 읽지 못한 경우에는 실행 여부를 단정하지 않고 기존의 보수적 결과 확인 안내를 유지한다.
        mutable.update { it.copy(ui = it.ui.resultUnknown(executionRequested = ticket?.attempted != false)
            .copy(cancellationRequested = ticket?.cancelRequested == true)) }
    }
    private fun currentOwner() = (sessions.state.value as? Session.SignedIn)?.userId
    private fun launch(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { unknown() }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newWithdrawalKey() = Uuid.random().toString()
