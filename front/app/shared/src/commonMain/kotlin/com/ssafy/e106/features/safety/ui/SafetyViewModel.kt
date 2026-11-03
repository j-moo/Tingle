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

data class ConnectedSafety(val owner: Session.SignedIn? = null, val room: Long? = null,
    val ui: SafetyActionState = SafetyActionState(SafetyOperation.Block, SafetyContext.Chat),
    val pending: PendingSafety? = null, val error: AppError? = null, val busy: Boolean = false)

/** 차단/나가기 결과를 보존해 회전·응답 유실 뒤에도 같은 키로 확인한다. 자동 명령 재실행은 하지 않는다. */
@OptIn(ExperimentalUuidApi::class)
class SafetyViewModel(private val api: SafetyRepository, private val chats: ChatRepository,
    private val sessions: SessionStore, private val prefs: AppPrefs) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedSafety())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var writing = false
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); writing = false; mutable.value = ConnectedSafety(it as? Session.SignedIn)
    } } }
    private fun key(room: Long, operation: String) = PrefKey("safety.pending.$room.$operation", PendingSafety.serializer())
    fun bind(room: Long, operation: SafetyOperation) {
        if (room <= 0 || mutable.value.busy) return
        val owner = mutable.value.owner ?: return
        if (mutable.value.room == room && mutable.value.ui.operation == operation && mutable.value.ui.phase == SafetyPhase.Succeeded) return
        val stamp = ++generation; job?.cancel()
        mutable.update { it.copy(room = room, ui = SafetyActionState(operation, SafetyContext.Chat), busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                val type = if (operation == SafetyOperation.Block) "BLOCK" else "LEAVE"
                val restored = prefs.flow(key(room, type)).first()?.takeIf { it.owner == owner.userId && it.room == room && it.operation == type }
                if (!valid(stamp, owner)) return@launch
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
    fun pause() { if (!writing) { generation++; job?.cancel(); mutable.update { it.copy(busy = false, ui = it.ui.copy(canRequest = false)) } } }
    fun ask() { if (!mutable.value.busy && mutable.value.ui.canRequest) mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Confirm)) } }
    fun cancel() { if (!mutable.value.busy) mutable.update { it.copy(ui = it.ui.copy(phase = SafetyPhase.Overview)) } }
    fun submit() {
        val s = mutable.value; val owner = s.owner ?: return; val room = s.room ?: return
        if (s.busy || s.ui.phase != SafetyPhase.Confirm || !s.ui.canRequest) return
        execute(PendingSafety(owner.userId, room, if(s.ui.operation == SafetyOperation.Block) "BLOCK" else "LEAVE", Uuid.random().toString()))
    }
    fun recover() { mutable.value.pending?.let(::execute) }
    private fun execute(request: PendingSafety) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.busy || request.owner != owner.userId || request.room != mutable.value.room) return
        val stamp = ++generation; writing = true
        mutable.update { it.copy(busy = true, pending = request, error = null, ui = it.ui.copy(phase = SafetyPhase.Processing)) }
        job = viewModelScope.launch {
            try {
                val pref = key(request.room, request.operation)
                prefs.set(pref, request, owner.userId); check(prefs.flow(pref).first() == request)
                if (!valid(stamp, owner)) return@launch
                when(val result = api.execute(request)) {
                    is ApiResult.Ok -> if (valid(stamp, owner)) {
                        prefs.set(pref, null, owner.userId)
                        // 서버에서 종료 명령을 확정했으므로 로컬 초안/미확인 전송도 더 이상 복구하지 않는다.
                        if (prefs.flow(pendingChatKey).first()?.room == request.room) prefs.set(pendingChatKey, null, owner.userId)
                        if (prefs.flow(chatDraftKey).first()?.room == request.room) prefs.set(chatDraftKey, null, owner.userId)
                        mutable.update { it.copy(pending = null, ui = it.ui.copy(phase = SafetyPhase.Succeeded, canRequest = false)) }
                    }
                    is ApiResult.Fail -> if (valid(stamp, owner)) {
                        val closed = result.error.chatCode() == "CHAT_CLOSED"
                        val definitive = result.error is AppError.Server && result.error.status in listOf(400, 404, 409, 410)
                        if (definitive) prefs.set(pref, null, owner.userId)
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
