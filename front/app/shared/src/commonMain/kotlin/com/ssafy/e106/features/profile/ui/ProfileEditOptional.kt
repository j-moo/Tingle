package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssafy.e106.core.auth.Session
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.resources.*
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/** 서버가 확인한 동의 여부 */
internal fun OptionalProfileState.agreed(type: OptionalConsentType) = when (type) {
    OptionalConsentType.PROFILE_RELIGION -> details.religionConsent
    OptionalConsentType.PROFILE_INTRODUCTION -> details.introductionConsent
    OptionalConsentType.PROFILE_PHOTO -> details.photoConsent
}
/** 동의 저장과 다시 조회가 끝나 바로 고칠 수 있다 = 동의 대기 중이던 시트를 열어도 된다 */
internal fun OptionalProfileState.readyToEdit(type: OptionalConsentType) = editable && agreed(type)
/** 동의 대기 중인데 확인이 끝났고(요청 · 확인창 없음) 여전히 고칠 수 없다 = 동의 확인 실패 */
internal fun OptionalProfileState.consentCheckFailed(pending: OptionalConsentType?) =
    pending != null && !busy && decision == null && !readyToEdit(pending)

/**
 * 프로필 수정 페이지. 닉네임 · 흡연 · 음주는 [ProfileEditViewModel] 이 "저장"으로 저장하고,
 * 종교 · 소개는 [OptionalProfileViewModel] 로 동의 시트 → 선택 · 입력 시트 → "완료"에서 바로 저장한다(참고 구현: [ProfileRoute]).
 */
@Composable
internal fun ProfileEditPage(
    session: Session.SignedIn, state: ProfileManagementState, model: ProfileEditViewModel, optionalModel: OptionalProfileViewModel,
    onBack: () -> Unit, onDiscard: () -> Unit, onOptional: () -> Unit, onCorrection: () -> Unit,
) {
    val optional by optionalModel.state.collectAsStateWithLifecycle()
    // 동의를 확인한 항목. confirmDecision 은 콜백이 없어서, 동의 저장 · 다시 조회가 끝나 고칠 수 있게 되면 그 시트를 연다
    var pending by rememberSaveable(session) { mutableStateOf<OptionalConsentType?>(null) }
    var sheet by rememberSaveable(session) { mutableStateOf<OptionalConsentType?>(null) }
    var retrySave by remember { mutableStateOf(false) }
    LaunchedEffect(pending, optional) {
        val type = pending ?: return@LaunchedEffect
        if (optional.readyToEdit(type)) { pending = null; sheet = type }
    }
    // 시트에서 저장이 끝나면(회전으로 화면이 바뀌어도) 시트를 닫고 보기 요약을 다시 받는다. 고치던 닉네임 · 흡연 · 음주는 유지된다.
    // 꺼내지 않으면 나중에 📷 선택 정보 화면이 남은 완료를 보고 바로 되돌아간다
    LaunchedEffect(session, optional.session, optional.completion, optional.busy) {
        if (optional.session == session && optionalModel.takeCompletion() != null) { sheet = null; model.load() }
    }
    // "다시 시도": 먼저 다시 확인하고, 확인되면 같은 입력으로 저장한다(결과 미확인 상태의 save() 는 무시되기 때문)
    LaunchedEffect(retrySave) {
        if (!retrySave) return@LaunchedEffect
        val checked = optionalModel.state.first { !it.busy }
        retrySave = false
        if (checked.editable && checked.details.canContinue) optionalModel.save()
    }
    fun open(type: OptionalConsentType) {
        if (!optional.agreed(type)) { optionalModel.decide(type, true); return }
        // 다른 항목을 고치다 만 값이 같이 저장되지 않게 서버 값에서 시작한다
        if (optional.editable) { optionalModel.discardDraft(); sheet = type }
    }
    fun close() { if (!optional.busy) { optionalModel.discardDraft(); sheet = null } }
    fun withdraw(type: OptionalConsentType) { optionalModel.discardDraft(); sheet = null; optionalModel.decide(type, false) }
    fun retry() { optionalModel.load(); if (optionalModel.state.value.busy) retrySave = true }
    fun enabled(type: OptionalConsentType) = if (optional.agreed(type)) optional.editable else optional.canDecide(type, true)
    // 선택 정보를 아직 못 불러왔으면 기본 프로필 값을 보여 준다(누를 수는 없다)
    val saved = optional.profile.takeIf { optional.loaded } ?: state.profile
    val religion = saved?.religion.religion()?.takeIf { !optional.loaded || optional.agreed(OptionalConsentType.PROFILE_RELIGION) }
    val introAdded = !optional.loaded || optional.agreed(OptionalConsentType.PROFILE_INTRODUCTION)

    ProfileConnectedEditScreen(state,
        onChange = model::change, onSave = { model.save() },
        onBack = onBack, onDiscard = onDiscard, onKeep = model::keepEditing,
        onOptional = onOptional, onCorrection = onCorrection, onReload = { model.load(); optionalModel.load() },
        religionChip = { ReligionChip(religion, enabled(OptionalConsentType.PROFILE_RELIGION)) { open(OptionalConsentType.PROFILE_RELIGION) } },
        belowChips = {
            // 동의 확인이나 선택 정보 조회가 실패하면 시트 대신 한 줄 오류와 "다시 확인"
            if (optional.consentCheckFailed(pending) || (!optional.loaded && !optional.busy && optional.error != null)) {
                InlineError(stringResource(Res.string.profile_consent_check_failed))
                TextAction(stringResource(Res.string.profile_state_recheck), optionalModel::load, enabled = !optional.busy)
            }
        },
        introduction = {
            EditIntroCard(saved?.introduction.takeIf { introAdded }, introAdded, enabled(OptionalConsentType.PROFILE_INTRODUCTION)) {
                open(OptionalConsentType.PROFILE_INTRODUCTION)
            }
        })

    // 동의(종교 · 소개)는 무엇에 동의하는지 적은 시트로, 철회는 기존 확인창으로 묻는다
    val ask = optional.decision?.takeIf { it.agreed && it.type != OptionalConsentType.PROFILE_PHOTO }
    OptionalProfileDialogs(if (ask != null) optional.copy(decision = null) else optional,
        optionalModel::confirmDecision, optionalModel::dismissDecision, optionalModel::deletePhoto)
    if (ask != null) consentSheetText(ask.type).let { (title, lines) ->
        ConsentSheet(title, lines, optional.busy, onAgree = { optionalModel.confirmDecision(); pending = ask.type },
            onDismiss = { optionalModel.dismissDecision(); pending = null }) { OptionalConsentInfo(optional, ask.type) }
    }
    val footer: @Composable ColumnScope.(OptionalConsentType) -> Unit = { type ->
        SheetFooter(optional, type, onSave = { optionalModel.save() }, onRetry = ::retry, onCancel = ::close) { withdraw(type) }
    }
    when (sheet?.takeIf { optional.agreed(it) }) {
        OptionalConsentType.PROFILE_RELIGION -> OptionSheet(stringResource(Res.string.profile_religion_title).withoutTag(),
            // 공개하지 않음도 저장되는 값이라 고를 수 있다(수정 화면 칩은 "종교 비공개", 보기 화면은 숨김)
            ProfileReligion.entries.map { Res.drawable.ic_church to stringResource(it.label()) }, optional.details.religion?.ordinal, ::close,
            footer = { footer(OptionalConsentType.PROFILE_RELIGION) }) { optionalModel.religion(ProfileReligion.entries[it]) }
        OptionalConsentType.PROFILE_INTRODUCTION -> IntroSheet(optional, optionalModel::introduction, ::close) { footer(OptionalConsentType.PROFILE_INTRODUCTION) }
        else -> Unit
    }
}

