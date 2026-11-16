package com.ssafy.e106.features.profile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.onboarding.data.*
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.*

/** 실제 Activity 재생성과 결과 실행기 복원을 검증한다. 서버/시스템 선택기만 테스트 대역이다. */
class ProfileLifecycleTestActivity : ComponentActivity() {
    companion object { internal lateinit var fixture: ProfileLifecycleFixture }
    lateinit var model: OptionalProfileViewModel
    lateinit var editModel: ProfileEditViewModel
    val instanceNumber = ++fixture.instances
    val photoRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                    options: ActivityOptionsCompat?) {
            fixture.pickerLaunches++; fixture.pickerRequest = requestCode
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoRegistry.onRestoreInstanceState(savedInstanceState?.getBundle("picker"))
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return OptionalProfileViewModel(fixture.server, fixture.server, fixture.session) as T
            }
        })[OptionalProfileViewModel::class.java]
        editModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ProfileEditViewModel(fixture.server, fixture.server, fixture.session, DevelopmentProfileAccess()) as T
            }
        })[ProfileEditViewModel::class.java]
        setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = photoRegistry
            }) {
                AppTheme {
                    if (fixture.management) ProfileManagementRoute(fixture.session.state.value as Session.SignedIn,
                        onExit = { fixture.exits++ }, model = editModel)
                    else OptionalProfileRoute(fixture.session.state.value as Session.SignedIn,
                        onContinue = { fixture.completedBy = instanceNumber },
                        onBack = { fixture.exits++ }, model = model)
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("picker", Bundle().also(photoRegistry::onSaveInstanceState))
        super.onSaveInstanceState(outState)
    }
}

internal class ProfileLifecycleFixture(val management: Boolean = false) {
    val session = TestSession(if (management) OnboardingStatus.READY else OnboardingStatus.PROFILE_REQUIRED)
    val server = LifecycleProfileServer().apply { data = data.copy(completed = management) }
    var instances = 0
    var pickerLaunches = 0
    var pickerRequest = 0
    var completedBy = 0
    var exits = 0
}

class LifecycleProfileServer : ProfileRepository, OptionalProfileRepository {
    var data = ProfileResponse(1, "Tester", "2000-01-01", "MALE", "NON_SMOKER", "NEVER")
    var records = OptionalConsentType.entries.map { OptionalConsentRecord(it, "v1", true, "v1") }
    var saveGate: CompletableDeferred<Unit>? = null
    var consentGate: CompletableDeferred<Unit>? = null
    var offline = false
    var writes = 0
    var consentWrites = 0
    var uploaded: ByteArray? = null
    override suspend fun get(userId: Long): ApiResult<ProfileResponse> =
        if (offline) ApiResult.Fail(AppError.Offline) else ApiResult.Ok(data)
    override suspend fun nickname(value: String) = ApiResult.Ok(NicknameAvailability(value, true))
    override suspend fun complete(userId: Long) = ApiResult.Ok(Unit)
    override suspend fun save(userId: Long, fields: JsonObject): ApiResult<ProfileResponse> {
        writes++; saveGate?.await()
        if (offline) return ApiResult.Fail(AppError.Offline)
        data = data.copy(introduction = fields["introduction"]?.jsonPrimitive?.contentOrNull ?: data.introduction,
            nickname = fields["nickname"]?.jsonPrimitive?.content ?: data.nickname)
        return ApiResult.Ok(data)
    }
    override suspend fun documents() = ApiResult.Ok(AgreementsResponse(records.map {
        AgreementItem(it.type.name, "v1", it.type.name, false, "https://example.test/terms")
    }))
    override suspend fun consents(userId: Long) = ApiResult.Ok(OptionalConsentsResponse(records))
    override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest): ApiResult<OptionalConsentRecord> {
        consentWrites++; consentGate?.await()
        val saved = OptionalConsentRecord(type, "v1", request.agreed, "v1")
        records = records.map { if (it.type == type) saved else it }
        return ApiResult.Ok(saved)
    }
    override suspend fun photo(userId: Long): ApiResult<ByteArray> = ApiResult.Ok(uploaded ?: byteArrayOf())
    override suspend fun upload(userId: Long, jpeg: ByteArray): ApiResult<ProfileResponse> {
        uploaded = jpeg; data = data.copy(hasProfilePhoto = true); return ApiResult.Ok(data)
    }
    override suspend fun deletePhoto(userId: Long) = ApiResult.Ok(Unit)
}

class ProfileLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(fixture: ProfileLifecycleFixture): ActivityScenario<ProfileLifecycleTestActivity> {
        ProfileLifecycleTestActivity.fixture = fixture
        val context = InstrumentationRegistry.getInstrumentation().context
        return ActivityScenario.launch(Intent(context, ProfileLifecycleTestActivity::class.java))
    }
    private fun loaded(scenario: ActivityScenario<ProfileLifecycleTestActivity>) {
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { ready = it.model.state.value.loaded && !it.model.state.value.busy }
            ready
        }
    }
    @Test fun saveSurvivesActivityRecreationAndNewScreenNavigatesOnce() {
        val f = ProfileLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.saveGate = gate
        launch(f).use { scenario ->
            loaded(scenario)
            lateinit var original: OptionalProfileViewModel
            scenario.onActivity { original = it.model; it.model.introduction("saved"); it.model.save() }
            compose.waitUntil(5_000) { f.server.writes == 1 }
            scenario.recreate()
            scenario.onActivity { assertSame(original, it.model); assertTrue(it.model.state.value.busy) }
            gate.complete(Unit)
            compose.waitUntil(5_000) { f.completedBy != 0 }
            assertEquals(2, f.completedBy); assertEquals(1, f.server.writes)
            scenario.recreate(); compose.waitForIdle(); assertEquals(2, f.completedBy)
        }
    }
    @Test fun consentPutSurvivesActivityRecreation() {
        val f = ProfileLifecycleFixture(); val gate = CompletableDeferred<Unit>(); f.server.consentGate = gate
        launch(f).use { scenario ->
            loaded(scenario)
            scenario.onActivity { it.model.decide(OptionalConsentType.PROFILE_PHOTO, false); it.model.confirmDecision() }
            compose.waitUntil(5_000) { f.server.consentWrites == 1 }
            scenario.recreate()
            scenario.onActivity { assertTrue(it.model.state.value.busy) }
            gate.complete(Unit); loaded(scenario)
            scenario.onActivity { assertFalse(it.model.state.value.details.photoConsent); assertFalse(it.model.state.value.recoveryRequired) }
            assertEquals(1, f.server.consentWrites)
        }
    }
    @Test fun basicProfilePatchSurvivesRecreationAndReturnsToOverview() {
        val f = ProfileLifecycleFixture(management = true); val gate = CompletableDeferred<Unit>()
        f.server.saveGate = gate
        launch(f).use { scenario ->
            compose.waitUntil(5_000) {
                var ready = false; scenario.onActivity { ready = it.editModel.state.value.canEdit }; ready
            }
            compose.onNodeWithContentDescription("프로필 수정").performClick()
            lateinit var original: ProfileEditViewModel
            scenario.onActivity {
                original = it.editModel
                it.editModel.change(it.editModel.state.value.editor!!.draft.copy(nickname = "New Name"))
                it.editModel.save()
            }
            compose.waitUntil(5_000) { f.server.writes == 1 }; scenario.recreate()
            scenario.onActivity { assertSame(original, it.editModel); assertTrue(it.editModel.state.value.busy) }
            gate.complete(Unit)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("내 프로필").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("New Name").assertExists()
            assertEquals(1, f.server.writes)
        }
    }
    @Test fun unsavedProfileSurvivesRecreationAndBackRequiresChoice() {
        // 실제 편집 ViewModel/Route를 사용하되 저장소만 대역으로 둔다. 서버 쓰기는 끝까지 0이어야 한다.
        val f = ProfileLifecycleFixture(management = true)
        launch(f).use { scenario ->
            compose.waitUntil(5_000) {
                var ready = false; scenario.onActivity { ready = it.editModel.state.value.canEdit }; ready
            }
            compose.onNodeWithContentDescription("프로필 수정").performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Layout Draft")
            scenario.recreate()
            compose.waitUntil(5_000) {
                var ready = false; scenario.onActivity { ready = it.editModel.state.value.canEdit }; ready
            }
            compose.onNodeWithText("Layout Draft").assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("변경 내용을 저장하지 않고 나갈까요?").assertIsDisplayed()
            compose.onNodeWithText("계속 수정하기").performClick()
            compose.onNodeWithText("Layout Draft").assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("저장하지 않고 나가기").performClick()
            compose.onNodeWithText("내 프로필").assertExists()
            scenario.onActivity { assertEquals(0, f.server.writes); assertEquals("Tester", f.server.data.nickname) }
        }
    }
    @Test fun pickerResultAndCropSurviveRecreationWithoutRelaunchOrUpload() {
        val f = ProfileLifecycleFixture()
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "lifecycle-photo.png")
        // 왼쪽 빨강·오른쪽 파랑으로 만들어 이동한 자르기 결과까지 검증한다.
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 400) for (y in 0 until 200) bitmap.setPixel(x, y, if (x < 200) Color.RED else Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            launch(f).use { scenario ->
                loaded(scenario)
                compose.onNodeWithText("눌러서 사진 선택", substring = true).performScrollTo().performClick()
                compose.waitUntil(5_000) { f.pickerLaunches == 1 }
                scenario.recreate(); compose.waitForIdle(); assertEquals(1, f.pickerLaunches)
                scenario.onActivity { it.photoRegistry.dispatchResult(f.pickerRequest, Uri.fromFile(file)) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("사진 구도 맞추기").fetchSemanticsNodes().isNotEmpty() }
                fun cropPixels(): List<Int> {
                    val pixels = compose.onNodeWithContentDescription("선택한 사진의 정사각형 영역").captureToImage().toPixelMap()
                    return List(pixels.width) { x -> pixels[x, pixels.height / 2].toArgb() }
                }
                val centered = cropPixels()
                compose.onNodeWithContentDescription("선택한 사진의 정사각형 영역").performTouchInput { swipeLeft() }
                val moved = cropPixels()
                assertNotEquals(centered, moved, "손가락 이동이 실제 자르기 영역을 바꿔야 한다")
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("선택한 사진의 정사각형 영역").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(moved, cropPixels(), "회전 후 이동한 구도가 유지되어야 한다")
                compose.onNodeWithText("잘린 사진 확인").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("잘린 사진 미리보기").fetchSemanticsNodes().isNotEmpty() }
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("잘린 사진 미리보기").fetchSemanticsNodes().isNotEmpty() }
                assertNull(f.server.uploaded); assertEquals(1, f.pickerLaunches)
                compose.onNodeWithText("이 사진 적용").performClick()
                compose.waitUntil(10_000) { f.server.uploaded != null }
                val bytes = f.server.uploaded!!; val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(result.width, result.height); assertTrue(result.width > 0); result.recycle()
            }
        } finally { file.delete() }
    }
    @Test fun offlineUnknownResultAllowsConfirmedExitWithoutClaimingSuccess() {
        val f = ProfileLifecycleFixture()
        launch(f).use { scenario ->
            loaded(scenario)
            scenario.onActivity { it.model.introduction("draft"); f.server.offline = true; it.model.save() }
            compose.waitUntil(5_000) {
                var unknown = false; scenario.onActivity { unknown = it.model.state.value.recoveryRequired }; unknown
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("결과를 확인하지 못했어요. 그래도 나갈까요?").assertExists()
            compose.onNodeWithText("나가기").performClick()
            assertEquals(1, f.exits); assertEquals(0, f.completedBy)
            scenario.onActivity { assertTrue(it.model.state.value.recoveryRequired) }
        }
    }
}
