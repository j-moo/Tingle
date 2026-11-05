package com.ssafy.e106.features.chat

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.realtime.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.KSerializer
import kotlin.test.*

/** 이전 HTTP 회귀 검사에서만 쓰는 유휴 구독. 앱 DI에는 실제 공통 ChatEvents가 필수다. */
internal object IdleChatEvents : ChatEvents {
    override fun <M> connect(roomId: Long, afterSequence: Long, message: KSerializer<M>, sequenceOf: (M) -> Long): Flow<ChatEvent<M>> =
        flow { awaitCancellation() }
}

private class ControlledChatEvents : ChatEvents {
    val events = MutableSharedFlow<ChatEvent<ChatMessageRecord>>(extraBufferCapacity = 32)
    val connections = mutableListOf<Pair<Long, Long>>()
    var active = 0
    @Suppress("UNCHECKED_CAST")
    override fun <M> connect(roomId: Long, afterSequence: Long, message: KSerializer<M>, sequenceOf: (M) -> Long): Flow<ChatEvent<M>> = flow {
        connections += roomId to afterSequence; active++
        try {
            // 테스트의 소비자가 ChatMessageRecord라는 전제. 운영 기반의 역직렬화/인증 구현을 대체하지 않는다.
            events.collect { emit(it as ChatEvent<M>) }
        } finally { active-- }
    }
    fun emit(event: ChatEvent<ChatMessageRecord>) { check(events.tryEmit(event)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRealtimeFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    // 주기 복구는 화면이 사라질 때 종료된다. 테스트도 가상 시간 정리 전에 ViewModel을 해제한다.
    private fun realtimeTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { store.clear() }
    }
    private fun model(api: ChatTestServer, events: ControlledChatEvents,
        session: TestSession = TestSession(OnboardingStatus.READY), prefs: ChatMemoryPrefs = ChatMemoryPrefs()) =
        ChatRoomViewModel(api, session, ChatLocalStorage(prefs), events).also { store.put("chat", it); it.open(20) }

    /** 중복 resume은 구독을 늘리지 않고, 이벤트 누락은 유지한 주기 조회로 보완한다. */
    @Test fun onlyReadableRoomSubscribesOnceAndPeriodicRecoveryRemains() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        assertEquals(listOf(20L to 2L), events.connections)
        m.resume(); m.open(20); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(2, api.calls.size); assertEquals(1, events.connections.size)
        m.pause(); runCurrent(); assertEquals(0, events.active)
        api.roomRecord = api.roomRecord.copy(status = "CLOSED")
        m.resume(); runCurrent()
        assertEquals(1, events.connections.size); assertFalse(m.state.value.ui.readable)
    }

