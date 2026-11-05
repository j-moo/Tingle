package com.ssafy.e106.core.realtime

import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionEvent
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private class Msg(val messageId: Long, val sequence: Long, val text: String = "")

private fun ready(userId: Long) = Session.SignedIn(userId, OnboardingStatus.READY, signupCompleted = true)

private class FakeSession(initial: Session) : SessionStore {
    override val state = MutableStateFlow(initial)
    override val events = emptyFlow<SessionEvent>()
    override suspend fun refreshOnboarding(): ApiResult<Unit> = ApiResult.Ok(Unit)
}

private fun subscribed(after: Long) = """{"type":"CHAT_SUBSCRIBED","version":"1.0","chatRoomId":1,"afterSequence":$after,"serverTime":"2026-11-03T00:00:00Z"}"""
private fun message(seq: Long) = """{"type":"CHAT_MESSAGE","version":"1.0","eventId":"chat-message:$seq","chatRoomId":1,""" +
    """"message":{"messageId":$seq,"sequence":$seq,"text":"t$seq","mine":false},"serverTime":"2026-11-03T00:00:00Z"}"""

/** 연결마다 정해 둔 대본을 돈다. 대본이 끝나면 서버가 닫은 것, 예외면 끊긴 것. 대본이 없으면 열린 채로 있는다 */
private class FakeSocket : ChatSocket {
    val opened = mutableListOf<Pair<Long, String>>()   // (afterSequence, token)
    var open = false
    val scripts = ArrayDeque<suspend (suspend (String) -> Unit) -> Unit>()
    override suspend fun open(roomId: Long, afterSequence: Long, accessToken: String, onText: suspend (String) -> Unit) {
        opened += afterSequence to accessToken
        open = true
        try {
            val script = scripts.removeFirstOrNull() ?: { awaitCancellation() }
            script(onText)
        } finally {
            open = false
        }
    }
}

private class Harness(val scope: TestScope, initial: Session = ready(7)) {
    val session = FakeSession(initial)
    val foreground = MutableStateFlow(true)
    val socket = FakeSocket()
    var token = "a1"
    var tokenError: Exception? = null           // 토큰 저장소 오류 흉내
    val probes = ArrayDeque<ApiResult<Boolean>>()
    var probed = 0
    val events = mutableListOf<ChatEvent<Msg>>()
    val chat = DefaultChatEvents(session, { tokenError?.let { throw it }; token }, foreground, socket, { probed++; probes.removeFirstOrNull() ?: ApiResult.Ok(true) })

    fun collect(after: Long = 0, onEvent: suspend (ChatEvent<Msg>) -> Unit = {}) = scope.backgroundScope.launch {
        // 버퍼 없이: 화면이 받아 갈 때까지 send 가 기다린다(화면이 느린 경우를 재현)
        chat.connect(1, after, Msg.serializer()) { it.sequence }.buffer(Channel.RENDEZVOUS).collect { events += it; onEvent(it) }
    }.also { scope.runCurrent() }
}

class ChatEventsTest {
    @Test
    fun deliversMessagesAndReconnectsSilentlyFromLastSequenceWithFreshToken() = runTest {
        val h = Harness(this)
        h.socket.scripts += { send -> send(subscribed(3)); send(message(4)); send(message(5)) }   // 그 뒤 서버가 닫음(토큰 만료 등)
        h.collect(after = 3)
        h.token = "a2"                         // 확인 조회(HTTP)가 재발급했다
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(listOf(3L to "a1", 5L to "a2"), h.socket.opened)
        assertEquals(1, h.probed)
        val kinds = h.events.map { (it as? ChatEvent.Message)?.message?.sequence ?: it::class.simpleName }
        assertEquals(listOf("Connected", 4L, 5L), kinds)   // 화면에 끊김을 알리지 않는다
        assertEquals("chat-message:4", (h.events[1] as ChatEvent.Message).eventId)
        assertTrue(h.socket.open)
    }

    @Test
    fun offlineBacksOffAndReportsReconnecting() = runTest {
        val h = Harness(this)
        repeat(2) { h.socket.scripts += { throw IOException("down") } }
        h.probes += ApiResult.Fail(AppError.Offline)
        h.probes += ApiResult.Fail(AppError.Offline)
        h.collect()
        assertEquals(1, h.socket.opened.size)
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(2, h.socket.opened.size)
        advanceTimeBy(RECONNECT_DELAYS[1] - 100)
        assertEquals(2, h.socket.opened.size, "두 번째는 더 기다린다")
        advanceTimeBy(200)
        assertEquals(3, h.socket.opened.size)
        assertEquals(listOf<ChatEvent<Msg>>(ChatEvent.Reconnecting(AppError.Offline), ChatEvent.Reconnecting(AppError.Offline)), h.events)
    }

    @Test
    fun transportErrorWithRoomStillReadableIsShownAsOffline() = runTest {
        val h = Harness(this)
        h.socket.scripts += { send -> send(subscribed(0)); throw IOException("reset") }
        h.collect()
        assertEquals(ChatEvent.Reconnecting(AppError.Offline), h.events.last())
    }

