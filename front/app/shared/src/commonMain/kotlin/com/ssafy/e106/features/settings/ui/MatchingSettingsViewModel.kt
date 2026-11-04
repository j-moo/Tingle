package com.ssafy.e106.features.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ssafy.e106.core.auth.*
import com.ssafy.e106.core.network.*
import com.ssafy.e106.features.preferences.ui.*
import com.ssafy.e106.features.settings.SettingsAccess
import com.ssafy.e106.features.settings.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConnectedSettingsState(
    val owner: Session.SignedIn? = null,
    val kind: SettingsKind = SettingsKind.AGE,
    val document: SettingsDocument? = null,
    val age: AgePreferenceState = AgePreferenceState(),
    val matching: ConflictValues.Matching? = null,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
    val conflict: SettingsConflictState<ConflictValues>? = null,
) {
    val intended: ConflictValues? get() = when (kind) {
        SettingsKind.AGE -> when (age.mode) {
            AgePreferenceMode.Any -> ConflictValues.Age(any = true)
            AgePreferenceMode.Range -> ConflictValues.Age(age.minimumValue, if (age.noUpperLimit) null else age.maximumValue)
                .takeIf { age.validRange }
            null -> ConflictValues.Age(selected = false)
        }
        SettingsKind.MATCHING -> matching
    }
    val editable get() = !busy && document != null && conflict == null && validSettingsEtag(document.etag)
    val canSave get() = editable && intended?.valid == true && intended != document?.values
}

