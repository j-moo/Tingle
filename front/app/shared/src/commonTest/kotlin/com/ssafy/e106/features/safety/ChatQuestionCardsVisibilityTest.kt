package com.ssafy.e106.features.safety

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.safety.data.IcebreakerResult
import com.ssafy.e106.features.safety.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 추천 칸은 질문 확인(checked)과 첫 메시지 페이지(firstPageLoaded)가 다 와야 그린다 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatQuestionCardsVisibilityTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { store.clear() } }
    private val questions = listOf("하나", "둘", "셋")
    // 연결부(ConnectedChatRoute)와 같은 함수: 첫 페이지 전엔 보낸 메시지 목록을 모른다(null)
    private fun used(room: ChatRoomViewModel) = usedMessageTexts(room.state.value.ui)

    @Test fun cardsNeedCheckedAndUsed() {
        val done = ChatExtrasState(questions = questions, checked = true)
        assertNull(visibleQuestions(done.copy(checked = false), emptySet()))
        assertNull(visibleQuestions(done, null))
        assertEquals(questions, visibleQuestions(done, emptySet()))
        assertNull(visibleQuestions(done, questions.toSet()))
        // 질문을 아직 못 받았어도 확인이 끝났으면 칸(생성 · 다시 시도 버튼)은 보인다
        assertEquals(emptyList(), visibleQuestions(ChatExtrasState(checked = true), emptySet()))
    }

    @Test fun usedUpRoomNeverShowsCardsEvenBeforeMessagesArrive() = test {
        val chats = ChatTestServer(); val gate = CompletableDeferred<Unit>()
        chats.onMessages = { _, _ -> gate.await()
            ApiResult.Ok(ChatMessagesPage(questions.mapIndexed { i, q -> chats.message(i + 1L, "ME").copy(text = q) }, null, chats.now)) }
        val api = ExtraServer().apply { result = ApiResult.Ok(IcebreakerResult("SUCCEEDED", questions, "GENERAL_FALLBACK", "v1")) }
        val room = ChatRoomViewModel(chats, TestSession(OnboardingStatus.READY), ChatLocalStorage(ChatMemoryPrefs()), IdleChatEvents)
            .also { store.put("room", it); it.open(20) }
        val extras = ChatExtrasViewModel(api, chats, TestSession(OnboardingStatus.READY)).also { store.put("e", it) }
        extras.bind(20, true)
        assertFalse(extras.state.value.checked); assertNull(visibleQuestions(extras.state.value, used(room)))
        runCurrent()
        // 질문은 왔지만 메시지는 아직: 칸이 없어야 한다
        assertTrue(extras.state.value.checked); assertTrue(room.state.value.ui.readable)
        assertFalse(room.state.value.ui.firstPageLoaded); assertNull(visibleQuestions(extras.state.value, used(room)))
        gate.complete(Unit); runCurrent()
        assertTrue(room.state.value.ui.firstPageLoaded); assertNull(visibleQuestions(extras.state.value, used(room)))
        // 상대 프로필에 갔다 돌아올 때(pause → resume)도 마찬가지
        room.pause(); assertFalse(room.state.value.ui.firstPageLoaded); assertNull(visibleQuestions(extras.state.value, used(room)))
        room.resume(); runCurrent(); assertNull(visibleQuestions(extras.state.value, used(room)))
    }

    @Test fun failedCheckStillMarksCheckedSoRetryIsShown() = test {
        val chats = ChatTestServer().apply { roomFailure = AppError.Offline }
        val m = ChatExtrasViewModel(ExtraServer(), chats, TestSession(OnboardingStatus.READY)).also { store.put("e", it) }
        m.bind(20, true); runCurrent()
        assertTrue(m.state.value.checked); assertTrue(m.state.value.accessRetryRequired)
        assertNotNull(visibleQuestions(m.state.value, emptySet()))
        m.clear(); assertFalse(m.state.value.checked)
    }
}