    @Test
    fun endsWhenRoomClosedOrRequestRejected() = runTest {
        for ((probe, expected) in listOf(
            ApiResult.Ok(false) to "CHAT_CLOSED",
            ApiResult.Fail(AppError.SessionExpired) to "SessionExpired",
            ApiResult.Fail(AppError.OnboardingRequired("m")) to "OnboardingRequired",
            ApiResult.Fail(AppError.Server(404, "NOT_FOUND", "m")) to "NOT_FOUND",
        )) {
            val h = Harness(this)
            h.socket.scripts += { }                      // 연결 단계 거절 · 서버가 닫음
            h.probes += probe
            val job = h.collect()
            advanceTimeBy(60_000)
            assertTrue(job.isCompleted, "Ended 뒤 Flow 가 끝난다")
            val end = h.events.single() as ChatEvent.Ended
            assertEquals(expected, (end.error as? AppError.Server)?.code ?: end.error::class.simpleName, "$probe")
            assertEquals(1, h.socket.opened.size, "끝나면 다시 연결하지 않는다")
        }
    }

    @Test
    fun temporaryProbeFailuresAreRetried() = runTest {
        for (error in listOf(AppError.Server(503, "SERVICE_UNAVAILABLE", "m"), AppError.Server(429, "TOO_MANY_REQUESTS", "m"), AppError.Unknown(IllegalStateException()))) {
            val h = Harness(this)
            h.socket.scripts += { }
            h.probes += ApiResult.Fail(error)
            h.collect()
            advanceTimeBy(RECONNECT_DELAYS[0] + 1)
            assertEquals(2, h.socket.opened.size, "$error")
            assertEquals(ChatEvent.Reconnecting(error), h.events.single())
        }
    }

