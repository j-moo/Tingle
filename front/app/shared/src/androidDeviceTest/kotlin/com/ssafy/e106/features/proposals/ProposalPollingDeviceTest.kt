package com.ssafy.e106.features.proposals

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.storage.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.proposals.data.*
import com.ssafy.e106.features.proposals.reason.*
import com.ssafy.e106.features.proposals.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** 배포 앱에 포함하지 않는 서버 응답 대역. 실제 Route의 자동 갱신과 화면 이동만 검사한다. */
internal class ProposalPollingServer : ProposalRepository {
    @Volatile var record = ProposalRecord(10, "PENDING", "NONE", "2026-11-05T00:00:00Z", "2026-11-05T00:10:00Z", false)
    val reads = AtomicInteger()
    // 자동 GET을 멈춰 둔 상태에서 실제 목록 버튼/시스템 뒤로 가기의 이동 가능 여부를 검사한다.
    @Volatile var onGet: (suspend () -> ApiResult<ProposalRecord>)? = null
    override suspend fun list(cursor: String?) = ApiResult.Ok(ProposalPage(listOf(record), null, "2026-11-05T00:00:00Z"))
    override suspend fun get(id: Long): ApiResult<ProposalRecord> { reads.incrementAndGet(); return onGet?.invoke() ?: ApiResult.Ok(record) }
    override suspend fun profile(id: Long) = ApiResult.Ok(PartnerProfile("자동 갱신 시험", 25, "FEMALE", null,
        "NON_SMOKER", "NEVER", null, false, "a".repeat(64)))
    override suspend fun photo(id: Long): ApiResult<ByteArray> = ApiResult.Fail(AppError.Offline)
    // 수락 응답을 바꿔 끼울 수 있게(기본은 상대 응답 기다림)
    @Volatile var onDecide: (() -> ProposalRecord)? = null
    override suspend fun decide(request: PendingDecision): ApiResult<ProposalRecord> {
        record = onDecide?.invoke() ?: record.copy(status = "WAITING_FOR_OTHER", myDecision = "ACCEPT")
        return ApiResult.Ok(record)
    }
}

/** 테스트용 메모리 저장소이며 실제 계정이나 디스크의 복구 기록을 변경하지 않는다. */
private class ProposalPollingPrefs : AppPrefs {
    private val values = mutableMapOf<String, Any?>()
    override fun <T> flow(key: PrefKey<T>): Flow<T?> = flowOf(null)
    override suspend fun <T> set(key: PrefKey<T>, value: T?, forUser: Long) { values[key.name] = value }
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> read(key: PrefKey<T>, forUser: Long): PrefRead<T> =
        values[key.name]?.let { PrefRead.Found(it as T) } ?: PrefRead.Missing
}

class ProposalPollingTestActivity : ComponentActivity() {
    companion object { internal var server = ProposalPollingServer() }
    lateinit var model: ProposalViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = TestSession(OnboardingStatus.READY)
        val reasonApi = object : MatchReasonRepository {
            override suspend fun request(id: Long, key: String): ApiResult<ReasonTask> = ApiResult.Fail(AppError.Offline)
            override suspend fun get(id: Long): ApiResult<ReasonResult> = ApiResult.Fail(AppError.Offline)
        }
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                if (modelClass == ProposalViewModel::class.java)
                    ProposalViewModel(server, session, ProposalPollingPrefs(), ProposalAccess(true)) as T
                else MatchReasonViewModel(reasonApi, session) as T
        })
        model = provider[ProposalViewModel::class.java]
        setContent { AppTheme {
            var room by remember { mutableStateOf<Long?>(null) }
            if (room != null) Text("채팅방 $room 진입 확인")
            else ConnectedProposalRoute(session.state.value as Session.SignedIn, {},
                onOpenChat = { room = it }, model = model, reasonModel = provider[MatchReasonViewModel::class.java])
        } }
    }
}

class ProposalPollingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun listButtonLeavesDuringSlowAutomaticGet() = leaveDuringSlowGet(systemBack = false)
    @Test fun systemBackLeavesDuringSlowAutomaticGet() = leaveDuringSlowGet(systemBack = true)

    // 내가 두 번째로 수락해 바로 서로 수락 · 방 있음: 보내기가 끝나면(쓰기 중엔 이동이 막힘) 결과 화면 없이 그 방으로
    @Test fun acceptThatMatchesOpensChatAfterWrite() {
        ProposalPollingTestActivity.server = ProposalPollingServer()
        val server = ProposalPollingTestActivity.server
        server.onDecide = { server.record.copy(status = "ACCEPTED", myDecision = "ACCEPT", chatAvailable = true, chatRoomId = 40) }
        ActivityScenario.launch<ProposalPollingTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            ProposalPollingTestActivity::class.java)).use { scenario ->
            fun ready() = compose.waitUntil(10_000) { var done = false; scenario.onActivity { done = !it.model.state.value.busy }; done }
            compose.waitUntil(10_000) { var done = false; scenario.onActivity {
                done = it.model.state.value.list.load == ProposalListLoad.Ready }; done }
            scenario.onActivity { it.model.open(10) }; ready()
            scenario.onActivity { it.model.ask(ProposalChoice.Accept); it.model.submit() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("채팅방 40 진입 확인").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("제안 응답 시간이 지났어요").assertDoesNotExist()
        }
    }

    // 자동 조회가 아니라 사용자가 연 프로필을 받는 중이어도(refreshingResult=false) 뒤로 가면 바로 목록. 쓰는 중에만 막는다
    @Test fun systemBackLeavesDuringManualProfileLoad() {
        ProposalPollingTestActivity.server = ProposalPollingServer()
        val server = ProposalPollingTestActivity.server
        val gate = CompletableDeferred<Unit>()
        ActivityScenario.launch<ProposalPollingTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            ProposalPollingTestActivity::class.java)).use { scenario ->
            try {
                compose.waitUntil(10_000) { var done = false; scenario.onActivity {
                    done = it.model.state.value.list.load == ProposalListLoad.Ready && !it.model.state.value.busy }; done }
                server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.record) }
                scenario.onActivity { it.model.open(10) }
                compose.waitUntil(2_000) { var loading = false; scenario.onActivity {
                    loading = it.model.state.value.busy && !it.model.state.value.refreshingResult }; loading }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(2_000) { var listed = false; scenario.onActivity {
                    listed = it.model.state.value.page == ProposalPageMode.List }; listed }
                server.onGet = null
                gate.complete(Unit); compose.waitForIdle()
                scenario.onActivity { assertEquals(ProposalPageMode.List, it.model.state.value.page) }
            } finally { gate.complete(Unit) }
        }
    }

    /** 응답을 풀기 전에 이동을 단언해, GET이 끝난 뒤 이동한 기존 검사와 구분한다. */
    private fun leaveDuringSlowGet(systemBack: Boolean) {
        ProposalPollingTestActivity.server = ProposalPollingServer()
        val server = ProposalPollingTestActivity.server
        val gate = CompletableDeferred<Unit>()
        ActivityScenario.launch<ProposalPollingTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            ProposalPollingTestActivity::class.java)).use { scenario ->
            fun ready() = compose.waitUntil(10_000) {
                var done = false; scenario.onActivity { done = !it.model.state.value.busy }; done
            }
            try {
                compose.waitUntil(10_000) { var done = false; scenario.onActivity {
                    done = it.model.state.value.list.load == ProposalListLoad.Ready }; done }
                scenario.onActivity { it.model.open(10) }; ready()
                scenario.onActivity { it.model.ask(ProposalChoice.Accept); it.model.submit() }; ready()
                server.onGet = { withContext(NonCancellable) { gate.await() }; ApiResult.Ok(server.record.copy(status = "EXPIRED")) }
                compose.waitUntil(10_000) { var loading = false; scenario.onActivity {
                    loading = it.model.state.value.refreshingResult }; loading }
                compose.onNodeWithText("수락했어요").assertExists()
                if (systemBack) scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                else compose.onNodeWithContentDescription("제안 목록으로").performClick()   // 프로필 왼쪽 위 <
                compose.waitUntil(2_000) { var listed = false; scenario.onActivity {
                    listed = it.model.state.value.page == ProposalPageMode.List && !it.model.state.value.busy }; listed }
                compose.onNodeWithText("수락했어요").assertDoesNotExist()
                gate.complete(Unit); compose.waitForIdle()
                scenario.onActivity { assertEquals(ProposalPageMode.List, it.model.state.value.page) }
                compose.onNodeWithText("제안 응답 시간이 지났어요").assertDoesNotExist()
            } finally { gate.complete(Unit) }
        }
    }

    // 서버 상태만 바꾸고 재조회 버튼은 누르지 않아, 실제 화면의 자동 전환을 검사한다.
    @Test fun actualRouteExpiresAutomaticallyAndMatchOpensServerRoomDirectly() {
        ProposalPollingTestActivity.server = ProposalPollingServer()
        val server = ProposalPollingTestActivity.server
        ActivityScenario.launch<ProposalPollingTestActivity>(Intent(InstrumentationRegistry.getInstrumentation().context,
            ProposalPollingTestActivity::class.java)).use { scenario ->
            fun awaitReady() = compose.waitUntil(10_000) {
                var ready = false; scenario.onActivity { ready = !it.model.state.value.busy }; ready
            }
            fun accept() {
                awaitReady(); scenario.onActivity { it.model.open(10) }; awaitReady()
                scenario.onActivity { it.model.ask(ProposalChoice.Accept); it.model.submit() }; awaitReady()
                compose.onNodeWithText("수락했어요").assertExists()
            }
            compose.waitUntil(10_000) { var ready = false; scenario.onActivity {
                ready = it.model.state.value.list.load == ProposalListLoad.Ready }; ready }
            accept()
            server.record = server.record.copy(status = "EXPIRED")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("제안 응답 시간이 지났어요").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("제안 목록으로").performClick()
            compose.onNodeWithText("아직 받은 제안이 없어요").assertExists()

            server.record = server.record.copy(status = "PENDING", myDecision = "NONE")
            scenario.onActivity { it.model.refresh() }; accept()
            server.record = server.record.copy(status = "ACCEPTED", chatAvailable = true, chatRoomId = 30)
            // 서로 수락 · 방 확인되면 결과 화면 없이 서버가 준 방 번호로 바로 채팅을 연다
            compose.waitUntil(10_000) { compose.onAllNodesWithText("채팅방 30 진입 확인").fetchSemanticsNodes().isNotEmpty() }
            val before = server.reads.get()
            val exitedAt = System.nanoTime()
            compose.waitUntil(8_000) { System.nanoTime() - exitedAt >= 6_000_000_000L }
            assertEquals(before, server.reads.get())
        }
    }
}
