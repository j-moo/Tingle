package com.ssafy.e106.features.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.onboarding.data.AgreementItem
import com.ssafy.e106.features.onboarding.ui.withProfileDocuments
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.DevelopmentProfileAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class ConsentDecision(val type: OptionalConsentType, val agreed: Boolean, val version: String)
enum class OptionalProfileNotice { VersionChanged, ConsentRequired, Saved, Checked, Unknown, Changed }
data class OptionalProfileState(
    val session: Session.SignedIn? = null,
    val busy: Boolean = false, val loaded: Boolean = false,
    val profile: ProfileResponse? = null,
    val consents: List<OptionalConsentRecord> = emptyList(),
    val documents: List<AgreementItem> = emptyList(),
    val details: ProfileDetailsState = ProfileDetailsState(),
    val photo: ByteArray? = null, val photoFailed: Boolean = false,
    val decision: ConsentDecision? = null, val deletePhotoPrompt: Boolean = false,
    val recoveryRequired: Boolean = false,
    val notice: OptionalProfileNotice? = null, val error: AppError? = null,
    val allowUnpublished: Boolean = false,
    val canRetryConsent: Boolean = false,
    val draftLoaded: Boolean = false,
    // 화면 재생성 중 저장이 끝나도 새 화면이 다음 이동을 한 번 소비할 수 있게 보관한다.
    val completion: ProfileResponse? = null,
) {
    fun consent(type: OptionalConsentType) = consents.singleOrNull { it.type == type }
    fun document(type: OptionalConsentType) = documents.singleOrNull { it.type == type.name }
    fun canDecide(type: OptionalConsentType, agreed: Boolean): Boolean {
        val record = consent(type) ?: return false
        val doc = document(type)
        return loaded && !busy && !recoveryRequired && record.agreed != agreed &&
            (!agreed || (doc?.version == record.version && (doc.canReview || allowUnpublished)))
    }
    val editable get() = loaded && !busy && !recoveryRequired && decision == null && !deletePhotoPrompt
    val canContinue get() = !busy && !recoveryRequired && decision == null && !deletePhotoPrompt && profile != null
}