    @Test
    fun rejectedUpgradeIsNotShownAsOffline() = runTest {
        val h = Harness(this)
        h.socket.scripts += { throw IOException("Expected HTTP 101 response but was '400'") }   // OkHttp 는 401 밖의 거절을 IOException 으로
        h.collect()                                                                              // 방은 읽힌다(Ok(true))
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(2, h.socket.opened.size)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun repeatedRejectionWhileRoomReadableEnds() = runTest {
        val h = Harness(this)
        repeat(MAX_REJECTED) { h.socket.scripts += { } }   // 구독 없이 닫힘(잘못된 afterSequence 등), 방은 읽힌다
        h.collect()
        advanceTimeBy(10 * 60_000)
        assertEquals(MAX_REJECTED, h.socket.opened.size)
        assertEquals("CHAT_CONNECT_FAILED", ((h.events.last() as ChatEvent.Ended).error as AppError.Server).code)
    }

    @Test
    fun subscribedConnectionResetsBackoffAndRejectionCount() = runTest {
        val h = Harness(this)
        repeat(MAX_REJECTED - 1) { h.socket.scripts += { } }
        h.socket.scripts += { send -> send(subscribed(0)) }   // 구독됐다가 닫힘
        repeat(MAX_REJECTED - 1) { h.socket.scripts += { } }
        h.collect()
        advanceTimeBy(10 * 60_000)
        assertTrue(h.events.none { it is ChatEvent.Ended }, "구독되면 거절 횟수를 다시 센다")
        assertTrue(h.socket.open)
    }

    @Test
    fun backoffStartsOverAfterSubscription() = runTest {
        val h = Harness(this)
        repeat(3) { h.socket.scripts += { } }                   // 1 · 2 · 5초 기다리며 실패
        h.socket.scripts += { send -> send(subscribed(0)) }     // 구독됐다가 닫힘 → 다음은 다시 1초
        h.collect()
        advanceTimeBy(RECONNECT_DELAYS.take(3).sum() + 1)
        assertEquals(4, h.socket.opened.size)
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(5, h.socket.opened.size)
    }

    @Test
    fun disconnectsInBackgroundAfterGraceAndReconnectsInForeground() = runTest {
        val h = Harness(this)
        h.socket.scripts += { send -> send(subscribed(0)); send(message(1)); awaitCancellation() }
        h.collect()
        h.foreground.value = false
        advanceTimeBy(BACKGROUND_GRACE_MILLIS - 100)
        h.foreground.value = true                // 화면 회전처럼 잠깐
        runCurrent()
        assertTrue(h.socket.open, "잠깐은 끊지 않는다")
        h.foreground.value = false
        advanceTimeBy(BACKGROUND_GRACE_MILLIS + 1)
        assertTrue(!h.socket.open)
        assertEquals(0, h.probed, "백그라운드로 끊은 건 조회하지 않는다")
        advanceTimeBy(60_000)
        assertEquals(1, h.socket.opened.size, "백그라운드에선 다시 연결하지 않는다")
        h.foreground.value = true
        runCurrent()
        assertEquals(listOf(0L to "a1", 1L to "a1"), h.socket.opened)
    }

    @Test
    fun endsOnSignOutOrAccountChange() = runTest {
        for (next in listOf<Session>(Session.SignedOut, ready(8))) {
            val h = Harness(this)
            h.socket.scripts += { send -> send(subscribed(0)); awaitCancellation() }
            val job = h.collect()
            h.session.state.value = next
            runCurrent()
            assertTrue(!h.socket.open)
            assertTrue(job.isCompleted, "Ended 뒤 Flow 가 끝난다")
            assertEquals(ChatEvent.Ended(AppError.SessionExpired), h.events.last())
        }
    }

    @Test
    fun notSignedInEndsWithoutConnecting() = runTest {
        val h = Harness(this, Session.SignedOut)
        h.collect()
        assertEquals(listOf<ChatEvent<Msg>>(ChatEvent.Ended(AppError.SessionExpired)), h.events)
        assertEquals(0, h.socket.opened.size)
    }

    @Test
    fun stoppingCollectionCloses() = runTest {
        val h = Harness(this)
        val job = h.collect()
        assertTrue(h.socket.open)
        job.cancel()
        runCurrent()
        assertTrue(!h.socket.open)
    }

    @Test
    fun brokenFrameEndsConnectionAndReconnects() = runTest {
        val h = Harness(this)
        h.socket.scripts += { send -> send(subscribed(0)); send("not json") }
        h.collect()
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(2, h.socket.opened.size)
    }

    @Test
    fun messageNotHandedOverIsReceivedAgain() = runTest {
        val h = Harness(this)
        val slow = CompletableDeferred<Unit>()
        h.socket.scripts += { send -> send(subscribed(0)); send(message(1)); awaitCancellation() }
        h.collect { if (it is ChatEvent.Connected && h.events.size == 1) slow.await() }   // 첫 Connected 를 오래 붙잡음 → message(1) 의 send 가 대기
        h.foreground.value = false
        advanceTimeBy(BACKGROUND_GRACE_MILLIS + 1)    // 넘기는 도중 백그라운드로 끊김
        h.foreground.value = true
        slow.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L, 0L), h.socket.opened.map { it.first }, "넘기지 못한 메시지를 건너뛰지 않는다")
        assertTrue(h.events.none { it is ChatEvent.Message })
    }

    @Test
    fun repeatedMalformedFramesEndEvenAfterSubscription() = runTest {
        val h = Harness(this)
        repeat(10) { h.socket.scripts += { send -> send(subscribed(0)); send("""{"type":"CHAT_MESSAGE","eventId":"x","message":{"bad":1}}""") } }
        h.collect()
        advanceTimeBy(RECONNECT_DELAYS[0] + 1)
        assertEquals(2, h.socket.opened.size)
        advanceTimeBy(RECONNECT_DELAYS[1] - 100)
        assertEquals(2, h.socket.opened.size, "구독돼도 형식 오류면 대기 시간이 늘어난다")
        advanceTimeBy(10 * 60_000)
        assertEquals(MAX_REJECTED, h.socket.opened.size)
        assertEquals("CHAT_PROTOCOL_ERROR", ((h.events.last() as ChatEvent.Ended).error as AppError.Server).code)
    }

    @Test
    fun endsWhenSameAccountLeavesReady() = runTest {
        val h = Harness(this)
        h.socket.scripts += { send -> send(subscribed(0)); awaitCancellation() }
        val job = h.collect()
        h.session.state.value = Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, signupCompleted = true)   // 약관 재동의
        runCurrent()
        assertTrue(!h.socket.open)
        assertTrue((h.events.last() as ChatEvent.Ended).error is AppError.OnboardingRequired)
        assertTrue(job.isCompleted)
    }

    @Test
    fun notReadyEndsWithoutConnecting() = runTest {
        val h = Harness(this, Session.SignedIn(7, OnboardingStatus.PROFILE_REQUIRED, signupCompleted = false))
        h.collect()
        assertTrue((h.events.single() as ChatEvent.Ended).error is AppError.OnboardingRequired)
        assertEquals(0, h.socket.opened.size)
    }

    @Test
    fun tokenStoreErrorEndsWithEvent() = runTest {
        val h = Harness(this)
        h.tokenError = IllegalStateException("Keystore")
        val job = h.collect()
        assertTrue((h.events.single() as ChatEvent.Ended).error is AppError.Unknown)
        assertTrue(job.isCompleted && !job.isCancelled, "예외로 실패하지 않고 끝난다")
    }

    @Test
    fun eventsUrlKeepsBasePath() {
        assertEquals("wss://k15e106.p.ssafy.io/dev/api/chat-rooms/7/events?afterSequence=3", chatEventsUrl("https://k15e106.p.ssafy.io/dev/api/", 7, 3))
        assertEquals("ws://10.0.2.2:8080/api/chat-rooms/1/events?afterSequence=0", chatEventsUrl("http://10.0.2.2:8080/api/", 1, 0))
    }
}
