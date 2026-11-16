package com.ssafy.e106.core.push

import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionEvent
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

private fun ready(userId: Long) = Session.SignedIn(userId, OnboardingStatus.READY, signupCompleted = true)

private class FakeSession(initial: Session) : SessionStore {
    override val state = MutableStateFlow(initial)
    override val events = emptyFlow<SessionEvent>()
    override suspend fun refreshOnboarding(): ApiResult<Unit> = ApiResult.Ok(Unit)
}

private class FakeDevice(var token: String? = "t1") : PushDevice {
    var fail = false                                  // FCM 오류 흉내
    var diskFail = false                              // 설치 ID 읽기 오류 흉내
    var saveFail = false                              // binding 저장 오류 흉내
    var binding: PushBinding? = null
    override val platform = "ANDROID"
    override suspend fun installationId(): String { check(!diskFail) { "disk" }; return "11111111-1111-1111-1111-111111111111" }
    override suspend fun token(): String? { check(!fail) { "FCM" }; return token }
    override val tokenRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override suspend fun binding() = binding
    override suspend fun saveBinding(binding: PushBinding?) { check(!saveFail) { "disk" }; this.binding = binding }
}

class PushMessageTest {
    @Test
    fun newProposalRequiresMatchingRecipientInstallationBindingAndValidDeadline() {
        val data = mapOf("type" to "NEW_PROPOSAL", "proposalId" to "42", "noticeId" to "proposal-42",
            "recipientUserId" to "7", "bindingId" to "b7", "installationId" to "installation",
            "expiresAt" to "2026-11-13T00:00:00Z")
        val mine = PushBinding(7, "b7")
        val now = Instant.parse("2026-11-12T00:00:00Z")
        val push = pushMessageOf(data)
        assertEquals(PushKind.NEW_PROPOSAL, push.kind)
        assertEquals(PushTarget.Proposal(42), push.target)
        assertEquals(true, push.showTo(7, mine, "installation", now))
        assertEquals(false, push.showTo(8, PushBinding(8, "b7"), "installation", now))
        assertEquals(false, push.showTo(7, PushBinding(7, "old"), "installation", now))
        assertEquals(false, push.showTo(7, mine, "different", now))
        assertEquals(false, push.showTo(7, mine, "installation", Instant.parse(data.getValue("expiresAt"))))
        for (missing in listOf("proposalId", "noticeId", "recipientUserId", "bindingId", "installationId", "expiresAt")) {
            assertEquals(false, pushMessageOf(data - missing).showTo(7, mine, "installation", now), missing)
        }
    }

