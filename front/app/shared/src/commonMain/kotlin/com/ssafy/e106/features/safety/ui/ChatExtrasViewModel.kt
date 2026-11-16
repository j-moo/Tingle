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
    val accessRetryRequired: Boolean = false,
    // 질문 출처(GENERATED · COMMON_TOPIC_TEMPLATE · GENERAL_FALLBACK). 일반 질문이면 안내 문구를 바꾼다
    val source: String? = null,
    // 관심 주제 동의. null 이면 아직 모름(스위치 숨김)
    val topicConsent: Boolean? = null, val topicSaving: Boolean = false,
    // 아직 닫지 않은 AI 위험 안내(나에게만 보임)
    val safety: List<SafetyNoticeItem> = emptyList(),
    // 이 방의 질문 확인이 한 번이라도 끝났는지(실패 포함). 끝나기 전엔 추천 칸을 그리지 않는다
    val checked: Boolean = false)

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
    private val closedSafety = mutableSetOf<Long>()
    private val closingSafety = mutableSetOf<Long>()
    private var safetyJob: Job? = null
    private var safetyWatch: Job? = null
    // 가장 최근에 시작한 질문 작업 번호. 취소된 옛 작업이 새 작업의 busy 를 풀지 못하게 한다
    private var workSeq = 0L
    init { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.state.collect {
        clear(); mutable.value = ChatExtrasState(owner = it as? Session.SignedIn)
    } } }
    fun clear() { generation++; job?.cancel(); safetyJob?.cancel(); safetyWatch?.cancel(); expiry?.cancel(); roomClock = null; key = null; ackKeys.clear(); acked.clear(); acking.clear(); closedSafety.clear(); closingSafety.clear()
        mutable.update { ChatExtrasState(owner = it.owner) } }
    fun bind(room: Long, readable: Boolean) {
        if (!readable) { clear(); return }
        // 같은 방으로 돌아온 경우: 질문은 그대로 두고 위험 안내만 다시 본다(입장 직후 조회 중이면 생략)
        if (mutable.value.room == room) { if (!mutable.value.busy) refreshSafety(); return }
        clear(); mutable.update { it.copy(room = room) }; check(); loadTopicConsent()
    }
    private fun loadTopicConsent() {
        val owner = mutable.value.owner ?: return; val stamp = generation
        viewModelScope.launch {
            // 조회 실패는 스위치를 숨길 뿐 질문 추천에 영향을 주지 않는다
            val result = runCatching { api.topicConsent(owner.userId) }.getOrNull()
            if (stamp == generation && owner == sessions.state.value && result is ApiResult.Ok &&
                result.value.noticeVersion == TOPIC_CONSENT_VERSION)
                mutable.update { it.copy(topicConsent = result.value.enabled) }
        }
    }
    /** 관심 주제 동의 저장. 응답을 받은 값으로만 스위치를 바꾼다(실패하면 그대로). */
    fun setTopicConsent(enabled: Boolean) {
        val s = mutable.value; val owner = s.owner ?: return
        if (s.topicSaving || s.topicConsent == null || s.topicConsent == enabled) return
        val stamp = generation; mutable.update { it.copy(topicSaving = true) }
        viewModelScope.launch {
            val result = try { api.topicConsent(owner.userId, TopicConsentBody(enabled, TOPIC_CONSENT_VERSION)) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (stamp != generation || owner != sessions.state.value) return@launch
            val saved = (result as? ApiResult.Ok)?.value?.enabled
            mutable.update { it.copy(topicSaving = false, topicConsent = saved ?: it.topicConsent) }
            // 동의를 끄면 서버가 공통 주제 질문을 일반 질문으로 바꾼다. 화면의 옛 질문을 바로 숨기고 다시 조회한다(GET 만, 생성 요청 없음)
            // 끄기 전에 시작한 질문 조회 · 생성 확인은 취소한다. 그 응답이 늦게 와서 옛 공통 주제 질문을 되살리면 안 된다
            if (saved == false) {
                job?.cancel()
                mutable.update { it.copy(busy = false, questions = emptyList(), source = null, status = null) }
                check()
            }
        }
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
                mutable.update { it.copy(error = response.error, accessRetryRequired = true, questions = emptyList(), source = null, notices = emptyList(),
                    safety = emptyList()) }
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
        if (!loadSafety(room, owner, stamp)) return@work
        result(api.questions(room), stamp, owner, room)
    }
    /** AI 위험 안내 조회. 실패는 질문 추천을 막지 않고, 미확인 안내를 안전하다고 바꾸지도 않는다(그냥 안 보임). false 는 방을 더 볼 수 없다는 뜻. */
    private suspend fun loadSafety(room: Long, owner: Session.SignedIn, stamp: Long): Boolean {
        val safety = api.safetyNotices(room)
        if (!valid(stamp, owner, room)) return false
        when (safety) {
            is ApiResult.Ok -> {
                // 안내 공개 직전에 방 권한을 다시 확인한다(종료 · 차단된 방의 안내를 잠깐이라도 보이지 않게)
                if (!allowed(room, owner, stamp)) return false
                mutable.update { it.copy(safety = safety.value.items.filter {
                    it.type in SAFETY_TYPES && it.noticeId !in closedSafety
                }) }
            }
            is ApiResult.Fail -> {
                if (safety.error.revokesChat()) { clear(); return false }
                mutable.update { it.copy(safety = emptyList()) }
            }
        }
        return true
    }
    /** 새 메시지(내가 보낸 것 포함) 뒤에 위험 안내를 정해진 시각에 몇 번만 다시 본다. 메시지가 이어지면 마지막 것부터 다시 센다. */
    fun watchSafety() {
        val s = mutable.value; val room = s.room ?: return; val owner = s.owner ?: return
        val stamp = generation
        safetyWatch?.cancel()
        safetyWatch = viewModelScope.launch {
            var waited = 0L
            for (at in SAFETY_REFRESH_AT_MS) {
                delay(at - waited); waited = at
                if (!valid(stamp, owner, room)) return@launch
                refreshSafety()
            }
        }
    }
    /** 채팅 중 새로 생긴 위험 안내만 다시 본다. 질문 조회 · 생성과 따로 돌아서 질문을 기다리는 중에도 갱신된다. */
    fun refreshSafety() {
        val s = mutable.value; val room = s.room ?: return; val owner = s.owner ?: return
        if (safetyJob?.isActive == true) return
        val stamp = generation
        safetyJob = viewModelScope.launch {
            try { loadSafety(room, owner, stamp) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 다음 메시지나 화면 복귀 때 다시 본다 */ }
        }
    }
    /** 사용자가 안내를 닫으면 서버에 표시 확인을 보낸다. 실패하면 안내를 그대로 둔다(다시 닫을 수 있음). */
    fun closeSafety(id: Long) {
        val s = mutable.value; val room = s.room ?: return; val owner = s.owner ?: return
        if (id in closedSafety || s.safety.none { it.noticeId == id } || !closingSafety.add(id)) return
        val stamp = generation
        viewModelScope.launch {
            try {
                val result = api.safetyShown(room, id)
                if (!valid(stamp, owner, room)) return@launch
                when {
                    result is ApiResult.Ok -> {
                        closedSafety += id; mutable.update { it.copy(safety = it.safety.filterNot { it.noticeId == id }) }
                    }
                    result is ApiResult.Fail && result.error.revokesChat() -> clear()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 다음에 다시 닫으면 재시도한다 */ }
            finally { if (valid(stamp, owner, room)) closingSafety -= id }
        }
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
        // 취소된 작업(동의 철회 전 요청)의 응답은 화면에 반영하지 않는다
        currentCoroutineContext().ensureActive()
        if(!valid(stamp, owner, room)) return false
        when(response) {
            is ApiResult.Fail -> {
                val error = response.error
                // 질문 GET의 404는 아직 생성하지 않은 경우에도 온다. 방 권한 재확인 후에만 생성 진입을 유지한다.
                val missing = error is AppError.Server && error.status == 404 && error.code == "RESOURCE_NOT_FOUND"
                if (missing) {
                    if (!allowed(room, owner, stamp)) return false
                } else if (error.revokesChat()) { clear(); return false }
                mutable.update { it.copy(error = error, questions = emptyList(), source = null, accessRetryRequired = !missing) }
                return false
            }
            is ApiResult.Ok -> {
                val value = response.value
                require(value.status in setOf("PENDING", "SUCCEEDED", "FAILED"))
                if(value.status == "SUCCEEDED") {
                    require(value.source in QUESTION_SOURCES && !value.promptVersion.isNullOrBlank())
                    require(value.questions.size == 3 && value.questions.all { it.isNotBlank() } && value.questions.distinct().size == 3)
                } else require(value.questions.isEmpty())
                // AI 지연 중 방이 끝났을 수 있으므로 내용 반영 직전에도 현재 방을 검사한다.
                if(!allowed(room, owner, stamp)) return false
                if(valid(stamp, owner, room)) mutable.update { it.copy(status = value.status, questions = value.questions,
                    source = value.source.takeIf { value.status == "SUCCEEDED" },
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
        val stamp = generation; val seq = ++workSeq; mutable.update { it.copy(busy = true, error = null, accessRetryRequired = false) }
        job = viewModelScope.launch {
            // 실패 종류별 처리는 allowed에서 끝낸다. 일시 실패 뒤의 방 연결을 여기서 다시 지우지 않는다.
            try { if(allowed(room, owner, stamp)) block(room, owner, stamp) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(valid(stamp, owner, room)) mutable.update { it.copy(error = chatInvalid(), questions = emptyList(), source = null) } }
            // 실패해도 확인은 끝난 것으로 둔다. 칸 안의 "다시 시도"가 보여야 한다
            finally { if(valid(stamp, owner, room) && seq == workSeq) mutable.update { it.copy(busy = false, checked = true) } }
        }
    }
}

/** 서버가 보내는 질문 출처: AI 생성, 두 사람 공통 관심 주제 템플릿, 일반 질문 */
private val QUESTION_SOURCES = setOf("GENERATED", "COMMON_TOPIC_TEMPLATE", "GENERAL_FALLBACK")
/** 서버 RiskAnalysisTypes.NoticeType. 모르는 유형은 보여 주지 않는다 */
internal val SAFETY_TYPES = setOf("MONEY_REQUEST", "PERSONAL_INFO", "OFF_PLATFORM", "HARASSMENT")
/** 서버 IcebreakerTopics.CONSENT_VERSION 과 같아야 저장된다 */
internal const val TOPIC_CONSENT_VERSION = "q040-consent-v1"
// ponytail: 서버 위험 분석은 5초 간격(app.risk.poll-ms)으로 시작하고 AI 응답 시간이 더해져, 마지막 메시지 뒤 이 시각들에만 다시 본다.
// 30초보다 늦은 안내는 다음 메시지 · 화면 복귀 때 잡힌다. 놓치는 일이 잦으면 서버가 안내 저장을 알려 주는 방식으로 바꾼다
internal val SAFETY_REFRESH_AT_MS = listOf(3_000L, 8_000L, 15_000L, 30_000L)
