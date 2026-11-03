package com.ssafy.e106.features.profile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.features.profile.data.*
import com.ssafy.e106.features.profile.photo.*
import com.ssafy.e106.resources.*
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
    var openFailed by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val skipLabel = stringResource(if (editingExisting) Res.string.profile_manage_optional_back else Res.string.optional_skip)
    ProfileDetailsScreen(state.details.copy(saving = state.busy, interactionEnabled = state.editable),
        onReligion, { onDecision(OptionalConsentType.PROFILE_RELIGION, it) },
        onIntroduction, { onDecision(OptionalConsentType.PROFILE_INTRODUCTION, it) },
        { onDecision(OptionalConsentType.PROFILE_PHOTO, it) }, onChoosePhoto, onDeletePhoto,
        onContinue = { if (!state.loaded) skipPrompt = true else onSave() },
        photoContent = {
            val bytes = state.photo
            if (bytes != null) ProfilePhotoBytes(bytes)
            else Text(stringResource(Res.string.optional_photo_unavailable))
        },
        // 결과 미확인 상태에서는 수정만 잠근다. 뒤로가기는 확인창을 거쳐 허용한다.
        onBack = onBack.takeIf { !state.busy },
        header = {
            if (state.busy) { LinearProgressIndicator(); Text(stringResource(Res.string.optional_loading)) }
            if (!state.loaded && !state.busy) Text(stringResource(Res.string.optional_unavailable))
            if (state.recoveryRequired) Text(stringResource(Res.string.optional_unknown))
            state.notice?.let { Text(stringResource(when (it) {
                OptionalProfileNotice.VersionChanged -> Res.string.optional_version_changed
                OptionalProfileNotice.ConsentRequired -> Res.string.optional_consent_required
                OptionalProfileNotice.Unknown -> Res.string.optional_unknown
                OptionalProfileNotice.Saved -> Res.string.optional_saved
                OptionalProfileNotice.Checked -> Res.string.optional_checked
                else -> Res.string.optional_changed
            })) }
            state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            if (!state.loaded || state.recoveryRequired || state.error != null || state.photoFailed) {
                TextButton(onClick = onReload, enabled = !state.busy) { Text(stringResource(Res.string.optional_refresh)) }
            }
            if (state.canRetryConsent) TextButton(onClick = onRetryConsent, enabled = !state.busy) {
                Text(stringResource(Res.string.optional_retry_consent))
            }
        },
        canChangeConsent = { type -> state.canDecide(type, state.consent(type)?.agreed != true) },
        consentInfo = { type ->
            val doc = state.document(type)
            val url = doc?.reviewUrl
            if (url != null) TextButton(onClick = {
                openFailed = try { uri.openUri(url); false } catch (_: Exception) { true }
            }, enabled = !state.busy) { Text(stringResource(Res.string.optional_document)) }
            else Text(stringResource(if (state.allowUnpublished) Res.string.optional_development_document else Res.string.optional_document_missing),
                style = MaterialTheme.typography.bodySmall)
            if (openFailed) Text(stringResource(Res.string.optional_document_failed))
            if (type == OptionalConsentType.PROFILE_RELIGION) Text(stringResource(Res.string.optional_religion_help),
                style = MaterialTheme.typography.bodySmall)
        },
        footer = {
            if (state.loaded) TextButton(onClick = { skipPrompt = true }, enabled = state.canContinue) {
                Text(skipLabel)
            }
        },
        continueEnabled = if (!state.loaded) state.canContinue else state.editable && state.details.canContinue,
        continueLabel = if (!state.loaded) skipLabel else if (editingExisting) stringResource(Res.string.profile_manage_optional_save) else null)
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
    if (skipPrompt) AlertDialog(onDismissRequest = { skipPrompt = false },
        title = { Text(stringResource(Res.string.optional_skip_title)) },
        text = { Text(stringResource(Res.string.optional_skip_body)) },
        confirmButton = { TextButton(enabled = state.canContinue, onClick = { skipPrompt = false; onSkip() }) { Text(skipLabel) } },
        dismissButton = { TextButton(onClick = { skipPrompt = false }) { Text(stringResource(Res.string.optional_cancel)) } })
}