    @Test
    fun chatMessageOpensRoomByLongId() {
        val m = pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "chatRoomId" to "42", "recipientUserId" to "7", "bindingId" to "b"))
        assertEquals(PushKind.CHAT_MESSAGE, m.kind)
        assertEquals(PushTarget.Chat(42), m.target)
        assertEquals(7L, m.recipientUserId)
        assertEquals("b", m.bindingId)
        // 서버가 제목 · 본문을 안 주면 앱 기본 문구
        assertNull(m.title); assertNull(m.body)
    }

    @Test
    fun matchedOpensNewChatRoom() {
        val m = pushMessageOf(mapOf("type" to "CHAT_ROOM_CREATED", "chatRoomId" to "42", "bindingId" to "b"))
        assertEquals(PushKind.CHAT_ROOM_CREATED, m.kind)
        assertEquals(PushTarget.Chat(42), m.target)
        assertEquals(PushTarget.Home, pushMessageOf(mapOf("type" to "CHAT_ROOM_CREATED", "chatRoomId" to "x")).target)
    }

    @Test
    fun serverTitleAndBodyAreTrimmedAndCapped() {
        val m = pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "chatRoomId" to "42", "title" to " 초록 ", "body" to "가".repeat(400)))
        assertEquals("초록", m.title)
        assertEquals(300, m.body?.length)
        assertNull(pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "title" to "  ", "body" to "")).title)
        // 자르는 자리에 이모지가 걸리면 반쪽을 남기지 않는다
        assertEquals("가".repeat(79), ("가".repeat(79) + "😀").clip(80))
        assertEquals("가".repeat(78) + "😀", ("가".repeat(78) + "😀😀").clip(80))
    }

    @Test
    fun onlyMessagesOfTheRoomOnScreenAreHidden() {
        val message = pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "chatRoomId" to "42"))
        assertTrue(message.seenIn(42))
        assertFalse(message.seenIn(43)); assertFalse(message.seenIn(null))   // 다른 방 · 방을 안 보는 중
        // 같은 방이어도 종료 안내 · 매칭 성사는 알린다
        assertFalse(pushMessageOf(mapOf("type" to "ONE_HOUR_BEFORE_END", "chatRoomId" to "42")).seenIn(42))
        assertFalse(pushMessageOf(mapOf("type" to "CHAT_ROOM_CREATED", "chatRoomId" to "42")).seenIn(42))
    }

    @Test
    fun endingNoticeOpensItsChatRoom() {
        val m = pushMessageOf(mapOf("type" to "ONE_HOUR_BEFORE_END", "chatRoomId" to "42"))
        assertEquals(PushKind.ONE_HOUR_BEFORE_END, m.kind)
        assertEquals(PushTarget.Chat(42), m.target)
        assertNull(m.recipientUserId)
        assertEquals(PushTarget.Home, pushMessageOf(mapOf("type" to "ONE_HOUR_BEFORE_END", "chatRoomId" to "x")).target)
    }

    @Test
    fun unknownTypeOrBrokenIdOpensHome() {
        assertEquals(PushKind.OTHER, pushMessageOf(mapOf("type" to "OTHER")).kind)   // enum 이름 OTHER 를 보내도 OTHER
        assertEquals(PushKind.OTHER, pushMessageOf(mapOf("type" to "NEW_THING")).kind)
        assertEquals(PushTarget.Home, pushMessageOf(mapOf("type" to "NEW_THING", "chatRoomId" to "1")).target)
        assertEquals(PushTarget.Home, pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "chatRoomId" to "x")).target)
        assertEquals(PushTarget.Home, pushMessageOf(emptyMap()).target)
    }

    @Test
    fun showsOnlyForCurrentAccountAndBinding() {
        val chat = pushMessageOf(mapOf("type" to "CHAT_MESSAGE", "chatRoomId" to "1", "recipientUserId" to "7", "bindingId" to "b7"))
        val notice = pushMessageOf(mapOf("type" to "ONE_HOUR_BEFORE_END", "bindingId" to "b7"))   // 수신자 userId 없음
        val mine = PushBinding(7, "b7")
        assertEquals(true, chat.showTo(7, mine))
        assertEquals(true, notice.showTo(7, mine))
        assertEquals(false, chat.showTo(null, mine))                    // 로그아웃 상태
        assertEquals(false, chat.showTo(7, null))                       // 등록 전 · 로그아웃으로 지움
        assertEquals(false, chat.showTo(8, PushBinding(8, "b7")))       // 수신자가 다른 계정
        // 오프라인 로그아웃 뒤 B 로그인: 서버엔 A(b7) 연결이 남았고 B 등록은 아직 → A 의 종료 안내가 와도 안 띄운다
        assertEquals(false, notice.showTo(8, mine))
        assertEquals(false, notice.showTo(8, PushBinding(8, "b8")))
        assertEquals(false, pushMessageOf(mapOf("type" to "ONE_HOUR_BEFORE_END")).showTo(7, mine))   // bindingId 없음
    }
}

class PushEventsTest {
    private fun TestScope.events(session: FakeSession) = DefaultPushEvents(session, backgroundScope).also { runCurrent() }

    @Test
    fun proposalReceiptRefreshesOnceAndDoesNotCreateATap() = runTest {
        val e = events(FakeSession(ready(7)))
        val accounts = mutableListOf<Long>()
        backgroundScope.launch { e.proposalArrivals.collect { accounts += it } }
        runCurrent()
        assertEquals(true, e.proposalArrived(7, "b7", 42))
        assertEquals(false, e.proposalArrived(7, "b7", 42))
        runCurrent()
        assertEquals(listOf(7L), accounts)
        assertNull(e.pending.value)
    }

    @Test
    fun signedOutTapIsIgnored() = runTest {
        val e = events(FakeSession(Session.SignedOut))
        e.open(PushTarget.Chat(1), forUser = 7)
        assertNull(e.pending.value)
    }

    @Test
    fun otherAccountTapIsIgnored() = runTest {
        val e = events(FakeSession(ready(8)))
        e.open(PushTarget.Chat(1), forUser = 7)
        assertNull(e.pending.value)
    }

