package com.ssafy.e106.features.home

import android.content.Intent
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.ble.BleAvailability
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.auth.ui.*
import com.ssafy.e106.features.onboarding.data.AgreementItem
import com.ssafy.e106.features.onboarding.ui.*
import com.ssafy.e106.features.profile.ui.*
import com.ssafy.e106.features.preferences.ui.*
import com.ssafy.e106.features.proposals.ui.*
import com.ssafy.e106.features.report.ui.*
import com.ssafy.e106.features.settings.ui.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.account.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 순수 화면만 띄우는 시험 호스트. 실제 로그인·권한 요청·신고·BLE 명령은 호출하지 않는다. */
class AdditionalResponsiveTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 실제 MainActivity와 같은 창 설정으로 검사한다. 다르면 IME 여백이 이중 적용될 수 있다.
        enableEdgeToEdge()
        if (intent.getBooleanExtra("ime", false)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContent {
            val width = intent.getIntExtra("width", 320)
            val height = intent.getIntExtra("height", 480)
            val scale = intent.getFloatExtra("fontScale", 2f)
            val window = LocalWindowInfo.current.containerSize
            // 물리 창 안에 지정한 dp 영역을 만든다. OS 글자/해상도 설정과 기존 앱은 변경하지 않는다.
            val ime = intent.getBooleanExtra("ime", false)
            val density = if (ime) LocalDensity.current.density else
                minOf(window.width.toFloat() / width, window.height.toFloat() / height)
            if (density > 0) CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                Box((if (ime) Modifier.fillMaxSize() else Modifier.requiredSize(width.dp, height.dp)).testTag("responsiveViewport")) {
                    AppTheme { ResponsiveExample(intent.getStringExtra("screen") ?: "login") }
                }
            }
        }
    }
}

/** 입력 예시는 레이아웃 검사용이다. ViewModel/Repository나 가짜 서비스 성공을 만들지 않는다. */
@Composable
private fun ResponsiveExample(screen: String) {
    var details by remember { mutableStateOf(ProfileDetailsState(religionConsent = true, introductionConsent = true)) }
    var age by remember { mutableStateOf(AgePreferenceState(AgePreferenceMode.Range, "24", "32")) }
    var report by remember { mutableStateOf(ReportState(roomKey = "1", canReport = true,
        rules = ReportRules(1000), reason = ReportReason.ETC)) }
    when (screen) {
        "login" -> SocialLoginScreen(SocialLoginState(availableProviders = LoginProvider.entries.toSet()), {})
        "agreements" -> OnboardingScreen(OnboardingState(
            session = Session.SignedIn(1, OnboardingStatus.AGREEMENT_REQUIRED, false),
            allowUnpublishedAgreements = true,
            agreements = listOf(AgreementItem("TERMS", "v1", "서비스 이용약관", true),
                AgreementItem("PRIVACY", "v1", "개인정보 수집 및 이용 동의", true),
                AgreementItem("AI_RISK_DETECTION", "v1", "AI 대화 위험 감지를 위한 개인정보 처리 동의", true),
                AgreementItem("ADULT", "v1", "만 19세 이상입니다", true))),
            { _, _ -> }, {}, {}, {}, {})
        "lifestyle" -> ProfileLifestyleScreen(ProfileLifestyleState(
            ProfileSmoking.NonSmoker, ProfileDrinking.Never), {}, {}, {})
        "details" -> ProfileDetailsScreen(details,
            {}, {}, { details = details.copy(introduction = it) }, {}, {}, {}, {}, {}, {})
        "age" -> AgePreferenceScreen(age, { age = it }, {})
        "proposal" -> ProposalProfileScreen(ProposalProfileState(
            load = ProposalProfileLoad.Ready, profile = PublicProposalProfile(nickname = "긴 이름을 사용하는 상대",
                age = 25, introduction = "서로의 취향을 알아가고 싶어요."),
            canRespond = true), {})
        "report" -> ReportScreen(report, {}, { report = report.copy(detail = it) }, { _, _ -> }, {})
        "matching" -> MatchingSettingsScreen(MatchingSettingsState(load = SettingsLoad.Ready,
            matching = true, photoRequired = false, ageLabel = "만 24세 ~ 32세"), {}, {}, {})
        "reset" -> KeyringResetScreen(KeyringResetState(stage = KeyringResetStage.Confirm,
            deviceLabel = "시험 키링", ownershipConfirmed = true, connected = true, pendingRecordCount = 0),
            {}, {}, {}, {})
        "account" -> AccountMenuScreen(AccountMenuState(load = AccountMenuLoad.Ready, nickname = "화면 확인"), {})
        "keyringWaiting" -> OnboardingScreen(OnboardingState(
            session = Session.SignedIn(1, OnboardingStatus.KEYRING_REQUIRED, false), purchaseUrl = "https://tingle.test/shop"),
            { _, _ -> }, {}, {}, {}, {})
        "permissionSheet" -> SignupSheet(onClose = {}, background = {}, animate = false) {
            PermissionSheetContent(BleAvailability.PermissionRequired, notificationGranted = false, denied = true)
        }
    }
}

