package com.ssafy.e106.features.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.profile.DevelopmentProfileAccess
import com.ssafy.e106.features.profile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class ProfileEditProblem { InvalidProfile, DuplicateNickname, InvalidNickname, ResultUnknown }
data class ProfileManagementState(
    val session: Session.SignedIn? = null,
    val profile: ProfileResponse? = null,
    val editor: ProfileEditState? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
    val problem: ProfileEditProblem? = null,
    val recoveryRequired: Boolean = false,
    val savedNotice: Boolean = false,
    val photo: ByteArray? = null,
    // 사진이 있다는데 받지 못함(보기 화면은 빈 칸 대신 기본 사람 모양)
    val photoFailed: Boolean = false,
    // 저장 성공 후 이동은 현재 화면이 소비한다. 회전 전 화면의 콜백을 붙잡지 않는다.
    val saveCompleted: Boolean = false,
    // 보기 화면의 연락 이메일 카드. 없거나 못 받으면 null(카드를 숨긴다)
    val contactEmail: String? = null,
) {
    val canEdit get() = editor != null && !busy && !recoveryRequired
}

/** Basic edits use a partial PATCH. Optional consent/values belong to OptionalProfileRoute. */
class ProfileEditViewModel(
    private val profiles: ProfileRepository,
    private val optional: OptionalProfileRepository,
    private val sessions: SessionStore,
    private val access: DevelopmentProfileAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileManagementState())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var job: Job? = null
    private var writing = false
    private var pending: JsonObject? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { s ->
                generation++; job?.cancel(); writing = false; pending = null
                mutable.value = ProfileManagementState(session = s as? Session.SignedIn)
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.session == sessions.state.value
    private fun work(block: suspend (Long, Int) -> Unit) {
        val s = mutable.value; val user = s.session ?: return
        if (s.busy || user != sessions.state.value || !access.canManageProfile(user)) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null, problem = null) }
        job = viewModelScope.launch {
            try { block(user.userId, stamp) }
            finally { if (valid(stamp)) { writing = false; mutable.update { it.copy(busy = false) } } }
        }
    }
    fun leave() {
        // 화면 구성 해제(dispose)가 아니라 사용자의 실제 이탈에서만 요청을 취소한다.
        val uncertain = writing
        generation++; job?.cancel(); writing = false
        // 사진은 지우지 않는다: 다시 들어올 때 같은 사진을 처음부터 그리지 않게(계정이 바뀌면 상태 전체가 초기화된다)
        mutable.update { it.copy(busy = false, saveCompleted = false, recoveryRequired = it.recoveryRequired || uncertain,
            editor = it.editor?.copy(confirmDiscard = false)) }
    }
    private fun unexpected() = AppError.Unknown(IllegalStateException("Invalid profile response"))
    private fun accepts(p: ProfileResponse, user: Long) = p.userId == user && hasRequiredSaved(p) &&
        (mutable.value.session?.isReady != true || p.completed)
    private fun basicDraft(p: ProfileResponse) = ProfileEditDraft(p.nickname!!,
        ProfileSmoking.entries.first { it.code() == p.smokingStatus },
        ProfileDrinking.entries.first { it.code() == p.drinkingFrequency })
    private fun accept(p: ProfileResponse, preserve: Boolean) {
        val saved = basicDraft(p)
        mutable.update { s ->
            val old = s.editor
            val draft = if (!preserve || old == null) saved else saved.copy(
                nickname = if (old.draft.nickname != old.saved.nickname) old.draft.nickname else saved.nickname,
                smoking = if (old.draft.smoking != old.saved.smoking) old.draft.smoking else saved.smoking,
                drinking = if (old.draft.drinking != old.saved.drinking) old.draft.drinking else saved.drinking)
            s.copy(profile = p, editor = ProfileEditState(saved, draft, p.birthDate!!,
                ProfileGender.entries.first { it.code() == p.gender }, p.hasProfilePhoto), error = null, problem = null)
        }
    }
    fun load() = work { user, stamp -> coroutineScope {
        // 연락 이메일은 선택 카드라 따로 받는다. 실패해도 프로필 불러오기에는 영향이 없다
        launch {
            val email = (profiles.contactEmail(user) as? ApiResult.Ok)?.value?.email?.takeIf { it.isNotBlank() }
            if (valid(stamp)) mutable.update { it.copy(contactEmail = email) }
        }
        // 다시 불러오는 동안 보던 사진은 지우지 않는다(돌아올 때마다 사진 칸이 깜빡이지 않게). 새 사진이 오면 바꾼다
        when (val result = profiles.get(user)) {
            is ApiResult.Fail -> if (valid(stamp)) mutable.update { it.copy(error = result.error) }
            is ApiResult.Ok -> if (valid(stamp)) {
                if (!accepts(result.value, user)) {
                    mutable.update { it.copy(profile = null, editor = null, problem = ProfileEditProblem.InvalidProfile) }
                    return@coroutineScope
                }
                val confirmed = pending?.let { expected -> matches(result.value, expected) } == true
                accept(result.value, preserve = !confirmed)
                pending = null
                mutable.update { it.copy(recoveryRequired = false, savedNotice = it.savedNotice || confirmed) }
                if (result.value.hasProfilePhoto) {
                    val image = (optional.photo(user) as? ApiResult.Ok)?.value
                    // 같은 사진이면 그대로 둬 다시 그리지 않는다. 못 받으면 옛 사진(바뀌었을 수 있다)을 남기지 않고 실패로 둔다
                    if (valid(stamp)) mutable.update { it.copy(photo = if (image != null && it.photo?.contentEquals(image) == true) it.photo else image,
                        photoFailed = image == null) }
                } else mutable.update { it.copy(photo = null, photoFailed = false) }
            }
        }
    } }
    fun change(value: ProfileEditDraft) {
        val s = mutable.value; val e = s.editor ?: return
        if (!s.canEdit || s.session != sessions.state.value) return
        // Ignore forged optional consent/values; they must go through their own server-confirmed flow.
        mutable.update { it.copy(editor = e.edit(e.draft.copy(nickname = value.nickname,
            smoking = value.smoking, drinking = value.drinking)), savedNotice = false, error = null, problem = null) }
    }
    fun requestBack(onExit: () -> Unit) {
        val s = mutable.value
        if (s.busy || s.recoveryRequired || s.session != sessions.state.value) return
        if (s.editor?.dirty == true) mutable.update { it.copy(editor = it.editor?.copy(confirmDiscard = true)) }
        else onExit()
    }
    fun keepEditing() { if (!mutable.value.busy) mutable.update { it.copy(editor = it.editor?.copy(confirmDiscard = false)) } }
    fun discard(onExit: () -> Unit) {
        val s = mutable.value
        if (s.busy || s.recoveryRequired || s.editor?.confirmDiscard != true || s.session != sessions.state.value) return
        mutable.update { it.copy(editor = it.editor?.discard(), error = null, problem = null) }; onExit()
    }
    fun save() = save { mutable.update { it.copy(saveCompleted = true) } }
    fun takeSaveCompletion(): Boolean {
        if (mutable.value.session != sessions.state.value || mutable.value.busy) return false
        val result = mutable.value.saveCompleted
        mutable.update { it.copy(saveCompleted = false) }
        return result
    }
    internal fun save(onSaved: () -> Unit) {
        val s = mutable.value; val e = s.editor ?: return
        if (!s.canEdit || !e.canSave) return
        val draft = e.draft.normalized()
        val fields = buildJsonObject {
            if (draft.nickname != e.saved.normalizedNickname) put("nickname", draft.nickname)
            if (draft.smoking != e.saved.smoking) put("smokingStatus", draft.smoking!!.code())
            if (draft.drinking != e.saved.drinking) put("drinkingFrequency", draft.drinking!!.code())
        }
        work { user, stamp ->
            if (fields.isEmpty()) { mutable.update { it.copy(editor = it.editor?.discard()) }; onSaved(); return@work }
            if ("nickname" in fields) when (val available = profiles.nickname(draft.nickname)) {
                is ApiResult.Fail -> { if (valid(stamp)) mutable.update { it.copy(error = available.error) }; return@work }
                is ApiResult.Ok -> {
                    if (!valid(stamp)) return@work
                    if (!available.value.available) {
                        mutable.update { it.copy(problem = if (available.value.reason == "INVALID_FORMAT") ProfileEditProblem.InvalidNickname else ProfileEditProblem.DuplicateNickname) }
                        return@work
                    }
                    if (available.value.nickname != draft.nickname) { mutable.update { it.copy(error = unexpected()) }; return@work }
                }
            }
            if (!valid(stamp)) return@work
            writing = true; pending = fields
            when (val result = profiles.save(user, fields)) {
                is ApiResult.Ok -> if (valid(stamp)) {
                    if (!accepts(result.value, user) || !matches(result.value, fields)) {
                        mutable.update { it.copy(recoveryRequired = true, problem = ProfileEditProblem.ResultUnknown, error = unexpected()) }
                    } else {
                        accept(result.value, preserve = false); pending = null
                        mutable.update { it.copy(savedNotice = true, recoveryRequired = false) }; onSaved()
                    }
                }
                is ApiResult.Fail -> if (valid(stamp)) {
                    val error = result.error
                    val uncertain = error is AppError.Offline || error is AppError.Unknown || (error is AppError.Server && error.status >= 500)
                    if (!uncertain) pending = null
                    mutable.update { it.copy(error = error, recoveryRequired = uncertain,
                        problem = if (uncertain) ProfileEditProblem.ResultUnknown else when ((error as? AppError.Server)?.code) {
                            "NICKNAME_DUPLICATED" -> ProfileEditProblem.DuplicateNickname
                            "INVALID_NICKNAME" -> ProfileEditProblem.InvalidNickname
                            else -> null
                        }) }
                }
            }
        }
    }
    private fun matches(p: ProfileResponse, fields: JsonObject) = fields.all { (key, value) ->
        value.jsonPrimitive.content == when (key) {
            "nickname" -> p.nickname
            "smokingStatus" -> p.smokingStatus
            "drinkingFrequency" -> p.drinkingFrequency
            else -> null
        }
    }
}
