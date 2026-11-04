package com.ssafy.e106.features.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.settings.data.*
import com.ssafy.e106.features.settings.ui.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// MR 수명 회귀 검증 코드: 테스트 APK에서만 동작하며 실제 API/사용자 설정은 수정하지 않는다.
class SettingsLifecycleServer : MatchingSettingsRepository {
    val entered = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    val gate = CompletableDeferred<Unit>()
    override suspend fun get(userId: Long, kind: SettingsKind) = ApiResult.Ok(
        SettingsDocument(ConflictValues.Matching(true,false), "\"1\""))
    override suspend fun save(userId: Long, kind: SettingsKind, values: ConflictValues, etag: String): ApiResult<SettingsDocument> {
        entered.set(true)
        try { gate.await() } catch(e: CancellationException) { cancelled.set(true); throw e }
        return ApiResult.Ok(SettingsDocument(values,"\"2\""))
    }
}
class SettingsLifecycleActivity : ComponentActivity() {
    companion object { lateinit var server: SettingsLifecycleServer }
    lateinit var model: MatchingSettingsViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T: ViewModel> create(clazz: Class<T>): T = MatchingSettingsViewModel(server,
                TestSession(OnboardingStatus.READY),SettingsAccess()) as T
        })[MatchingSettingsViewModel::class.java]
        setContent { AppTheme { ConnectedSettingsRoute(Session.SignedIn(1,OnboardingStatus.READY,false),{},model) } }
    }
}
class SettingsLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(server: SettingsLifecycleServer): ActivityScenario<SettingsLifecycleActivity> {
        SettingsLifecycleActivity.server=server
        return ActivityScenario.launch(Intent(InstrumentationRegistry.getInstrumentation().context,SettingsLifecycleActivity::class.java))
    }
    private fun scenarioIsSaving(scenario: ActivityScenario<SettingsLifecycleActivity>): Boolean {
        var saving = true
        scenario.onActivity { saving = it.model.state.value.saving }
        return saving
    }
    @Test fun rotationKeepsSelectedSettingsAndUnsavedDraft() {
        val server=SettingsLifecycleServer()
        launch(server).use { scenario ->
            compose.onNodeWithText("매칭 설정").performClick()
            compose.waitForIdle()
            scenario.onActivity {
                assertNotNull(it.model.state.value.document)
                it.model.editMatching(photoRequired=true)
                assertEquals(true,it.model.state.value.matching?.photoRequired)
            }
            scenario.recreate(); compose.waitForIdle()
            compose.onNodeWithText("희망 나이 · 매칭 설정").assertDoesNotExist()
            scenario.onActivity { assertEquals("Rotation must preserve edited photo preference",true,it.model.state.value.matching?.photoRequired) }
        }
    }
    @Test fun rotationDoesNotCancelPendingSave() {
        val server=SettingsLifecycleServer()
        try { launch(server).use { scenario ->
            compose.onNodeWithText("매칭 설정").performClick(); compose.waitForIdle()
            scenario.onActivity { it.model.editMatching(photoRequired=true); it.model.save() }
            compose.waitUntil(5_000) { server.entered.get() }
            scenario.recreate(); compose.waitForIdle()
            assertFalse("Rotation cancelled settings PUT",server.cancelled.get())
            server.gate.complete(Unit)
            compose.waitUntil(5_000) { !SettingsLifecycleActivity.server.cancelled.get() && !scenarioIsSaving(scenario) }
            scenario.onActivity { assertTrue(it.model.state.value.saved) }
        } } finally { server.gate.complete(Unit) }
    }
    @Test fun backgroundDoesNotCancelPendingSave() {
        val server = SettingsLifecycleServer()
        try { launch(server).use { scenario ->
            compose.onNodeWithText("매칭 설정").performClick(); compose.waitForIdle()
            scenario.onActivity { it.model.editMatching(photoRequired = true); it.model.save() }
            compose.waitUntil(5_000) { server.entered.get() }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertFalse(server.cancelled.get())
            server.gate.complete(Unit)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) { !scenarioIsSaving(scenario) }
            scenario.onActivity { assertTrue(it.model.state.value.saved) }
        } } finally { server.gate.complete(Unit) }
    }
    @Test fun explicitBackClearsDraft() {
        launch(SettingsLifecycleServer()).use { scenario ->
            compose.onNodeWithText("매칭 설정").performClick(); compose.waitForIdle()
            scenario.onActivity { it.model.editMatching(photoRequired = true) }
            compose.onNodeWithText("돌아가기").performClick(); compose.waitForIdle()
            compose.onNodeWithText("희망 나이 · 매칭 설정").assertExists()
            scenario.onActivity { assertNull(it.model.state.value.matching) }
        }
    }
}
