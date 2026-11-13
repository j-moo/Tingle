package com.ssafy.e106.features.onboarding

import androidx.lifecycle.ViewModelStore
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.AgreementsResponse
import com.ssafy.e106.features.onboarding.ui.RequiredConsentViewModel
import com.ssafy.e106.features.profile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

private class FakeConsents(photo: Boolean, intro: Boolean, religion: Boolean = false) : OptionalProfileRepository {
    val agreed = mutableMapOf(OptionalConsentType.PROFILE_PHOTO to photo, OptionalConsentType.PROFILE_INTRODUCTION to intro,
        OptionalConsentType.PROFILE_RELIGION to religion)
    var offline = false
    var failWrites = false
    /** 서버가 내려 주는 동의 문서(같은 버전 · 볼 수 있는 주소) */
    var documents = listOf("PROFILE_PHOTO", "PROFILE_INTRODUCTION", "PROFILE_RELIGION").map {
        com.ssafy.e106.features.onboarding.data.AgreementItem(it, "v2", "(선택) $it", false, "https://tingle.test/$it/") }
    val writes = mutableListOf<Pair<OptionalConsentType, OptionalConsentRequest>>()
    override suspend fun documents(): ApiResult<AgreementsResponse> = ApiResult.Ok(AgreementsResponse(documents))
    override suspend fun consents(userId: Long): ApiResult<OptionalConsentsResponse> = if (offline) ApiResult.Fail(AppError.Offline)
        else ApiResult.Ok(OptionalConsentsResponse(agreed.map { (type, on) -> OptionalConsentRecord(type, "v2", on, if (on) "v2" else null) }))
    override suspend fun consent(userId: Long, type: OptionalConsentType, request: OptionalConsentRequest): ApiResult<OptionalConsentRecord> {
        writes += type to request
        if (failWrites) return ApiResult.Fail(AppError.Offline)
        agreed[type] = request.agreed
        return ApiResult.Ok(OptionalConsentRecord(type, request.version, request.agreed, request.version))
    }
    override suspend fun photo(userId: Long): ApiResult<ByteArray> = ApiResult.Fail(AppError.Offline)
    override suspend fun upload(userId: Long, jpeg: ByteArray): ApiResult<com.ssafy.e106.features.profile.data.ProfileResponse> = ApiResult.Fail(AppError.Offline)
    override suspend fun deletePhoto(userId: Long): ApiResult<Unit> = ApiResult.Fail(AppError.Offline)
}

/** 필수 동의(사진 · 자기소개)가 빠진 계정만 막고, 선택(종교)이나 확인 실패로는 막지 않는다 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequiredConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun teardown() { store.clear(); Dispatchers.resetMain() }
    private fun vm(repo: FakeConsents) = RequiredConsentViewModel(repo).also { store.put("gate", it) }

    @Test fun missingRequiredConsentsBlockAndAgreeSavesThemWithServerVersion() = runTest(dispatcher) {
        val repo = FakeConsents(photo = false, intro = false); val m = vm(repo)
        m.check(1); runCurrent()
        assertEquals(listOf(OptionalConsentType.PROFILE_PHOTO, OptionalConsentType.PROFILE_INTRODUCTION), m.state.value.missing.map { it.type })
        m.agree(); runCurrent()
        assertTrue(m.state.value.missing.isEmpty())
        assertEquals(listOf(OptionalConsentType.PROFILE_PHOTO to OptionalConsentRequest(true, "v2"),
            OptionalConsentType.PROFILE_INTRODUCTION to OptionalConsentRequest(true, "v2")), repo.writes)
    }

    // 전문을 볼 수 없거나(주소 없음) 버전이 다른 동의는 받지 않는다. 읽을 수 없는 문서에 동의가 기록되지 않게 시트를 띄우지 않는다
    @Test fun consentIsNotCollectedWhenItsDocumentCannotBeReviewed() = runTest(dispatcher) {
        for (documents in listOf(emptyList(), FakeConsents(false, false).documents.map { it.copy(version = "v1") })) {
            val repo = FakeConsents(photo = false, intro = false).apply { this.documents = documents }
            val m = RequiredConsentViewModel(repo).also { store.put("gate-${documents.size}", it) }
            m.check(1); runCurrent(); m.agree(); runCurrent()
            assertTrue(m.state.value.missing.isEmpty()); assertTrue(repo.writes.isEmpty())
        }
    }

    @Test fun optionalReligionAloneDoesNotBlock() = runTest(dispatcher) {
        val m = vm(FakeConsents(photo = true, intro = true, religion = false))
        m.check(1); runCurrent()
        assertTrue(m.state.value.missing.isEmpty())
    }

    @Test fun failedCheckDoesNotBlock() = runTest(dispatcher) {
        val repo = FakeConsents(photo = false, intro = false).apply { offline = true }; val m = vm(repo)
        m.check(1); runCurrent()
        assertTrue(m.state.value.missing.isEmpty())
    }

    @Test fun failedSaveKeepsBlockingAndNeverMarksAgreed() = runTest(dispatcher) {
        val repo = FakeConsents(photo = false, intro = true).apply { failWrites = true }; val m = vm(repo)
        m.check(1); runCurrent()
        m.agree(); runCurrent()
        assertEquals(listOf(OptionalConsentType.PROFILE_PHOTO), m.state.value.missing.map { it.type })
        assertEquals(AppError.Offline, m.state.value.error)
        assertFalse(repo.agreed.getValue(OptionalConsentType.PROFILE_PHOTO))
    }
}
