package com.ssafy.e106.features.survey

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.survey.data.*
import com.ssafy.e106.features.survey.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** 실제 Route와 Activity 재생성을 사용한다. 서버 대역은 이 테스트 APK에만 들어간다. */
class SurveyLifecycleTestActivity : ComponentActivity() {
    companion object { internal lateinit var fixture: SurveyLifecycleFixture }
    lateinit var model: SurveyViewModel
    lateinit var changeModel: SurveyChangeViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return (if (modelClass == SurveyViewModel::class.java)
                    SurveyViewModel(fixture.server, fixture.session, fixture.cursor, SurveyAccess())
                else SurveyChangeViewModel(fixture.server, fixture.session, fixture.cursor, SurveyAccess())) as T
            }
        }
        model = ViewModelProvider(this, factory)[SurveyViewModel::class.java]
        changeModel = ViewModelProvider(this, factory)[SurveyChangeViewModel::class.java]
        setContent { AppTheme { SurveyRoute(fixture.session.state.value as Session.SignedIn,
            onBack = { fixture.exits.incrementAndGet() }, model = model, changeModel = changeModel) } }
    }
}

internal class SurveyLifecycleFixture {
    val session = TestSession(OnboardingStatus.SURVEY_REQUIRED)
    val cursor = CursorMemory()
    val server = LifecycleSurveyServer()
    // 요청/화면 콜백과 테스트 스레드 사이에서 횟수가 안정적으로 보이도록 원자적 카운터를 쓴다.
    val exits = AtomicInteger()
}

/** 취소 가능한 대기로 요청을 멈춰 두어 잘못된 leave 호출을 실제로 드러낸다. */
internal class LifecycleSurveyServer : SurveyRepository by SurveyServer() {
    val base = SurveyServer()
    var saveGate: CompletableDeferred<Unit>? = null
    var completeGate: CompletableDeferred<Unit>? = null
    var changeGate: CompletableDeferred<Unit>? = null
    var previousGate: CompletableDeferred<Unit>? = null
    val saves = AtomicInteger(); val completes = AtomicInteger(); val moves = AtomicInteger(); val plans = AtomicInteger()
    var target = surveyDefinition
    override suspend fun current() = ApiResult.Ok(target)
    override suspend fun start(version: String) = base.start(version)
    override suspend fun get(id: Long): ApiResult<SurveySession> {
        if (id == 10L) { previousGate?.await(); return ApiResult.Ok(base.data.copy(sessionId = 10)) }
        return base.get(id)
    }
    override suspend fun questionnaire(id: Long) = base.questionnaire(id)
    override suspend fun requestAnalysis(id: Long) = base.requestAnalysis(id)
    override suspend fun analysis(id: Long) = base.analysis(id)
    override suspend fun save(id: Long, questionId: String, value: JsonElement): ApiResult<SurveySession> {
        saves.incrementAndGet(); saveGate?.await(); return base.save(id, questionId, value)
    }
    override suspend fun complete(id: Long): ApiResult<SurveySession> {
        completes.incrementAndGet(); completeGate?.await(); return base.complete(id)
    }
    override suspend fun migrationPlan(id: Long, target: String): ApiResult<SurveyMigrationPlan> {
        plans.incrementAndGet(); return ApiResult.Ok(SurveyMigrationPlan(id, target, emptyList(), listOf("Q001", "Q002"), emptyList(), "b".repeat(64)))
    }
    override suspend fun migrate(id: Long, target: String, policyHash: String): ApiResult<SurveySession> {
        assertEquals("b".repeat(64), policyHash)
        moves.incrementAndGet(); changeGate?.await()
        base.definition = this.target
        base.data = SurveySession(12, this.target.manifest, "DRAFT", 0, 2, "Q001", emptyList())
        return ApiResult.Ok(base.data)
    }
}

class SurveyLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(f: SurveyLifecycleFixture): ActivityScenario<SurveyLifecycleTestActivity> {
        SurveyLifecycleTestActivity.fixture = f
        return ActivityScenario.launch(Intent(InstrumentationRegistry.getInstrumentation().context, SurveyLifecycleTestActivity::class.java))
    }
    private fun ready(s: ActivityScenario<SurveyLifecycleTestActivity>, page: SurveyPage) {
        compose.waitUntil(10_000) {
            var ok = false; s.onActivity { ok = !it.model.state.value.busy && it.model.state.value.page == page }; ok
        }
    }
    private fun start(s: ActivityScenario<SurveyLifecycleTestActivity>) {
        ready(s, SurveyPage.Intro); s.onActivity { it.model.start() }; ready(s, SurveyPage.Question)
    }
    @Test fun backDuringSaveWaitsForServerResultAndNavigatesOnceAfterRecreation() {
        val f = SurveyLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.saveGate = gate
        launch(f).use { s ->
            start(s); s.onActivity { it.model.answer(SurveyAnswer.Unknown) }
            compose.waitUntil(5_000) { f.server.saves.get() == 1 }
            s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle(); assertEquals(0, f.exits.get())
            s.onActivity { assertTrue(it.model.state.value.busy); assertTrue(it.model.state.value.exitRequested) }
            s.recreate(); gate.complete(Unit)
            compose.waitUntil(5_000) { f.exits.get() == 1 }
            assertEquals(JsonPrimitive("unknown"), f.server.base.data.answers.single().value)
            assertEquals(1, f.server.saves.get())
        }
    }
    @Test fun previousSessionReadLocksVersionButtonEvenWhenAnswerIsAlreadySaved() {
        val f = SurveyLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.previousGate = gate
        f.cursor.values[1] = 11; f.cursor.previous[1] = SurveyBookmark(10)
        f.server.base.persist("Q001", JsonPrimitive("unknown"))
        launch(f).use { s ->
            ready(s, SurveyPage.Resume); s.onActivity { it.model.resume(); it.model.previous() }; ready(s, SurveyPage.Question)
            compose.onNodeWithText("직전 회차 확인").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                var busy = false; s.onActivity { busy = it.model.state.value.busy }; busy
            }
            compose.onNodeWithText("최신 설문 확인").assertIsNotEnabled()
            s.onActivity { assertFalse(it.model.beginChange()) }
            gate.complete(Unit); ready(s, SurveyPage.Resume)
            assertEquals(0, f.server.moves.get())
        }
    }
    @Test fun nextQuestionStartsAtTopWhileRecreationRetainsCurrentScroll() {
        val f = SurveyLifecycleFixture()
        val longDefinition = surveyDefinition.copy(questions = surveyDefinition.questions.map { q ->
            q.copy(text = "question-${q.ordinal}", options = q.options.map { it.copy(label = it.label + "\n" + "긴 보기 내용\n".repeat(15)) })
        })
        f.server.target = longDefinition; f.server.base.definition = longDefinition
        launch(f).use { s ->
            start(s); s.onActivity { it.model.answer(SurveyAnswer.Unknown) }; ready(s, SurveyPage.Question)
            compose.onNodeWithText("최신 설문 확인").performScrollTo()
            compose.onNodeWithText("question-1").assertIsNotDisplayed()
            s.recreate(); compose.onNodeWithText("question-1").assertIsNotDisplayed()
            compose.onNodeWithText("다음").performClick()
            compose.onNodeWithText("question-2").assertIsDisplayed()
        }
    }
    @Test fun answerSaveSurvivesRecreationAndBackgroundWithoutLosingReviewPosition() {
        val f = SurveyLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.saveGate = gate
        launch(f).use { s ->
            start(s)
            lateinit var original: SurveyViewModel
            s.onActivity { original = it.model; it.model.answer(SurveyAnswer.Unknown) }
            compose.waitUntil(5_000) { f.server.saves.get() == 1 }
            s.recreate(); s.onActivity { assertSame(original, it.model); assertTrue(it.model.state.value.busy) }
            s.moveToState(Lifecycle.State.CREATED); gate.complete(Unit); s.moveToState(Lifecycle.State.RESUMED)
            ready(s, SurveyPage.Question)
            s.onActivity { assertEquals(SurveySaveState.Saved, it.model.state.value.saveState); it.model.next(); it.model.answer(SurveyAnswer.Any) }
            compose.waitUntil(5_000) { f.server.saves.get() == 2 }; ready(s, SurveyPage.Question)
            s.onActivity { it.model.next() }; ready(s, SurveyPage.Review)
            s.recreate(); s.moveToState(Lifecycle.State.CREATED); s.moveToState(Lifecycle.State.RESUMED)
            ready(s, SurveyPage.Review); assertEquals(2, f.server.saves.get())
        }
    }
    @Test fun submissionContinuesToAnalysisAfterRecreationAndBackground() {
        val f = SurveyLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.completeGate = gate
        f.server.base.persist("Q001", JsonPrimitive(4)); f.server.base.persist("Q002", JsonPrimitive("any"))
        launch(f).use { s ->
            ready(s, SurveyPage.Intro); s.onActivity { it.model.start() }; ready(s, SurveyPage.Resume)
            s.onActivity { it.model.resume() }; ready(s, SurveyPage.Review)
            s.onActivity { it.model.submit() }; compose.waitUntil(5_000) { f.server.completes.get() == 1 }
            s.recreate(); s.moveToState(Lifecycle.State.CREATED); gate.complete(Unit); s.moveToState(Lifecycle.State.RESUMED)
            ready(s, SurveyPage.Completed)
            assertEquals(1, f.server.base.analysisRequests); assertEquals(1, f.server.completes.get())
            s.onActivity { assertEquals(21L, it.model.state.value.analysisReference?.resultId); assertFalse(it.model.state.value.recoveryRequired) }
        }
    }
    @Test fun migrationBackWaitsForResultAcrossRecreation() {
        val f = SurveyLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.changeGate = gate
        launch(f).use { s ->
            start(s)
            f.server.target = surveyDefinition.copy(manifest = surveyManifest.copy(surveyVersion = "v2"))
            compose.onNodeWithText("최신 설문 확인").performScrollTo().performClick()
            compose.waitUntil(5_000) { f.server.plans.get() == 1 }
            compose.onNodeWithText("확인하고 새 설문으로 옮기기").assertExists()
            s.recreate()
            compose.onNodeWithText("확인하고 새 설문으로 옮기기").assertExists(); assertEquals(1, f.server.plans.get())
            compose.onNodeWithText("확인하고 새 설문으로 옮기기").performClick()
            compose.waitUntil(5_000) { f.server.moves.get() == 1 }
            // 시스템 뒤로가기를 눌러도 요청은 유지되고 새 회차가 확정된 뒤에만 전환 화면을 닫는다.
            s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            s.onActivity {
                assertTrue(it.changeModel.state.value.busy)
                assertTrue(it.changeModel.state.value.exitRequested)
                assertNotNull(f.cursor.pending[1])
            }
            s.recreate(); s.moveToState(Lifecycle.State.CREATED); gate.complete(Unit); s.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) {
                var ok = false; s.onActivity { ok = it.model.state.value.session?.sessionId == 12L && !it.model.state.value.busy }; ok
            }
            assertEquals(1, f.server.moves.get()); assertEquals(1, f.server.plans.get())
            compose.onNodeWithText("확인하고 새 설문으로 옮기기").assertDoesNotExist()
        }
    }
}
