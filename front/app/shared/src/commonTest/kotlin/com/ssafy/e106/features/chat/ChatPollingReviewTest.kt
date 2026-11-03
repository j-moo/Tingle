package com.ssafy.e106.features.chat

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPollingReviewTest {
    @Test fun unscaledImageBoundsRejectLargeAndOverflowingDimensions() {
        assertTrue(chatImageAllocationAllowed(1024, 1024))
        assertTrue(chatImageAllocationAllowed(2048, 2048))
        assertFalse(chatImageAllocationAllowed(2049, 2048))
        assertFalse(chatImageAllocationAllowed(16000, 16000))
        assertFalse(chatImageAllocationAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(chatImageAllocationAllowed(0, 100))
    }
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.clear() } }
    private fun room(api: ChatTestServer, local: ChatLocalStorage = ChatLocalStorage(ChatMemoryPrefs())) =
        ChatRoomViewModel(api, TestSession(OnboardingStatus.READY), local).also { models.put("room", it); it.open(20) }

    @Test fun pollingPermissionFailureKeepsMessagesAndRecoversOnNextTick() = test {
        for (error in listOf(AppError.Offline, AppError.Server(503, "SERVICE_UNAVAILABLE", "later"))) {
            val api = ChatTestServer(); val m = room(api); runCurrent(); m.draft("typing")
            val before = m.state.value.ui.messages
            api.roomFailure = error; advanceTimeBy(5_001); runCurrent()
            assertTrue(m.state.value.ui.readable); assertTrue(m.state.value.ui.inputEnabled)
            assertEquals(before, m.state.value.ui.messages); assertEquals("typing", m.state.value.ui.draft)
            assertEquals(error, m.state.value.error)
            api.roomFailure = null
            api.onMessages = { _, _ -> ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), null, api.now)) }
            advanceTimeBy(5_000); runCurrent()
            assertEquals(listOf("1", "2", "3"), m.state.value.ui.messages.map { it.id }); assertNull(m.state.value.error)
        }
    }
    @Test fun messagePollingFailureKeepsCursorAndRetriesWithoutReload() = test {
        val api = ChatTestServer(); val m = room(api); runCurrent()
        api.onMessages = { _, after -> assertEquals(2L, after); ApiResult.Fail(AppError.Offline) }
        advanceTimeBy(5_001); runCurrent()
        assertTrue(m.state.value.ui.readable); assertEquals(2, m.state.value.ui.messages.size)
        api.onMessages = { _, after -> assertEquals(2L, after); ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), null, api.now)) }
        advanceTimeBy(5_000); runCurrent(); assertEquals(3, m.state.value.ui.messages.size)
    }
    @Test fun temporaryFailureDoesNotDisableExpiryAndRevocationStillClearsImmediately() = test {
        val api = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:06Z") }
        val m = room(api); runCurrent(); api.roomFailure = AppError.Offline
        advanceTimeBy(5_001); runCurrent(); assertTrue(m.state.value.ui.readable)
        advanceTimeBy(1_000); runCurrent(); assertEquals(ConversationLoad.Closed, m.state.value.ui.load)
        val other = ChatTestServer(); val n = room(other); runCurrent()
        other.roomFailure = AppError.Server(403, "FORBIDDEN", "")
        advanceTimeBy(5_001); runCurrent(); assertFalse(n.state.value.ui.readable); assertTrue(n.state.value.ui.messages.isEmpty())
    }
    @Test fun olderPageReconcilesUnknownSendAgainstServerMaskedMessage() = test {
        val api = ChatTestServer().apply {
            onMessages = { _, _ -> ApiResult.Ok(ChatMessagesPage(listOf(message(9)), "older", now)) }
            onSend = { ApiResult.Fail(AppError.Offline) }
        }
        val prefs = ChatMemoryPrefs(); val m = room(api, ChatLocalStorage(prefs)); runCurrent()
        m.draft("original"); m.send(); runCurrent(); val pending = m.state.value.pending!!
        api.onMessages = { cursor, _ ->
            assertEquals("older", cursor)
            ApiResult.Ok(ChatMessagesPage(listOf(api.message(3, "ME").copy(clientMessageId = pending.body.clientMessageId, text = "[웹 주소 차단]")), null, api.now))
        }
        m.older(); runCurrent()
        assertNull(m.state.value.pending); assertNull(prefs.flow(pendingChatKey).first())
        assertEquals(listOf("3", "9"), m.state.value.ui.messages.map { it.id })
        assertEquals("[웹 주소 차단]", m.state.value.ui.messages.first().text)
    }
    @Test fun draftsRemainIndependentAndLegacyDraftMigratesOnlyForItsRoom() = test {
        val prefs = ChatMemoryPrefs(); prefs.set(chatDraftKey, ChatDraftRecord(1, 20, "legacy"), 1)
        val local = ChatLocalStorage(prefs); val m = room(ChatTestServer(), local); runCurrent()
        assertEquals("legacy", m.state.value.ui.draft); assertNull(prefs.flow(chatDraftKey).first())
        m.draft("room20"); m.open(21); runCurrent(); assertEquals("", m.state.value.ui.draft)
        m.draft("room21"); m.open(20); runCurrent(); assertEquals("room20", m.state.value.ui.draft)
        m.open(21); runCurrent(); assertEquals("room21", m.state.value.ui.draft)
    }
    @Test fun restoredServerPermissionAllowsNewDraftAfterRevokedDataWasCleared() = test {
        val api = ChatTestServer(); val m = room(api); runCurrent()
        api.roomFailure = AppError.Server(403, "AI_RISK_CONSENT_REQUIRED", "")
        m.recoverNew(); runCurrent(); assertFalse(m.state.value.ui.readable)
        api.roomFailure = null; m.reload(); runCurrent()
        m.draft("new consent"); m.send(); runCurrent()
        assertEquals(1, api.writes.size); assertNull(m.state.value.pending)
    }
    @Test fun listClearsOnlyExplicitlyClosedRoomAndLateDraftCannotReviveIt() = test {
        val api = ChatTestServer(); val prefs = ChatMemoryPrefs(); val local = ChatLocalStorage(prefs)
        val m = room(api, local); runCurrent(); m.draft("late draft")
        prefs.set(chatDraftKey(21), ChatDraftRecord(1, 21, "keep"), 1)
        prefs.set(pendingChatKey, PendingChatSend(1, 20, "key", ChatSendBody("client", "text")), 1)
        api.roomRecord = api.roomRecord.copy(status = "CLOSED")
        val list = ChatListViewModel(api, TestSession(OnboardingStatus.READY), local).also { models.put("list", it) }
        list.refresh(); runCurrent(); advanceTimeBy(201); runCurrent()
        assertNull(prefs.flow(chatDraftKey(20)).first()); assertNull(prefs.flow(pendingChatKey).first())
        assertEquals("keep", prefs.flow(chatDraftKey(21)).first()?.text)
    }
    @Test fun publicFieldChecksDoNotReloadUnchangedPhotoOrChangeScope() = test {
        var profiles = 0; var photos = 0
        val api = object : ChatTestServer() {
            override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> {
                profiles++; return ApiResult.Ok(ChatPartnerRecord("상대", 25, "FEMALE", "NON_SMOKER", "NEVER", null, null, true))
            }
            override suspend fun photo(id: Long): ApiResult<ByteArray> { photos++; return ApiResult.Fail(AppError.Offline) }
        }
        val m = ChatPartnerViewModel(api, TestSession(OnboardingStatus.READY)).also { models.put("partner", it) }
        m.load(20); runCurrent(); val before = m.state.value
        repeat(3) { m.checkAccess(); runCurrent(); assertEquals(before, m.state.value) }
        api.roomFailure = AppError.Offline; m.checkAccess(); runCurrent(); assertEquals(before, m.state.value)
        assertEquals(4, profiles); assertEquals(1, photos)
        api.roomFailure = AppError.Server(410, "CHAT_CLOSED", ""); m.checkAccess(); runCurrent()
        assertFalse(m.state.value.visible); assertNull(m.state.value.profile); assertNull(m.state.value.image)
    }
    @Test fun permissionCheckCannotResurrectProfileAfterAccountChange() = test {
        val gate = CompletableDeferred<Unit>(); var gated = false
        val api = object : ChatTestServer() {
            override suspend fun room(id: Long): ApiResult<ChatRoomRecord> {
                if (gated) withContext(NonCancellable) { gate.await() }
                return super.room(id)
            }
        }
        val sessions = TestSession(OnboardingStatus.READY)
        val m = ChatPartnerViewModel(api, sessions).also { models.put("partner", it) }
        m.load(20); runCurrent(); gated = true; m.checkAccess(); runCurrent()
        sessions.state.value = Session.SignedOut; runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(m.state.value.scope); assertNull(m.state.value.profile)
    }
}