/** 종교 · 소개 시트 아래: 바로 저장 안내 · 오류 · 완료(결과 미확인이면 다시 시도) · 취소 · 동의 철회 */
@Composable
private fun ColumnScope.SheetFooter(optional: OptionalProfileState, type: OptionalConsentType,
    onSave: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onWithdraw: () -> Unit) {
    Text(stringResource(Res.string.profile_sheet_saves_now), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    optional.error?.let { InlineError(it.message) }
    if (optional.recoveryRequired) AppButton(stringResource(Res.string.state_retry), Modifier.fillMaxWidth(),
        loading = optional.busy, onClick = onRetry)
    else AppButton(stringResource(Res.string.profile_sheet_done), Modifier.fillMaxWidth(), loading = optional.busy,
        enabled = optional.editable && optional.details.canContinue, onClick = onSave)
    AppButton(stringResource(Res.string.optional_cancel), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
        enabled = !optional.busy, onClick = onCancel)
    TextButton(onClick = onWithdraw, enabled = optional.canDecide(type, false), modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(Res.string.profile_sheet_withdraw), color = AppColors.Error)
    }
}

/** 소개 입력 시트: 200자 · 줄바꿈 3개 제한, 글자 수, 오류 문구는 선택 정보 화면과 같다. 키보드가 올라오면 시트가 따라 올라온다 */
@Composable
private fun IntroSheet(optional: OptionalProfileState, onChange: (String) -> Unit, onDismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit) {
    val intro = optional.details
    val error = when {
        intro.introductionTooLong -> stringResource(Res.string.profile_intro_too_long)
        intro.introductionTooManyLines -> stringResource(Res.string.profile_intro_too_many_lines)
        else -> null
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ActionSheet(onDismiss = onDismiss) {
            Text(stringResource(Res.string.profile_intro_title).withoutTag(), style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
            OutlinedTextField(intro.introduction, onChange, Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, shape = AppShapes.Control,
                enabled = optional.editable, isError = error != null,
                placeholder = { Text(stringResource(Res.string.profile_intro_placeholder)) },
                supportingText = { Column {
                    Text(stringResource(Res.string.profile_intro_count, intro.introductionLength))
                    Text(error ?: stringResource(Res.string.profile_intro_rule))
                } })
            footer()
        }
    }
}

/** 수정 화면 종교 칩: 동의 전이거나 값이 없으면 점선 "종교 추가", 공개하지 않음이면 "종교 비공개" */
@Composable
private fun ReligionChip(religion: ProfileReligion?, enabled: Boolean, onClick: () -> Unit) {
    val text = when (religion) {
        null -> stringResource(Res.string.profile_religion_add)
        ProfileReligion.Private -> stringResource(Res.string.profile_religion_hidden)
        else -> stringResource(religion.label())
    }
    EditChip(Res.drawable.ic_church, text, religion?.let { stringResource(Res.string.profile_chip_change,
        stringResource(Res.string.profile_religion_title).withoutTag(), stringResource(it.label())) }, enabled, onClick = onClick)
}

/** 수정 화면 소개 카드: 오른쪽 위에 "고치기"(동의 전이면 "소개 추가"), 카드 전체를 누를 수 있다 */
@Composable
private fun EditIntroCard(text: String?, added: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val action = stringResource(if (added) Res.string.profile_intro_edit else Res.string.profile_intro_add)
    ProfileIntroCard(text, Modifier.clip(AppShapes.Card).clickable(enabled, onClickLabel = action, role = Role.Button, onClick = onClick)) {
        Text(action, style = MaterialTheme.typography.labelLarge, color = if (enabled) AppColors.FreshInk else AppColors.TextDisabled)
    }
}