    @Test
    fun coldStartTapIsKeptWhileRestoringThenDroppedIfSignedOut() = runTest {
        val s = FakeSession(Session.Restoring)
        val e = events(s)
        e.open(PushTarget.Chat(1), forUser = 7)
        assertEquals(PushTarget.Chat(1), e.pending.value)
        s.state.value = ready(7)
        runCurrent()
        assertEquals(PushTarget.Chat(1), e.pending.value)

        e.consume(PushTarget.Chat(1))
        e.open(PushTarget.Home, forUser = 7)
        s.state.value = Session.Restoring
        runCurrent()
        s.state.value = ready(9)   // 복원 결과가 다른 계정
        runCurrent()
        assertNull(e.pending.value)
    }

    @Test
    fun logoutClearsPending() = runTest {
        val s = FakeSession(ready(7))
        val e = events(s)
        e.open(PushTarget.Chat(1), forUser = 7)
        s.state.value = Session.SignedOut
        runCurrent()
        assertNull(e.pending.value)
    }

    @Test
    fun consumeKeepsNewerTap() = runTest {
        val e = events(FakeSession(ready(7)))
        e.open(PushTarget.Chat(1), forUser = 7)
        e.open(PushTarget.Chat(2), forUser = 7)
        e.consume(PushTarget.Chat(1))
        assertEquals(PushTarget.Chat(2), e.pending.value)
        e.consume(PushTarget.Chat(2))
        assertNull(e.pending.value)
    }
}

class PushRegistrarTest {
    private class Harness(scope: TestScope, initial: Session, var status: HttpStatusCode = HttpStatusCode.OK, beforeRegister: suspend () -> Unit = {}) {
        val session = FakeSession(initial)
        val device = FakeDevice()
        val bodies = mutableListOf<String>()
        var bindings = 0
        var offline = false
        var gate: CompletableDeferred<Unit>? = null   // 정하면 응답이 그 동안 멈춘다
        init {
            // 엔진도 테스트 스케줄러에서 돌려 runCurrent() 가 응답까지 기다리게 한다
            val engine = MockEngine.create {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { req ->
                    check(req.method == HttpMethod.Put && req.url.encodedPath == "/api/push-installations")
                    if (offline) throw IOException("offline")
                    bodies += req.body.toByteArray().decodeToString()
                    gate?.await()
                    respond("""{"serverTime":"2026-11-02T00:00:00Z","bindingId":"b${++bindings}"}""", status, headersOf("Content-Type", "application/json"))
                }
            }
            val client = createHttpClient("https://test/api/", httpLog = false, engine = engine)
            PushRegistrar(session, client, device, beforeRegister).start(scope.backgroundScope)
            scope.runCurrent()
        }
    }

    @Test
    fun registersOnceWhenReady() = runTest {
        val h = Harness(this, ready(7))
        assertEquals(listOf("""{"installationId":"11111111-1111-1111-1111-111111111111","platform":"ANDROID","token":"t1"}"""), h.bodies)
        h.session.state.value = Session.SignedIn(7, OnboardingStatus.READY, signupCompleted = false)   // 같은 사용자, 값만 다름
        h.device.tokenRefreshed.emit(Unit)                                                            // 토큰 그대로
        runCurrent()
        assertEquals(1, h.bodies.size)
    }

