package com.ssafy.e106.features.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.data.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.onboarding.TestSession
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

internal class PollingDeviceServer : ChatTestServer() {
    var listFailure: AppError? = null
    val permissions = AtomicInteger(); val profiles = AtomicInteger(); val photos = AtomicInteger()
    override suspend fun list(cursor: String?): ApiResult<ChatRoomsPage> = listFailure?.let { ApiResult.Fail(it) } ?: super.list(cursor)
    override suspend fun room(id: Long): ApiResult<ChatRoomRecord> { permissions.incrementAndGet(); return super.room(id) }
    override suspend fun profile(id: Long): ApiResult<ChatPartnerRecord> { profiles.incrementAndGet(); return super.profile(id) }
    override suspend fun photo(id: Long): ApiResult<ByteArray> { photos.incrementAndGet(); return super.photo(id) }
}

class ChatPollingTestActivity : ComponentActivity() {
    companion object {
        internal lateinit var server: PollingDeviceServer
        internal var startInList = false
        internal val consentCalls = AtomicInteger()
    }
    lateinit var room: ChatRoomViewModel
    lateinit var partner: ChatPartnerViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = TestSession(OnboardingStatus.READY); val local = ChatLocalStorage(ChatMemoryPrefs())
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                ChatRoomViewModel::class.java -> ChatRoomViewModel(server, session, local, IdleChatEvents)
                ChatPartnerViewModel::class.java -> ChatPartnerViewModel(server, session)
                else -> ChatListViewModel(server, session, local)
            } as T
        })
        room = provider[ChatRoomViewModel::class.java]; partner = provider[ChatPartnerViewModel::class.java]
        setContent { AppTheme {
            var consent by remember { mutableStateOf(false) }
            if (consent) Text("동의 화면 테스트 도착")
            else ConnectedChatRoute(session.state.value as Session.SignedIn, initialRoom = if (startInList) null else 20,
                onHome = {}, onProposals = {}, onProfile = {}, onSafety = { _, _ -> },
                onConsent = { consentCalls.incrementAndGet(); consent = true },
                listModel = provider[ChatListViewModel::class.java], roomModel = room, partnerModel = partner)
        } }
    }
}

class ChatPollingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(consentRequired: Boolean = false): ActivityScenario<ChatPollingTestActivity> {
        ChatPollingTestActivity.server = PollingDeviceServer()
        ChatPollingTestActivity.startInList = consentRequired
        ChatPollingTestActivity.consentCalls.set(0)
        if (consentRequired) ChatPollingTestActivity.server.listFailure = AppError.Server(403, "AI_RISK_CONSENT_REQUIRED", "동의가 필요해요")
        return ActivityScenario.launch(Intent(InstrumentationRegistry.getInstrumentation().context, ChatPollingTestActivity::class.java))
    }
    @Test fun actualListConsentErrorOffersNavigationWithoutOpeningRoom() {
        launch(consentRequired = true).use {
            // 실제 목록 Route의 버튼을 눌러 상위 화면 이동 요청까지 전달되는지 검증한다.
            compose.onNodeWithText("동의 내용 확인하기").performClick()
            compose.onNodeWithText("동의 화면 테스트 도착").assertExists()
            assertEquals(1, ChatPollingTestActivity.consentCalls.get())
            assertEquals(0, ChatPollingTestActivity.server.permissions.get())
        }
    }
    @Test fun menuOpenedInSameEventAsTypingKeepsUnsavedDraft() {
        launch().use { scenario ->
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity { ready = it.room.state.value.ui.inputEnabled }; ready }
            val openMenu = compose.onNodeWithContentDescription("더보기").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            // 같은 메인 이벤트에서 입력과 실제 메뉴 버튼 동작을 실행해 200ms 저장 전에 전환한다.
            scenario.onActivity { it.room.draft("아직 저장 전인 초안"); assertTrue(openMenu()) }
            compose.onNodeWithText("신고하기").assertExists()
            scenario.onActivity { assertEquals("아직 저장 전인 초안", it.room.state.value.ui.draft) }
            compose.onNodeWithText("이전").performClick()
            scenario.onActivity { assertEquals("아직 저장 전인 초안", it.room.state.value.ui.draft) }
        }
    }
    // 이전 메시지가 남은 방에서도 AI 분석 안내에 닿도록 메뉴에 둔 항목
    @Test fun menuOpensAiNotice() {
        launch().use { scenario ->
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity { ready = it.room.state.value.ui.readable }; ready }
            compose.onNodeWithContentDescription("더보기").performClick()
            compose.onNodeWithText("AI 대화 분석 안내").performClick()
            compose.onNodeWithText("동의 화면 테스트 도착").assertExists()
            assertEquals(1, ChatPollingTestActivity.consentCalls.get())
        }
    }
    @Test fun actualProfileRouteKeepsItsScopeAcrossTwoPollingTicks() {
        launch().use { scenario ->
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity { ready = it.room.state.value.ui.readable }; ready }
            compose.onNode(SemanticsMatcher("상대 프로필 보기") {
                it.config.contains(SemanticsActions.OnClick) && it.config[SemanticsActions.OnClick].label == "상대 프로필 보기" }).performClick()
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity { ready = it.partner.state.value.visible }; ready }
            lateinit var before: ChatPartnerState
            scenario.onActivity { before = it.partner.state.value }
            val api = ChatPollingTestActivity.server
            val permissions = api.permissions.get(); val profiles = api.profiles.get(); val photos = api.photos.get()
            compose.waitUntil(15_000) { api.permissions.get() >= permissions + 2 }
            scenario.onActivity { assertEquals(before, it.partner.state.value) }
            // 공개 필드는 다시 확인하지만 기존 화면과 변경 없는 사진은 유지한다.
            assertTrue(api.profiles.get() >= profiles + 2); assertEquals(photos, api.photos.get())
        }
    }
    @Test fun actualConversationStaysVisibleDuringOfflineTick() {
        launch().use { scenario ->
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity { ready = it.room.state.value.ui.readable }; ready }
            scenario.onActivity { ChatPollingTestActivity.server.roomFailure = AppError.Offline }
            compose.waitUntil(10_000) { var failed = false; scenario.onActivity { failed = it.room.state.value.error == AppError.Offline }; failed }
            scenario.onActivity { assertTrue(it.room.state.value.ui.inputEnabled); assertEquals(2, it.room.state.value.ui.messages.size) }
            compose.onNodeWithText("server-2").assertExists()
        }
    }
}
