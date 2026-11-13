package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.photo.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OptionalProfileRoute(
    session: Session.SignedIn, onContinue: (ProfileResponse) -> Unit, onBack: () -> Unit,
    model: OptionalProfileViewModel = koinViewModel(),
    editingExisting: Boolean = false,
) {
    val state by model.state.collectAsStateWithLifecycle()
    // 사진 선택기가 떠 있는 동안 회전해도 실행기를 다시 등록할 수 있도록 편집 여부를 복원한다.
    var editingPhoto by rememberSaveable(session) { mutableStateOf(false) }
    var exitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    var unknownExitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    val currentContinue by rememberUpdatedState(onContinue)
    // 회전으로 Composition이 교체되어도 저장은 ViewModel에서 계속한다. 이동은 새 화면이 한 번 소비한다.
    LaunchedEffect(session, state.session, state.completion, state.busy) {
        if (state.session == session) model.takeCompletion()?.let { model.leave(); currentContinue(it) }
    }
    // Returning from a document or the system photo picker rechecks server consent.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load() }
    if (state.session != session) return
    val safeBack: () -> Unit = {
        if (!state.busy && state.recoveryRequired) unknownExitPrompt = true
        else if (!state.busy) {
            val p = state.profile
            val dirty = p != null && ((state.details.introductionConsent && state.details.normalizedIntroduction != p.introduction?.takeIf { it.isNotBlank() }) ||
                (state.details.religionConsent && state.details.religion != p.religion.religion()))
            if (dirty) exitPrompt = true else { model.leave(); onBack() }
        }
    }
    ProfileBackHandler(!editingPhoto, safeBack)
    if (editingPhoto) {
        ProfilePhotoPreparation(onReady = { bytes -> editingPhoto = false; model.upload(bytes) },
            onClose = { editingPhoto = false }, canApply = state.editable && state.details.photoConsent)
    } else OptionalProfileScreen(state,
        onDecision = model::decide, onConfirm = model::confirmDecision, onDismiss = model::dismissDecision,
        onReload = model::load, onRetryConsent = model::retryUnresolvedConsent,
        onIntroduction = model::introduction, onReligion = model::religion,
        onChoosePhoto = { editingPhoto = true }, onDeletePhoto = model::requestDeletePhoto,
        onConfirmDeletePhoto = model::deletePhoto,
        onSave = { model.save() }, onSkip = { model.discardDraft(); model.skip() }, onBack = safeBack,
        editingExisting = editingExisting)
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false },
        title = { Text(stringResource(Res.string.profile_edit_discard_title)) },
        text = { Text(stringResource(Res.string.optional_skip_body)) },
        confirmButton = { TextButton(enabled = !state.busy && !state.recoveryRequired, onClick = {
            model.discardDraft(); exitPrompt = false; model.leave(); onBack()
        }) { Text(stringResource(Res.string.profile_edit_discard)) } },
        dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text(stringResource(Res.string.profile_edit_keep)) } })
    if (unknownExitPrompt) ProfileUnknownExitDialog(
        onDismiss = { unknownExitPrompt = false },
        onExit = { unknownExitPrompt = false; model.leave(); onBack() })
}

