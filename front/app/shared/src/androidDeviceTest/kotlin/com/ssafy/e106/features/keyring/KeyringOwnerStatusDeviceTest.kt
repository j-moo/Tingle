package com.ssafy.e106.features.keyring

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
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.*
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import org.junit.Rule
import org.junit.Test

/** 테스트 APK 전용 순수 상태 화면. 실제 소유 인증·기록 읽기/삭제·서버 요청을 수행하지 않는다. */
class KeyringOwnerStatusTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val width = intent.getIntExtra("width", 320)
            val height = intent.getIntExtra("height", 640)
            val window = LocalWindowInfo.current.containerSize
            val density = minOf(window.width.toFloat() / width, window.height.toFloat() / height)
            // OS 글자 크기나 해상도를 바꾸지 않고 같은 Compose 화면에 작은 영역과 큰 글자를 전달한다.
            if (density > 0) CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    AppTheme { OnboardingPanel("키링 상태 · 합성 예시") {
                        val privateExample = intent.getBooleanExtra("authorized", false)
                        KeyringStatusDiagnostics(BleDeviceStatus(50, 3750, null, if (privateExample) 0 else null,
                            BleOwnerFlowStatus(ownerAuthorized = privateExample,
                                records = if (privateExample) BleRecordStorage(262144, 12000, "AVAILABLE", 2, 3, 1) else null,
                                applied = false, timeReady = true, matchingReady = false, storedSettingsVersion = 10,
                                batteryPercentSource = "VOLTAGE_ESTIMATE", batteryPresence = "UNCONFIRMED")))
                    } }
                }
            }
        }
    }
}

class KeyringOwnerStatusDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun open(authorized: Boolean, width: Int = 320, height: Int = 640): ActivityScenario<KeyringOwnerStatusTestActivity> {
        val context = InstrumentationRegistry.getInstrumentation().context
        return ActivityScenario.launch(Intent(context, KeyringOwnerStatusTestActivity::class.java)
            .putExtra("authorized", authorized).putExtra("width", width).putExtra("height", height))
    }
    @Test fun unknownCountsAndAuthenticationHintRemainVisibleAfterRecreation() {
        open(false).use { scenario ->
            compose.onNodeWithText("미전송 기록: 미확인", substring = true).assertExists()
            compose.onNodeWithText("기록 상세는 소유자 인증 후", substring = true).performScrollTo().assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("이전 소유 정보 복구 필요 수: 미확인", substring = true).performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun narrowLargeTextCanReachLastNoticeWithoutClaimsOfServerCompletion() {
        open(true).use {
            compose.onNodeWithText("전압 기반 추정", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("이전 소유 정보 복구 필요 수: 1", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("현재 연결한 기기에서 마지막으로 읽은 값", substring = true).performScrollTo().assertExists()
            compose.onNodeWithText("서버 확인 완료", substring = true).assertDoesNotExist()
        }
    }
    @Test fun landscapeCanReachRecordsAndNotice() {
        open(true, 640, 320).use {
            compose.onNodeWithText("기록 사용 공간: 12000 B / 262144 B", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("현재 연결한 기기에서 마지막으로 읽은 값", substring = true).performScrollTo().assertExists()
        }
    }
}
