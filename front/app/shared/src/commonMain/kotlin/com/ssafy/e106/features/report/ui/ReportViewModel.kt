package com.ssafy.e106.features.report.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.report.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class ReportStorageIssue { Unreadable, Corrupt }

data class ConnectedReport(val owner: Session.SignedIn? = null, val room: Long? = null,
    val ui: ReportState = ReportState(), val pending: PendingReport? = null,
    val receipt: ReportReceipt? = null, val error: AppError? = null, val busy: Boolean = false,
    // pending=null만으로는 '저장된 요청 없음'과 '읽기 미완료'를 구분할 수 없다.
    val recoveryComplete: Boolean = false, val storageIssue: ReportStorageIssue? = null)

/** 증거는 이 방에서 실제 조회한 메시지만 선택한다. 임의 회원 ID나 다른 방의 메시지를 보내지 않는다. */
@OptIn(ExperimentalUuidApi::class)
class ReportViewModel(private val api: ReportRepository, private val chats: ChatRepository,
    private val sessions: SessionStore, private val prefs: AppPrefs) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedReport())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var job: Job? = null
    private var writing = false
    private var queuedRoom: Long? = null
    private var expiry: Job? = null
    private var cursor: String? = null
    private val pendingStore = ReportPendingStore(prefs)
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        epoch++; job?.cancel(); expiry?.cancel(); writing = false; queuedRoom = null; cursor = null; mutable.value = ConnectedReport(it as? Session.SignedIn)
    } } }
    private fun valid(stamp: Long, owner: Session.SignedIn) = stamp == epoch && owner == sessions.state.value
    fun bind(room: Long) {
        val owner = mutable.value.owner ?: return
        if (room <= 0) return
        // 전송은 끊지 않는다. 마지막 이동 대상만 기억하고, 원래 방으로 돌아오면 예약을 취소한다.
        if (writing) { queuedRoom = room.takeIf { it != mutable.value.room }; return }
        queuedRoom = null
        // 중복 억제는 같은 방에만 적용한다. 다른 방은 이전 조회를 취소하고 즉시 복구한다.
        if (mutable.value.room == room && (mutable.value.busy || mutable.value.recoveryComplete)) return
        val stamp = ++epoch; job?.cancel(); expiry?.cancel(); cursor = null
        // 방별 메모리 상태만 비운다. 이전 방의 미확인 요청은 저장소에 남겨 그 방에 돌아왔을 때 복구한다.
        mutable.value = ConnectedReport(owner = owner, room = room,
            ui = ReportState(room.toString(), rules = ReportRules(1000)), busy = true)
        job = viewModelScope.launch {
            try {
                val recovered = pendingStore.read(room, owner.userId)
                if(!valid(stamp, owner)) return@launch
                val pending = when(recovered) {
                    is PrefRead.Found -> recovered.value
                    PrefRead.Missing -> null
                    PrefRead.OtherUser -> return@launch
                    else -> { storageFailed(recovered); return@launch }
                }
                if(!valid(stamp, owner)) return@launch
                // 저장된 요청 복구는 새 신고 권한을 부여하지 않는다. 종료된 방에서도 같은 요청의 결과만 확인한다.
                if(pending != null) mutable.update { it.copy(recoveryComplete = true, pending = pending, ui = it.ui.copy(
                    step = ReportStep.Unknown, reason = ReportReason.valueOf(pending.body.reason), detail = pending.body.detail.orEmpty())) }
                else {
                    // 저장된 요청이 없음을 확인한 뒤에만 신규 신고를 위한 방 권한을 조회한다.
                    mutable.update { it.copy(recoveryComplete = true) }
                    loadEvidence(room, null, owner, stamp)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) mutable.update { it.copy(error = chatInvalid(), ui = it.ui.copy(canReport = false)) } }
            finally { if(valid(stamp, owner)) mutable.update { it.copy(busy = false) } }
        }
    }
    // 저장소 확인 실패 시 기존 요청 번호를 유지하고 서버 전송을 막는다.
    private fun storageFailed(result: PrefRead<*>) {
        mutable.update { it.copy(storageIssue = if(result == PrefRead.Corrupt) ReportStorageIssue.Corrupt else ReportStorageIssue.Unreadable,
            error = null, ui = it.ui.copy(canReport = false)) }
    }
    fun pause() {
        if (!writing) { epoch++; job?.cancel(); expiry?.cancel(); mutable.update { it.copy(busy = false, ui = it.ui.copy(evidence = emptyList(), evidenceLoad = EvidenceLoad.Loading)) } }
    }
    /** 복귀 시 저장 요청 확인을 우선하며, 복구된 요청은 사용자 확인 없이 자동 제출하지 않는다. */
    fun resume(room: Long) {
        val s = mutable.value
        if(s.room != room || !s.recoveryComplete) bind(room)
        else if(s.pending == null && s.receipt == null) evidence()
    }
    fun evidence(more: Boolean = false) {
        val s = mutable.value; val owner = s.owner ?: return; val room = s.room ?: return
        // 재조회 버튼 등 다른 진입 경로도 복구를 건너뛰고 신규 신고 권한을 열 수 없다.
        if(!s.recoveryComplete) { bind(room); return }
        if(s.busy || s.pending != null || more && cursor == null) return
        val stamp = epoch; mutable.update { it.copy(busy = true, error = null, ui = it.ui.copy(evidenceLoad = EvidenceLoad.Loading)) }
        job = viewModelScope.launch {
            try { loadEvidence(room, if(more) cursor else null, owner, stamp) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) mutable.update { it.copy(error = chatInvalid(), ui = it.ui.copy(evidenceLoad = EvidenceLoad.Failed)) } }
            finally { if(valid(stamp, owner)) mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun loadEvidence(room: Long, next: String?, owner: Session.SignedIn, stamp: Long) {
        val access = chats.room(room)
        if (!valid(stamp, owner)) return
        if(access is ApiResult.Fail || (access as ApiResult.Ok).value.let { it.chatRoomId != room || it.status != "ACTIVE" || !it.canRead || Instant.parse(it.expiresAt) <= Instant.parse(it.serverTime) }) {
            val failure = (access as? ApiResult.Fail)?.error
            mutable.update { it.copy(error = failure, ui = it.ui.copy(canReport = false, evidence = emptyList(), selectedIds = emptySet(),
                evidenceLoad = EvidenceLoad.Failed, accessRetryAvailable = failure != null && !failure.revokesChat())) }; return
        }
        val roomRecord = (access as ApiResult.Ok).value
        expiry?.cancel()
        expiry = viewModelScope.launch {
            delay((Instant.parse(roomRecord.expiresAt) - Instant.parse(roomRecord.serverTime)).inWholeMilliseconds)
            if (mutable.value.owner == owner && mutable.value.room == room) {
                // 제출 영수증 복구는 허용하되 종료된 대화 본문은 즉시 숨긴다.
                if (!writing) { epoch++; job?.cancel() }
                mutable.update { it.copy(busy = writing, ui = it.ui.copy(canReport = false, evidence = emptyList(), selectedIds = emptySet(), accessRetryAvailable = false)) }
            }
        }
        // 메시지를 못 받아도 증거 미첨부 신고는 계약상 가능하다. 방 권한은 위에서 확인했다.
        mutable.update { it.copy(ui = it.ui.copy(canReport = true, accessRetryAvailable = false)) }
        when(val page = chats.messages(room, next)) {
            is ApiResult.Ok -> if(valid(stamp, owner)) {
                // 메시지 조회가 지연되는 동안 나가기·차단이 발생할 수 있다. 본문 공개 직전에 권한을 다시 확인한다.
                // 기존 요청의 영수증 복구와 별개이며, 서버가 최신 권한을 확인하지 못하면 본문을 보류한다.
                val latest = chats.room(room)
                if(!valid(stamp, owner)) return
                if(latest is ApiResult.Fail || (latest as ApiResult.Ok).value.let {
                    it.chatRoomId != room || it.status != "ACTIVE" || !it.canRead ||
                        Instant.parse(it.expiresAt) <= Instant.parse(it.serverTime)
                }) {
                    cursor = null
                    val failure = (latest as? ApiResult.Fail)?.error
                    mutable.update { it.copy(error = failure,
                        ui = it.ui.copy(canReport = false, evidence = emptyList(), selectedIds = emptySet(),
                            evidenceLoad = EvidenceLoad.Failed, hasMoreEvidence = false,
                            accessRetryAvailable = failure != null && !failure.revokesChat())) }
                    return
                }
                cursor = page.value.nextCursor
                val evidence = page.value.items.map { ReportEvidence(it.messageId, room.toString(), it.sender == "ME", it.text, it.createdAt) }
                mutable.update { it.copy(ui = it.ui.copy(evidenceLoad = EvidenceLoad.Ready,
                    evidence = ((if(next == null) emptyList() else it.ui.evidence) + evidence).distinctBy { it.id }, hasMoreEvidence = cursor != null)) }
            }
            is ApiResult.Fail -> if(valid(stamp, owner)) mutable.update { it.copy(error = page.error,
                ui = it.ui.copy(canReport = !page.error.revokesChat(), evidenceLoad = EvidenceLoad.Failed,
                    evidence = if(page.error.revokesChat()) emptyList() else it.ui.evidence,
                    // 본문 접근이 사라지면 이전에 골랐던 증거 ID도 더 이상 제출하지 않는다.
                    selectedIds = if(page.error.revokesChat()) emptySet() else it.ui.selectedIds)) }
        }
    }
    fun reason(value: ReportReason) { edit { copy(reason = value) } }
    fun detail(value: String) { edit { copy(detail = value) } }
    private fun edit(change: ReportState.() -> ReportState) { if(!mutable.value.busy && mutable.value.pending == null) mutable.update { it.copy(ui = it.ui.change()) } }
    fun select(id: Long, selected: Boolean) { edit {
        if(visibleEvidence.none { it.id == id }) this else copy(selectedIds = if(selected) selectedIds + id else selectedIds - id)
    } }
    fun action(action: ReportAction) { when(action) {
        ReportAction.NextEvidence -> if(mutable.value.ui.validForm) edit { copy(step = ReportStep.Evidence) }
        ReportAction.Review -> if(mutable.value.ui.validSubmission) edit { copy(step = ReportStep.Confirm) }
        ReportAction.Edit -> edit { copy(step = ReportStep.Form) }
        ReportAction.ClearEvidence -> edit { copy(selectedIds = emptySet()) }
        ReportAction.ReloadEvidence -> evidence()
        ReportAction.LoadMoreEvidence -> evidence(true)
        ReportAction.Submit -> submit()
        ReportAction.Retry, ReportAction.CheckResult -> {
            val pending = mutable.value.pending
            if(pending != null) transmit(pending) else { edit { copy(step = ReportStep.Form) }; evidence() }
        }
        else -> Unit
    } }
    private fun submit() {
        val s = mutable.value; val owner = s.owner ?: return; val room = s.room ?: return
        if(!s.recoveryComplete || s.busy || s.pending != null || s.ui.step !in listOf(ReportStep.Form, ReportStep.Confirm) || !s.ui.validSubmission) return // 사유 시트에서 바로 접수할 수 있다
        transmit(PendingReport(owner.userId, Uuid.random().toString(), ReportBody(room, s.ui.reason!!.name,
            s.ui.selectedIds.sorted(), s.ui.detail.takeIf { it.isNotEmpty() })))
    }
    private fun transmit(request: PendingReport) {
        val owner = mutable.value.owner ?: return; val room = mutable.value.room ?: return
        // 저장소 복구가 끝나기 전에는 어떤 경로에서도 기존 요청을 새 키로 덮어쓰지 않는다.
        if(!mutable.value.recoveryComplete || mutable.value.busy || request.owner != owner.userId || request.body.chatRoomId != room) return
        val stamp = ++epoch; writing = true
        mutable.update { it.copy(busy = true, pending = request, error = null, storageIssue = null, ui = it.ui.copy(step = ReportStep.Sending)) }
        job = viewModelScope.launch {
            try {
                // 성공 요청은 방이 닫힌 후에도 동일 키로 영수증을 복구하므로 재시도 앞에서 ACTIVE를 강요하지 않는다.
                val saved = pendingStore.save(room, owner.userId, request)
                if(!valid(stamp, owner)) return@launch
                if(saved !is PrefRead.Found) {
                    if(saved != PrefRead.OtherUser) {
                        storageFailed(saved)
                        mutable.update { it.copy(ui = it.ui.copy(step = ReportStep.Unknown)) }
                    }
                    return@launch
                }
                if(!valid(stamp, owner)) return@launch
                when(val result = api.submit(request)) {
                    is ApiResult.Ok -> {
                        require(result.value.reportId > 0 && result.value.status == "RECEIVED" && result.value.createdAt.isNotBlank())
                        if(valid(stamp, owner)) { pendingStore.save(room, owner.userId, null)
                            // 저장 확인도 대기할 수 있으므로 계정/화면이 바뀐 뒤의 완료 응답은 버린다.
                            if(!valid(stamp, owner)) return@launch
                            mutable.update { it.copy(pending = null, receipt = result.value, ui = it.ui.copy(step = ReportStep.Received,
                                detail = "", evidence = emptyList(), selectedIds = emptySet())) } }
                    }
                    is ApiResult.Fail -> if(valid(stamp, owner)) {
                        val definitive = result.error is AppError.Server && result.error.status in listOf(400, 404, 409, 410)
                        if(definitive) pendingStore.save(room, owner.userId, null)
                        if(!valid(stamp, owner)) return@launch
                        mutable.update { it.copy(error = result.error, pending = if(definitive) null else request,
                            ui = it.ui.copy(step = if(result.error.chatCode() == "IDEMPOTENCY_CONFLICT") ReportStep.Conflict else if(definitive) ReportStep.Failed else ReportStep.Unknown,
                                // 응답 대기 중 방이 만료됐다면 일시 오류가 와도 새 신고 권한을 다시 열지 않는다.
                                canReport = it.ui.canReport && !result.error.revokesChat(), evidence = if(result.error.revokesChat()) emptyList() else it.ui.evidence)) }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) mutable.update { it.copy(error = chatInvalid(), ui = it.ui.copy(step = ReportStep.Unknown)) } }
            finally { if(valid(stamp, owner)) {
                writing = false; mutable.update { it.copy(busy = false) }
                // 성공·실패·저장 확인 실패 모두 완료 후 예약된 방으로 연결한다.
                val next = queuedRoom; queuedRoom = null
                if(next != null) bind(next)
            } }
        }
    }
}