/** 기존 기본 프로필/설문/홈/채팅 검사를 복제하지 않고 다른 화면의 필수 조작 접근성을 확장한다. */
@RunWith(Parameterized::class)
class AdditionalResponsiveDeviceTest(private val screen: String, private val width: Int,
    private val height: Int, private val scale: Float) {
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}x{2}-font{3}")
        fun cases(): List<Array<Any>> = listOf("login", "agreements", "lifestyle", "details", "age",
            "proposal", "report", "matching", "reset", "account", "keyringWaiting", "permissionSheet").flatMap { screen ->
            listOf(arrayOf(screen, 320, 480, 2f), arrayOf(screen, 640, 320, 1.3f),
                arrayOf(screen, 800, 1280, 1.3f))
        }
    }

    @Test fun importantControlsAreReachableAndTextIsNotClipped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AdditionalResponsiveTestActivity>(
            Intent(context, AdditionalResponsiveTestActivity::class.java)
                .putExtra("screen", screen).putExtra("width", width).putExtra("height", height)
                .putExtra("fontScale", scale).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val labels = when (screen) {
                "login" -> listOf("네이버 로그인")
                "agreements" -> listOf("이용약관 전체동의", "다음")
                "lifestyle" -> listOf("주 4회 이상", "다음")
                "details" -> listOf("공개하지 않음", "다음")
                "age" -> listOf("최대 나이는 제한하지 않을게요", "저장하고 계속하기")
                "proposal" -> listOf("수락하기", "거절하기")
                "report" -> listOf("증거 메시지 선택")
                "matching" -> listOf("키링 관리")
                "reset" -> listOf("키링 초기화하기")
                "account" -> listOf("로그아웃")
                "keyringWaiting" -> listOf("키링 찾기", "구매하기", "가입 상태 다시 확인", "로그아웃")
                "permissionSheet" -> listOf("접근 권한 안내", "앱 설정 열기")
                else -> listOf("로그아웃")
            }
            labels.forEach { label ->
                // LazyColumn의 화면 밖 버튼은 아직 생성되지 않을 수 있어 목록을 통해 먼저 찾는다.
                if (screen == "report" && height <= 320) {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
                }
                val node = compose.onNodeWithText(label)
                // 스크롤 밖 고정 버튼과 스크롤 안의 버튼을 동일한 도달 조건으로 검사한다.
                try { node.performScrollTo() } catch (_: AssertionError) { node.assertIsDisplayed() }
                node.assertIsDisplayed()
                val viewport = compose.onNodeWithTag("responsiveViewport").fetchSemanticsNode().boundsInRoot
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$screen/$label 조작 영역 잘림: $bounds / $viewport",
                    bounds.width > 0 && bounds.height > 0 && bounds.left >= viewport.left - 1 &&
                        bounds.right <= viewport.right + 1 && bounds.top >= viewport.top - 1 &&
                        bounds.bottom <= viewport.bottom + 1)
                // 노드가 존재해도 내부 글자가 잘리면 실패한다. 의도적으로 생략한 상단 제목은 검사 대상이 아니다.
                val textNode = compose.onNodeWithText(label, useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                // 문단의 측정 가능 폭은 실제 글자 폭보다 클 수 있다. 줄별 글자 폭을 검사하며
                // 글꼴 줄 높이와 정수 픽셀 반올림의 1px 차이는 실제 잘림과 구분한다.
                assertTrue("$screen/$label 글자 잘림: ${layouts.map { "${it.size}/${it.multiParagraph.height}/${it.multiParagraph.width}" }}",
                    layouts.isNotEmpty() && layouts.none { it.multiParagraph.height > it.size.height + 1f ||
                        (0 until it.lineCount).any { line -> it.getLineRight(line) - it.getLineLeft(line) > it.size.width + 1f } ||
                        it.multiParagraph.didExceedMaxLines })
            }
        }
    }
}

