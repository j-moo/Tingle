package com.ssafy.e106.features.chat

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.features.chat.ui.ConversationLoad
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/**
 * 알림이 넘긴 방 ID로 실제 ConnectedChatRoute에 진입하는 이후 구간을 검증한다.
 * FCM 수신·알림 탭 자체는 이 검사의 범위가 아니다. 시험 서버는 테스트 APK 안에서만 동작한다.
 */
class ChatEntryRecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    // 기존 실제 Route 호스트를 재사용한다. 별도의 가짜 성공 화면으로 접근 차단을 대신하지 않는다.
    private fun launch(server: PollingDeviceServer): ActivityScenario<ChatPollingTestActivity> {
        ChatPollingTestActivity.server = server
        ChatPollingTestActivity.startInList = false
        ChatPollingTestActivity.consentCalls.set(0)
        return ActivityScenario.launch(Intent(
            InstrumentationRegistry.getInstrumentation().context, ChatPollingTestActivity::class.java))
    }

    private fun awaitState(scenario: ActivityScenario<ChatPollingTestActivity>, load: ConversationLoad) {
        compose.waitUntil(10_000) {
            var reached = false
            scenario.onActivity { reached = it.room.state.value.ui.load == load }
            reached
        }
    }

    @Test fun closedRoomEntryShowsClosureWithoutFetchingOrSendingMessages() {
        val server = PollingDeviceServer().apply {
            roomRecord = roomRecord.copy(status = "CLOSED", canRead = false, canSend = false)
        }
        launch(server).use { scenario ->
            awaitState(scenario, ConversationLoad.Closed)
            compose.onNodeWithText("종료된 대화방이에요").assertExists()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            scenario.onActivity {
                // 화면을 우회해 호출해도 종료된 방에는 전송하지 않아야 한다.
                it.room.draft("전송되면 안 되는 시험 문장")
                it.room.send()
                assertTrue(server.calls.isEmpty())
                assertTrue(server.writes.isEmpty())
                assertTrue(server.reads.isEmpty())
                assertFalse(it.room.state.value.ui.readable)
            }
            // 아래 고정 버튼(스크롤 부모 없음)
            compose.onNodeWithText("채팅 목록으로").assertIsDisplayed().performClick()
            scenario.onActivity { assertTrue(server.writes.isEmpty()) }
        }
    }

    @Test fun missingOrNonParticipantRoomDoesNotRevealCachedMessages() {
        val server = PollingDeviceServer().apply {
            // 없는 방과 비참여자의 공개 응답은 같은 코드이며 상세 사유를 추측하지 않는다.
            roomFailure = AppError.Server(404, "RESOURCE_NOT_FOUND", "대상을 확인할 수 없습니다.")
        }
        launch(server).use { scenario ->
            awaitState(scenario, ConversationLoad.Unavailable)
            compose.onNodeWithText("지금은 대화를 확인할 수 없어요").assertExists()
            compose.onNodeWithText("server-2").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            scenario.onActivity {
                assertTrue(server.calls.isEmpty())
                assertTrue(server.writes.isEmpty())
            }
        }
    }

    @Test fun recreationRechecksClosedRoomBeforeRestoringConversation() {
        val server = PollingDeviceServer()
        launch(server).use { scenario ->
            awaitState(scenario, ConversationLoad.Active)
            compose.onNodeWithText("server-2").assertExists()
            var messageRequests = 0
            var permissionRequests = 0
            scenario.onActivity {
                it.room.draft("화면 재생성 전 초안")
                messageRequests = server.calls.size
                permissionRequests = server.permissions.get()
                server.roomRecord = server.roomRecord.copy(status = "CLOSED", canRead = false, canSend = false)
            }
            // Activity 재생성은 실제 OS 프로세스 종료와 다르다. 저장된 화면 진입의 권한 재검증만 확인한다.
            scenario.recreate()
            awaitState(scenario, ConversationLoad.Closed)
            compose.onNodeWithText("종료된 대화방이에요").assertExists()
            compose.onNodeWithText("server-2").assertDoesNotExist()
            compose.onNodeWithText("화면 재생성 전 초안").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            scenario.onActivity {
                assertTrue(server.permissions.get() > permissionRequests)
                assertEquals(messageRequests, server.calls.size)
                assertTrue(it.room.state.value.ui.messages.isEmpty())
                assertFalse(it.room.state.value.ui.inputEnabled)
            }
        }
    }
}
