package com.ssafy.e106.features.chat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ui.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 실제 앱처럼 edge-to-edge 창에서 채팅 UI를 검사한다. 계정·네트워크·메시지 전송은 사용하지 않는다. */
class ChatImeInsetsTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 회전은 이 시험 창에만 적용하며 휴대폰 자동 회전 설정을 변경하지 않는다.
        if (intent.getBooleanExtra("landscape", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            var draft by rememberSaveable { mutableStateOf("") }
            AppTheme {
                ChatConversationScreen(ChatConversationState(ConversationLoad.Active, canRead = true, canSend = true,
                    draft = draft, messages = listOf(ConversationMessage("1", false, "키보드 간격 검증"))),
                    onDraftChange = { draft = it }, onAction = {})
            }
        }
    }
}

/** 입력칸이 보이는지만 검사하면 큰 빈칸을 놓친다. 실제 IME 상단과 입력칸 하단의 거리도 검사한다. */
class ChatImeInsetsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun verify(landscape: Boolean) {
        // IME 경계를 네이티브 API로 읽을 수 있는 OS에서만 기하 검사를 실행한다.
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ChatImeInsetsTestActivity>(Intent(context, ChatImeInsetsTestActivity::class.java)
            .putExtra("landscape", landscape).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val input = compose.onNode(hasSetTextAction())
            repeat(2) { cycle ->
                input.performClick()
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                    visible
                }
                if (cycle == 0) input.performTextInput("미전송 시험")
                // 키보드 애니메이션이 끝난 뒤의 기하를 비교한다. 특정 기종의 키보드 높이는 하드코딩하지 않는다.
                var measurement = ""
                var valid = false
                try {
                    compose.waitUntil(10_000) {
                        val bounds = input.fetchSemanticsNode().boundsInWindow
                        scenario.onActivity { activity ->
                            val ime = activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
                            val height = activity.windowManager.currentWindowMetrics.bounds.height()
                            val density = activity.resources.displayMetrics.density
                            val gapDp = (height - ime - bounds.bottom) / density
                            measurement = "cycle=$cycle window=$height ime=$ime inputBottom=${bounds.bottom} gapDp=$gapDp"
                            // 입력 아래 글자 수·기본 여백은 허용하되 키보드 높이만큼의 빈칸과 가림은 거부한다.
                            valid = gapDp in 0f..64f && bounds.height >= 48f * density && bounds.top >= 0
                        }
                        valid
                    }
                } catch (_: ComposeTimeoutException) {
                    // 수치를 실패 보고에 남겨 OS/키보드별 재현 결과를 비교할 수 있게 한다.
                }
                android.util.Log.i("ChatImeGeometry", measurement)
                assertTrue("입력창은 키보드 바로 위에 있어야 함: $measurement", valid)
                compose.onNodeWithText("전송").assertIsDisplayed().assertIsEnabled()
                Espresso.closeSoftKeyboard()
                compose.waitUntil(10_000) {
                    var visible = true
                    scenario.onActivity { visible = it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
                    !visible
                }
                input.assertTextContains("미전송 시험")
            }
        }
    }

    @Test fun portraitInputStaysAboveKeyboardWithoutLargeGap() = verify(false)
    @Test fun landscapeInputStaysAboveKeyboardWithoutLargeGap() = verify(true)
}
