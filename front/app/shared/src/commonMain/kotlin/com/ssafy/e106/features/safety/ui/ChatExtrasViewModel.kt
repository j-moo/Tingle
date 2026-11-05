package com.ssafy.e106.features.safety.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.safety.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ChatExtrasState(val owner: Session.SignedIn? = null, val room: Long? = null,
    val questions: List<String> = emptyList(), val status: String? = null, val busy: Boolean = false,
    val retryable: Boolean = false, val error: AppError? = null, val notices: List<ChatNoticeRecord> = emptyList(),
    val accessRetryRequired: Boolean = false)

/** 부가 기능 실패는 기본 채팅을 취소하지 않는다. 다만 권한을 잃은 결과는 표시하지 않는다. */
@OptIn(ExperimentalUuidApi::class)
class ChatExtrasViewModel(private val api: ChatExtrasRepository, private val chats: ChatRepository,
    private val sessions: SessionStore) : ViewModel() {
    private val mutable = MutableStateFlow(ChatExtrasState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var expiry: Job? = null
    private var roomClock: Pair<Instant, TimeSource.Monotonic.ValueTimeMark>? = null
    private var key: String? = null
    private val ackKeys = mutableMapOf<String, String>()
    private val acked = mutableSetOf<String>()
    private val acking = mutableSetOf<String>()
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        clear(); mutable.value = ChatExtrasState(owner = it as? Session.SignedIn)
    } } }
    fun clear() { generation++; job?.cancel(); expiry?.cancel(); roomClock = null; key = null; ackKeys.clear(); acked.clear(); acking.clear()
        mutable.update { ChatExtrasState(owner = it.owner) } }
    fun bind(room: Long, readable: Boolean) {
        if (!readable) { clear(); return }
        if (mutable.value.room == room) return
        clear(); mutable.update { it.copy(room = room) }; check()
    }
    private fun valid(stamp: Long, owner: Session.SignedIn, room: Long) = stamp == generation && owner == sessions.state.value && mutable.value.room == room
    private suspend fun allowed(room: Long, owner: Session.SignedIn, stamp: Long): Boolean {
        // 이전 방/계정에서 늦게 도착한 조회는 현재 화면의 오류나 만료 시각도 바꾸지 않는다.
        if (!valid(stamp, owner, room)) return false
        val response = chats.room(room)
        currentCoroutineContext().ensureActive()
        if (!valid(stamp, owner, room)) return false
        return when(response) {
        is ApiResult.Fail -> {
            if (response.error.revokesChat()) clear()
            else {
                // 통신 실패는 접근 권한 상실이 아니다. 방과 요청 키는 보존하되 미확인 내용을 숨기고 재시도를 제공한다.
                mutable.update { it.copy(error = response.error, accessRetryRequired = true, questions = emptyList(), notices = emptyList()) }
            }
            false
        }
        is ApiResult.Ok -> response.value.let {
            val now = Instant.parse(it.serverTime); val remaining = Instant.parse(it.expiresAt) - now
            val okay = it.chatRoomId == room && it.status == "ACTIVE" && it.canRead && remaining.isPositive()
            if (okay) {
                roomClock = now to TimeSource.Monotonic.markNow()
                expiry?.cancel(); val stamp = generation
                expiry = viewModelScope.launch { delay(remaining.inWholeMilliseconds); if(stamp == generation) clear() }
            }
            if (!okay) clear()
            okay
        }
        }
    }
    private fun noticeCurrent(notice: ChatNoticeRecord): Boolean = runCatching {
        val clock = roomClock ?: return false
        val now = clock.first + clock.second.elapsedNow()
        now >= Instant.parse(notice.scheduledAt) && now < Instant.parse(notice.expiresAt)
    }.getOrDefault(false)
    fun check() = work { room, owner, stamp ->
        val notices = api.notices(room)
        if (!valid(stamp, owner, room)) return@work
        // 요청 시작 때의 권한으로 지연 안내를 공개하지 않는다. 질문 조회가 느려도 안내는 먼저 보호한다.
        when (notices) {
            is ApiResult.Ok -> {
                if (!allowed(room, owner, stamp)) return@work
                mutable.update { it.copy(notices = notices.value.items.filter {
                    it.type == "ONE_HOUR_BEFORE_END" && it.acknowledgedAt == null && it.text.isNotBlank() && it.noticeId !in acked && noticeCurrent(it)
                }) }
            }
            is ApiResult.Fail -> {
                if (notices.error.revokesChat()) { clear(); return@work }
                // 안내 조회 실패는 이전 안내를 재사용하지 않으며, 독립 기능인 질문 조회는 계속한다.
                mutable.update { it.copy(notices = emptyList()) }
            }
        }
        result(api.questions(room), stamp, owner, room)
    }
    fun generate() {
        if (mutable.value.status == "SUCCEEDED" || mutable.value.status == "FAILED" && !mutable.value.retryable) return
        work { room, owner, stamp ->
            val requestKey = key ?: Uuid.random().toString().also { key = it }
            when(val task = api.requestQuestions(room, requestKey)) {
                is ApiResult.Fail -> if(valid(stamp, owner, room)) {
                    // 생성 응답의 권한 거절도 방 조회 결과보다 최신인 종료 신호로 처리한다.
                    if (task.error.revokesChat()) clear() else mutable.update { it.copy(error = task.error) }
                }
                is ApiResult.Ok -> {
                    val t = task.value
                    require(t.chatRoomId == room && t.kind == "ICEBREAKERS" && t.statusUrl == "/api/chat-rooms/$room/icebreakers")
                    require(t.status in setOf("PENDING", "SUCCEEDED", "FAILED"))
                    require(t.pollAfterSeconds >= 1)
                    if(!valid(stamp, owner, room)) return@work
                    mutable.update { it.copy(status = t.status, retryable = t.retryable) }
                    // 무한 조회를 피한다. 오래 걸리면 수동 확인으로 이어가며 기본 채팅은 계속 가능하다.
                    repeat(if(t.status == "PENDING") 30 else 1) {
                        if (t.status == "PENDING") delay(t.pollAfterSeconds.toLong() * 1000L)
                        if(!allowed(room, owner, stamp)) return@work
                        // HTTP 실패를 PENDING으로 오인해 최대 30회 반복하지 않는다. 수동 GET으로 복구한다.
                        if (!result(api.questions(room), stamp, owner, room)) return@work
                        if(mutable.value.status != "PENDING") return@work

                    }
                }
            }
        }
    }
    /** false는 조회 중단을 뜻한다. 실패를 안전/성공 상태로 바꾸거나 생성 POST를 자동 반복하지 않는다. */
    private suspend fun result(response: ApiResult<IcebreakerResult>, stamp: Long, owner: Session.SignedIn, room: Long): Boolean {
        if(!valid(stamp, owner, room)) return false
        when(response) {
            is ApiResult.Fail -> {
                val error = response.error
                // 질문 GET의 404는 아직 생성하지 않은 경우에도 온다. 방 권한 재확인 후에만 생성 진입을 유지한다.
                val missing = error is AppError.Server && error.status == 404 && error.code == "RESOURCE_NOT_FOUND"
                if (missing) {
                    if (!allowed(room, owner, stamp)) return false
                } else if (error.revokesChat()) { clear(); return false }
                mutable.update { it.copy(error = error, questions = emptyList(), accessRetryRequired = !missing) }
                return false
            }
            is ApiResult.Ok -> {
                val value = response.value
                require(value.status in setOf("PENDING", "SUCCEEDED", "FAILED"))
                if(value.status == "SUCCEEDED") {
                    require(value.source in setOf("GENERATED", "GENERAL_FALLBACK") && !value.promptVersion.isNullOrBlank())
                    require(value.questions.size == 3 && value.questions.all { it.isNotBlank() } && value.questions.distinct().size == 3)
                } else require(value.questions.isEmpty())
                // AI 지연 중 방이 끝났을 수 있으므로 내용 반영 직전에도 현재 방을 검사한다.
                if(!allowed(room, owner, stamp)) return false
                if(valid(stamp, owner, room)) mutable.update { it.copy(status = value.status, questions = value.questions,
                    error = null, retryable = if(value.status == "FAILED") false else it.retryable) }
            }
        }
        return valid(stamp, owner, room)
    }
    fun displayedNotice(id: String) {
        val s = mutable.value; val room = s.room ?: return; val owner = s.owner ?: return
        if(id in acked || !acking.add(id) || s.notices.none { it.noticeId == id && noticeCurrent(it) }) return
        val stamp = generation
        viewModelScope.launch {
            try {
                if(!allowed(room, owner, stamp)) return@launch
                val key = ackKeys.getOrPut(id) { Uuid.random().toString() }
                if(api.acknowledge(room, id, key) is ApiResult.Ok && valid(stamp, owner, room)) {
                    acked += id; mutable.update { it.copy(notices = it.notices.filterNot { it.noticeId == id }) }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { /* 표시 완료 실패는 다음 명시적 표시 때 같은 키로 재시도한다. */ }
            finally { if(valid(stamp, owner, room)) acking -= id }
        }
    }
    private fun work(block: suspend (Long, Session.SignedIn, Long) -> Unit) {
        val s = mutable.value; val owner = s.owner ?: return; val room = s.room ?: return
        if(s.busy) return
        val stamp = generation; mutable.update { it.copy(busy = true, error = null, accessRetryRequired = false) }
        job = viewModelScope.launch {
            // 실패 종류별 처리는 allowed에서 끝낸다. 일시 실패 뒤의 방 연결을 여기서 다시 지우지 않는다.
            try { if(allowed(room, owner, stamp)) block(room, owner, stamp) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner, room)) mutable.update { it.copy(error = chatInvalid(), questions = emptyList()) } }
            finally { if(valid(stamp, owner, room)) mutable.update { it.copy(busy = false) } }
        }
    }
}
