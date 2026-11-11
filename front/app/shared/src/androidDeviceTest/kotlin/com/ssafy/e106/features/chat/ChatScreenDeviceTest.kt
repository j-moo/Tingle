package com.ssafy.e106.features.chat

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
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.safety.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** 테스트 APK에서만 예시 화면을 구성한다. 실서버 메시지/차단/신고는 생성하지 않는다. */
class ChatScreenTestActivity : ComponentActivity() {
    var displayed: List<Long> = emptyList()
    var writes = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var page by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf("") }
            var phase by remember { mutableStateOf(SafetyPhase.Overview) }
            AppTheme {
                if(page) SafetyActionScreen(SafetyActionState(SafetyOperation.Block,SafetyContext.Chat,phase,true)) {
                    when(it) {
                        SafetyUiAction.Confirm -> { writes++; phase = SafetyPhase.Processing }
                        else -> page = false
                    }
                } else ChatConversationScreen(ChatConversationState(ConversationLoad.Active, canRead=true,canSend=true,draft=draft,
                    messages=(1..40).map { ConversationMessage(it.toString(),false,"메시지 $it\n추가 문맥", "12:00") }),
                    { draft = it }, { if(it == ConversationAction.OpenMenu) page=true }, onMessagesDisplayed={ displayed=it })
            }
        }
    }
}
class ChatScreenDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun onlyVisibleMessagesAreAcknowledgedAndBlockRequiresConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ChatScreenTestActivity>(Intent(context,ChatScreenTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { assertTrue(it.displayed.isNotEmpty()); assertFalse(it.displayed.contains(1)); assertTrue(it.displayed.contains(40)) }
            // 더보기는 아이콘의 접근성 이름으로 찾고, 새 차단 화면에서 바로 최종 확인을 누른다.
            compose.onNodeWithContentDescription("더보기").performClick()
            scenario.onActivity { assertEquals(0,it.writes) }
            compose.onNodeWithText("차단하기").performClick()
            scenario.onActivity { assertEquals(1,it.writes) }
            compose.onNodeWithText("처리 중…").assertExists()
        }
    }
}