/** 선택 동의와 값 저장은 별도 요청이다. 성공한 동의를 PATCH 실패 때문에 되돌리지 않는다. */
class OptionalProfileViewModel(
    private val optional: OptionalProfileRepository,
    private val profiles: ProfileRepository,
    private val sessions: SessionStore,
    private val allowUnpublished: Boolean = false,
    private val access: DevelopmentProfileAccess = DevelopmentProfileAccess(),
    private val siteBase: String? = null,   // 서버가 전문 주소를 안 준 프로필 동의를 랜딩 정적 페이지로 채운다(약관 시트와 같게)
) : ViewModel() {
    private val mutable = MutableStateFlow(OptionalProfileState(allowUnpublished = allowUnpublished))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var writing = false
    private var unresolvedConsent: ConsentDecision? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { session ->
                job?.cancel(); generation++; writing = false; unresolvedConsent = null
                mutable.value = OptionalProfileState(session = session as? Session.SignedIn, allowUnpublished = allowUnpublished)
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.session == sessions.state.value
    private fun work(block: suspend (Long, Int) -> Unit) {
        val s = mutable.value; val user = s.session ?: return
        // 화면의 진입 분기만 믿지 않고 모든 조회·쓰기 직전에 가입 단계도 검사한다.
        if (s.busy || user != sessions.state.value || !(access.canEditProfile(user) || access.canManageProfile(user))) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try { block(user.userId, stamp) }
            finally { if (valid(stamp)) { writing = false; mutable.update { it.copy(busy = false) } } }
        }
    }
    fun leave() {
        // 실제 뒤로가기/나가기에만 호출한다. 회전 시에는 viewModelScope의 요청을 유지한다.
        val uncertain = writing
        generation++; job?.cancel(); writing = false
        mutable.update { it.copy(busy = false, decision = null, deletePhotoPrompt = false, photo = null, completion = null,
            recoveryRequired = it.recoveryRequired || uncertain) }
    }
    fun load() = work { user, stamp -> refresh(user, stamp) }
    private fun fail(error: AppError, stamp: Int) {
        if (valid(stamp)) mutable.update { it.copy(error = error) }
    }
    private fun invalidResponse() = AppError.Unknown(IllegalStateException("Invalid optional profile response"))
    private suspend fun refresh(user: Long, stamp: Int, reset: Set<OptionalConsentType> = emptySet()): Boolean {
        val p = profiles.get(user)
        if (!valid(stamp)) return false
        if (p !is ApiResult.Ok) { fail((p as ApiResult.Fail).error, stamp); return false }
        if (p.value.userId != user) { fail(invalidResponse(), stamp); return false }
        // Keep basic onboarding usable even when the optional API has not been deployed.
        mutable.update { it.copy(profile = p.value, photo = null, photoFailed = false) }
        val c = optional.consents(user)
        if (!valid(stamp)) return false
        if (c !is ApiResult.Ok) { mutable.update { it.copy(loaded = false) }; fail((c as ApiResult.Fail).error, stamp); return false }
        val records = c.value.consents
        if (records.size != OptionalConsentType.entries.size || records.map { it.type }.toSet() != OptionalConsentType.entries.toSet() ||
            records.any { it.version.isBlank() || it.version.length > 100 || (it.agreed && it.agreedVersion.isNullOrBlank()) }) {
            mutable.update { it.copy(loaded = false) }; fail(invalidResponse(), stamp); return false
        }
        val d = optional.documents()
        if (!valid(stamp)) return false
        // Existing valid consent remains valid even if a document is newer or unavailable.
        val docs = (d as? ApiResult.Ok)?.value?.agreements.orEmpty().withProfileDocuments(siteBase)
        mutable.update { old ->
            fun agreed(type: OptionalConsentType) = records.single { it.type == type }.agreed
            val intro = agreed(OptionalConsentType.PROFILE_INTRODUCTION)
            val religion = agreed(OptionalConsentType.PROFILE_RELIGION)
            val photo = agreed(OptionalConsentType.PROFILE_PHOTO)
            val first = !old.draftLoaded
            val pending = unresolvedConsent
            val resolved = pending == null || records.single { it.type == pending.type }.agreed == pending.agreed
            old.copy(loaded = true, draftLoaded = true, consents = records, documents = docs,
                recoveryRequired = !resolved,
                canRetryConsent = !resolved,
                decision = old.decision?.takeIf { decision -> records.single { it.type == decision.type }.let {
                    it.version == decision.version && it.agreed != decision.agreed
                } },
                notice = if (!resolved) OptionalProfileNotice.Unknown else if (old.notice == OptionalProfileNotice.Unknown) OptionalProfileNotice.Checked else old.notice,
                error = (d as? ApiResult.Fail)?.error,
                details = old.details.copy(
                    introductionConsent = intro, religionConsent = religion, photoConsent = photo,
                    introduction = if (!intro) "" else if (first || OptionalConsentType.PROFILE_INTRODUCTION in reset) p.value.introduction.orEmpty() else old.details.introduction,
                    religion = if (!religion) null else if (first || OptionalConsentType.PROFILE_RELIGION in reset) p.value.religion.religion() else old.details.religion,
                    photoStatus = if (photo && p.value.hasProfilePhoto) ProfilePhotoStatus.Registered else ProfilePhotoStatus.Empty))
        }
        if (!mutable.value.recoveryRequired) unresolvedConsent = null
        if (p.value.hasProfilePhoto && mutable.value.details.photoConsent) {
            val bytes = optional.photo(user)
            if (!valid(stamp)) return false
            mutable.update { it.copy(photo = (bytes as? ApiResult.Ok)?.value, photoFailed = bytes is ApiResult.Fail) }
        }
        return !mutable.value.recoveryRequired
    }
    fun decide(type: OptionalConsentType, agreed: Boolean) {
        val s = mutable.value
        if (s.session != sessions.state.value || !s.canDecide(type, agreed)) return
        mutable.update { it.copy(decision = ConsentDecision(type, agreed, s.consent(type)!!.version), notice = null, error = null) }
    }
    fun dismissDecision() { if (!mutable.value.busy) mutable.update { it.copy(decision = null, deletePhotoPrompt = false) } }
    fun confirmDecision() {
        val decision = mutable.value.decision ?: return
        if (!mutable.value.canDecide(decision.type, decision.agreed) || mutable.value.consent(decision.type)?.version != decision.version) return
        changeConsent(decision)
    }
    fun retryUnresolvedConsent() { unresolvedConsent?.let(::changeConsent) }
    private fun changeConsent(decision: ConsentDecision) = work { user, stamp ->
        writing = true; unresolvedConsent = decision
        mutable.update { it.copy(decision = null, canRetryConsent = false) }
        val result = optional.consent(user, decision.type, OptionalConsentRequest(decision.agreed, decision.version))
        if (!valid(stamp)) return@work
        if (result is ApiResult.Ok && result.value.type == decision.type && result.value.agreed == decision.agreed &&
            result.value.version == decision.version && (!decision.agreed || result.value.agreedVersion == decision.version)) {
            // A successful withdrawal invalidates local data immediately, even if the next GET fails.
            mutable.update { s -> s.copy(recoveryRequired = true, notice = OptionalProfileNotice.Changed,
                photo = if (!decision.agreed && decision.type == OptionalConsentType.PROFILE_PHOTO) null else s.photo,
                details = when (decision.type) {
                    OptionalConsentType.PROFILE_PHOTO -> s.details.copy(photoConsent = decision.agreed,
                        photoStatus = if (!decision.agreed) ProfilePhotoStatus.Empty else s.details.photoStatus)
                    OptionalConsentType.PROFILE_INTRODUCTION -> s.details.copy(introductionConsent = decision.agreed,
                        introduction = if (!decision.agreed) "" else s.details.introduction)
                    OptionalConsentType.PROFILE_RELIGION -> s.details.copy(religionConsent = decision.agreed,
                        religion = if (!decision.agreed) null else s.details.religion)
                }) }
            refresh(user, stamp, if (!decision.agreed) setOf(decision.type) else emptySet())
        } else mutationFailure((result as? ApiResult.Fail)?.error ?: invalidResponse(), user, stamp)
    }
    private suspend fun mutationFailure(error: AppError, user: Long, stamp: Int) {
        val code = (error as? AppError.Server)?.code
        val uncertain = error is AppError.Offline || error is AppError.Unknown || (error is AppError.Server && error.status >= 500)
        if (!uncertain) unresolvedConsent = null
        mutable.update { it.copy(recoveryRequired = true, decision = null, canRetryConsent = uncertain && unresolvedConsent != null, error = error, notice = when(code) {
            "AGREEMENT_VERSION_OUTDATED" -> OptionalProfileNotice.VersionChanged
            "OPTIONAL_CONSENT_REQUIRED" -> OptionalProfileNotice.ConsentRequired
            else -> if (uncertain) OptionalProfileNotice.Unknown else null
        }) }
        // No automatic write retry. A read cannot prove that a timed-out opposite write will never finish.
        if (!uncertain) { refresh(user, stamp); if (valid(stamp)) mutable.update { it.copy(error = error) } }
    }
    fun introduction(value: String) {
        val s = mutable.value
        if (s.editable && s.details.introductionConsent && s.session == sessions.state.value)
            mutable.update { it.copy(details = it.details.copy(introduction = value), notice = null) }
    }
    fun religion(value: ProfileReligion) {
        val s = mutable.value
        if (s.editable && s.details.religionConsent && s.session == sessions.state.value)
            mutable.update { it.copy(details = it.details.copy(religion = value), notice = null) }
    }
    fun save() = save { profile -> mutable.update { it.copy(completion = profile) } }
    fun takeCompletion(): ProfileResponse? {
        if (mutable.value.session != sessions.state.value || mutable.value.busy) return null
        val result = mutable.value.completion
        mutable.update { it.copy(completion = null) }
        return result
    }
    // UI 콜백을 요청에 캡처하지 않는다. 화면은 save()와 completion 상태를 사용한다.
    internal fun save(onSaved: (ProfileResponse) -> Unit) {
        val s = mutable.value
        if (!s.editable || !s.details.canContinue) return
        val fields = buildJsonObject {
            if (s.details.introductionConsent) put("introduction", s.details.normalizedIntroduction?.let(::JsonPrimitive) ?: JsonNull)
            if (s.details.religionConsent) put("religion", s.details.religion?.code()?.let(::JsonPrimitive) ?: JsonNull)
        }
        work { user, stamp ->
            if (fields.isEmpty()) { if (refresh(user, stamp) && valid(stamp)) mutable.value.profile?.let(onSaved); return@work }
            writing = true
            when (val result = profiles.save(user, fields)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    if (result.value.userId != user) { mutationFailure(invalidResponse(), user, stamp); return@work }
                    mutable.update { it.copy(recoveryRequired = true, notice = OptionalProfileNotice.Saved) }
                    if (refresh(user, stamp, setOf(OptionalConsentType.PROFILE_INTRODUCTION, OptionalConsentType.PROFILE_RELIGION)) && valid(stamp))
                        mutable.value.profile?.let(onSaved)
                }
                is ApiResult.Fail -> if (valid(stamp)) mutationFailure(result.error, user, stamp)
            }
        }
    }
    fun skip() = skip { profile -> mutable.update { it.copy(completion = profile) } }
    internal fun skip(onContinue: (ProfileResponse) -> Unit) {
        val s = mutable.value
        if (s.canContinue && s.session == sessions.state.value) s.profile?.let(onContinue)
    }
    fun discardDraft() {
        val s = mutable.value; val p = s.profile ?: return
        if (s.busy || s.recoveryRequired || s.session != sessions.state.value) return
        mutable.update { it.copy(details = it.details.copy(
            introduction = if (it.details.introductionConsent) p.introduction.orEmpty() else "",
            religion = if (it.details.religionConsent) p.religion.religion() else null)) }
    }
    fun requestDeletePhoto() { if (mutable.value.editable && mutable.value.profile?.hasProfilePhoto == true) mutable.update { it.copy(deletePhotoPrompt = true) } }
    fun deletePhoto() {
        if (!mutable.value.deletePhotoPrompt || mutable.value.busy || mutable.value.recoveryRequired) return
        work { user, stamp ->
            writing = true; mutable.update { it.copy(deletePhotoPrompt = false) }
            when (val result = optional.deletePhoto(user)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    mutable.update { it.copy(photo = null, recoveryRequired = true, details = it.details.copy(photoStatus = ProfilePhotoStatus.Empty)) }
                    refresh(user, stamp)
                }
                is ApiResult.Fail -> if (valid(stamp)) mutationFailure(result.error, user, stamp)
            }
        }
    }
    fun upload(jpeg: ByteArray) {
        val s = mutable.value
        if (!s.editable || !s.details.photoConsent || jpeg.isEmpty() || jpeg.size > 10 * 1024 * 1024) return
        work { user, stamp ->
            writing = true
            when (val result = optional.upload(user, jpeg)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    if (result.value.userId != user || !result.value.hasProfilePhoto) { mutationFailure(invalidResponse(), user, stamp); return@work }
                    mutable.update { it.copy(photo = null, recoveryRequired = true, notice = OptionalProfileNotice.Saved) }
                    refresh(user, stamp)
                }
                is ApiResult.Fail -> if (valid(stamp)) mutationFailure(result.error, user, stamp)
            }
        }
    }
}
