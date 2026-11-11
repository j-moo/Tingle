package com.ssafy.e106.features.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration

data class ConnectedChatList(val owner: Session.SignedIn? = null, val ui: ChatListState = ChatListState(), val error: AppError? = null)

/** 페이지 길이를 전체 개수로 해석하지 않는다. 서버 커서만 다음 조회에 전달한다. */
class ChatListViewModel(private val api: ChatRepository, private val sessions: SessionStore,
    private val local: ChatLocalStorage, private val now: () -> Duration = monotonicChatClock()) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedChatList())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var cursor: String? = null
    private var cleanupJob: Job? = null
    private val lifetimes = mutableMapOf<Long, ChatExpiry>()
    private val expiryJobs = mutableMapOf<Long, Job>()
    private var visible = false
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        generation++; job?.cancel(); cleanupJob?.cancel(); expiryJobs.values.forEach { job -> job.cancel() }
        expiryJobs.clear(); lifetimes.clear(); visible = false
        cursor = null; mutable.value = ConnectedChatList(it as? Session.SignedIn)
    } } }
    fun refresh() = load(null)
    fun more() { cursor?.let { load(it) } }
    fun pause() { generation++; job?.cancel(); cleanupJob?.cancel(); visible = false
        expiryJobs.values.forEach { it.cancel() }; expiryJobs.clear()
        mutable.update { it.copy(ui = ChatListState()) }; cursor = null }
    private fun load(next: String?) {
        val owner = mutable.value.owner ?: return
        if (job?.isActive == true) { if (next != null) return; job?.cancel() }
        visible = true
        val stamp = ++generation
        mutable.update { it.copy(error = null, ui = when {
            next != null -> it.ui.copy(page = ChatListPage.Loading)
            // 이미 목록이 보이면(당겨서 새로고침) 목록은 두고 위쪽 표시만 돌린다
            it.ui.load == ChatListLoad.Ready && it.ui.rooms.isNotEmpty() -> it.ui.copy(refreshing = true, refreshFailed = false,
                page = if (it.ui.page == ChatListPage.Loading) ChatListPage.More else it.ui.page)
            else -> ChatListState()
        }) }
        job = viewModelScope.launch {
            val requestStarted = now()
            val result = api.list(next)
            if (stamp != generation || owner != sessions.state.value) return@launch
            when (result) {
                is ApiResult.Ok -> {
                    // 한 페이지에 없다는 이유로 삭제하지 않는다. 서버가 CLOSED라고 명시한 방만 정리한다.
                    for (room in result.value.items.filter { it.chatRoomId > 0 && it.status == "CLOSED" }) {
                        try { local.forgetRoom(owner.userId, room.chatRoomId) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (stamp == generation && owner == sessions.state.value)
                            mutable.update { it.copy(error = chatInvalid()) } }
                        if (stamp != generation || owner != sessions.state.value) return@launch
                    }
                    val rows = result.value.items.filter { it.chatRoomId > 0 }.map { room ->
                        lifetimes.getOrPut(room.chatRoomId) { ChatExpiry(now) }.observe(room, requestStarted)
                        ChatListItem(
                        chatRoomId = room.chatRoomId, hasNewMessages = room.hasNewMessage, closesAtLabel = room.expiresAt,
                        access = when { room.status == "CLOSED" || lifetimes.getValue(room.chatRoomId).remaining() <= 0 -> ChatRoomAccess.Closed
                            room.status == "ACTIVE" && room.canRead -> ChatRoomAccess.Allowed
                            else -> ChatRoomAccess.Unknown }) }
                    cursor = result.value.nextCursor
                    // 다음 쪽만 받은 것이면 새로고침 실패 안내는 그대로 둔다(첫 쪽은 아직 옛 내용)
                    mutable.update { it.copy(ui = ChatListState(ChatListLoad.Ready,
                        visibleChatRooms((if (next == null) emptyList() else it.ui.rooms) + rows),
                        if (cursor == null) ChatListPage.End else ChatListPage.More,
                        refreshFailed = next != null && it.ui.refreshFailed)) }
                    // 목록에 머물러도 만료 시각에 행을 지우며, 다른 페이지의 보관 방은 별도 권한 조회로 확인한다.
                    for (room in result.value.items.filter { it.chatRoomId > 0 }) scheduleExpiry(owner, room.chatRoomId)
                    if (next == null) checkStoredRooms(owner)
                }
                is ApiResult.Fail -> mutable.update { it.copy(error = result.error, ui = when {
                    result.error.revokesChat() -> ChatListState(load = ChatListLoad.Failed)
                    next != null -> it.ui.copy(page = ChatListPage.Failed)
                    // 새로고침 실패: 보던 목록 + 안내 한 줄
                    it.ui.refreshing -> it.ui.copy(refreshing = false, refreshFailed = true)
                    else -> ChatListState(load = ChatListLoad.Failed)
                }) }
            }
        }
    }
    private fun scheduleExpiry(owner: Session.SignedIn, id: Long) {
        val lifetime = lifetimes[id] ?: return
        expiryJobs.remove(id)?.cancel()
        expiryJobs[id] = viewModelScope.launch {
            delay(lifetime.remaining())
            if (!visible || sessions.state.value != owner) return@launch
            mutable.update { it.copy(ui = it.ui.copy(rooms = it.ui.rooms.filterNot { room -> room.chatRoomId == id })) }
            forget(owner, id)
        }
    }
    private suspend fun forget(owner: Session.SignedIn, id: Long) {
        try { local.forgetRoom(owner.userId, id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (sessions.state.value == owner) mutable.update { it.copy(error = chatInvalid()) } }
    }
    private fun checkStoredRooms(owner: Session.SignedIn) {
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            try {
                for (id in local.storedRooms(owner.userId)) {
                    val started = now(); val result = api.room(id)
                    currentCoroutineContext().ensureActive()
                    if (!visible || sessions.state.value != owner) return@launch
                    when (result) {
                        is ApiResult.Fail -> if (result.error.revokesChat()) forget(owner, id)
                        is ApiResult.Ok -> {
                            val room = result.value; require(room.chatRoomId == id)
                            val lifetime = lifetimes.getOrPut(id) { ChatExpiry(now) }
                            lifetime.observe(room, started)
                            if (room.status == "CLOSED" || !room.canRead || lifetime.remaining() <= 0) {
                                mutable.update { it.copy(ui = it.ui.copy(rooms = it.ui.rooms.filterNot { row -> row.chatRoomId == id })) }
                                forget(owner, id)
                            } else scheduleExpiry(owner, id)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 일시 실패는 데이터 삭제로 해석하지 않고 다음 목록 진입 때 다시 확인한다. */ }
        }
    }
}
