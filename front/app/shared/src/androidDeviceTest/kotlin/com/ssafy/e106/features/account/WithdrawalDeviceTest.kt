package com.ssafy.e106.features.account

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.data.*
import com.ssafy.e106.features.account.ui.*
import org.junit.Rule
import org.junit.Test

/** 예시 데이터만 전달하는 시험 전용 화면. 실행 콜백은 비워 두고 확인 체크만 조작한다. */
class WithdrawalTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val op = WithdrawalOperation("11111111-1111-4111-8111-111111111111", "PREPARED",
            "2026-11-06T00:00:00Z", "2026-11-06T00:30:00Z", externalCleanupStatus = "NOT_STARTED", devicePermanentlyBlocked = false)
        val example = when (intent.getStringExtra("mode")) {
            "loading" -> WithdrawalState()
            "unavailable" -> WithdrawalState(stage = WithdrawalStage.Unavailable)
            "unknown" -> WithdrawalState().prepared(op).resultUnknown()
            "blocked" -> WithdrawalState().checked(WithdrawalEligibility(false, 1, 1,
                listOf("WITHDRAWAL_KEYRING_REGISTERED", "WITHDRAWAL_RESET_PENDING"), "2026-11-06T00:00:00Z"))
            "complete" -> WithdrawalState().prepared(op).resolved(op.copy(status = "COMPLETED",
                completedAt = "2026-11-06T00:01:00Z", rejoinAvailableAt = "2026-11-09T00:01:00Z",
                expiresAt = "2026-11-09T00:01:00Z", externalCleanupStatus = "PENDING"))
            else -> WithdrawalState().prepared(op)
        }
        setContent {
            var state by remember { mutableStateOf(example) }
            val width = intent.getIntExtra("width", 320)
            val height = intent.getIntExtra("height", 640)
            val window = LocalWindowInfo.current.containerSize
            val density = minOf(window.width.toFloat() / width, window.height.toFloat() / height)
            // 기기의 해상도/글자 설정을 바꾸지 않고 좁은 영역과 큰 글자 조합을 검사한다.
            if (density > 0) CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    AppTheme { WithdrawalScreen(state, { state = state.checkAccepted(it) }, {}) }
                }
            }
        }
    }
}

class WithdrawalDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun open(mode: String, width: Int = 320, height: Int = 640): ActivityScenario<WithdrawalTestActivity> =
        ActivityScenario.launch(Intent(InstrumentationRegistry.getInstrumentation().context, WithdrawalTestActivity::class.java)
            .putExtra("mode", mode).putExtra("width", width).putExtra("height", height))

    @Test fun finalConfirmationRequiresCheckboxAndDoesNotPretendExecution() {
        open("confirm").use { scenario ->
            compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("삭제되는 정보와 재가입 제한을 확인했고, 회원탈퇴에 동의해요.").performScrollTo().performClick()
            compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().assertIsNotEnabled()
        }
    }
    @Test fun unknownResultOffersOnlyRecoveryAndBlockedHasNoLostMvpAction() {
        open("unknown").use {
            // 결과를 모를 때는 새로 보내지 않고 "다시 확인"만 둔다. 기존 요청 결과부터 확인한다는 안내 글도 남아 있어야 한다
            compose.onNodeWithText("다시 확인").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("기존 요청의 결과를 먼저 확인해요", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("동의하고 회원탈퇴").assertDoesNotExist()
        }
        open("blocked").use {
            compose.onNodeWithText("완료되지 않은 초기화: 1건").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("키링 관리로").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("분실 문의", substring = true).assertDoesNotExist()
        }
    }
    @Test fun landscapeLargeTextKeepsPendingPhotoCleanupAndFinalButtonReachable() {
        open("complete", 640, 320).use {
            compose.onNodeWithText("사진 파일 삭제는 아직 처리 중", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("2026-11-09T00:01:00Z", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("확인").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("추가 파일 정리가 완료됐어요.").assertDoesNotExist()
        }
    }
    @Test fun loadingShowsDisabledButtonAndFailedCheckOffersRetryWithoutSpinnerText() {
        open("loading").use {
            // 확인 중에는 "…하고 있어요" 글 대신 뼈대를 두고, 버튼은 실제 자리에 막아 둔다
            compose.onNodeWithText("탈퇴 안내 확인하기").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("확인하고 있어요", substring = true).assertDoesNotExist()
        }
        open("unavailable").use {
            compose.onNodeWithText("탈퇴 가능 여부를 확인하지 못했어요").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("다시 시도").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("탈퇴 안내 확인하기").assertDoesNotExist()
        }
    }
}
