package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.features.account.ui.*
import com.ssafy.e106.features.onboarding.ui.OnboardingPanel
import com.ssafy.e106.features.profile.photo.ProfilePhotoBytes
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private enum class ProfileDestination { Overview, Edit, Optional, Correction }

@Composable
fun ProfileManagementRoute(session: Session.SignedIn, onExit: () -> Unit, model: ProfileEditViewModel = koinViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(session) { mutableStateOf(ProfileDestination.Overview) }
    var unknownExitPrompt by rememberSaveable(session) { mutableStateOf(false) }
    // 구성 변경에서는 저장을 유지하고, 재생성된 화면이 저장 완료 상태를 소비해 이동한다.
    LaunchedEffect(session, state.session, state.saveCompleted, state.busy) {
        if (state.session == session && model.takeSaveCompletion()) page = ProfileDestination.Overview
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (page != ProfileDestination.Optional) model.load() }
    if (state.session != session) return
    fun overview() { page = ProfileDestination.Overview }
    fun returnFromOptional() { page = ProfileDestination.Edit; model.load() }
    fun exit() { model.leave(); onExit() }
    fun back() {
        if (state.busy) return
        if (state.recoveryRequired) { unknownExitPrompt = true; return }
        when (page) {
            ProfileDestination.Edit -> model.requestBack(::overview)
            ProfileDestination.Correction -> page = ProfileDestination.Edit
            else -> exit()
        }
    }
    ProfileBackHandler(page != ProfileDestination.Optional) {
        back()
    }
    when (page) {
        ProfileDestination.Optional -> OptionalProfileRoute(session, { returnFromOptional() }, ::returnFromOptional, editingExisting = true)
        ProfileDestination.Correction -> HelpGuideScreen(HelpGuideState(HelpGuideKind.Correction), onAction = {
            if (it == HelpGuideAction.Back) page = ProfileDestination.Edit
        })
        ProfileDestination.Edit -> ProfileConnectedEditScreen(state,
            onChange = model::change, onSave = { model.save() },
            onBack = ::back, onDiscard = { model.discard(::overview) }, onKeep = model::keepEditing,
            onOptional = { if (state.canEdit) page = ProfileDestination.Optional },
            onCorrection = { if (state.canEdit) page = ProfileDestination.Correction }, onReload = model::load)
        ProfileDestination.Overview -> OnboardingPanel(stringResource(Res.string.profile_manage_title)) {
            if (!session.isReady) Text(stringResource(Res.string.profile_manage_dev_notice))
            if (state.busy) { CircularProgressIndicator(); Text(stringResource(Res.string.profile_manage_load)) }
            ProfileManagementNotice(state)
            state.profile?.let { p ->
                Text(p.nickname.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                state.photo?.let { Box(Modifier.size(144.dp)) { ProfilePhotoBytes(it) } }
                OptionalProfileSummary(state)
            }
            AppButton(stringResource(Res.string.profile_edit_title), enabled = state.canEdit,
                onClick = { page = ProfileDestination.Edit })
            TextButton(onClick = model::load, enabled = !state.busy) { Text(stringResource(Res.string.profile_manage_reload)) }
            TextButton(onClick = ::back, enabled = !state.busy) {
                Text(stringResource(Res.string.onboarding_previous))
            }
        }
    }
    // 오프라인에서도 사용자를 가두지 않는다. 나가더라도 서버 반영 여부는 미확인으로 남긴다.
    if (unknownExitPrompt) ProfileUnknownExitDialog(
        onDismiss = { unknownExitPrompt = false },
        onExit = { unknownExitPrompt = false; exit() })
}

/** Same pure editor as the Preview, with optional operations delegated to their consent flow. */
@Composable
fun ProfileConnectedEditScreen(
    state: ProfileManagementState, onChange: (ProfileEditDraft) -> Unit, onSave: () -> Unit,
    onBack: () -> Unit, onDiscard: () -> Unit, onKeep: () -> Unit,
    onOptional: () -> Unit, onCorrection: () -> Unit, onReload: () -> Unit,
) {
    val editor = state.editor
    if (editor == null) {
        OnboardingPanel(stringResource(Res.string.profile_edit_title)) {
            ProfileManagementNotice(state)
            if (state.busy) CircularProgressIndicator()
            AppButton(stringResource(Res.string.profile_manage_reload), enabled = !state.busy, onClick = onReload)
            TextButton(onClick = onBack, enabled = !state.busy) { Text(stringResource(Res.string.onboarding_previous)) }
        }
        return
    }
    ProfileEditScreen(editor.copy(saving = state.busy, interactionEnabled = !state.recoveryRequired),
        onChange, onSave, onBack, onDiscard, onKeep, onOptional, onCorrection,
        photoContent = { state.photo?.let { ProfilePhotoBytes(it) } ?: Text(stringResource(Res.string.optional_photo_unavailable)) },
        optionalContent = {
            OptionalProfileSummary(state)
            Text(stringResource(Res.string.profile_manage_optional_help), style = MaterialTheme.typography.bodySmall)
        },
        photoActionLabel = stringResource(Res.string.profile_manage_optional),
        header = {
            ProfileManagementNotice(state)
            if (state.recoveryRequired || state.error != null) TextButton(onClick = onReload, enabled = !state.busy) {
                Text(stringResource(Res.string.profile_manage_reload))
            }
        })
}

@Composable private fun OptionalProfileSummary(state: ProfileManagementState) {
    val p = state.profile ?: return
    Text(stringResource(Res.string.profile_intro_title), style = MaterialTheme.typography.titleMedium)
    Text(p.introduction?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.profile_manage_none))
    Text(stringResource(Res.string.profile_religion_title), style = MaterialTheme.typography.titleMedium)
    Text(p.religion.religion()?.let { stringResource(it.label()) } ?: stringResource(Res.string.profile_manage_none))
}

@Composable private fun ProfileManagementNotice(state: ProfileManagementState) {
    if (state.savedNotice) Text(stringResource(Res.string.profile_manage_saved))
    state.problem?.let { Text(stringResource(when(it) {
        ProfileEditProblem.InvalidProfile -> Res.string.profile_manage_invalid
        ProfileEditProblem.DuplicateNickname -> Res.string.onboarding_duplicate_nickname
        ProfileEditProblem.InvalidNickname -> Res.string.profile_edit_nickname_invalid
        ProfileEditProblem.ResultUnknown -> Res.string.profile_manage_unknown
    }), color = MaterialTheme.colorScheme.error) }
    if (state.recoveryRequired && state.problem != ProfileEditProblem.ResultUnknown) Text(stringResource(Res.string.profile_manage_unknown))
    state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
}
