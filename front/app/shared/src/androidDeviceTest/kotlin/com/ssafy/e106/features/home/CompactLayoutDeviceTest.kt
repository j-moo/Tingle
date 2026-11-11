package com.ssafy.e106.features.home

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.features.survey.ui.*
import com.ssafy.e106.features.home.ui.*
import com.ssafy.e106.features.keyring.data.BleDeviceStatus
import com.ssafy.e106.features.keyring.data.BleOwnerFlowStatus
import com.ssafy.e106.features.keyring.data.OwnedKeyring
import com.ssafy.e106.features.keyring.ui.OwnedKeyringLiveStatus
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

/** 테스트 APK에서만 크기/글자 배율을 바꾼다. 기기의 설정과 실제 계정 데이터는 변경하지 않는다. */
class CompactLayoutTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 회전 검사는 이 테스트 Activity에만 적용한다. 시스템 자동 회전 설정은 바꾸지 않는다.
        if (intent.getBooleanExtra("landscape", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            val density = LocalDensity.current
            val scale = intent.getFloatExtra("fontScale", 2f)
            val tabletWidth = intent.getIntExtra("tabletWidthDp", 0)
            val windowWidth = LocalWindowInfo.current.containerSize.width
            // 실제 태블릿 대신 논리적 폭을 검사한다. 공통 여백도 같은 LocalDensity를 읽는다.
            val testDensity = if (tabletWidth > 0 && windowWidth > 0) windowWidth.toFloat() / tabletWidth else density.density
            // 320x480은 작은 화면 또는 키보드로 세로 공간이 줄어든 상황의 레이아웃 제약이다.
            // 실제 IME 동작은 별도의 수동 확인과 구분한다.
            CompositionLocalProvider(LocalDensity provides Density(testDensity, scale)) {
                Box((if (tabletWidth > 0) Modifier.fillMaxWidth().height(1280.dp)
                    else if (intent.getBooleanExtra("full", false)) Modifier.fillMaxSize()
                    else Modifier.width(320.dp).height(480.dp)).testTag("viewport")) {
                    var nickname by rememberSaveable { mutableStateOf("") }
                    var birth by rememberSaveable { mutableStateOf("") }
                    var draft by rememberSaveable { mutableStateOf("테스트 입력") }
                    var selected by remember { mutableStateOf<SurveyAnswer?>(null) }
                    AppTheme {
                        when (intent.getStringExtra("screen")) {
                            "home" -> HomeScreen(HomeState(HomeLoadState.Ready), {}, {})
                            "home_keyrings" -> HomeScreen(
                                HomeState(HomeLoadState.Ready, keyring = HomeKeyringState.Registered, keyrings = listOf(
                                    OwnedKeyring(7, "REGISTERED", "0.1.0-registration", label = "초록 키링"),
                                    OwnedKeyring(8, "REGISTERED", "0.1.0-registration"),
                                )), {}, {},
                                // 실제 BLE 대신 한 대의 관측값만 넣어 다른 키링 행으로 복제되지 않는지 확인한다.
                                keyringStatus = { OwnedKeyringLiveStatus(1, 7, 1, loading = false,
                                    metrics = BleDeviceStatus(72, null, null, null,
                                        BleOwnerFlowStatus(batteryPercentSource = "VOLTAGE_ESTIMATE"))) },
                            )
                            "profile" -> ProfileBasicsScreen(ProfileBasicsState(nickname, birth, ProfileGender.Male, canContinue = true),
                                { nickname = it }, { birth = it }, {}, {})
                            "survey" -> SurveyQuestionScreen(SurveyQuestionState("layout", 16, 100,
                                "작은 화면에서도 긴 질문과 마지막 응답을 확인할 수 있나요?",
                                (1..6).map { SurveyOption("$it", "검증 선택지 $it") },
                                answer = selected, saveState = SurveySaveState.Saved),
                                { selected = it }, {}, {}, {})
                            else -> ChatConversationScreen(ChatConversationState(ConversationLoad.Active,
                                nickname = "긴 이름을 사용하는 상대방", canRead = true, canSend = true, draft = draft,
                                messages = listOf(ConversationMessage("1", false, "확인할 최근 메시지"))),
                                { draft = it }, {})
                        }
                    }
                }
            }
        }
    }
}

class CompactLayoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(screen: String): ActivityScenario<CompactLayoutTestActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ActivityScenario.launch(Intent(context, CompactLayoutTestActivity::class.java)
            .putExtra("screen", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun fullyVisible(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("조작 영역이 화면 안에 있어야 한다: $bounds / $viewport",
            bounds.width > 0 && bounds.height > 0 && bounds.left >= viewport.left &&
                bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
    }
    @Test fun homeShowsNicknameAndBatteryOnlyForTheMatchingKeyring() {
        launch("home_keyrings").use {
            compose.onNodeWithText("내 키링").performScrollTo().performClick()
            compose.onNode(hasText("초록 키링") and hasText("배터리 약 72%")).assertExists()
            compose.onNode(hasText("내 키링 2") and hasText("배터리 미확인")).assertExists()
            compose.onNodeWithText("0.1.0-registration", substring = true).assertDoesNotExist()
        }
    }

    @Test fun chatInputAndSendRemainReachableAtLargeFont() {
        launch("chat").use {
            fullyVisible(compose.onNode(hasSetTextAction()))
            fullyVisible(compose.onNodeWithContentDescription("전송"))
        }
    }
    @Test fun profileFieldsCanScrollAtLargeFont() {
        launch("profile").use {
            val fields = compose.onAllNodes(hasSetTextAction())
            fullyVisible(fields[0].performScrollTo())
            fullyVisible(fields[1].performScrollTo())
            fullyVisible(compose.onNodeWithText("다음"))
        }
    }
    @Test fun surveyLastChoiceAndNextRemainReachableAtLargeFont() {
        launch("survey").use {
            fullyVisible(compose.onNodeWithText("검증 선택지 6").performScrollTo())
            compose.onNodeWithText("검증 선택지 6").performClick()
            fullyVisible(compose.onNodeWithText("다음"))
            compose.onNodeWithText("다음").assertIsEnabled()
        }
    }
}

/** 넓은 폭/짧은 높이에서 필수 조작이 실제로 보이는지 검사한다. 서버 성공을 검증하는 테스트가 아니다. */
class WideLayoutDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(screen: String, tablet: Boolean = false): ActivityScenario<CompactLayoutTestActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<CompactLayoutTestActivity>(Intent(context, CompactLayoutTestActivity::class.java)
            .putExtra("screen", screen).putExtra("full", true).putExtra("fontScale", 1.3f)
            .putExtra("landscape", !tablet).putExtra("tabletWidthDp", if (tablet) 800 else 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!tablet) compose.waitUntil(10_000) {
            val rect = compose.onAllNodesWithTag("viewport").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            rect != null && rect.width > rect.height
        }
        return scenario
    }
    private fun controls(screen: String) {
        when (screen) {
            "profile" -> {
                compose.onAllNodes(hasSetTextAction())[0].performScrollTo().assertIsDisplayed()
                compose.onAllNodes(hasSetTextAction())[1].performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("다음").assertIsDisplayed()
            }
            "survey" -> {
                compose.onNodeWithText("검증 선택지 6").performScrollTo().performClick()
                compose.onNodeWithText("다음").assertIsDisplayed().assertIsEnabled()
            }
            "home" -> {
                compose.onNodeWithText("받은 제안").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("마이").assertIsDisplayed()
            }
            else -> {
                compose.onNode(hasSetTextAction()).assertIsDisplayed()
                compose.onNodeWithContentDescription("전송").assertIsDisplayed().assertIsEnabled()
            }
        }
    }
    @Test fun landscapeProfileControls() { launch("profile").use { controls("profile") } }
    @Test fun landscapeSurveyControls() { launch("survey").use { controls("survey") } }
    @Test fun landscapeChatControls() { launch("chat").use { controls("chat") } }
    @Test fun landscapeHomeControls() { launch("home").use { controls("home") } }
    @Test fun tabletProfileHasBoundedForm() {
        launch("profile", true).use {
            controls("profile")
            val root = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val input = compose.onAllNodes(hasSetTextAction())[1].fetchSemanticsNode().boundsInRoot
            assertTrue("800dp 화면의 입력 영역은 640dp 이하로 제한되어야 한다", input.width <= root.width * .8f + 1f)
        }
    }
    @Test fun tabletSurveyControls() { launch("survey", true).use { controls("survey") } }
    @Test fun tabletChatControls() { launch("chat", true).use { controls("chat") } }
    @Test fun tabletHomeControls() { launch("home", true).use { controls("home") } }
}