    @Test
    fun waitsUntilOnboardingFinishes() = runTest {
        val h = Harness(this, Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, signupCompleted = false))
        assertEquals(0, h.bodies.size)
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(1, h.bodies.size)
    }

    @Test
    fun registersAgainAfterRelogin() = runTest {
        val h = Harness(this, ready(7))
        h.session.state.value = Session.SignedOut
        runCurrent()
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(2, h.bodies.size)
    }

    @Test
    fun registersNewToken() = runTest {
        val h = Harness(this, ready(7))
        h.device.token = "t2"
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(2, h.bodies.size)
        assertEquals(true, h.bodies.last().contains("\"token\":\"t2\""))
    }

    @Test
    fun tokenChangedBeforeReadyIsReadWhenReady() = runTest {
        val h = Harness(this, Session.SignedIn(7, OnboardingStatus.PROFILE_REQUIRED, signupCompleted = false))
        h.device.token = "t2"
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(0, h.bodies.size)
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(true, h.bodies.single().contains("\"token\":\"t2\""))
    }

    @Test
    fun retriesAfterServerErrorOrOffline() = runTest {
        val h = Harness(this, ready(7), status = HttpStatusCode.ServiceUnavailable)
        assertEquals(1, h.bodies.size)
        assertNull(h.device.binding)          // 실패 응답에는 저장하지 않는다
        h.status = HttpStatusCode.OK
        h.offline = true
        h.device.tokenRefreshed.emit(Unit)   // 다음 기회: 오프라인이라 또 실패
        runCurrent()
        h.offline = false
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(2, h.bodies.size)
        h.device.tokenRefreshed.emit(Unit)   // 이제 성공했으니 같은 토큰은 안 보냄
        runCurrent()
        assertEquals(2, h.bodies.size)
    }

    @Test
    fun savesBindingAndClearsOnlyOnSignOut() = runTest {
        val h = Harness(this, ready(7))
        assertEquals("7:b1", h.device.binding?.let { "${it.userId}:${it.id}" })
        h.session.state.value = Session.SignedIn(7, OnboardingStatus.AGREEMENT_REQUIRED, signupCompleted = false)   // 약관 재동의 등
        runCurrent()
        h.session.state.value = Session.Restoring
        runCurrent()
        assertEquals("b1", h.device.binding?.id)   // 오프라인 시작에도 알림이 뜨도록 남긴다
        h.session.state.value = Session.SignedOut
        runCurrent()
        assertNull(h.device.binding)
    }

    @Test
    fun accountSwitchWithSameTokenRegistersAgain() = runTest {
        val h = Harness(this, ready(7))
        h.session.state.value = ready(8)   // SignedOut 을 못 보고 바로 다른 계정
        runCurrent()
        assertEquals(2, h.bodies.size)
        assertEquals("8:b2", h.device.binding?.let { "${it.userId}:${it.id}" })
    }

    @Test
    fun lateResponseAfterSignOutDoesNotRestoreBinding() = runTest {
        val h = Harness(this, Session.SignedOut)
        h.gate = CompletableDeferred()
        h.session.state.value = ready(7)
        runCurrent()                          // 요청은 나갔고 응답 대기 중
        assertEquals(1, h.bodies.size)
        h.session.state.value = Session.SignedOut
        runCurrent()
        h.gate!!.complete(Unit)
        runCurrent()
        assertNull(h.device.binding)          // 로그아웃이 지운 binding 을 늦은 응답이 되살리지 않는다
        h.gate = null
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(2, h.bodies.size)        // 완료로 남지 않아 다시 보낸다
    }

    @Test
    fun diskErrorIsRetriedNextTime() = runTest {
        val h = Harness(this, Session.SignedOut)
        h.device.diskFail = true
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(0, h.bodies.size)        // 설치 ID 를 못 읽어도 등록기는 살아 있다
        h.device.diskFail = false
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(1, h.bodies.size)

        h.session.state.value = Session.SignedOut
        runCurrent()
        h.device.saveFail = true              // 이번엔 PUT 성공 뒤 binding 저장이 실패
        h.session.state.value = ready(7)
        runCurrent()
        h.device.saveFail = false
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(3, h.bodies.size)        // 저장 실패는 완료가 아니라 다시 보낸다
        assertEquals("b3", h.device.binding?.id)
    }

    @Test
    fun tokenErrorDoesNotStopRegistrar() = runTest {
        val h = Harness(this, Session.SignedOut)
        h.device.fail = true
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(0, h.bodies.size)
        h.device.fail = false
        h.device.tokenRefreshed.emit(Unit)
        runCurrent()
        assertEquals(1, h.bodies.size)
    }

    @Test
    fun waitsForPendingLogoutFlushBeforeRegistering() = runTest {
        val flush = CompletableDeferred<Unit>()      // 이전 계정 로그아웃 재전송이 아직 안 끝났다
        val h = Harness(this, ready(7), beforeRegister = { flush.await() })
        assertEquals(0, h.bodies.size)                // 서버가 이전 계정에 묶어 둔 설치로 먼저 등록하지 않는다
        flush.complete(Unit)
        runCurrent()
        assertEquals(1, h.bodies.size)
    }

    @Test
    fun noTokenNoRequest() = runTest {
        val h = Harness(this, Session.SignedOut)
        h.device.token = null
        h.session.state.value = ready(7)
        runCurrent()
        assertEquals(0, h.bodies.size)
    }
}
