package com.ssafy.e106.features.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.realtime.ChatEvent
import com.ssafy.e106.core.realtime.ChatEvents
import com.ssafy.e106.features.chat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ConnectedChatRoom(val owner: Session.SignedIn? = null, val id: Long? = null,
    val ui: ChatConversationState = ChatConversationState(), val error: AppError? = null,
    val pending: PendingChatSend? = null, val sending: Boolean = false, val readError: Boolean = false)

/** 읽기는 화면 수명에 묶고 쓰기는 회전에 취소하지 않는다. 계정 변경은 모든 이전 결과를 폐기한다. */
@OptIn(ExperimentalUuidApi::class)
class ChatRoomViewModel(private val api: ChatRepository, private val sessions: SessionStore,
    private val local: ChatLocalStorage, private val events: ChatEvents,
    private val now: () -> Duration = monotonicChatClock()) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedChatRoom())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var writeEpoch = 0L
    private val visiblePending = linkedSetOf<Long>()
    private var readJob: Job? = null
    private var sendJob: Job? = null
    private var expiryJob: Job? = null
    private var draftJob: Job? = null
    private var pollJob: Job? = null
    private var realtimeJob: Job? = null
    private var recoveryRequested = false
    private var ackJob: Job? = null
    private var partnerJob: Job? = null
    private val storage = local.mutex
    private val prefs = local.prefs
    private var records = emptyList<ChatMessageRecord>()
    private var receivedThrough = 0L
    private var needsInitialPage = true
    private val acknowledged = mutableSetOf<Long>()
    private var resumed = false
    private var readPending = false
    private var readGeneration = 0L
    private val lifetime = ChatExpiry(now)

    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect { session ->
        epoch++; writeEpoch++; cancelReads(); lifetime.reset(); sendJob?.cancel(); draftJob?.cancel(); resumed = false
        records = emptyList(); acknowledged.clear(); receivedThrough = 0; needsInitialPage = true
        mutable.value = ConnectedChatRoom(owner = session as? Session.SignedIn)
    } } }
    private fun valid(stamp: Long, owner: Session.SignedIn, id: Long) = stamp == epoch &&
        owner == sessions.state.value && mutable.value.id == id
    private fun writeValid(stamp: Long, owner: Session.SignedIn, id: Long) = stamp == writeEpoch &&
        owner == sessions.state.value && mutable.value.id == id
    // 화면 이탈은 수신/조회만 정리한다. 전송 작업은 회전 중에도 유지하며 계정 변경 시 별도로 취소한다.
    private fun stopRealtime() { realtimeJob?.cancel(); realtimeJob = null; recoveryRequested = false }
    private fun cancelReads() { readGeneration++; visiblePending.clear(); readJob?.cancel(); expiryJob?.cancel(); pollJob?.cancel(); stopRealtime(); ackJob?.cancel(); partnerJob?.cancel(); readPending = false }

    /** [nickname]: 목록에서 들어오면 목록의 상대 닉네임을 첫 제목으로 쓴다(방 상세 응답엔 닉네임이 없다) */
    fun open(id: Long, nickname: String? = null) {
        if (id <= 0) return
        if (mutable.value.id != id) {
            if (mutable.value.sending) return
            persistDraft(); epoch++; writeEpoch++; cancelReads(); lifetime.reset(); records = emptyList(); acknowledged.clear(); receivedThrough = 0; needsInitialPage = true
            // 다른 방 진입은 같은 화면의 중복 ON_RESUME와 다르므로 첫 조회/구독을 새로 시작한다.
            resumed = false
            mutable.update { ConnectedChatRoom(owner = it.owner, id = id, ui = ChatConversationState(nickname = nickname)) }
        } else if (nickname != null && mutable.value.ui.nickname == null) mutable.update { it.copy(ui = it.ui.copy(nickname = nickname)) }
        resume()
    }
    fun resume() {
        // ON_RESUME와 첫 화면 구성이 겹쳐도 이미 보이는 방의 연결/조회를 중복 시작하지 않는다.
        if (resumed) return
        resumed = true
        if (!mutable.value.sending) reload()
        // 실시간 이벤트는 즉시 반영하고, 주기 재조회는 놓친 이벤트·권한 변경·일시 HTTP 실패를 복구한다.
        // 두 경로는 readPending으로 합쳐 실행하며 화면 밖에서는 모두 취소한다.
        pollJob = viewModelScope.launch { while (isActive) { delay(5_000); if (mutable.value.ui.readable) recoverNew() } }
    }
    fun pause() {
        persistDraft(); epoch++; resumed = false; cancelReads()
        // 수신 본문은 백그라운드에서 숨기고, 복귀 시 현재 권한부터 다시 조회한다.
        // 본문을 비울 때 복구 위치도 함께 초기화한다. 남은 순번 때문에 이전 메시지를 건너뛰지 않게 한다.
        records = emptyList(); receivedThrough = 0; needsInitialPage = true
        mutable.update { it.copy(ui = it.ui.copy(messages = emptyList(), canRead = false, canSend = false,
            nextCursor = null, historyLoad = HistoryLoad.Idle)) }
    }
    fun reload() = read { owner, id, stamp ->
        // 최초 페이지를 다시 읽을 때 구독과 복구 위치를 함께 초기화하여 이전 방 결과가 섞이지 않게 한다.
        stopRealtime(); needsInitialPage = true; receivedThrough = 0
        mutable.update { it.copy(ui = it.ui.copy(load = ConversationLoad.Loading, canRead = false, canSend = false, messages = emptyList()), error = null) }
        val pending = storage.withLock { prefs.flow(pendingChatKey).first() }?.takeIf { it.owner == owner.userId }
        val draft = local.draft(owner.userId, id)
        if (!valid(stamp, owner, id)) return@read
        // 입력창을 열기 전에 초안을 복원한다. 늦게 온 메시지 목록은 새로 입력한 초안을 건드리지 않는다.
        mutable.update { it.copy(pending = pending, ui = it.ui.copy(draft = if (pending?.room == id) "" else draft)) }
        if (!access(owner, id, stamp)) return@read
        loadPartner(owner, id, stamp)
        val result = api.messages(id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ApiResult.Ok -> if (valid(stamp, owner, id) && resumed) {
                records = mergeChatMessages(emptyList(), result.value.items)
                receivedThrough = records.maxOfOrNull { it.sequence } ?: 0
                needsInitialPage = false
                reconcilePending(owner, id, records)
                // 최신 초안 복원 정책을 유지한다. 첫 페이지의 늦은 응답으로 사용자의 새 입력을 덮지 않는다.
                mutable.update { it.copy(ui = it.ui.copy(nextCursor = result.value.nextCursor, historyLoad = HistoryLoad.Idle)) }; render()
                startRealtime(owner, id, stamp)
            }
            is ApiResult.Fail -> fail(result.error, owner, id, stamp)
        }
    }

    /**
     * 방 상세 응답엔 닉네임이 없어 상대 프로필 API로 따로 받는다. access()는 5초마다 불리므로 첫 페이지 조회 때만,
     * 닉네임을 모를 때만 부른다. 방 열람 흐름(read) 밖이라 실패해도 방을 닫지 않고 제목은 "대화"로 둔다.
     */
    private fun loadPartner(owner: Session.SignedIn, id: Long, stamp: Long) {
        if (mutable.value.ui.nickname != null || partnerJob?.isActive == true) return
        partnerJob = viewModelScope.launch {
            val result = try { api.profile(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // 그사이 방이 바뀌었거나 닫혔으면(stamp) 늦게 온 결과는 버린다
            if (result is ApiResult.Ok && valid(stamp, owner, id))
                mutable.update { it.copy(ui = it.ui.copy(nickname = result.value.nickname)) }
        }
    }

    /** 공통 기반은 연결/인증을 맡는다. 기능은 이벤트를 신호로 사용하고 HTTP로 권한·누락·본문을 함께 재확인한다. */
    private fun startRealtime(owner: Session.SignedIn, id: Long, stamp: Long) {
        if (!valid(stamp, owner, id) || !resumed || !mutable.value.ui.readable || realtimeJob?.isActive == true) return
        mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Connecting)) }
        realtimeJob = viewModelScope.launch {
            val seen = linkedSetOf<String>()
            try {
                events.connect(id, receivedThrough, ChatMessageRecord.serializer()) { it.sequence }.collect { event ->
                    if (!valid(stamp, owner, id) || !resumed) return@collect
                    when (event) {
                        is ChatEvent.Connected -> {
                            mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Connected)) }
                            // 기반이 이미 전달한 순번과 HTTP로 확인한 순번은 다르다. 복구는 후자에서 시작한다.
                            requestRecovery()
                        }
                        is ChatEvent.Message -> {
                            val message = event.message
                            require(message.messageId > 0 && message.sequence > 0 && message.sender in setOf("ME", "PARTNER"))
                            if (message.sequence > receivedThrough && seen.add(event.eventId)) {
                                // 장시간 열린 방에서도 중복 판별용 메모리가 무한히 늘지 않게 제한한다.
                                if (seen.size > 512) seen.remove(seen.first())
                                requestRecovery()
                            }
                        }
                        is ChatEvent.Reconnecting ->
                            mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Reconnecting)) }
                        is ChatEvent.Ended -> {
                            if (event.error.revokesChat()) fail(event.error, owner, id, stamp)
                            else mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Stopped)) }
                        }
                    }
                }
                if (valid(stamp, owner, id) && resumed && mutable.value.ui.readable)
                    mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Stopped)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (valid(stamp, owner, id) && resumed && mutable.value.ui.readable)
                    mutable.update { it.copy(ui = it.ui.copy(realtime = ChatRealtimeStatus.Stopped)) }
            }
        }
    }

    private fun requestRecovery() {
        recoveryRequested = true
        drainRecovery()
    }

    /** 조회/전송 중 온 신호는 하나로 합쳐 보존하고, 현재 작업이 끝난 뒤 한 번 더 복구한다. */
    private fun drainRecovery() {
        if (!recoveryRequested || readPending || mutable.value.sending || !resumed || !mutable.value.ui.readable) return
        recoveryRequested = false
        recoverNew()
    }

    /** 각 내용 조회 전 방의 현재 권한을 검사하고, 서버 시각으로 남은 수명을 계산한다. */
    private suspend fun access(owner: Session.SignedIn, id: Long, stamp: Long, background: Boolean = false): Boolean {
        val requestStarted = now()
        val result = api.room(id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ApiResult.Fail -> { readFailure(result.error, owner, id, stamp, background); return false }
            is ApiResult.Ok -> {
                if (!valid(stamp, owner, id) || !resumed) return false
                val room = result.value
                require(room.chatRoomId == id && room.status in setOf("ACTIVE", "CLOSED"))
                lifetime.observe(room, requestStarted)
                if (room.status == "CLOSED" || lifetime.remaining() <= 0) {
                    fail(AppError.Server(410, "CHAT_CLOSED", ""), owner, id, stamp); return false
                }
                if (!room.canRead) { fail(AppError.Server(403, "AI_RISK_CONSENT_REQUIRED", ""), owner, id, stamp); return false }
                local.allowRoom(owner.userId, id)
                if (!valid(stamp, owner, id) || !resumed) return false
                val remaining = lifetime.remaining()
                if (remaining <= 0) { fail(AppError.Server(410, "CHAT_CLOSED", ""), owner, id, stamp); return false }
                mutable.update { it.copy(ui = it.ui.withAccess(room.status, room.canRead, room.canSend)
                    .copy(closesAtLabel = room.expiresAt), error = null) }
                expiryJob?.cancel()
                expiryJob = viewModelScope.launch { delay(remaining)
                    if (valid(stamp, owner, id)) fail(AppError.Server(410, "CHAT_CLOSED", ""), owner, id, stamp) }
                return true
            }
        }
    }
    fun older() { val cursor = mutable.value.ui.nextCursor ?: return
        // 이력 조회의 일시 실패도 현재 대화와 아직 저장되지 않은 입력을 지우지 않는다.
        read(background = true) { owner, id, stamp ->
            mutable.update { it.copy(ui = it.ui.copy(historyLoad = HistoryLoad.Loading)) }
            if (!access(owner, id, stamp, background = true)) return@read
            val result = api.messages(id, cursor = cursor)
            // 취소를 무시하고 도착한 응답도 이전 커서·화면 상태를 되살릴 수 없다.
            currentCoroutineContext().ensureActive()
            when (result) {
                is ApiResult.Ok -> if (valid(stamp, owner, id) && resumed) {
                    records = mergeChatMessages(records, result.value.items)
                    // 이전 페이지에서 이미 전송된 메시지를 찾은 경우에도 미확인 말풍선을 정리한다.
                    reconcilePending(owner, id, result.value.items)
                    mutable.update { it.copy(ui = it.ui.copy(nextCursor = result.value.nextCursor, historyLoad = HistoryLoad.Idle)) }; render()
                }
                is ApiResult.Fail -> if (result.error.revokesChat()) fail(result.error, owner, id, stamp)
                    else if (valid(stamp, owner, id)) mutable.update { it.copy(error = result.error, ui = it.ui.copy(historyLoad = HistoryLoad.Failed)) }
            }
        }
    }
    fun recoverNew() = read(background = true) { owner, id, stamp ->
        if (!access(owner, id, stamp, background = true)) return@read
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val result = api.messages(id, cursor, if (cursor == null) receivedThrough else null)
            currentCoroutineContext().ensureActive()
            if (!valid(stamp, owner, id) || !resumed) return@read
            when (result) {
                is ApiResult.Fail -> { readFailure(result.error, owner, id, stamp, background = true); return@read }
                is ApiResult.Ok -> {
                    // 내 전송 응답이 앞 순번을 먼저 가져와도 복구 기준은 수신 페이지가 확정한 위치만 전진한다.
                    val page = result.value
                    require(page.items.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
                    records = mergeChatMessages(records, page.items)
                    receivedThrough = maxOf(receivedThrough, page.items.maxOfOrNull { it.sequence } ?: receivedThrough)
                    reconcilePending(owner, id, page.items); render()
                    cursor = page.nextCursor
                    require(cursor == null || seen.add(cursor))
                }
            }
        } while (cursor != null)
    }

    fun draft(text: String) {
        val s = mutable.value; val owner = s.owner ?: return; val id = s.id ?: return
        if (!s.ui.inputEnabled || s.pending != null) return
        mutable.update { it.copy(ui = it.ui.copy(draft = text)) }
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(200); persistDraft() }
    }
    /** 화면을 빨리 나가도 마지막 입력을 보존한다. 계정이 바뀐 저장은 공통 저장소가 거절한다. */
    private fun persistDraft() {
        val s = mutable.value; val owner = s.owner ?: return; val id = s.id ?: return
        if (!s.ui.readable || s.pending != null || s.sending) return
        val value = ChatDraftRecord(owner.userId, id, s.ui.draft)
        draftJob?.cancel()
        viewModelScope.launch {
            try { storage.withLock {
                // 방 종료가 먼저 확인되었다면 이전 화면에서 예약한 저장으로 초안을 되살리지 않는다.
                if (!local.closedLocked(owner.userId, id)) {
                    local.trackLocked(owner.userId, id)
                    prefs.set(chatDraftKey(id), value.takeIf { it.text.isNotEmpty() }, owner.userId)
                }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (mutable.value.owner == owner && mutable.value.id == id)
                mutable.update { it.copy(error = chatInvalid()) } }
        }
    }
    fun send() {
        val s = mutable.value; val owner = s.owner ?: return; val id = s.id ?: return
        if (!s.ui.submitEnabled || s.pending != null || s.sending) return
        val body = ChatSendBody(Uuid.random().toString(), s.ui.validatedDraft.normalized)
        transmit(PendingChatSend(owner.userId, id, Uuid.random().toString(), body))
    }
    fun retry() { mutable.value.pending?.takeIf { it.room == mutable.value.id }?.let(::transmit) }
    private fun transmit(request: PendingChatSend) {
        val s = mutable.value; val owner = s.owner ?: return
        if (s.sending || !s.ui.readable || !s.ui.canSend || request.owner != owner.userId || request.room != s.id) return
        draftJob?.cancel(); readGeneration++; readJob?.cancel(); readPending = false
        // 전송 때문에 이전 기록 조회를 취소하면 로딩도 함께 해제하여 다시 조회할 수 있게 한다.
        val stamp = writeEpoch; mutable.update { it.copy(sending = true, pending = request, error = null,
            ui = it.ui.copy(historyLoad = HistoryLoad.Idle)) }; render()
        sendJob = viewModelScope.launch {
            try {
                // 전송 전에 기록을 확인한다. 저장 실패 시 네트워크 전송을 시작하지 않는다.
                storage.withLock {
                    check(!local.closedLocked(owner.userId, request.room))
                    local.trackLocked(owner.userId, request.room)
                    prefs.set(pendingChatKey, request, owner.userId)
                    check(prefs.flow(pendingChatKey).first() == request)
                    // AppPrefs는 쓰기 오류를 삼키므로 null 삭제 대신 빈 초안을 쓰고 양쪽 기록을 확인한다.
                    // 여기서 실패하면 미확인 요청을 유지해 다음에도 같은 UUID로만 재시도한다.
                    val cleared = ChatDraftRecord(owner.userId, request.room, "")
                    prefs.set(chatDraftKey(request.room), cleared, owner.userId)
                    check(prefs.flow(chatDraftKey(request.room)).first() == cleared)
                }
                if (!writeValid(stamp, owner, request.room)) return@launch
                mutable.update { it.copy(ui = it.ui.copy(draft = "")) }
                when (val result = api.send(request.room, request.key, request.body)) {
                    is ApiResult.Ok -> {
                        require(result.value.clientMessageId == request.body.clientMessageId && result.value.sender == "ME")
                        if (!writeValid(stamp, owner, request.room)) return@launch
                        storage.withLock { prefs.set(pendingChatKey, null, owner.userId) }
                        mutable.update { it.copy(pending = null) }
                        if (resumed && mutable.value.ui.readable) records = mergeChatMessages(records, listOf(result.value))
                    }
                    is ApiResult.Fail -> if (writeValid(stamp, owner, request.room)) {
                        if (result.error.revokesChat()) fail(result.error, owner, request.room, epoch)
                        else {
                            // 명시적 400/409는 재전송하지 않는다. 응답 유실/5xx는 같은 요청을 남긴다.
                            if (result.error is AppError.Server && result.error.status in listOf(400, 409)) {
                                storage.withLock {
                                    prefs.set(pendingChatKey, null, owner.userId)
                                    if (!local.closedLocked(owner.userId, request.room))
                                        prefs.set(chatDraftKey(request.room), ChatDraftRecord(owner.userId, request.room, request.body.text), owner.userId)
                                }
                                mutable.update { it.copy(pending = null, ui = it.ui.copy(draft = request.body.text)) }
                            }
                            mutable.update { it.copy(error = result.error) }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (writeValid(stamp, owner, request.room)) mutable.update { it.copy(error = chatInvalid()) } }
            finally { if (writeValid(stamp, owner, request.room)) {
                mutable.update { it.copy(sending = false) }; render()
                // 복귀 직후 전송으로 첫 페이지 조회를 취소했다면 전송 종료 뒤 전체 첫 페이지를 다시 받는다.
                // 새 메시지 한 건만 가진 상태를 정상 복구로 취급하지 않는다.
                if (resumed && (!mutable.value.ui.readable || needsInitialPage)) reload()
                else drainRecovery()
            } }
        }
    }
    /** 서버 다운로드나 단순 방 진입이 아니라 LazyColumn에 실제 표시된 상대 메시지만 ACK한다. */
    fun displayed(ids: List<Long>) {
        val s = mutable.value; val owner = s.owner ?: return; val id = s.id ?: return
        if (!resumed || !s.ui.readable) return
        visiblePending.addAll(ids.filter { key -> key !in acknowledged && records.any { it.messageId == key && it.sender == "PARTNER" } })
        if (ackJob?.isActive == true || visiblePending.isEmpty()) return
        val stamp = epoch
        ackJob = viewModelScope.launch {
            // ACK 도중 새로 보인 메시지도 큐에 보존한다. 실패는 사용자 재확인 시에만 재시도한다.
            while (valid(stamp, owner, id) && resumed && visiblePending.isNotEmpty()) {
                val selected = visiblePending.take(100)
                when (val result = api.read(id, selected)) {
                    is ApiResult.Ok -> if (valid(stamp, owner, id) && resumed) {
                        if (!result.value.messageIds.containsAll(selected)) {
                            mutable.update { it.copy(readError = true) }; break
                        }
                        acknowledged.addAll(selected); visiblePending.removeAll(selected.toSet())
                        mutable.update { it.copy(readError = false) }
                    } else break
                    is ApiResult.Fail -> {
                        if (valid(stamp, owner, id)) {
                            if (result.error.revokesChat()) fail(result.error, owner, id, stamp)
                            else mutable.update { it.copy(readError = true) }
                        }
                        break
                    }
                }
            }
        }
    }
    private suspend fun reconcilePending(owner: Session.SignedIn, id: Long, values: List<ChatMessageRecord>) {
        val pending = mutable.value.pending ?: return
        if (pending.room == id && values.any { it.sender == "ME" && it.clientMessageId == pending.body.clientMessageId }) {
            storage.withLock { prefs.set(pendingChatKey, null, owner.userId) }
            mutable.update { it.copy(pending = null) }
        }
    }
    private fun render() { mutable.update { s ->
        val pending = s.pending?.takeIf { it.room == s.id }
        val messages = records.map { ConversationMessage(it.messageId.toString(), it.sender == "ME", it.text, it.createdAt) } +
            listOfNotNull(pending?.let { ConversationMessage("pending", true, it.body.text,
                delivery = if (s.sending) MessageDelivery.Sending else MessageDelivery.Unknown) })
        s.copy(ui = s.ui.copy(messages = if (resumed && s.ui.readable) messages else emptyList(),
            submitting = s.sending || s.pending != null))
    } }
    private suspend fun fail(error: AppError, owner: Session.SignedIn, id: Long, stamp: Long) {
        if (!valid(stamp, owner, id)) return
        records = emptyList(); mutable.update { it.copy(error = error, ui = it.ui.accessError(error.chatCode())) }
        if (error.revokesChat()) {
            // 종료 전에 시작된 조회가 뒤늦게 접근 권한을 되살리지 못하게 한다.
            epoch++; writeEpoch++; readPending = false; visiblePending.clear()
            draftJob?.cancel(); local.forgetRoom(owner.userId, id)
            mutable.update { if (it.owner == owner && it.id == id)
                it.copy(pending = it.pending?.takeUnless { p -> p.room == id }, sending = false) else it }
        }
        // 권한 종료와 HTTP 조회 실패 모두 재시도 전까지 구독을 멈춘다. 저장소 정리 후 취소한다.
        stopRealtime()
    }
    /** 일시적인 백그라운드 조회 실패는 마지막 정상 화면과 만료 타이머를 보존하고 다음 주기 또는 실시간 이벤트에 재시도한다. */
    private suspend fun readFailure(error: AppError, owner: Session.SignedIn, id: Long, stamp: Long, background: Boolean) {
        if (!background || error.revokesChat()) fail(error, owner, id, stamp)
        else if (valid(stamp, owner, id)) mutable.update { it.copy(error = error,
            ui = it.ui.copy(historyLoad = if (it.ui.historyLoad == HistoryLoad.Loading) HistoryLoad.Failed else it.ui.historyLoad)) }
    }
    private fun read(background: Boolean = false, block: suspend (Session.SignedIn, Long, Long) -> Unit) {
        val s = mutable.value; val owner = s.owner ?: return; val id = s.id ?: return
        if (!resumed || s.sending || readPending || owner != sessions.state.value) return
        val stamp = epoch; val readStamp = ++readGeneration; readPending = true
        readJob = viewModelScope.launch {
            try { block(owner, id, stamp) }
            catch (e: CancellationException) { throw e }
            // 예상하지 못한 파싱·저장소 오류도 기존 화면 보존/권한 종료 정책으로 처리한다.
            catch (_: Exception) { currentCoroutineContext().ensureActive(); readFailure(chatInvalid(), owner, id, stamp, background) }
            // 이전 조회의 finally가 새 조회를 끝내거나 중복 복구를 시작하지 않도록 요청 세대도 확인한다.
            finally { if (readStamp == readGeneration && valid(stamp, owner, id)) { readPending = false; drainRecovery() } }
        }
    }
}