/** Preview uses this screen directly; no API or ViewModel is created there. */
@Composable
fun OptionalProfileScreen(
    state: OptionalProfileState,
    onDecision: (OptionalConsentType, Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
    onReload: () -> Unit, onRetryConsent: () -> Unit,
    onIntroduction: (String) -> Unit, onReligion: (ProfileReligion) -> Unit,
    onChoosePhoto: () -> Unit, onDeletePhoto: () -> Unit, onConfirmDeletePhoto: () -> Unit,
    onSave: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit,
    editingExisting: Boolean = false,
) {
    var skipPrompt by remember(state.session) { mutableStateOf(false) }
    val skipLabel = stringResource(if (editingExisting) Res.string.profile_manage_optional_back else Res.string.optional_skip)
    val saveLabel = if (editingExisting) stringResource(Res.string.profile_manage_optional_save) else null
    val title = stringResource(Res.string.profile_details_title)
    if (!state.loaded && (state.busy || state.error == null)) {
        // 처음 불러오는 중: 실제 화면 틀(위쪽 줄 · 제목 · 항목 이름 · 아래 버튼)은 그대로 두고 값 자리만 회색으로 둔다
        ProfileSkeletonFrame(title, saveLabel ?: stringResource(Res.string.profile_continue), onBack.takeIf { !state.busy }, spacing = AppSpacing.Section) {
            Text(stringResource(Res.string.profile_details_heading), style = MaterialTheme.typography.headlineMedium)
            SkeletonGroup(spacing = AppSpacing.Section) {
                BoneField(Res.string.profile_photo_title) { PhotoBoneWide() }
                BoneField(Res.string.profile_intro_title) { SkeletonBone(height = 120.dp, shape = AppShapes.Control) }
                BoneField(Res.string.profile_religion_title) { repeat(3) { SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control) } }
            }
        }
    } else if (!state.loaded) {
        // 못 불러왔다: 상태 화면 + 당겨서 새로고침. 가입 중에는 선택 정보 없이 이어 갈 수 있게 아래 버튼을 남긴다
        val skippable = !editingExisting && state.canContinue
        StateScreen(state.error.stateKind(), state.error.stateTitle(), barTitle = title, onBack = onBack, onRefresh = onReload,
            primary = StateAction(stringResource(Res.string.state_retry), onClick = onReload).takeIf { skippable },
            secondary = StateAction(skipLabel) { skipPrompt = true }.takeIf { skippable })
    } else {
    val (refreshing, refresh) = pullRefresh(state.busy, onReload)
    ProfileDetailsScreen(state.details.copy(saving = state.busy, interactionEnabled = state.editable),
        onReligion, { onDecision(OptionalConsentType.PROFILE_RELIGION, it) },
        onIntroduction, { onDecision(OptionalConsentType.PROFILE_INTRODUCTION, it) },
        { onDecision(OptionalConsentType.PROFILE_PHOTO, it) }, onChoosePhoto, onDeletePhoto,
        onContinue = onSave,
        photoContent = {
            val bytes = state.photo
            if (bytes != null) ProfilePhotoBytes(bytes)
            else Text(stringResource(Res.string.optional_photo_unavailable))
        },
        // 결과 미확인 상태에서는 수정만 잠근다. 뒤로가기는 확인창을 거쳐 허용한다.
        onBack = onBack.takeIf { !state.busy },
        header = { OptionalProfileStatus(state, onReload, onRetryConsent) },
        canChangeConsent = { type -> state.canDecide(type, state.consent(type)?.agreed != true) },
        consentInfo = { OptionalConsentInfo(state, it) },
        footer = { TextButton(onClick = { skipPrompt = true }, enabled = state.canContinue) { Text(skipLabel) } },
        continueEnabled = state.editable && state.details.canContinue, continueLabel = saveLabel,
        refreshing = refreshing, onRefresh = refresh)
    }
    OptionalProfileDialogs(state, onConfirm, onDismiss, onConfirmDeletePhoto)
    if (skipPrompt) AlertDialog(onDismissRequest = { skipPrompt = false },
        title = { Text(stringResource(Res.string.optional_skip_title)) },
        text = { Text(stringResource(Res.string.optional_skip_body)) },
        confirmButton = { TextButton(enabled = state.canContinue, onClick = { skipPrompt = false; onSkip() }) { Text(skipLabel) } },
        dismissButton = { TextButton(onClick = { skipPrompt = false }) { Text(stringResource(Res.string.optional_cancel)) } })
}

/**
 * 결과 미확인 · 동의 변경 · 오류 안내와 다시 확인. 계정 메뉴의 선택 프로필과 가입 아코디언이 같이 쓴다.
 * 안내 문구는 줄이지 않는다(같은 요청을 두 번 보내지 않게 하는 설명 · 선택 정보 없이 이어 간다는 설명). 불러오는 중 표시는 두지 않는다
 */
