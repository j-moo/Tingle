package com.ssafy.e106.features.safety

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.*
import com.ssafy.e106.features.chat.data.ChatLocalStorage
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.safety.data.IcebreakerResult
import com.ssafy.e106.features.safety.ui.*
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** 실제 채팅 Route/추천 ViewModel을 연결하는 기기 테스트 호스트. 배포 앱에 포함하지 않는다. */
class QuestionCardsTestActivity : ComponentActivity() {
    internal val chats = ChatTestServer()
    internal val api = ExtraServer().apply {
        result = ApiResult.Ok(IcebreakerResult("SUCCEEDED", listOf("요즘 즐겨 듣는 음악은?", "쉬는 날에는 뭘 하나요?", "좋아하는 음식은?"), "GENERAL_FALLBACK", "v1"))
    }
    internal lateinit var roomModel: ChatRoomViewModel
    internal lateinit var extras: ChatExtrasViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = TestSession(OnboardingStatus.READY)
        val local = ChatLocalStorage(ChatMemoryPrefs())
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                ChatRoomViewModel::class.java -> ChatRoomViewModel(chats, session, local, IdleChatEvents)
                ChatPartnerViewModel::class.java -> ChatPartnerViewModel(chats, session)
                ChatExtrasViewModel::class.java -> ChatExtrasViewModel(api, chats, session)
                else -> ChatListViewModel(chats, session, local)
            } as T
        })
        roomModel = provider[ChatRoomViewModel::class.java]; extras = provider[ChatExtrasViewModel::class.java]
        setContent { AppTheme {
            ConnectedChatRoute(session.state.value as Session.SignedIn, initialRoom = 20,
                onHome = {}, onProposals = {}, onProfile = {}, onSafety = { _, _ -> }, onConsent = {},
                support = { id, readable, editable, draft, insert, _, _ ->
                    ConnectedChatExtras(id, readable, editable, insert, extras, draft)
                }, listModel = provider[ChatListViewModel::class.java], roomModel = roomModel,
                partnerModel = provider[ChatPartnerViewModel::class.java])
        } }
    }
}

class QuestionCardsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val first = "요즘 즐겨 듣는 음악은?"
    private fun launch() = ActivityScenario.launch<QuestionCardsTestActivity>(Intent(
        InstrumentationRegistry.getInstrumentation().context, QuestionCardsTestActivity::class.java))
    private fun open() { compose.onNodeWithText("대화 주제 추천").performScrollTo().performClick() }
    private fun pick() { compose.onNodeWithText(first).performScrollTo().performClick() }

    @Test fun toggleReusesResultAndEmptyDraftIsFilledWithoutSending() {
        launch().use { scenario ->
            compose.onNodeWithText(first).assertDoesNotExist()
            // 여닫기 동작만 맞아도 가중치 배치 오류로 입력창 위에 큰 공백이 생길 수 있다.
            val toggleBottom = compose.onNodeWithText("대화 주제 추천").getUnclippedBoundsInRoot().bottom.value
            val inputTop = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot().top.value
            assertTrue(inputTop - toggleBottom in 0f..100f, "추천은 입력창 바로 위에 붙어 있어야 한다")
            var queries = 0
            scenario.onActivity { queries = it.api.queries }
            repeat(2) {
                open()
                compose.onNodeWithText(first).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("추천 질문 접기").performScrollTo().performClick()
            }
            open(); pick()
            compose.onNodeWithText("작성 중인 내용을 바꿀까요?").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertTextContains(first)
            compose.onNodeWithContentDescription("전송", useUnmergedTree = true).assertIsDisplayed()
            scenario.onActivity {
                assertEquals(first, it.roomModel.state.value.ui.draft)
                assertEquals(0, it.api.requests); assertEquals(queries, it.api.queries)
                assertTrue(it.chats.writes.isEmpty(), "추천 선택은 서버에 메시지를 전송하지 않는다")
            }
        }
    }

    @Test fun existingDraftRequiresExplicitReplacementAndCancelPreservesIt() {
        launch().use { scenario ->
            compose.onNode(hasSetTextAction()).performTextInput("내가 쓰던 인사")
            open(); pick()
            compose.onNodeWithText("기존 내용 유지").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("내가 쓰던 인사")
            pick()
            compose.onNodeWithText("질문으로 바꾸기").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains(first)
            scenario.onActivity { assertTrue(it.chats.writes.isEmpty()) }
        }
    }

    @Test fun closingRoomDuringConfirmationCannotInsertOldQuestion() {
        launch().use { scenario ->
            compose.onNode(hasSetTextAction()).performTextInput("유지할 내용")
            open(); pick()
            scenario.onActivity {
                it.chats.roomRecord = it.chats.roomRecord.copy(status = "CLOSED", canRead = false, canSend = false)
                it.roomModel.reload()
            }
            compose.onNodeWithText("질문으로 바꾸기").assertDoesNotExist()
            compose.onNodeWithText("대화 주제 추천").assertDoesNotExist()
            scenario.onActivity { assertTrue(it.chats.writes.isEmpty()) }
        }
    }

    @Test fun pendingAndFailedRecommendationDoNotLockComposer() {
        launch().use { scenario ->
            for (status in listOf("PENDING", "FAILED")) {
                scenario.onActivity {
                    it.api.result = ApiResult.Ok(IcebreakerResult(status, emptyList(), null, null))
                    it.extras.check()
                }
                open()
                compose.onNode(hasSetTextAction()).performTextReplacement("추천 없이도 대화해요")
                compose.onNodeWithContentDescription("전송", useUnmergedTree = true).assertIsEnabled()
                compose.onNodeWithText("추천 질문 접기").performScrollTo().performClick()
            }
            scenario.onActivity { assertEquals(0, it.api.requests); assertTrue(it.chats.writes.isEmpty()) }
        }
    }
}
