package com.ssafy.e106.features.chat

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 채팅방 위쪽 상대 닉네임 · 사진. 방 상세 응답엔 닉네임이 없어 상대 프로필 API로 따로 받는다 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomHeaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.clear() } }

    private class Server : ChatTestServer() {
        var profiles = 0
        var profileResult: ApiResult<ChatPartnerRecord>? = null
        var roomGate: CompletableDeferred<Unit>? = null
        override suspend fun room(id: Long): ApiResult<ChatRoomRecord> { roomGate?.await(); return super.room(id) }
        override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> { profiles++; return profileResult ?: super.profile(id) }
    }
    private fun room(api: ChatTestServer, nickname: String? = null) = ChatRoomViewModel(api, TestSession(OnboardingStatus.READY),
        ChatLocalStorage(ChatMemoryPrefs()), IdleChatEvents).also { models.put("room", it); it.open(20, nickname) }

    @Test fun accessFillsNicknameOnceAndPollingOrOlderDoNotAskAgain() = test {
        val api = Server().apply { onMessages = { cursor, after ->
            ApiResult.Ok(ChatMessagesPage(listOf(message(2)), "older".takeIf { cursor == null && after == null }, now)) } }
        val m = room(api); runCurrent()
        assertEquals("상대", m.state.value.ui.nickname); assertEquals(1, api.profiles)
        advanceTimeBy(5_001); runCurrent(); m.older(); runCurrent()
        assertTrue(api.calls.size >= 3); assertEquals(1, api.profiles)
    }
    @Test fun profileFailureKeepsRoomOpen() = test {
        for (failure in listOf(AppError.Offline, AppError.Server(404, "RESOURCE_NOT_FOUND", ""), AppError.Server(410, "CHAT_CLOSED", ""))) {
            val api = Server().apply { profileResult = ApiResult.Fail(failure) }
            val m = room(api); runCurrent()
            assertTrue(m.state.value.ui.readable); assertNull(m.state.value.ui.nickname); assertNull(m.state.value.error)
        }
    }
    @Test fun sameRoomReloadKeepsNicknameWhileLoading() = test {
        val api = Server(); val m = room(api); runCurrent()
        val gate = CompletableDeferred<Unit>(); api.roomGate = gate
        m.pause(); m.resume(); runCurrent()
        assertEquals(ConversationLoad.Loading, m.state.value.ui.load); assertEquals("상대", m.state.value.ui.nickname)
        gate.complete(Unit); runCurrent()
        assertTrue(m.state.value.ui.readable); assertEquals("상대", m.state.value.ui.nickname); assertEquals(1, api.profiles)
    }
    @Test fun listNicknameIsFirstValueAndClosedRoomClearsIt() = test {
        val gate = CompletableDeferred<Unit>(); val api = Server().apply { roomGate = gate }
        val m = room(api, "목록 이름"); runCurrent()
        assertEquals(ConversationLoad.Loading, m.state.value.ui.load); assertEquals("목록 이름", m.state.value.ui.nickname)
        api.roomRecord = api.roomRecord.copy(status = "CLOSED"); gate.complete(Unit); runCurrent()
        assertEquals(ConversationLoad.Closed, m.state.value.ui.load); assertNull(m.state.value.ui.nickname)
    }
    @Test fun lateProfileOfPreviousRoomIsDropped() = test {
        val gate = CompletableDeferred<Unit>()
        val api = object : ChatTestServer() {
            override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> {
                if (id == 20L) withContext(NonCancellable) { gate.await() }
                return ApiResult.Ok(ChatPartnerRecord("방$id", 25, "FEMALE", "NON_SMOKER", "NEVER", null, null, false))
            }
        }
        val m = room(api); runCurrent(); m.open(21); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("방21", m.state.value.ui.nickname)
    }
}
