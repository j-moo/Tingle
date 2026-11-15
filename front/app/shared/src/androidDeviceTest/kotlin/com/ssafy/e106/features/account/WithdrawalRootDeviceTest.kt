package com.ssafy.e106.features.account

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.OnboardingStatus
import com.ssafy.e106.core.network.ApiResult
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.push.*
import com.ssafy.e106.features.home.ui.*
import com.ssafy.e106.features.proposals.ProposalAccess
import com.ssafy.e106.features.keyring.data.*
import com.ssafy.e106.features.keyring.ui.OwnedKeyringsViewModel
import com.ssafy.e106.features.chat.ChatMemoryPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.data.*
import com.ssafy.e106.features.account.ui.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** 실제 인증 루트의 결과 유지 여부를 합성 서버로 검사한다. 실계정/네트워크는 사용하지 않는다. */
class WithdrawalRootTestActivity:ComponentActivity() {
    companion object {
        internal var sessions=LoggedIn()
        internal var scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        internal var server=Server()
        internal var records=Records()
        internal var controller=WithdrawalCoordinator(server,WithdrawalJournal(records),sessions,End(sessions),scope)
        internal fun reset() {
            scope.cancel();scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            // 이전 시험 Activity의 수집기에 다음 시험의 가입 상태를 흘리지 않도록 세션 저장소도 분리한다.
            sessions=LoggedIn()
            server=Server();records=Records()
            controller=WithdrawalCoordinator(server,WithdrawalJournal(records),sessions,End(sessions),scope)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { val session by sessions.state.collectAsState()
            var keyringDestination by remember { mutableStateOf(false) }
            AppTheme { WithdrawalRoot(session, content={
                WithdrawalKeyringNavigation(75,controller) { keyringDestination=true }
                Text(if(keyringDestination) "키링 관리 목적지" else "인증 기본 화면")
            },controller=controller) }
        }
    }
}

class WithdrawalRootDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun blockedScreenButtonNavigatesThroughRealServiceHostBridge() {
        WithdrawalRootTestActivity.reset()
        WithdrawalRootTestActivity.server.eligibilityValue=WithdrawalRootTestActivity.server.eligibilityValue.copy(
            eligible=false,registeredKeyringCount=1,blockingCodes=listOf("KEYRING_REGISTERED"))
        val context=InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalRootTestActivity>(Intent(context,WithdrawalRootTestActivity::class.java)).use { scenario ->
            scenario.onActivity { WithdrawalRootTestActivity.controller.open(75) }
            compose.onNodeWithText("키링 관리로").performScrollTo().performClick()
            compose.onNodeWithText("키링 관리 목적지").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("키링 관리 목적지").assertDoesNotExist()
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
    @Test fun cancellationResponseLossOffersSameCancellationRetryWithoutNewPreparation() {
        WithdrawalRootTestActivity.reset()
        val context=InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalRootTestActivity>(Intent(context,WithdrawalRootTestActivity::class.java)).use { scenario ->
            scenario.onActivity { WithdrawalRootTestActivity.controller.open(75) }
            compose.onNodeWithText("탈퇴 안내 확인하기").performScrollTo().performClick()
            scenario.onActivity { WithdrawalRootTestActivity.server.cancelFails=true }
            compose.onNodeWithText("탈퇴 준비 취소").performScrollTo().performClick()
            compose.onNodeWithText("탈퇴 준비 취소를 확인해 주세요").assertExists()
            scenario.onActivity { WithdrawalRootTestActivity.server.cancelFails=false }
            compose.onNodeWithText("다시 확인").performScrollTo().performClick()
            compose.onNodeWithText("탈퇴 전 확인해 주세요").assertExists()
            assertEquals(2,WithdrawalRootTestActivity.server.cancels)
            assertEquals(1,WithdrawalRootTestActivity.server.keys.size)
            assertEquals(0,WithdrawalRootTestActivity.server.sends)
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
    @Test fun storedCompletionRemainsVisibleWhenResultRefreshIsOffline() {
        WithdrawalRootTestActivity.reset()
        val completed=WithdrawalOperation("11111111-1111-4111-8111-111111111111","COMPLETED",
            "2026-11-08T00:00:00Z","2026-11-11T00:01:00Z",completedAt="2026-11-08T00:01:00Z",
            rejoinAvailableAt="2026-11-11T00:01:00Z",externalCleanupStatus="PENDING",devicePermanentlyBlocked=false)
        runBlocking { WithdrawalJournal(WithdrawalRootTestActivity.records).save(WithdrawalTicket(75,
            completed.withdrawalId,completed,"synthetic",attempted=true,autoResume=true)) }
        WithdrawalRootTestActivity.server.response=ApiResult.Fail(AppError.Offline)
        val context=InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalRootTestActivity>(Intent(context,WithdrawalRootTestActivity::class.java)).use { scenario ->
            scenario.onActivity { WithdrawalRootTestActivity.controller.open(75) }
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertExists()
            compose.onNodeWithText("다시 확인").performScrollTo().performClick()
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertExists()
            scenario.recreate()
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertExists()
            assertEquals(0,WithdrawalRootTestActivity.server.sends)
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
    @Test fun failedExecuteWithPreparedResultExplainsRetryAndRequiresNewConsent() {
        WithdrawalRootTestActivity.reset()
        val context=InstrumentationRegistry.getInstrumentation().context
        try {
            ActivityScenario.launch<WithdrawalRootTestActivity>(Intent(context,WithdrawalRootTestActivity::class.java)).use { scenario ->
                scenario.onActivity {
                    // 실행은 500, 영수증 조회는 기존 PREPARED를 반환한 실서버 순서를 재현한다.
                    WithdrawalRootTestActivity.server.sent={ ApiResult.Fail(AppError.Server(500,"INTERNAL_SERVER_ERROR","synthetic")) }
                    WithdrawalRootTestActivity.controller.open(75)
                }
                compose.onNodeWithText("탈퇴 안내 확인하기").performScrollTo().performClick()
                compose.onNodeWithText("이전 요청에서 탈퇴가 완료되지 않았어요.",substring=true).assertDoesNotExist()
                compose.onNodeWithText("삭제되는 정보와 재가입 제한을 확인했고, 회원탈퇴에 동의해요.").performScrollTo().performClick()
                compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().performClick()
                compose.onNodeWithText("이전 요청에서 탈퇴가 완료되지 않았어요.",substring=true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().assertIsNotEnabled()
                assertEquals(1,WithdrawalRootTestActivity.server.sends)
            }
        } finally {
            WithdrawalRootTestActivity.scope.cancel()
        }
    }
    @Test fun finalClickEndsSessionButCompletionSurvivesActivityRecreation() {
        WithdrawalRootTestActivity.reset()
        val context=InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalRootTestActivity>(Intent(context,WithdrawalRootTestActivity::class.java)).use { scenario ->
            scenario.onActivity { WithdrawalRootTestActivity.controller.open(75) }
            compose.onNodeWithText("탈퇴 안내 확인하기").performScrollTo().performClick()
            compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("삭제되는 정보와 재가입 제한을 확인했고, 회원탈퇴에 동의해요.").performScrollTo().performClick()
            compose.onNodeWithText("동의하고 회원탈퇴").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(Session.SignedOut,WithdrawalRootTestActivity.sessions.state.value)
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertExists()
            compose.onNodeWithText("인증 기본 화면").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("회원탈퇴가 완료됐어요").assertExists()
            compose.onNodeWithText("사진 파일 삭제는 아직 처리 중",substring=true).performScrollTo().assertIsDisplayed()
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
}

/** 실제 서비스 호스트의 가입 분기와 키링 Route를 함께 검사한다. BLE와 원격 서버만 시험 환경으로 대체한다. */
class WithdrawalSignupHostActivity : ComponentActivity() {
    private lateinit var testKoin: org.koin.core.KoinApplication
    companion object { internal var keyringReads = 0 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = WithdrawalRootTestActivity.sessions
        val controller = WithdrawalRootTestActivity.controller
        // 첫 진입은 탈퇴 제한 화면이다. 회전 때는 소비한 이동 요청을 다시 만들지 않는다.
        if (savedInstanceState == null) controller.open(75)
        val home = HomeSessionViewModel(unused(), unused(), unused(), sessions, unused(), unused(), prefs = unused())
        viewModelStore.put("signup-home", home)
        val owned = object : OwnedKeyringRepository {
            override suspend fun list(): ApiResult<List<OwnedKeyring>> {
                keyringReads++
                return ApiResult.Ok(listOf(OwnedKeyring(4, "REGISTERED", "synthetic")))
            }
        }
        val pushes = object : PushEvents {
            override val pending = MutableStateFlow<PushTarget?>(null)
            override fun consume(target: PushTarget) = Unit
        }
        // 전역 Koin을 재사용하면 앞선 시험의 Coordinator가 섞인다. Activity별 격리 컨테이너를 사용한다.
        testKoin = koinApplication { modules(module {
            single { controller }
            single { KeyringResetStore(ChatMemoryPrefs()) }
            factory { OwnedKeyringsViewModel(owned, sessions) }
        }) }
        setContent {
            val session by sessions.state.collectAsState()
            KoinIsolatedContext(testKoin) {
                AppTheme { WithdrawalRoot(session, content = {
                    (session as? Session.SignedIn)?.let {
                        // 화면 분기·실제 목록 Route는 제품 코드를 실행하고, 자동 BLE 통신만 수행하지 않는다.
                        SignedInServiceHost(it, ProposalAccess(false), home, pushes, foregroundSync = {})
                    }
                }, controller = controller) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testKoin.close()
    }

    // 호출되면 가입 제한 밖의 일반 서비스 조회가 실행된 것이므로 시험을 실패시킨다.
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> throw AssertionError("Unexpected service call: ${method.name}") } as T
}

class WithdrawalSignupHostDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun expiredPreparationShowsBlockedReasonAndReachesActualKeyringManagement() {
        WithdrawalRootTestActivity.reset()
        WithdrawalRootTestActivity.sessions.state.value=Session.SignedIn(75,OnboardingStatus.PROFILE_REQUIRED,false)
        val original=WithdrawalTicket(75,"11111111-1111-4111-8111-111111111111",
            WithdrawalOperation("11111111-1111-4111-8111-111111111111","PREPARED",
                "2026-11-08T00:00:00Z","2026-11-08T00:30:00Z",externalCleanupStatus="NOT_STARTED",
                devicePermanentlyBlocked=false),"synthetic")
        runBlocking { WithdrawalJournal(WithdrawalRootTestActivity.records).save(original) }
        WithdrawalRootTestActivity.server.response=ApiResult.Fail(AppError.Server(410,"WITHDRAWAL_OPERATION_EXPIRED","synthetic"))
        WithdrawalRootTestActivity.server.eligibilityValue=WithdrawalRootTestActivity.server.eligibilityValue.copy(
            eligible=false,registeredKeyringCount=1,blockingCodes=listOf("KEYRING_REGISTERED"))
        val context=InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalSignupHostActivity>(Intent(context,WithdrawalSignupHostActivity::class.java)).use { scenario ->
            // 만료 복구 결과로 실제 차단 화면을 그리고, 제품 호스트의 키링 목록까지 이동한다.
            compose.onNodeWithText("키링 관리로").performScrollTo().performClick()
            compose.onNodeWithText("내 키링").assertExists()
            compose.onNodeWithText("초기화",useUnmergedTree=true).assertExists()
            scenario.recreate()
            compose.onNodeWithText("내 키링").assertExists()
            runBlocking { assertEquals(original,WithdrawalJournal(WithdrawalRootTestActivity.records).all().single()) }
            assertEquals(0,WithdrawalRootTestActivity.server.sends)
            assertEquals(0,WithdrawalRootTestActivity.server.keys.size)
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
    @Test fun incompleteSignupReachesActualOwnedKeyringRouteAndSurvivesRotation() {
        WithdrawalRootTestActivity.reset()
        WithdrawalRootTestActivity.sessions.state.value = Session.SignedIn(75, OnboardingStatus.PROFILE_REQUIRED, false)
        WithdrawalRootTestActivity.server.eligibilityValue = WithdrawalRootTestActivity.server.eligibilityValue.copy(
            eligible = false, registeredKeyringCount = 1, blockingCodes = listOf("KEYRING_REGISTERED"))
        WithdrawalSignupHostActivity.keyringReads = 0
        val context = InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch<WithdrawalSignupHostActivity>(Intent(context, WithdrawalSignupHostActivity::class.java)).use { scenario ->
            compose.onNodeWithText("키링 관리로").performScrollTo().performClick()
            compose.onNodeWithText("내 키링").assertExists()
            compose.onNodeWithText("초기화", useUnmergedTree = true).assertExists()
            assertEquals(Session.SignedIn(75, OnboardingStatus.PROFILE_REQUIRED, false), WithdrawalRootTestActivity.sessions.state.value)
            org.junit.Assert.assertTrue(WithdrawalSignupHostActivity.keyringReads > 0)
            scenario.recreate()
            compose.onNodeWithText("내 키링").assertExists()
            compose.onNodeWithText("초기화", useUnmergedTree = true).assertExists()
        }
        WithdrawalRootTestActivity.scope.cancel()
    }
}
