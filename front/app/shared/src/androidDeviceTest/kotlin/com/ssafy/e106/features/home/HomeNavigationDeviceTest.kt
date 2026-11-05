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
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.home.ui.*
import com.ssafy.e106.features.proposals.ui.*
import org.junit.Rule
import org.junit.Test

/** 테스트 APK에만 들어가는 화면 호스트. 실제 서버 계정·제안·결정을 생성하지 않는다. */
class HomeNavigationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var page by remember { mutableStateOf(0) }
            AppTheme {
                when (page) {
                    0 -> HomeScreen(HomeState(HomeLoadState.Ready), {}, onAction = {
                        if (it == HomeAction.Proposals) page = 1
                        if (it == HomeAction.Profile) page = 2
                    })
                    1 -> ProposalListScreen(ProposalListState(ProposalListLoad.Ready), {}, onAction = {
                        if (it == ProposalListAction.Home) page = 0
                        if (it == ProposalListAction.Profile) page = 2
                    })
                    else -> AccountMenuScreen(AccountMenuState(AccountMenuLoad.Ready, "메뉴 검증"), onAction = {
                        if (it == AccountMenuAction.Home) page = 0
                    })
                }
            }
        }
    }
}

class HomeNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeProposalMenuNavigationIsClickable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<HomeNavigationTestActivity>(Intent(context, HomeNavigationTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.onNodeWithText("받은 제안").performScrollTo().performClick()
            compose.onNodeWithText("제안", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("마이").performClick()
            compose.onNodeWithText("메뉴 검증").assertExists()
            compose.onNodeWithText("홈").performClick()
            compose.onNodeWithText("당신의 일상에,\n새로운 인연을.").assertExists()
        }
    }
}
