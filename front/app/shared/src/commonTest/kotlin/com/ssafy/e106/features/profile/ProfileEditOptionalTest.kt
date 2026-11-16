package com.ssafy.e106.features.profile

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.TestSession
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** 수정 화면의 종교 · 소개 시트 흐름: 수정 화면(ProfileEditPage)이 두 ViewModel 을 부르는 순서 그대로 검증한다 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditOptionalTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val session = TestSession(OnboardingStatus.READY)
    private val religion = OptionalConsentType.PROFILE_RELIGION
    private val intro = OptionalConsentType.PROFILE_INTRODUCTION
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun server() = OptionalServer().apply { data = data.copy(completed = true, introduction = "existing"); apply(intro, true) }
    private fun optional(s: OptionalServer) = OptionalProfileViewModel(s, s, session).also { store.put("optional", it); it.load() }
    private fun edit(s: OptionalServer) = ProfileEditViewModel(s, s, session, DevelopmentProfileAccess()).also { store.put("edit", it); it.load() }

    // 동의 시트 "동의하고 입력하기" → confirmDecision → 동의 저장 · 다시 조회가 끝나야 선택 시트를 연다
    @Test fun confirmedConsentOpensPickerOnlyAfterRefresh() = runTest(dispatcher) {
        val s = server(); val m = optional(s); runCurrent()
        assertFalse(m.state.value.agreed(religion)); assertTrue(m.state.value.canDecide(religion, true))
        m.decide(religion, true); val pending = religion
        assertFalse(m.state.value.consentCheckFailed(pending))   // 동의 시트가 떠 있는 동안
        m.confirmDecision()
        assertFalse(m.state.value.readyToEdit(pending)); assertFalse(m.state.value.consentCheckFailed(pending))
        runCurrent()
        assertEquals(listOf(religion to OptionalConsentRequest(true, "v2")), s.changes)
        assertTrue(m.state.value.readyToEdit(pending)); assertFalse(m.state.value.consentCheckFailed(pending))
    }

    // 동의 뒤 다시 조회가 실패하면 시트 대신 한 줄 오류 · "다시 확인"
    @Test fun failedRecheckAfterConsentShowsErrorInsteadOfPicker() = runTest(dispatcher) {
        val s = server(); val m = optional(s); runCurrent()
        m.decide(religion, true); s.consentReadFailure = AppError.Offline; m.confirmDecision(); runCurrent()
        assertFalse(m.state.value.readyToEdit(religion)); assertTrue(m.state.value.consentCheckFailed(religion))
        s.consentReadFailure = null; m.load(); runCurrent()
        assertTrue(m.state.value.readyToEdit(religion))
    }

    // 시트를 취소하면 discardDraft: 고치다 만 소개가 나중에 종교를 저장할 때 같이 저장되지 않는다
    @Test fun cancelledIntroDraftIsNotSavedWithReligion() = runTest(dispatcher) {
        val s = server().apply { apply(religion, true) }; val m = optional(s); runCurrent()
        m.introduction("half written"); m.discardDraft()
        m.religion(ProfileReligion.Catholic); m.save(); runCurrent()
        val fields = s.patches.single()
        assertEquals(JsonPrimitive("existing"), fields["introduction"]); assertEquals(JsonPrimitive("CATHOLIC"), fields["religion"])
    }

    // 시트에서 저장하면 completion 을 꺼내 수정 화면을 다시 불러오고, 고치던 닉네임은 유지된다
    @Test fun sheetSaveReloadsEditorAndKeepsNicknameDraft() = runTest(dispatcher) {
        val s = server().apply { apply(religion, true) }; val e = edit(s); val m = optional(s); runCurrent()
        e.change(e.state.value.editor!!.draft.copy(nickname = "Draft Name"))
        m.religion(ProfileReligion.Buddhist); m.save(); runCurrent()
        assertNotNull(m.takeCompletion()); assertNull(m.takeCompletion())
        e.load(); runCurrent()
        assertEquals("BUDDHIST", e.state.value.profile?.religion)
        assertEquals("Draft Name", e.state.value.editor?.draft?.nickname); assertTrue(s.patches.none { "nickname" in it })
    }

    // 소개 시트 "완료"는 200자나 줄바꿈 3개를 넘으면 꺼진다
    @Test fun introSheetDoneDisabledOverLimits() = runTest(dispatcher) {
        val m = optional(server()); runCurrent()
        fun done() = m.state.value.editable && m.state.value.details.canContinue
        m.introduction("가".repeat(200)); assertTrue(done())
        m.introduction("가".repeat(201)); assertFalse(done())
        m.introduction("a\nb\nc\nd"); assertTrue(done())
        m.introduction("a\nb\nc\nd\ne"); assertFalse(done())
    }
}