/** 입력·저장값·충돌 비교값을 분리한다. 계정/화면이 바뀌면 늦은 응답을 폐기한다. */
class MatchingSettingsViewModel(
    private val repository: MatchingSettingsRepository,
    private val sessions: SessionStore,
    private val access: SettingsAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectedSettingsState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.collect { owner ->
                work?.cancel(); generation++
                mutable.value = ConnectedSettingsState(owner = owner as? Session.SignedIn)
            }
        }
    }
    private fun valid(stamp: Int) = stamp == generation && mutable.value.owner == sessions.state.value
    private fun launch(block: suspend (Long, Int) -> Unit) {
        val s = mutable.value; val owner = s.owner ?: return
        if (s.busy || owner != sessions.state.value || !access.allowed(owner)) return
        val stamp = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { block(owner.userId, stamp) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid(stamp)) failed(AppError.Unknown(e), s.saving || mutable.value.saving) }
            finally { if (valid(stamp)) mutable.update { it.copy(busy = false, saving = false) } }
        }
    }

    fun open(kind: SettingsKind) {
        if (mutable.value.busy) return
        leave()
        mutable.update { it.copy(kind = kind) }
        load()
    }
    fun leave() {
        work?.cancel(); generation++
        mutable.value = ConnectedSettingsState(owner = mutable.value.owner, kind = mutable.value.kind)
    }
    fun background() {
        val s = mutable.value
        // 회전/홈 버튼은 저장 취소 사유가 아니다. 요청은 ViewModel에서 완료하고 계정 변경 때만 폐기한다.
        if (s.saving) return
        work?.cancel(); generation++
        // 읽기만 중단하며, 이미 결과 확인이 필요한 저장은 복귀 후 다시 조회해 비교한다.
        val conflict = s.conflict?.requireReload(ConflictStage.Changed)
        mutable.value = s.copy(busy = false, saving = false, conflict = conflict)
    }
    fun editAge(value: AgePreferenceState) {
        if (mutable.value.editable) mutable.update { it.copy(age = value.copy(saving = false, conflict = null), saved = false, error = null) }
    }
    fun editMatching(enabled: Boolean? = null, photoRequired: Boolean? = null) {
        if (!mutable.value.editable) return
        mutable.update { it.copy(matching = it.matching!!.copy(enabled = enabled ?: it.matching.enabled,
            photoRequired = photoRequired ?: it.matching.photoRequired), saved = false, error = null) }
    }

    fun load() = launch { id, stamp ->
        val before = mutable.value
        val keep = before.conflict?.intended ?: before.intended?.takeIf { it != before.document?.values && before.document != null }
        if (keep != null) mutable.update { it.copy(conflict = SettingsConflictState(id, keep, ConflictStage.Loading)) }
        when (val result = repository.get(id, before.kind)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                val doc = result.value
                if (keep != null || !validSettingsEtag(doc.etag)) {
                    mutable.update { it.copy(document = doc, conflict = SettingsConflictState(id, keep ?: doc.values,
                        if (validSettingsEtag(doc.etag)) ConflictStage.Review else ConflictStage.VersionMissing,
                        doc.etag?.let { tag -> ConflictSnapshot(id, doc.values, tag) })) }
                } else applyDocument(doc)
            }
            is ApiResult.Fail -> if (valid(stamp)) failed(result.error, false)
        }
    }

    fun save() {
        val s = mutable.value
        if (!s.canSave) return
        saveValues(s.intended!!, s.document!!.etag!!)
    }
    private fun saveValues(values: ConflictValues, etag: String) = launch { id, stamp ->
        mutable.update { it.copy(saving = true, saved = false,
            conflict = it.conflict?.copy(stage = ConflictStage.Saving)) }
        when (val result = repository.save(id, mutable.value.kind, values, etag)) {
            is ApiResult.Ok -> if (valid(stamp)) {
                // 성공 본문과 ETag가 유효할 때만 서버 저장 완료다. 기기 동기화 완료는 만들지 않는다.
                if (result.value.values != values || !validSettingsEtag(result.value.etag))
                    failed(AppError.Unknown(IllegalStateException("settings response mismatch")), true)
                else { applyDocument(result.value); mutable.update { it.copy(saved = true) } }
            }
            is ApiResult.Fail -> if (valid(stamp)) failed(result.error, true)
        }
    }

    fun conflictAction(action: ConflictAction<ConflictValues>) {
        val s = mutable.value; val conflict = s.conflict ?: return
        if (s.busy) return
        when (action) {
            ConflictAction.Reload -> load()
            // 취소해도 이전 ETag로 저장할 수 없도록 비교 상태를 유지한다. 뒤로 이동은 Route가 처리한다.
            ConflictAction.Back -> Unit
            is ConflictAction.UseLatest -> if (action.snapshot == conflict.reviewed) {
                s.document?.let(::applyDocument)
            }
            is ConflictAction.SaveReviewed -> if (action == conflict.saveAction) saveValues(action.intended, action.snapshot.etag)
        }
    }

    private fun applyDocument(doc: SettingsDocument) {
        mutable.update { s -> when (val value = doc.values) {
            is ConflictValues.Age -> s.copy(document = doc, conflict = null, error = null,
                age = AgePreferenceState(mode = when { !value.selected -> null; value.any -> AgePreferenceMode.Any; else -> AgePreferenceMode.Range },
                    minimumAge = value.minimum?.toString().orEmpty(), maximumAge = value.maximum?.toString().orEmpty(),
                    noUpperLimit = value.selected && !value.any && value.maximum == null))
            is ConflictValues.Matching -> s.copy(document = doc, matching = value, conflict = null, error = null)
        } }
    }
    private fun failed(error: AppError, write: Boolean) {
        val s = mutable.value
        if (error is AppError.OnboardingRequired || error == AppError.SessionExpired ||
            error is AppError.Server && error.status == 403) {
            mutable.value = ConnectedSettingsState(owner = s.owner, kind = s.kind, error = error)
            return
        }
        val reason = when {
            error is AppError.Server && error.status == 412 -> ConflictStage.Changed
            error is AppError.Server && error.status == 428 -> ConflictStage.VersionMissing
            write -> ConflictStage.OutcomeUnknown
            else -> ConflictStage.LoadFailed
        }
        mutable.update { it.copy(error = error, saved = false,
            conflict = if (write && s.intended != null) SettingsConflictState(s.owner!!.userId, s.intended!!, reason)
                else it.conflict?.requireReload(reason)) }
    }
    fun refreshStage() = launch { _, stamp ->
        val result = sessions.refreshOnboarding()
        if (valid(stamp) && result is ApiResult.Fail) mutable.update { it.copy(error = result.error) }
    }
}