    @Test fun connectedUsesHttpCheckpointRatherThanSocketHighWaterAndPaginates() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        api.onMessages = { cursor, after ->
            if (cursor == null) {
                assertEquals(2L, after)
                ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), "next+/=", api.now))
            } else {
                assertEquals("next+/=", cursor); assertNull(after)
                ApiResult.Ok(ChatMessagesPage(listOf(api.message(4)), null, api.now))
            }
        }
        events.emit(ChatEvent.Connected(999)); runCurrent()
        assertEquals(listOf("1", "2", "3", "4"), m.state.value.ui.messages.map { it.id })
        assertEquals(ChatRealtimeStatus.Connected, m.state.value.ui.realtime)
        assertTrue(api.reads.isEmpty()) // 다운로드만으로 읽음 ACK를 보내면 안 된다.
    }

    @Test fun eventsDuringReadAreCoalescedAndDuplicateIdsDoNotDuplicateMessages() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        val gate = CompletableDeferred<Unit>(); var requests = 0
        api.onMessages = { _, after ->
            requests++
            if (requests == 1) { assertEquals(2L, after); gate.await()
                ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), null, api.now))
            } else {
                assertEquals(3L, after)
                ApiResult.Ok(ChatMessagesPage(listOf(api.message(4)), null, api.now))
            }
        }
        events.emit(ChatEvent.Message("chat-message:3", api.message(3))); runCurrent()
        events.emit(ChatEvent.Message("chat-message:3", api.message(3)))
        repeat(4) { events.emit(ChatEvent.Message("chat-message:4", api.message(4))) }
        runCurrent(); assertEquals(1, requests)
        gate.complete(Unit); runCurrent()
        assertEquals(2, requests)
        assertEquals(listOf("1", "2", "3", "4"), m.state.value.ui.messages.map { it.id })
    }

    @Test fun messageDuringSendRecoversWithoutSkippingEarlierSequences() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        val gate = CompletableDeferred<Unit>()
        api.onSend = { body -> gate.await(); ApiResult.Ok(api.message(10, "ME").copy(clientMessageId = body.clientMessageId)) }
        m.draft("새 메시지"); m.send(); runCurrent()
        events.emit(ChatEvent.Message("chat-message:10", api.message(10, "ME"))); runCurrent()
        assertEquals(1, api.calls.size)
        api.onMessages = { _, after ->
            assertEquals(2L, after) // 내 전송 응답10을 받았어도 누락 복구 시작점은2다.
            ApiResult.Ok(ChatMessagesPage(listOf(api.message(3), api.message(10, "ME")), null, api.now))
        }
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("1", "2", "3", "10"), m.state.value.ui.messages.map { it.id })
        assertEquals(1, api.writes.size); assertNull(m.state.value.pending)
    }

    @Test fun pauseDropsLateRecoveryThenResumeRechecksRoomAndSubscribesOnce() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        val gate = CompletableDeferred<Unit>()
        api.onMessages = { _, _ -> withContext(NonCancellable) { gate.await() }
            ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), null, api.now)) }
        events.emit(ChatEvent.Connected(2)); runCurrent()
        m.pause(); runCurrent(); assertEquals(0, events.active)
        gate.complete(Unit); runCurrent()
        assertTrue(m.state.value.ui.messages.isEmpty())
        api.onMessages = null; m.resume(); runCurrent()
        assertEquals(2, events.connections.size); assertEquals(1, events.active)
        assertEquals(listOf("1", "2"), m.state.value.ui.messages.map { it.id })
    }

    @Test fun accountChangeStopsSubscriptionAndCannotShowOldEvents() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents(); val session = TestSession(OnboardingStatus.READY)
        val m = model(api, events, session); runCurrent()
        session.state.value = Session.SignedIn(99, OnboardingStatus.READY, true); runCurrent()
        events.emit(ChatEvent.Message("chat-message:3", api.message(3))); runCurrent()
        assertEquals(0, events.active); assertTrue(m.state.value.ui.messages.isEmpty())
        assertNull(m.state.value.id)
    }

    @Test fun statuslessClosedEventRevokesContentAndPendingSend() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents(); val prefs = ChatMemoryPrefs()
        val m = model(api, events, prefs = prefs); runCurrent()
        api.onSend = { ApiResult.Fail(AppError.Offline) }; m.draft("미확인 전송"); m.send(); runCurrent()
        assertNotNull(m.state.value.pending)
        events.emit(ChatEvent.Ended(AppError.Server(0, "CHAT_CLOSED", ""))); runCurrent()
        assertEquals(ConversationLoad.Closed, m.state.value.ui.load)
        assertTrue(m.state.value.ui.messages.isEmpty()); assertNull(m.state.value.pending)
        assertNull(prefs.flow(pendingChatKey).first()); assertEquals(0, events.active)
    }

    @Test fun transientAndTerminalConnectionStatesAllowExplicitReconnect() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        events.emit(ChatEvent.Reconnecting(AppError.Offline)); runCurrent()
        assertEquals(ChatRealtimeStatus.Reconnecting, m.state.value.ui.realtime)
        events.emit(ChatEvent.Ended(AppError.Server(0, "CHAT_CONNECT_FAILED", ""))); runCurrent()
        assertEquals(ChatRealtimeStatus.Stopped, m.state.value.ui.realtime)
        assertTrue(m.state.value.ui.readable)
        m.reload(); runCurrent()
        assertEquals(2, events.connections.size); assertEquals(1, events.active)
    }
    /** 방 변경은 중복 resume이 아니므로 새 권한 조회와 구독이 반드시 시작돼야 한다. */
    @Test fun changingRoomWhileResumedRebindsSubscription() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        m.open(21); runCurrent()
        assertEquals(21L, m.state.value.id)
        assertTrue(m.state.value.ui.readable)
        assertEquals(listOf(20L, 21L), events.connections.map { it.first })
        assertEquals(1, events.active)
    }

    /** 최신 조회 오류 복구와 실시간 신호가 함께 동작하며 일시 실패가 대화를 지우지 않아야 한다. */
    @Test fun failedEventRecoveryKeepsMessagesAndNextEventRecovers() = realtimeTest {
        val api = ChatTestServer(); val events = ControlledChatEvents()
        val m = model(api, events); runCurrent()
        api.onMessages = { _, _ -> ApiResult.Fail(AppError.Offline) }
        events.emit(ChatEvent.Message("chat-message:3", api.message(3))); runCurrent()
        assertEquals(listOf("1", "2"), m.state.value.ui.messages.map { it.id })
        assertTrue(m.state.value.ui.readable)
        api.onMessages = { _, _ -> ApiResult.Ok(ChatMessagesPage(listOf(api.message(3), api.message(4)), null, api.now)) }
        events.emit(ChatEvent.Message("chat-message:4", api.message(4))); runCurrent()
        assertEquals(listOf("1", "2", "3", "4"), m.state.value.ui.messages.map { it.id })
    }

}
