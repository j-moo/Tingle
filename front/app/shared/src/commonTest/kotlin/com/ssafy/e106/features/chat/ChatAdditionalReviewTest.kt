package com.ssafy.e106.features.chat

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.storage.PrefKey
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAdditionalReviewTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun teardown() { models.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.clear() } }
    // 실시간 입력은 별도 검사로 분리하고 이 검사는 기존 조회/저장 복구 전제를 유지한다.
    private fun room(api: ChatTestServer) = ChatRoomViewModel(api, TestSession(OnboardingStatus.READY),
        ChatLocalStorage(ChatMemoryPrefs()), IdleChatEvents, now = { dispatcher.scheduler.currentTime.milliseconds })
        .also { models.put("room", it); it.open(20) }

    @Test fun delayedInitialMessagesDoNotOverwriteNewDraft() = test {
        val gate = CompletableDeferred<Unit>(); val api = ChatTestServer()
        api.onMessages = { _, _ -> gate.await(); ApiResult.Ok(ChatMessagesPage(listOf(api.message(2)), null, api.now)) }
        val m = room(api); runCurrent(); assertTrue(m.state.value.ui.inputEnabled)
        m.draft("안녕하세요"); advanceTimeBy(201); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("안녕하세요", m.state.value.ui.draft)
    }

    @Test fun sendBeforeReturnHistoryLoadsRestoresMessagesOneTwoAndThree() = test {
        val api = ChatTestServer(); val m = room(api); runCurrent()
        assertEquals(listOf("1", "2"), m.state.value.ui.messages.map { it.id })
        m.pause()
        val gate = CompletableDeferred<Unit>(); var firstReturnPage = true
        api.onMessages = { _, after ->
            if (after == null && firstReturnPage) {
                firstReturnPage = false
                // 취소된 복귀 조회가 뒤늦게 도착하는 경우까지 재현한다.
                withContext(NonCancellable) { gate.await() }
                ApiResult.Ok(ChatMessagesPage(listOf(api.message(2), api.message(1, "ME")), null, api.now))
            } else {
                val messages = listOf(api.message(1, "ME"), api.message(2), api.message(3, "ME"))
                ApiResult.Ok(ChatMessagesPage(if (after == null) messages.reversed() else messages.filter { it.sequence > after }, null, api.now))
            }
        }
        m.resume(); runCurrent(); assertTrue(m.state.value.ui.inputEnabled)
        m.draft("세 번째 메시지"); m.send(); runCurrent()
        val afterSend = m.state.value.ui.messages.map { it.id }
        gate.complete(Unit); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, api.writes.size)
        // 폴링을 기다리기 전 복구 완료와, 취소된 옛 응답/후속 폴링 뒤 유지 모두 확인한다.
        assertEquals(listOf("1", "2", "3"), afterSend)
        assertEquals(listOf("1", "2", "3"), m.state.value.ui.messages.map { it.id })
    }

    @Test fun sendingDuringOlderFetchReleasesHistoryLoadingAndIgnoresCancelledResult() = test {
        val gate = CompletableDeferred<Unit>(); val api = ChatTestServer()
        api.onMessages = { cursor, _ ->
            if (cursor != null) withContext(NonCancellable) { gate.await() }
            ApiResult.Ok(ChatMessagesPage(listOf(api.message(2)), if (cursor == null) "older" else "stale", api.now))
        }
        val m = room(api); runCurrent(); m.older(); runCurrent()
        assertEquals(HistoryLoad.Loading, m.state.value.ui.historyLoad)
        m.draft("전송"); m.send(); runCurrent()
        // 취소된 요청의 늦은 응답을 실제로 완료한 후 버튼과 커서를 확인한다.
        gate.complete(Unit); runCurrent()
        assertEquals(1, api.writes.size); assertTrue(m.state.value.ui.canLoadOlder)
        assertEquals("older", m.state.value.ui.nextCursor)
        api.onMessages = { cursor, _ -> assertEquals("older", cursor); ApiResult.Ok(ChatMessagesPage(emptyList(), null, api.now)) }
        m.older(); runCurrent(); assertNull(m.state.value.ui.nextCursor)
    }

    private fun delayedPermissionServer() = object : ChatTestServer() {
        var permissionCalls = 0
        init { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:07Z") }
        override suspend fun room(id: Long): ApiResult<ChatRoomRecord> {
            permissionCalls++
            if (permissionCalls > 1) {
                delay(1_500)
                return ApiResult.Ok(roomRecord.copy(serverTime = "2026-11-03T00:00:05Z"))
            }
            return super.room(id)
        }
    }

    @Test fun delayedPermissionAndMessagesCannotExtendKnownRoomExpiry() = test {
        val api = delayedPermissionServer(); val gate = CompletableDeferred<Unit>()
        val m = room(api); runCurrent()
        api.onMessages = { _, _ -> withContext(NonCancellable) { gate.await() }; ApiResult.Ok(ChatMessagesPage(listOf(api.message(3)), null, api.now)) }
        advanceTimeBy(7_000); runCurrent()
        // 응답은 6.5초에 도착해도 최초에 확인한 7초 종료를 미룰 수 없다.
        val atExpiry = m.state.value.ui
        gate.complete(Unit); runCurrent()
        assertEquals(ConversationLoad.Closed, atExpiry.load)
        assertEquals(ConversationLoad.Closed, m.state.value.ui.load)
        assertTrue(m.state.value.ui.messages.isEmpty())
    }

    @Test fun delayedPermissionCannotExtendKnownPartnerExpiry() = test {
        val api = delayedPermissionServer()
        val m = ChatPartnerViewModel(api, TestSession(OnboardingStatus.READY), now = { testScheduler.currentTime.milliseconds })
            .also { models.put("partner", it) }
        m.load(20); runCurrent(); assertTrue(m.state.value.visible)
        advanceTimeBy(5_000); m.checkAccess(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ChatPartnerLoad.Closed, m.state.value.load); assertFalse(m.state.value.visible)
    }

    @Test fun initialPermissionDelayConsumesRemainingLifetimeBeforeAnyContentRequest() = test {
        val api = object : ChatTestServer() {
            var profiles = 0
            override suspend fun room(id: Long): ApiResult<ChatRoomRecord> {
                delay(3_000)
                return ApiResult.Ok(roomRecord.copy(expiresAt = "2026-11-03T00:00:02Z"))
            }
            override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> { profiles++; return super.profile(id) }
        }
        val m = room(api)
        val partner = ChatPartnerViewModel(api, TestSession(OnboardingStatus.READY), now = { testScheduler.currentTime.milliseconds })
            .also { models.put("partner", it); it.load(20) }
        advanceTimeBy(3_000); runCurrent()
        assertEquals(ConversationLoad.Closed, m.state.value.ui.load)
        assertEquals(ChatPartnerLoad.Closed, partner.state.value.load)
        assertTrue(api.calls.isEmpty()); assertEquals(0, api.profiles)
    }

    @Test fun olderPermissionOfflineAndServerFailurePreserveEvenUndebouncedInput() = test {
        for (failure in listOf(AppError.Offline, AppError.Server(503, "SERVICE_UNAVAILABLE", "later"))) {
            val api = ChatTestServer().apply { onMessages = { _, _ -> ApiResult.Ok(ChatMessagesPage(listOf(message(2)), "older", now)) } }
            val m = room(api); runCurrent(); m.draft("저장 전 입력")
            api.roomFailure = failure; m.older(); runCurrent()
            assertEquals("저장 전 입력", m.state.value.ui.draft); assertEquals("older", m.state.value.ui.nextCursor)
            assertEquals(1, m.state.value.ui.messages.size); assertTrue(m.state.value.ui.inputEnabled)
            assertEquals(HistoryLoad.Failed, m.state.value.ui.historyLoad)
            api.roomFailure = null; m.older(); runCurrent(); assertEquals(HistoryLoad.Idle, m.state.value.ui.historyLoad)
        }
    }

    @Test fun draftClearFailureStopsHttpAndPreservesSameRequestForRetry() = test {
        val prefs = object : ChatMemoryPrefs() {
            var rejectClear = false
            override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) {
                if (rejectClear && key.name == chatDraftKey(20).name && (value as? ChatDraftRecord)?.text == "") return
                super.set(key, value, forUser)
            }
        }
        val api = ChatTestServer(); val local = ChatLocalStorage(prefs)
        val m = ChatRoomViewModel(api, TestSession(OnboardingStatus.READY), local, IdleChatEvents).also { models.put("room", it); it.open(20) }
        runCurrent(); m.draft("중복되면 안 되는 본문"); advanceTimeBy(201); runCurrent()
        prefs.rejectClear = true; m.send(); runCurrent()
        assertTrue(api.writes.isEmpty()); val pending = assertNotNull(m.state.value.pending)
        assertEquals(pending, prefs.flow(pendingChatKey).first())
        prefs.rejectClear = false; m.retry(); runCurrent()
        assertEquals(listOf(pending.key to pending.body), api.writes)
        val reopened = ChatRoomViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(prefs), IdleChatEvents)
            .also { models.put("room", it); it.open(20) }
        runCurrent(); assertEquals("", reopened.state.value.ui.draft); assertNull(reopened.state.value.pending)
    }

    @Test fun publicConsentWithdrawalRemovesFieldsAndPhotoWithoutResettingScope() = test {
        val api = object : ChatTestServer() {
            var withdrawn = false; var photos = 0
            override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> = ApiResult.Ok(ChatPartnerRecord(
                "상대", 25, "FEMALE", "NON_SMOKER", "NEVER", if (withdrawn) null else "BUDDHISM",
                if (withdrawn) null else "소개", !withdrawn))
            override suspend fun photo(id: Long): ApiResult<ByteArray> { photos++; return ApiResult.Fail(AppError.Offline) }
        }
        val m = ChatPartnerViewModel(api, TestSession(OnboardingStatus.READY)).also { models.put("partner", it); it.load(20) }
        runCurrent(); val scope = m.state.value.scope; assertEquals("소개", m.state.value.profile?.introduction)
        api.withdrawn = true; m.checkAccess(); runCurrent()
        assertEquals(scope, m.state.value.scope); assertTrue(m.state.value.visible)
        assertNull(m.state.value.profile?.religion); assertNull(m.state.value.profile?.introduction)
        assertFalse(m.state.value.profile!!.hasProfilePhoto); assertNull(m.state.value.image)
        assertEquals(ChatPartnerPhoto.Empty, m.state.value.photo); assertEquals(1, api.photos)
    }

    @Test fun listKeepsNicknameOnlyForReadableRooms() = test {
        val api = object : ChatTestServer() {
            override suspend fun list(cursor: String?) = ApiResult.Ok(ChatRoomsPage(listOf(
                roomRecord.copy(partnerNickname = "하늘"),
                roomRecord.copy(chatRoomId = 21, canRead = false, partnerNickname = "숨김"),
                roomRecord.copy(chatRoomId = 22),
            ), null, now))
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()))
            .also { models.put("list", it); it.refresh() }
        runCurrent()
        val names = m.state.value.ui.rooms.associate { it.chatRoomId to it.nickname }
        assertEquals(mapOf(20L to "하늘", 21L to null, 22L to null), names)
    }

    @Test fun absentClosedRoomIsRecheckedAndCleanedAfterStorageRecreation() = test {
        val prefs = ChatMemoryPrefs(); val firstLocal = ChatLocalStorage(prefs)
        firstLocal.mutex.withLock { firstLocal.trackLocked(1, 20) }
        prefs.set(chatDraftKey(20), ChatDraftRecord(1, 20, "private draft"), 1)
        prefs.set(pendingChatKey, PendingChatSend(1, 20, "key", ChatSendBody("client", "text")), 1)
        val api = object : ChatTestServer() {
            val checked = mutableListOf<Long>()
            override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> = ApiResult.Ok(ChatRoomsPage(emptyList(), null, now))
            override suspend fun room(id: Long): ApiResult<ChatRoomRecord> { checked += id; return ApiResult.Ok(roomRecord.copy(status = "CLOSED")) }
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(prefs))
            .also { models.put("list", it); it.refresh() }
        runCurrent(); assertTrue(m.state.value.ui.rooms.isEmpty()); assertEquals(listOf(20L), api.checked)
        assertNull(prefs.flow(chatDraftKey(20)).first()); assertNull(prefs.flow(pendingChatKey).first())
    }

    @Test fun absentRoomIsNotDeletedForPaginationOrTemporaryPermissionFailure() = test {
        val prefs = ChatMemoryPrefs(); val local = ChatLocalStorage(prefs)
        local.mutex.withLock { local.trackLocked(1, 20) }
        prefs.set(chatDraftKey(20), ChatDraftRecord(1, 20, "keep"), 1)
        val api = object : ChatTestServer() {
            override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> = ApiResult.Ok(ChatRoomsPage(emptyList(), "page2", now))
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), local).also { models.put("list", it) }
        for (failure in listOf(null, AppError.Offline, AppError.Server(503, "UNAVAILABLE", ""))) {
            api.roomFailure = failure; m.refresh(); runCurrent()
            assertEquals("keep", prefs.flow(chatDraftKey(20)).first()?.text)
        }
    }

    @Test fun listRemovesExpiredRowAndDraftWhileRemainingOnScreen() = test {
        val prefs = ChatMemoryPrefs(); val local = ChatLocalStorage(prefs)
        local.mutex.withLock { local.trackLocked(1, 20) }
        prefs.set(chatDraftKey(20), ChatDraftRecord(1, 20, "expired"), 1)
        val api = ChatTestServer().apply { roomRecord = roomRecord.copy(expiresAt = "2026-11-03T00:00:02Z") }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), local, now = { testScheduler.currentTime.milliseconds })
            .also { models.put("list", it); it.refresh() }
        runCurrent(); assertEquals(1, m.state.value.ui.rooms.size)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(m.state.value.ui.rooms.isEmpty()); assertNull(prefs.flow(chatDraftKey(20)).first())
    }
    // 당겨서 새로고침: 보던 목록은 두고 refreshing 만 켠다. 실패하면 목록 + refreshFailed, 다시 성공하면 풀린다
    @Test fun pullRefreshKeepsRoomsAndMarksFailure() = test {
        val api = object : ChatTestServer() {
            var answer: (suspend () -> ApiResult<ChatRoomsPage>)? = null
            override suspend fun list(cursor: String?) = answer?.invoke() ?: super.list(cursor)
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()))
            .also { models.put("list", it); it.refresh() }
        runCurrent(); assertEquals(1, m.state.value.ui.rooms.size)
        val gate = CompletableDeferred<ApiResult<ChatRoomsPage>>(); api.answer = { gate.await() }
        m.refresh(); runCurrent()
        assertTrue(m.state.value.ui.refreshing); assertEquals(ChatListLoad.Ready, m.state.value.ui.load); assertEquals(1, m.state.value.ui.rooms.size)
        gate.complete(ApiResult.Fail(AppError.Offline)); runCurrent()
        assertFalse(m.state.value.ui.refreshing); assertTrue(m.state.value.ui.refreshFailed); assertEquals(1, m.state.value.ui.rooms.size)
        api.answer = null; m.refresh(); runCurrent()
        assertFalse(m.state.value.ui.refreshing); assertFalse(m.state.value.ui.refreshFailed); assertEquals(1, m.state.value.ui.rooms.size)
    }

    // 새로고침이 채팅 권한 회수(AI 동의 철회 등)로 실패하면 보던 목록을 남기지 않는다
    @Test fun pullRefreshRevokedAccessClearsRooms() = test {
        val api = object : ChatTestServer() {
            var failure: AppError? = null
            override suspend fun list(cursor: String?) = failure?.let { ApiResult.Fail(it) } ?: super.list(cursor)
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()))
            .also { models.put("list", it); it.refresh() }
        runCurrent(); assertEquals(1, m.state.value.ui.rooms.size)
        api.failure = AppError.Server(403, "AI_RISK_CONSENT_REQUIRED", ""); m.refresh(); runCurrent()
        assertEquals(ChatListLoad.Failed, m.state.value.ui.load); assertTrue(m.state.value.ui.rooms.isEmpty())
    }

    // 다음 쪽을 받는 중에 당겨서 새로고침하면 그 요청을 버리고 첫 쪽을 새로 받는다(무시되지 않는다)
    @Test fun refreshDuringNextPageLoadReplacesIt() = test {
        val cursors = mutableListOf<String?>(); val gate = CompletableDeferred<Unit>()
        val api = object : ChatTestServer() {
            override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> {
                cursors += cursor
                // 취소를 늦게 처리하는 요청처럼 끝까지 응답한다(2쪽은 다른 방 21)
                if (cursor != null) { withContext(NonCancellable) { gate.await() }
                    return ApiResult.Ok(ChatRoomsPage(listOf(roomRecord.copy(chatRoomId = 21)), null, now)) }
                return ApiResult.Ok(ChatRoomsPage(listOf(roomRecord), "page2", now))
            }
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()))
            .also { models.put("list", it); it.refresh() }
        runCurrent(); m.more(); runCurrent(); assertEquals(ChatListPage.Loading, m.state.value.ui.page)
        m.refresh(); runCurrent()
        assertEquals(listOf(null, "page2", null), cursors)
        assertFalse(m.state.value.ui.refreshing); assertEquals(ChatListPage.More, m.state.value.ui.page)
        gate.complete(Unit); runCurrent(); assertEquals(ChatListPage.More, m.state.value.ui.page)
        assertEquals(listOf(20L), m.state.value.ui.rooms.map { it.chatRoomId })   // 버려진 2쪽은 붙지 않는다
    }

    // 새로고침이 실패한 뒤에도 다음 쪽은 이전 커서로 이어 받고, 실패 안내는 첫 쪽을 다시 받을 때까지 남는다
    @Test fun nextPageAfterFailedRefreshKeepsNotice() = test {
        val cursors = mutableListOf<String?>()
        val api = object : ChatTestServer() {
            var failFirst = false
            override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> {
                cursors += cursor
                if (cursor == null && failFirst) return ApiResult.Fail(AppError.Offline)
                return ApiResult.Ok(ChatRoomsPage(listOf(roomRecord.copy(chatRoomId = if (cursor == null) 20 else 21)), if (cursor == null) "page2" else null, now))
            }
        }
        val m = ChatListViewModel(api, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()))
            .also { models.put("list", it); it.refresh() }
        runCurrent(); api.failFirst = true; m.refresh(); runCurrent()
        assertTrue(m.state.value.ui.refreshFailed); assertEquals(ChatListPage.More, m.state.value.ui.page)
        m.more(); runCurrent()
        assertEquals("page2", cursors.last()); assertEquals(listOf(20L, 21L), m.state.value.ui.rooms.map { it.chatRoomId })
        assertTrue(m.state.value.ui.refreshFailed); assertEquals(ChatListPage.End, m.state.value.ui.page)
        api.failFirst = false; m.refresh(); runCurrent(); assertFalse(m.state.value.ui.refreshFailed)
    }
}
