package com.ssafy.e106.features.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.auth.ui.*
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.proposals.ui.*
import com.ssafy.e106.features.report.ui.*
import com.ssafy.e106.features.safety.ui.*
import com.ssafy.e106.features.settings.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 실제 화면에 상태만 전달하는 테스트 전용 호스트. SDK/API/실제 신고·차단은 호출하지 않는다. */
class RecoveryUiTestActivity : ComponentActivity() {
    var proposal by mutableStateOf(ProposalListState(ProposalListLoad.Ready, page = ProposalPageState.More))
    var chatList by mutableStateOf(ChatListState(ChatListLoad.Failed))
    var login by mutableStateOf(SocialLoginState(setOf(LoginProvider.Google, LoginProvider.Kakao)))
    var chat by mutableStateOf(ChatConversationState(ConversationLoad.Active, canRead = true, canSend = true,
        draft = "보존할 입력", messages = listOf(ConversationMessage("pending-1", true, "전송할 내용", delivery = MessageDelivery.Unknown))))
    var report by mutableStateOf(ReportState("1", true, ReportRules(1000), ReportStep.Unknown, ReportReason.SPAM,
        detail = "보존할 신고 내용"))
    var safety by mutableStateOf(SafetyActionState(SafetyOperation.Block, SafetyContext.Chat, SafetyPhase.Unknown, true))
    var conflict by mutableStateOf(SettingsConflictState(1, ConflictValues.Matching(true, false), ConflictStage.OutcomeUnknown))
    val actions = mutableListOf<Any>()
    var recoveryId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                when (intent.getStringExtra("screen")) {
                    "login" -> SocialLoginScreen(login, {
                        actions += it
                        login = login.copy(busyProvider = it, errorMessage = null)
                    })
                    "chatList" -> ChatListScreen(chatList, {}, {
                        actions += it
                        chatList = ChatListState()
                    })
                    "chat" -> ChatConversationScreen(chat, { chat = chat.copy(draft = it) }, { actions += it },
                        onMessageRecovery = { id, action ->
                            recoveryId = id; actions += action
                            chat = chat.copy(submitting = true, messages = chat.messages.map { it.copy(delivery = MessageDelivery.Checking) })
                        })
                    "report" -> ReportScreen(report, {}, {}, { _, _ -> }, {
                        actions += it
                        report = report.copy(step = ReportStep.Sending)
                    })
                    "safety" -> SafetyActionScreen(safety) {
                        actions += it
                        safety = safety.copy(phase = SafetyPhase.Processing)
                    }
                    "conflict" -> SettingsConflictScreen(conflict) {
                        actions += it
                        conflict = conflict.copy(stage = if (it is ConflictAction.Reload) ConflictStage.Loading else ConflictStage.Saving)
                    }
                    else -> ProposalListScreen(proposal, {}, {
                        actions += it
                        proposal = if (it == ProposalListAction.LoadMore) proposal.copy(page = ProposalPageState.Loading)
                            else ProposalListState()
                    })
                }
            }
        }
    }
}

class RecoveryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(screen: String): ActivityScenario<RecoveryUiTestActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch<RecoveryUiTestActivity>(
            Intent(context, RecoveryUiTestActivity::class.java).putExtra("screen", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            .also { compose.waitForIdle() }
    }
    private fun tap(text: String) {
        val button = compose.onNodeWithText(text)
        // 하단 고정 버튼에는 스크롤 부모가 없다. 이미 보이면 그대로 누르고 목록 안에 가려졌을 때만 스크롤한다.
        if (!button.isDisplayed()) button.performScrollTo()
        button.performClick()
    }

    @Test fun emptyProposalPageCanContinueAndCannotRepeatLoading() {
        launch("proposals").use { scenario ->
            // 첫 페이지가 비어도 다음 커서가 있으면 전체 목록이 비었다고 단정하면 안 된다.
            compose.onNodeWithText("아직 받은 제안이 없어요").assertDoesNotExist()
            tap("제안 더 보기")
            compose.onNodeWithText("추가 제안 확인 중…").assertIsNotEnabled()
            scenario.onActivity { assertEquals(listOf(ProposalListAction.LoadMore), it.actions) }
        }
    }
    @Test fun emptyProposalPageFailureOffersRetryAndFinalEmptyIsDistinct() {
        launch("proposals").use { scenario ->
            scenario.onActivity { it.proposal = it.proposal.copy(page = ProposalPageState.Failed) }
            tap("추가 제안 다시 불러오기")
            scenario.onActivity {
                assertEquals(listOf(ProposalListAction.LoadMore), it.actions)
                it.proposal = ProposalListState(ProposalListLoad.Ready)
            }
            compose.onNodeWithText("아직 받은 제안이 없어요").assertExists()
            compose.onNodeWithText("제안 더 보기").assertDoesNotExist()
        }
    }
    @Test fun initialProposalFailureRetriesWithoutShowingEmptySuccess() {
        launch("proposals").use { scenario ->
            scenario.onActivity { it.proposal = ProposalListState(ProposalListLoad.Failed) }
            compose.onNodeWithText("아직 받은 제안이 없어요").assertDoesNotExist()
            tap("다시 불러오기")
            compose.onNodeWithText("제안을 불러오고 있어요").assertExists()
            compose.onNodeWithText("다시 불러오기").assertDoesNotExist()
            scenario.onActivity { assertEquals(listOf(ProposalListAction.Refresh), it.actions) }
        }
    }
    @Test fun chatListFailureRetriesWithoutEmptySuccess() {
        launch("chatList").use { scenario ->
            tap("대화 목록 다시 불러오기")
            scenario.onActivity { assertEquals(listOf(ChatListAction.Refresh), it.actions) }
            compose.onNodeWithText("대화 목록 다시 불러오기").assertDoesNotExist()
        }
    }
    @Test fun loginFailureCanRetryAndLocksBothProviders() {
        launch("login").use { scenario ->
            scenario.onActivity { it.login = it.login.copy(errorMessage = "로그인 테스트 오류") }
            compose.onNodeWithText("로그인 테스트 오류").performScrollTo().assertIsDisplayed()
            tap("카카오 로그인")
            compose.onNodeWithText("카카오 로그인").assertIsNotEnabled()
            compose.onNodeWithText("Google로 로그인").assertIsNotEnabled()
            compose.onNodeWithText("로그인 테스트 오류").assertDoesNotExist()
            scenario.onActivity {
                assertEquals(listOf(LoginProvider.Kakao), it.actions)
                // 취소/실패 후 busy 해제 상태에서는 다시 시도할 수 있어야 한다.
                it.login = it.login.copy(busyProvider = null)
            }
            compose.onNodeWithText("카카오 로그인").assertIsEnabled()
        }
    }
    @Test fun unknownMessageChecksSameIdAndLocksInput() {
        launch("chat").use { scenario ->
            compose.onNodeWithText("다시 보내기").assertDoesNotExist()
            tap("전송 결과 확인")
            // 비활성 입력창은 SetText 동작을 제거하므로 보존된 텍스트로 찾아 잠금을 확인한다.
            compose.onNodeWithText("보존할 입력").assertIsNotEnabled()
            scenario.onActivity {
                assertEquals("pending-1", it.recoveryId)
                assertEquals(listOf(MessageRecoveryAction.CheckResult), it.actions)
                assertEquals("보존할 입력", it.chat.draft)
            }
        }
    }
    @Test fun knownFailedMessageOffersRetryAndClosedRoomHidesContent() {
        launch("chat").use { scenario ->
            scenario.onActivity { it.chat = it.chat.copy(messages = it.chat.messages.map { it.copy(delivery = MessageDelivery.Failed) }) }
            tap("다시 보내기")
            scenario.onActivity {
                assertEquals(listOf(MessageRecoveryAction.Retry), it.actions)
                it.chat = it.chat.withAccess("CLOSED", false, false)
            }
            compose.onNodeWithText("종료된 대화방이에요").assertExists()
            compose.onNodeWithText("전송할 내용").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        }
    }
    @Test fun reportUnknownChecksResultWithoutEditingOrClaimingReceipt() {
        launch("report").use { scenario ->
            compose.onNodeWithText("신고가 접수됐어요").assertDoesNotExist()
            compose.onNodeWithText("입력 내용 수정").assertDoesNotExist()
            tap("접수 결과 확인")
            compose.onNodeWithText("접수 중…").assertIsNotEnabled()
            scenario.onActivity {
                assertEquals(listOf(ReportAction.CheckResult), it.actions)
                assertEquals("보존할 신고 내용", it.report.detail)
            }
        }
    }
    @Test fun reportKnownFailureRetriesOriginalSubmission() {
        launch("report").use { scenario ->
            scenario.onActivity { it.report = it.report.copy(step = ReportStep.Failed) }
            compose.onNodeWithText("입력 내용 수정").assertExists()
            tap("같은 신고 다시 시도")
            compose.onNodeWithText("접수 중…").assertIsNotEnabled()
            scenario.onActivity { assertEquals(listOf(ReportAction.Retry), it.actions) }
        }
    }
    @Test fun safetyUnknownChecksResultAndLocksProcessing() {
        launch("safety").use { scenario ->
            // 차단 여부를 모르는 상태를 성공으로 바꾸지 않고 원 요청 결과 확인으로 연결한다.
            compose.onNodeWithText("차단하기").assertDoesNotExist()
            tap("처리 결과 확인")
            compose.onNodeWithText("처리 중…").assertIsNotEnabled()
            scenario.onActivity { assertEquals(listOf(SafetyUiAction.CheckResult), it.actions) }
        }
    }
    @Test fun settingsUnknownReloadsBeforeExplicitReviewedSave() {
        launch("conflict").use { scenario ->
            compose.onNodeWithText("내 입력으로 다시 저장").assertDoesNotExist()
            tap("최신 설정 불러오기")
            scenario.onActivity {
                assertEquals(listOf(ConflictAction.Reload), it.actions)
                it.conflict = it.conflict.copy(stage = ConflictStage.Review,
                    latest = ConflictSnapshot(1, ConflictValues.Matching(false, false), "\"v2\""))
            }
            tap("내 입력으로 다시 저장")
            scenario.onActivity {
                assertTrue(it.actions.last() is ConflictAction.SaveReviewed<*>)
                assertEquals("\"v2\"", (it.actions.last() as ConflictAction.SaveReviewed<*>).snapshot.etag)
            }
            // 로딩 문구는 제목과 버튼에 함께 있으므로 클릭 가능한 버튼만 검사한다.
            compose.onNode(hasText("확인한 설정을 저장하고 있어요") and hasClickAction()).assertIsNotEnabled()
        }
    }
}
