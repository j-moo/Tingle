package com.ssafy.e106.features.chat

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

// 테스트 전용 서버/저장소. 앱 실행 DI에는 등록하지 않는다.
internal open class ChatMemoryPrefs : AppPrefs {
    private val values = MutableStateFlow(mapOf<String, Any?>())
    var refuseWrites = false
    @Suppress("UNCHECKED_CAST") override fun <T> flow(key: PrefKey<T>): Flow<T?> = values.map { it[key.name] as T? }
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
        if (!refuseWrites) values.value = values.value + (key.name to value)
    }
    @Suppress("UNCHECKED_CAST") override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> =
        (values.value[key.name] as T?)?.let { PrefRead.Found(it) } ?: PrefRead.Missing
}
internal open class ChatTestServer : ChatRepository {
    val now = "2026-11-03T00:00:00Z"
    var roomRecord = ChatRoomRecord(20, "ACTIVE", now, "2026-11-10T00:00:00Z", null, null, now, true, true, true)
    var roomFailure: AppError? = null
    val calls = mutableListOf<Triple<Long, String?, Long?>>()
    val writes = mutableListOf<Pair<String, ChatSendBody>>()
    val reads = mutableListOf<List<Long>>()
    var onMessages: (suspend (String?, Long?) -> ApiResult<ChatMessagesPage>)? = null
    var onSend: (suspend (ChatSendBody) -> ApiResult<ChatMessageRecord>)? = null
    fun message(id: Long, sender: String = "PARTNER") = ChatMessageRecord(id, "client-$id", sender, id, "server-$id", now)
    override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> = ApiResult.Ok(ChatRoomsPage(listOf(roomRecord), null, now))
    override suspend fun room(id: Long): ApiResult<ChatRoomRecord> = roomFailure?.let { ApiResult.Fail(it) } ?: ApiResult.Ok(roomRecord.copy(chatRoomId = id))
    override suspend fun messages(id: Long, cursor: String?, after: Long?): ApiResult<ChatMessagesPage> {
        calls += Triple(id, cursor, after)
        return onMessages?.invoke(cursor, after) ?: ApiResult.Ok(ChatMessagesPage(listOf(message(2), message(1, "ME")), null, now))
    }
    override suspend fun send(id: Long, key: String, body: ChatSendBody): ApiResult<ChatMessageRecord> {
        writes += key to body
        return onSend?.invoke(body) ?: ApiResult.Ok(message(3, "ME").copy(clientMessageId = body.clientMessageId, text = "[웹 주소 차단]"))
    }
    override suspend fun read(id: Long, ids: List<Long>): ApiResult<ChatReadsResult> { reads += ids; return ApiResult.Ok(ChatReadsResult(ids, now)) }
    override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> = ApiResult.Ok(ChatPartnerRecord("상대", 25, "FEMALE", "NON_SMOKER", "NEVER", null, null, false))
    override suspend fun photo(id: Long): ApiResult<ByteArray> = ApiResult.Fail(AppError.Server(410, "PHOTO_REMOVED", ""))
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun chatTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { store.clear() } }
    private fun vm(server: ChatTestServer, prefs: ChatMemoryPrefs = ChatMemoryPrefs(), session: TestSession = TestSession(OnboardingStatus.READY)) =
        // 전송/복원 검사는 유휴 이벤트로 격리하고 방별 저장소는 실제 기능 객체를 사용한다.
        ChatRoomViewModel(server, session, ChatLocalStorage(prefs), IdleChatEvents).also { store.put("chat", it); it.open(20) }

    @Test fun fastExitPersistsDraftBeforeDebounce() = chatTest {
        val prefs = ChatMemoryPrefs(); val api = ChatTestServer(); val m = vm(api, prefs)
        runCurrent(); m.draft("보존할 초안"); m.pause(); runCurrent()
        assertEquals("보존할 초안", prefs.flow(chatDraftKey(20)).first()?.text)
    }
    @Test fun cancelledReadCannotRestoreAccessAfterPauseAndResume() = chatTest {
        val api = ChatTestServer(); val gate = CompletableDeferred<Unit>(); var first = true
        api.onMessages = { _, _ ->
            if(first) { first = false; withContext(NonCancellable) { gate.await() } }
            ApiResult.Ok(ChatMessagesPage(listOf(api.message(2)), null, api.now))
        }
        val m = vm(api); runCurrent(); m.pause()
        api.roomRecord = api.roomRecord.copy(status = "CLOSED"); m.resume(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(m.state.value.ui.readable); assertTrue(m.state.value.ui.messages.isEmpty())
    }
    @Test fun visibleMessagesArrivingDuringAckAreNotLost() = chatTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : ChatTestServer() {
            override suspend fun read(id: Long, ids: List<Long>): ApiResult<ChatReadsResult> {
                reads += ids; if(reads.size == 1) gate.await()
                return ApiResult.Ok(ChatReadsResult(ids, now))
            }
        }
        api.onMessages = { _, _ -> ApiResult.Ok(ChatMessagesPage(listOf(api.message(2), api.message(3)), null, api.now)) }
        val m = vm(api); runCurrent(); m.displayed(listOf(2)); runCurrent(); m.displayed(listOf(3))
        gate.complete(Unit); runCurrent(); assertEquals(listOf(listOf(2L), listOf(3L)), api.reads)
    }
    @Test fun deniedRoomNeverLoadsMessages() = chatTest {
        val api = ChatTestServer().apply { roomFailure = AppError.OnboardingRequired("가입 필요") }
        val m = vm(api); runCurrent(); assertTrue(api.calls.isEmpty()); assertFalse(m.state.value.ui.readable)
        assertIs<AppError.OnboardingRequired>(m.state.value.error)
    }
    @Test fun readPermissionIsSeparateFromSendPermission() = chatTest {
        val api = ChatTestServer().apply { roomRecord = roomRecord.copy(canSend = false) }
        val m = vm(api); runCurrent(); assertTrue(m.state.value.ui.readable); assertFalse(m.state.value.ui.inputEnabled)
        m.draft("not sent"); m.send(); runCurrent(); assertTrue(api.writes.isEmpty())
    }
    @Test fun downloadDoesNotAckOnlyVisiblePartnerIdsDo() = chatTest {
        val api = ChatTestServer(); val m = vm(api); runCurrent(); assertTrue(api.reads.isEmpty())
        m.displayed(listOf(1, 2, 999)); runCurrent(); assertEquals(listOf(listOf(2L)), api.reads)
        m.displayed(listOf(2)); runCurrent(); assertEquals(1, api.reads.size)
    }
    @Test fun sendShowsServerMaskedBodyAndClearsOriginal() = chatTest {
        val api = ChatTestServer(); val m = vm(api); runCurrent(); m.draft("https://example.com"); m.send(); runCurrent()
        assertNull(m.state.value.pending); assertEquals("", m.state.value.ui.draft)
        assertEquals("[웹 주소 차단]", m.state.value.ui.messages.last().text)
    }
    @Test fun lostResponseSurvivesRecreationWithExactKeyAndBody() = chatTest {
        val api = ChatTestServer().apply { onSend = { ApiResult.Fail(AppError.Offline) } }; val prefs = ChatMemoryPrefs()
        val m = vm(api, prefs); runCurrent(); m.draft("안녕\r\n반가워"); m.send(); runCurrent()
        val first = api.writes.single(); assertEquals("안녕\n반가워", first.second.text)
        val restored = vm(api, prefs); runCurrent(); assertEquals(1, api.writes.size)
        // 재생성 후 기록 복구와 실제 두 번째 전송을 함께 확인한다. last()만 비교하면 무전송도 통과한다.
        assertEquals(first.first, restored.state.value.pending?.key)
        assertEquals(first.second, restored.state.value.pending?.body)
        restored.retry(); runCurrent(); assertEquals(2, api.writes.size); assertEquals(first, api.writes[1])
    }
    @Test fun persistenceFailureDoesNotSend() = chatTest {
        val api = ChatTestServer(); val prefs = ChatMemoryPrefs().apply { refuseWrites = true }
        val m = vm(api, prefs); runCurrent(); m.draft("hello"); m.send(); runCurrent()
        assertTrue(api.writes.isEmpty()); assertNotNull(m.state.value.error)
    }
    @Test fun rotationKeepsOneInFlightSend() = chatTest {
        val api = ChatTestServer(); val gate = CompletableDeferred<Unit>()
        api.onSend = { body -> gate.await(); ApiResult.Ok(api.message(3, "ME").copy(clientMessageId = body.clientMessageId)) }
        val m = vm(api); runCurrent(); m.draft("hello"); m.send(); runCurrent(); m.pause(); m.resume(); m.send(); runCurrent()
        assertEquals(1, api.writes.size); gate.complete(Unit); runCurrent(); assertFalse(m.state.value.sending)
    }
    @Test fun closedRoomClearsMessagesDraftAndPending() = chatTest {
        val api = ChatTestServer().apply { onSend = { ApiResult.Fail(AppError.Offline) } }; val prefs = ChatMemoryPrefs()
        val m = vm(api, prefs); runCurrent(); m.draft("hello"); m.send(); runCurrent()
        api.roomRecord = api.roomRecord.copy(status = "CLOSED", canRead = false, canSend = false)
        m.reload(); runCurrent(); assertEquals(ConversationLoad.Closed, m.state.value.ui.load)
        assertTrue(m.state.value.ui.messages.isEmpty()); assertNull(m.state.value.pending)
        assertNull(prefs.flow(pendingChatKey).first())
    }
    @Test fun pauseStopsReadsAndHidesBody() = chatTest {
        val api = ChatTestServer(); val m = vm(api); runCurrent(); m.pause(); val count = api.calls.size
        advanceTimeBy(10_000); runCurrent(); assertEquals(count, api.calls.size); assertTrue(m.state.value.ui.messages.isEmpty())
    }
    @Test fun lateOldAccountReadCannotShowInNewSession() = chatTest {
        val api = ChatTestServer(); val gate = CompletableDeferred<Unit>(); val session = TestSession(OnboardingStatus.READY)
        api.onMessages = { _, _ -> withContext(NonCancellable) { gate.await() }; ApiResult.Ok(ChatMessagesPage(listOf(api.message(1)), null, api.now)) }
        val m = vm(api, session = session); runCurrent(); session.state.value = Session.SignedOut; runCurrent()
        gate.complete(Unit); runCurrent(); assertNull(m.state.value.owner); assertTrue(m.state.value.ui.messages.isEmpty())
    }
    @Test fun forwardRecoveryUsesCursorOnlyAndDeduplicates() = chatTest {
        val api = ChatTestServer(); val m = vm(api); runCurrent()
        api.onMessages = { cursor, after ->
            if (cursor == null) { assertEquals(2L, after); ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), "opaque+/=", api.now)) }
            else { assertNull(after); assertEquals("opaque+/=", cursor); ApiResult.Ok(ChatMessagesPage(listOf(api.message(3), api.message(4)), null, api.now)) }
        }
        m.recoverNew(); runCurrent(); assertEquals(listOf("1", "2", "3", "4"), m.state.value.ui.messages.map { it.id })
    }
    @Test fun expiryUsesServerTimeAndRevokesLocallyWithoutPollingSuccess() = chatTest {
        val api = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:01Z") }
        val m = vm(api); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(ConversationLoad.Closed, m.state.value.ui.load); assertTrue(m.state.value.ui.messages.isEmpty())
    }
}
