package com.ssafy.e106.features.safety.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.safety.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class SafetyStorageIssue { Unreadable, Corrupt }

data class ConnectedSafety(val owner: Session.SignedIn? = null, val room: Long? = null,
    val ui: SafetyActionState = SafetyActionState(SafetyOperation.Block, SafetyContext.Chat),
    val pending: PendingSafety? = null, val error: AppError? = null, val busy: Boolean = false,
    // 요청 없음과 저장소 미확인을 분리해 새 키로 중복 명령이 시작되지 않게 한다.
    val recoveryComplete: Boolean = false, val storageIssue: SafetyStorageIssue? = null)

/** 차단/나가기 결과를 보존해 회전·응답 유실 뒤에도 같은 키로 확인한다. 자동 명령 재실행은 하지 않는다. */
@OptIn(ExperimentalUuidApi::class)
class SafetyViewModel(private val api: SafetyRepository, private val chats: ChatRepository,
    private val sessions: SessionStore, private val prefs: AppPrefs) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedSafety())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var writing = false
    private val pendingStore = SafetyPendingStore(prefs)
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); writing = false; mutable.value = ConnectedSafety(it as? Session.SignedIn)
    } } }
    fun bind(room: Long, operation: SafetyOperation) {
        if (room <= 0 || mutable.value.busy) return
        val owner = mutable.value.owner ?: return
        if (mutable.value.room == room && mutable.value.ui.operation == operation && mutable.value.ui.phase == SafetyPhase.Succeeded) return
        val stamp = ++generation; job?.cancel()
        // 다른 방/작업의 미확인 요청을 화면 상태에 남겨두지 않는다. 디스크의 요청은 보존한다.
        mutable.value = ConnectedSafety(owner = owner, room = room, ui = SafetyActionState(operation, SafetyContext.Chat), busy = true)
        job = viewModelScope.launch {
            try {
                val type = if (operation == SafetyOperation.Block) "BLOCK" else "LEAVE"
                val recovered = pendingStore.read(room, type, owner.userId)
                if (!valid(stamp, owner)) return@launch
                val restored = when (recovered) {
                    is PrefRead.Found -> recovered.value
                    PrefRead.Missing -> null
                    PrefRead.OtherUser -> return@launch
                    else -> { storageFailed(recovered); return@launch }
                }
                mutable.update { it.copy(recoveryComplete = true) }
                if (restored != null) mutable.update { it.copy(pending = restored, ui = it.ui.copy(phase = SafetyPhase.Unknown, canRequest = true)) }
                else when (val response = chats.room(room)) {
                    is ApiResult.Ok -> if (valid(stamp, owner)) mutable.update { it.copy(pending = null,
                        ui = it.ui.copy(canRequest = response.value.chatRoomId == room && response.value.status == "ACTIVE" && response.value.canRead)) }
                    is ApiResult.Fail -> if (valid(stamp, owner)) mutable.update { it.copy(error = response.error, ui = it.ui.copy(phase = SafetyPhase.Unavailable)) }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if (valid(stamp, owner)) mutable.update { it.copy(error = chatInvalid(), ui = it.ui.copy(phase = SafetyPhase.Unavailable)) } }
            finally { if (valid(stamp, owner)) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun valid(stamp: Long, owner: Session.SignedIn) = stamp == generation && owner == sessions.state.value
    private fun storageFailed(result: PrefRead<*>) {
        mutable.update { it.copy(storageIssue = if (result == PrefRead.Corrupt) SafetyStorageIssue.Corrupt else SafetyStorageIssue.Unreadable,
            ui = it.ui.copy(canRequest = false)) }
    }
    fun pause() { if (!writing) { generation++; job?.cancel(); mutable.update { it.copy(busy = false, ui = it.ui.copy(canRequest = false)) } } }
    fun ask() { if (!mutable.value.busy && mutable.value.recoveryComplete && mutable.value.pending == null && mutable.value.ui.canRequest) mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Confirm)) } }
    fun cancel() { if (!mutable.value.busy) mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Overview)) } }
    fun submit() {
        val s = mutable.value; val owner = s.owner ?: return; val room = s.room ?: return
        if (!s.recoveryComplete || s.pending != null || s.busy || s.ui.phase != SafetyPhase.Confirm || !s.ui.canRequest) return
        execute(PendingSafety(owner.userId, room, if(s.ui.operation == SafetyOperation.Block) "BLOCK" else "LEAVE", Uuid.random().toString()))
    }
    fun recover() { mutable.value.pending?.let(::execute) }
    private fun execute(request: PendingSafety) {
        val owner = mutable.value.owner ?: return
        if (!mutable.value.recoveryComplete || mutable.value.busy || request.owner != owner.userId || request.room != mutable.value.room) return
        val stamp = ++generation; writing = true
        mutable.update { it.copy(busy = true, pending = request, error = null, storageIssue = null, ui = it.ui.copy(phase = SafetyPhase.Processing)) }
        job = viewModelScope.launch {
            try {
                val saved = pendingStore.save(request.room, request.operation, owner.userId, request)
                if (!valid(stamp, owner)) return@launch
                if (saved !is PrefRead.Found) {
                    if (saved != PrefRead.OtherUser) {
                        storageFailed(saved)
                        mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Unknown)) }
                    }
                    return@launch
                }
                when(val result = api.execute(request)) {
                    is ApiResult.Ok -> if (valid(stamp, owner)) {
                        val cleared = pendingStore.save(request.room, request.operation, owner.userId, null)
                        if (!valid(stamp, owner)) return@launch
                        if (cleared !is PrefRead.Found) {
                            // 서버 성공 뒤 완료 표식 저장이 실패하면 같은 키로만 결과를 다시 확인한다.
                            if (cleared != PrefRead.OtherUser) { storageFailed(cleared); mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Unknown)) } }
                            return@launch
                        }
                        // 서버에서 종료 명령을 확정했으므로 로컬 초안/미확인 전송도 더 이상 복구하지 않는다.
                        if (prefs.flow(pendingChatKey).first()?.room == request.room) prefs.set(pendingChatKey, null, owner.userId)
                        if (prefs.flow(chatDraftKey).first()?.room == request.room) prefs.set(chatDraftKey, null, owner.userId)
                        // 로컬 정리도 중단 가능한 작업이다. 계정 전환 뒤 이전 작업의 완료 화면을 공개하지 않는다.
                        if (!valid(stamp, owner)) return@launch
                        mutable.update { it.copy(pending = null, ui = it.ui.copy(phase = SafetyPhase.Succeeded, canRequest = false)) }
                    }
                    is ApiResult.Fail -> if (valid(stamp, owner)) {
                        val closed = result.error.chatCode() == "CHAT_CLOSED"
                        val definitive = result.error is AppError.Server && result.error.status in listOf(400, 404, 409, 410)
                        if (definitive) {
                            val cleared = pendingStore.save(request.room, request.operation, owner.userId, null)
                            if (!valid(stamp, owner)) return@launch
                            if (cleared !is PrefRead.Found) {
                                if (cleared != PrefRead.OtherUser) { storageFailed(cleared); mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Unknown)) } }
                                return@launch
                            }
                        }
                        mutable.update { it.copy(error = result.error, pending = if(definitive) null else request,
                            ui = it.ui.copy(phase = if(closed) SafetyPhase.ClosedUnconfirmed else if(definitive) SafetyPhase.Unavailable else SafetyPhase.Unknown)) }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner)) mutable.update { it.copy(error = chatInvalid(), ui = it.ui.copy(phase = SafetyPhase.Unknown)) } }
            finally { if(valid(stamp, owner)) { writing = false; mutable.update { it.copy(busy = false) } } }
        }
    }
}