@Composable
internal fun OptionalProfileStatus(state: OptionalProfileState, onReload: () -> Unit, onRetryConsent: () -> Unit) {
    val unavailable = !state.loaded && !state.busy
    // 결과 미확인 문구는 한 번만 적는다
    val notice = state.notice?.takeIf { it != OptionalProfileNotice.Unknown || !state.recoveryRequired }
    val reload = unavailable || state.recoveryRequired || state.error != null || state.photoFailed
    if (!unavailable && !state.recoveryRequired && notice == null && state.error == null && !reload && !state.canRetryConsent) return
    // 안내 · 오류 · 버튼을 한 상자에 모은다(흩어져 있으면 무엇이 무엇의 버튼인지 알 수 없다)
    Column(Modifier.fillMaxWidth().background(AppColors.SurfaceMuted, AppShapes.Card).padding(AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (unavailable) StatusText(Res.string.optional_unavailable)
        if (state.recoveryRequired) StatusText(Res.string.optional_unknown)
        notice?.let { StatusText(when (it) {
            OptionalProfileNotice.VersionChanged -> Res.string.optional_version_changed
            OptionalProfileNotice.ConsentRequired -> Res.string.optional_consent_required
            OptionalProfileNotice.Unknown -> Res.string.optional_unknown
            OptionalProfileNotice.Saved -> Res.string.optional_saved
            OptionalProfileNotice.Checked -> Res.string.optional_checked
            else -> Res.string.optional_changed
        }) }
        state.error?.let { InlineError(it.message) }
        // 다시 불러오기: 결과 미확인이면 "다시 확인", 그 밖의 실패는 "다시 시도"
        if (reload) AppButton(stringResource(if (state.recoveryRequired) Res.string.profile_state_recheck else Res.string.state_retry),
            Modifier.fillMaxWidth().padding(top = AppSpacing.ExtraSmall), variant = AppButtonVariant.Secondary, enabled = !state.busy, onClick = onReload)
        if (state.canRetryConsent) AppButton(stringResource(Res.string.optional_retry_consent), Modifier.fillMaxWidth(),
            variant = AppButtonVariant.Secondary, enabled = !state.busy, onClick = onRetryConsent)
    }
}

@Composable private fun StatusText(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
}

/** 동의 칸 아래 문서 보기 링크와 도움말 */
@Composable
internal fun OptionalConsentInfo(state: OptionalProfileState, type: OptionalConsentType) {
    var openFailed by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val url = state.document(type)?.reviewUrl
    if (url != null) TextButton(onClick = {
        openFailed = try { uri.openUri(url); false } catch (_: Exception) { true }
    }, enabled = !state.busy) { Text(stringResource(Res.string.optional_document)) }
    else Text(stringResource(if (state.allowUnpublished) Res.string.optional_development_document else Res.string.optional_document_missing),
        style = MaterialTheme.typography.bodySmall)
    if (openFailed) Text(stringResource(Res.string.optional_document_failed))
    if (type == OptionalConsentType.PROFILE_RELIGION) Text(stringResource(Res.string.optional_religion_help),
        style = MaterialTheme.typography.bodySmall)
}

/** 동의 · 철회 확인창과 사진 삭제 확인창 */
@Composable
internal fun OptionalProfileDialogs(state: OptionalProfileState, onConfirm: () -> Unit, onDismiss: () -> Unit, onConfirmDeletePhoto: () -> Unit) {
    state.decision?.let { d ->
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(if (d.agreed) Res.string.optional_grant_title else Res.string.optional_withdraw_title)) },
            text = { Text(stringResource(if (d.agreed) Res.string.optional_grant_body else when (d.type) {
                OptionalConsentType.PROFILE_PHOTO -> Res.string.optional_withdraw_photo
                OptionalConsentType.PROFILE_INTRODUCTION -> Res.string.optional_withdraw_introduction
                OptionalConsentType.PROFILE_RELIGION -> Res.string.optional_withdraw_religion
            })) },
            confirmButton = { TextButton(onClick = onConfirm, enabled = !state.busy) { Text(stringResource(if (d.agreed) Res.string.optional_confirm else Res.string.optional_withdraw)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.optional_cancel)) } })
    }
    if (state.deletePhotoPrompt) AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_photo_delete)) },
        text = { Text(stringResource(Res.string.optional_photo_delete_body)) },
        confirmButton = { TextButton(onClick = onConfirmDeletePhoto) { Text(stringResource(Res.string.profile_photo_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.optional_cancel)) } })
}
