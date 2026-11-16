package com.ssafy.e106.features.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.AuthRepository
import com.ssafy.e106.core.auth.LoginResult
import com.ssafy.e106.core.auth.Provider
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.auth.SessionStore
import com.ssafy.e106.core.network.createHttpClient
import com.ssafy.e106.core.push.PushEvents
import com.ssafy.e106.core.push.PushTarget
import com.ssafy.e106.core.realtime.ChatEvents
import com.ssafy.e106.core.storage.AppPrefs
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.account.End
import com.ssafy.e106.features.account.LoggedIn
import com.ssafy.e106.features.account.Records
import com.ssafy.e106.features.account.Server
import com.ssafy.e106.features.account.data.WithdrawalJournal
import com.ssafy.e106.features.account.ui.WithdrawalCoordinator
import com.ssafy.e106.features.chat.ChatMemoryPrefs
import com.ssafy.e106.features.chat.IdleChatEvents
import com.ssafy.e106.features.chat.chatModule
import com.ssafy.e106.features.home.ui.SignedInServiceHost
import com.ssafy.e106.features.keyring.data.HttpOwnedKeyringRepository
import com.ssafy.e106.features.keyring.data.KeyringSettingsChanges
import com.ssafy.e106.features.keyring.data.OwnedKeyringRepository
import com.ssafy.e106.features.onboarding.onboardingModule
import com.ssafy.e106.features.profile.profileModule
import com.ssafy.e106.features.proposals.proposalModule
import com.ssafy.e106.features.proposals.reason.matchReasonModule
import com.ssafy.e106.features.settings.settingsModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** 이동 분기를 시험용으로 다시 만들지 않는다. 앱과 같은 호스트를 띄우고 네트워크·BLE만 분리한다. */
class HomeNavigationTestActivity : ComponentActivity() {
    private lateinit var client: HttpClient
    private lateinit var di: KoinApplication
    val pushTap = MutableStateFlow<PushTarget?>(null)
    val proposalArrivals = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val proposalListReads = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = LoggedIn()
        val session = sessions.state.value as Session.SignedIn
        val emptyPage = """{"items":[],"nextCursor":null,"serverTime":"2026-11-11T09:00:00Z"}"""
        // 실제 목록 응답의 닉네임이 서비스 호스트를 거쳐 채팅 화면에 도달하는지 확인한다.
        val chatPage = """{"items":[{"chatRoomId":20,"status":"ACTIVE","createdAt":"2026-11-11T09:00:00Z",
            "expiresAt":"2026-11-18T09:00:00Z","closedAt":null,"deleteAt":null,"serverTime":"2026-11-11T09:00:00Z",
            "canRead":true,"canSend":true,"hasNewMessage":false,"partnerNickname":"하늘"}],
            "nextCursor":null,"serverTime":"2026-11-11T09:00:00Z"}"""
        client = createHttpClient("https://test/dev/api/", false, engine = MockEngine { request ->
            check(request.method == HttpMethod.Get)
            val body = when (request.url.encodedPath) {
                "/dev/api/users/75/settings" -> """{"matchingEnabled":true,"requirePartnerPhoto":false,"settingsVersion":1}"""
                "/dev/api/users/75/profile" -> """{"userId":75,"nickname":"메뉴 검증"}"""
                "/dev/api/keyrings" -> """{"items":[{"deviceId":4,"status":"REGISTERED","firmwareVersion":"test"}]}"""
                "/dev/api/match-proposals" -> {
                    proposalListReads.incrementAndGet()
                    emptyPage
                }
                "/dev/api/chat-rooms" -> chatPage
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\"")))
        })
        di = koinApplication {
            modules(homeModule, profileModule(), settingsModule(), onboardingModule(), proposalModule(),
                matchReasonModule, chatModule, module {
                    single { client }
                    single<SessionStore> { sessions }
                    single<AppPrefs> { ChatMemoryPrefs() }
                    single { com.ssafy.e106.core.push.NotificationInbox(get()) }
                    single<ChatEvents> { IdleChatEvents }
                    single { KeyringSettingsChanges() }
                    single<OwnedKeyringRepository> { HttpOwnedKeyringRepository(get()) }
                    single { WithdrawalCoordinator(Server(), WithdrawalJournal(Records()), sessions, End(sessions), lifecycleScope) }
                    single<AuthRepository> {
                        object : AuthRepository {
                            override fun available(provider: Provider) = false
                            override suspend fun login(provider: Provider): LoginResult = error("Unexpected login")
                            override suspend fun logout() = error("Unexpected logout")
                        }
                    }
                    single<PushEvents> {
                        object : PushEvents {
                            override val pending = pushTap
                            override val proposalArrivals = this@HomeNavigationTestActivity.proposalArrivals
                            override fun consume(target: PushTarget) { pushTap.compareAndSet(target, null) }
                        }
                    }
                })
        }
        setContent {
            KoinIsolatedContext(di) {
                AppTheme { SignedInServiceHost(session, foregroundSync = {}) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        di.close()
        client.close()
    }
}

class HomeNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun proposalTapOpensListAndForegroundReceiptRefreshesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<HomeNavigationTestActivity>(Intent(context, HomeNavigationTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            awaitText("새 인연을 기다리는 중")
            lateinit var reads: AtomicInteger
            scenario.onActivity {
                reads = it.proposalListReads
                it.pushTap.value = PushTarget.Proposal(42)
            }
            awaitText("아직 받은 제안이 없어요")
            val before = reads.get()
            scenario.onActivity { it.proposalArrivals.tryEmit(75) }
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() > before }
            awaitText("아직 받은 제안이 없어요")
        }
    }

    @Test fun accountTabsOpenHomeProposalListAndChatListThroughServiceHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<HomeNavigationTestActivity>(Intent(context, HomeNavigationTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            // 탭의 선택 표시만 보는 대신 목적지 고유 문구가 뜰 때까지 확인한다.
            openAccount()
            compose.onNodeWithText("홈").performClick()
            awaitText("새 인연을 기다리는 중")
            compose.onNodeWithContentDescription("알림").assertExists()   // 오른쪽 위 종 모양(받은 알림 모음)

            openAccount()
            compose.onNodeWithText("제안").performClick()
            awaitText("아직 받은 제안이 없어요")

            openAccount()
            compose.onNodeWithText("채팅").performClick()
            awaitText("하늘")
            compose.onNodeWithText("상대 정보 확인 필요").assertDoesNotExist()
        }
    }

    private fun openAccount() {
        compose.onNodeWithText("마이").performClick()
        awaitText("메뉴 검증")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