/** 기존 채팅/기본 프로필 IME 검사와 별도로 추가 입력 화면의 실제 키보드 경계를 확인한다. */
class AdditionalFormImeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun verify(screen: String, submit: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AdditionalResponsiveTestActivity>(
            Intent(context, AdditionalResponsiveTestActivity::class.java).putExtra("screen", screen)
                .putExtra("ime", true).putExtra("fontScale", 1.3f)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(10_000) {
                val bounds = compose.onAllNodesWithTag("responsiveViewport").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                bounds != null && bounds.width > bounds.height
            }
            // 신고는 LazyColumn이라 화면 밖 항목을 먼저 구성하게 스크롤한다.
            if (screen == "report") compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
            val input = compose.onAllNodes(hasSetTextAction())[0]
            input.performScrollTo().performClick()
            compose.waitUntil(10_000) {
                var shown = false
                scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                shown
            }
            input.assertIsFocused().performTextReplacement(if (screen == "age") "25" else "시험 입력")
            compose.waitForIdle()
            val density = context.resources.displayMetrics.density
            // OS 키보드 애니메이션은 Compose idle과 별개다. 최종 크기로 재배치된 상태를 관찰한다.
            runCatching { compose.waitUntil(5_000) { input.fetchSemanticsNode().boundsInRoot.height >= 48 * density } }
            val bounds = input.fetchSemanticsNode().boundsInRoot
            // 순수 예시 화면만 외부 테스트 폴더에 기록한다. 서버/사용자 입력 데이터는 포함하지 않는다.
            val evidence = context.getExternalFilesDir(null)!!
            java.io.File(evidence, "$screen-ime.png").outputStream().use { stream ->
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            java.io.File(evidence, "$screen-tree.txt").writeText(compose.onRoot().printToString())
            assertTrue("$screen 키보드 위 입력칸 높이가 부족함: $bounds", bounds.height >= 48 * density)
            val button = compose.onNodeWithText(submit)
            if (screen == "report") compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(submit))
            try { button.performScrollTo() } catch (_: AssertionError) { button.assertIsDisplayed() }
            button.assertIsDisplayed()
            // 화면 재배치가 키보드를 강제로 닫아 통과한 것으로 보이지 않도록 끝에서도 확인한다.
            scenario.onActivity { assertTrue("검사 중 IME 유지", it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())) }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            if (screen == "report") compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasSetTextAction())
            input.performScrollTo().assertTextContains(if (screen == "age") "25" else "시험 입력")
        }
    }
    @Test fun introductionWithLandscapeKeyboard() = verify("details", "다음")
    @Test fun ageWithLandscapeKeyboard() = verify("age", "저장하고 계속하기")
    @Test fun reportWithLandscapeKeyboard() = verify("report", "증거 메시지 선택")
}
