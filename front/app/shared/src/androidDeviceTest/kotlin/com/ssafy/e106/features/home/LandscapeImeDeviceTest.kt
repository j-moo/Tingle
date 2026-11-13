package com.ssafy.e106.features.home

import android.content.Intent
import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 실제 IME로 줄어든 가로 창을 검사한다. 서버와 연결하지 않는 테스트 Activity만 사용한다. */
class LandscapeImeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(screen: String): ActivityScenario<CompactLayoutTestActivity> {
        // 네이티브 IME 표시 여부를 확인할 수 있는 OS에서만 실행한다.
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch<CompactLayoutTestActivity>(Intent(context, CompactLayoutTestActivity::class.java)
            .putExtra("screen", screen).putExtra("full", true).putExtra("landscape", true)
            .putExtra("fontScale", 1.3f).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {
            compose.waitUntil(10_000) {
                val bounds = compose.onAllNodesWithTag("viewport").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                bounds != null && bounds.width > bounds.height
            }
        }
    }

    private fun awaitKeyboard(scenario: ActivityScenario<CompactLayoutTestActivity>, visible: Boolean) {
        compose.waitUntil(10_000) {
            var shown = false
            scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            shown == visible
        }
        compose.waitForIdle()
    }

    private fun inputIsUsable(input: SemanticsNodeInteraction) {
        input.assertIsDisplayed()
        val bounds = input.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        // 노드 존재만 검사하면 키보드 뒤에 가려진 입력칸도 통과하므로 실제 보이는 높이를 확인한다.
        assertTrue("키보드 위 입력 영역이 48dp 이상 필요: $bounds", bounds.height >= 48f * density)
    }

    @Test fun profileNicknameAndDateStayUsableWithLandscapeKeyboard() {
        launch("profile").use { scenario ->
            val nickname = compose.onAllNodes(hasSetTextAction())[0]
            nickname.performScrollTo().performClick()
            awaitKeyboard(scenario, true)
            inputIsUsable(nickname)
            nickname.performTextInput("sample")
            nickname.performImeAction()
            val birth = compose.onAllNodes(hasSetTextAction())[1]
            birth.assertIsFocused()
            inputIsUsable(birth)
            birth.performTextInput("20001010")
            // 짧은 창의 다음 버튼은 폼 끝으로 이동한다. IME를 닫지 않고도 스크롤로 접근해야 한다.
            compose.onNodeWithText("다음").performScrollTo().assertIsDisplayed().assertIsEnabled()
            awaitKeyboard(scenario, true)
            birth.performImeAction()
            awaitKeyboard(scenario, false)
            birth.assertTextContains("2000-10-10")
            compose.onNodeWithText("다음").assertIsDisplayed().assertIsEnabled()
            nickname.performScrollTo().assertTextContains("sample")
        }
    }

    @Test fun chatDraftAndSendStayUsableWithLandscapeKeyboard() {
        launch("chat").use { scenario ->
            val input = compose.onNode(hasSetTextAction())
            input.performClick()
            awaitKeyboard(scenario, true)
            inputIsUsable(input)
            input.performTextReplacement("가로 입력\n여러 줄\n보존")
            // 표시 줄 수만 줄어야 하며 실제 여러 줄 초안은 보존한다. 전송 터치 영역도 확인한다.
            inputIsUsable(compose.onNodeWithContentDescription("전송"))
            compose.onNodeWithContentDescription("전송").assertIsEnabled()
            // 전송 콜백은 실행하지 않는다. 키보드 닫기가 입력을 지우지 않는지만 검사한다.
            Espresso.closeSoftKeyboard()
            awaitKeyboard(scenario, false)
            input.assertTextContains("가로 입력\n여러 줄\n보존")
        }
    }
}
