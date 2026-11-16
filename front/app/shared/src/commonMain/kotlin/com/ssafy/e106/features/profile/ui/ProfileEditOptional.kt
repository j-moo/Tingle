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
/** 시트 "완료"를 누를 수 있다(소개 200자 · 줄바꿈 3개 제한 포함) */
internal val OptionalProfileState.canFinishSheet get() = editable && details.canContinue

internal enum class ConsentWait { Wait, Open, Drop }
/**
 * 동의 대기 항목의 다음 단계. 고칠 수 있게 되면 시트를 연다. 결과 미확인(다시 확인 · 다시 시도 대기)이면 계속 기다리고,
 * 확인이 끝났는데 동의가 없으면(거절된 요청 등) 대기를 푼다. 이유는 [OptionalProfileStatus] 가 보여 준다
 */
internal fun OptionalProfileState.settle(pending: OptionalConsentType): ConsentWait = when {
    readyToEdit(pending) -> ConsentWait.Open
    busy || decision != null || recoveryRequired || !loaded -> ConsentWait.Wait
    else -> ConsentWait.Drop
}

/**
 * 프로필 수정 페이지. 닉네임 · 흡연 · 음주는 [ProfileEditViewModel] 이 "저장"으로 저장하고,
 * 종교 · 소개는 [OptionalProfileViewModel] 로 (동의) → 선택 · 입력 시트 → "완료"에서 바로 저장한다(참고 구현: [ProfileRoute]).
 * 소개 동의는 가입 때 받는 필수 동의라 철회를 두지 않고, 혹시 빠져 있으면 기본 동의 확인창으로 받는다.
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
    // 결과 미확인 상태에서 닫은 시트의 입력. 다시 열 때 버리지 않는다
    var unsaved by rememberSaveable(session) { mutableStateOf(false) }
    // 시트 저장 뒤 수정 화면 다시 불러오기. 다른 요청 중이면 무시되므로 끝날 때까지 기다린다
    var reload by rememberSaveable(session) { mutableStateOf(false) }
    var retrySave by remember { mutableStateOf(false) }
    LaunchedEffect(pending, optional) {
        val type = pending ?: return@LaunchedEffect
        when (optional.settle(type)) {
            ConsentWait.Open -> { pending = null; sheet = type }
            ConsentWait.Drop -> pending = null
            ConsentWait.Wait -> Unit
        }
    }
    // 시트에서 저장이 끝나면(회전으로 화면이 바뀌어도) 시트를 닫고 보기 요약을 다시 받는다. 고치던 닉네임 · 흡연 · 음주는 유지된다.
    // 꺼내지 않으면 나중에 📷 선택 정보 화면이 남은 완료를 보고 바로 되돌아간다
    LaunchedEffect(session, optional.session, optional.completion, optional.busy) {
        if (optional.session == session && optionalModel.takeCompletion() != null) { sheet = null; unsaved = false; reload = true }
    }
    LaunchedEffect(reload, state.busy) { if (reload && !state.busy) { reload = false; model.load() } }
    // "다시 시도": 먼저 다시 확인하고, 확인되면 같은 입력으로 저장한다(결과 미확인 상태의 save() 는 무시되기 때문)
    LaunchedEffect(retrySave) {
        if (!retrySave) return@LaunchedEffect
        val checked = optionalModel.state.first { !it.busy }
        retrySave = false
        if (checked.canFinishSheet) optionalModel.save()
    }
    fun open(type: OptionalConsentType) {
        if (!optional.agreed(type)) { optionalModel.decide(type, true); return }
        if (!optional.editable) return
        // 다른 항목을 고치다 만 값이 같이 저장되지 않게 서버 값에서 시작한다(저장 결과를 모르는 입력은 남긴다)
        if (!unsaved) optionalModel.discardDraft()
        unsaved = false; sheet = type
    }
    fun close() {
        if (optional.busy) return
        if (optional.recoveryRequired) unsaved = true else optionalModel.discardDraft()
        sheet = null
    }
    fun withdraw(type: OptionalConsentType) { optionalModel.discardDraft(); sheet = null; optionalModel.decide(type, false) }
    fun retry() { optionalModel.load(); if (optionalModel.state.value.busy) retrySave = true }
    // 기본 정보 저장 중 · 결과 미확인이면 시트를 열지 않는다(저장이 끝나면 화면이 바뀌어 시트가 사라진다)
    fun enabled(type: OptionalConsentType) = state.canEdit &&
        if (optional.agreed(type)) optional.editable else optional.canDecide(type, true)
    fun confirm() { val d = optional.decision; optionalModel.confirmDecision(); if (d?.agreed == true) pending = d.type }
    fun dismiss() { optionalModel.dismissDecision(); pending = null }
    // 선택 정보를 아직 못 불러왔으면 기본 프로필 값을 보여 준다(누를 수는 없다)
    val saved = optional.profile.takeIf { optional.loaded } ?: state.profile
    val religion = saved?.religion.religion()?.takeIf { !optional.loaded || optional.agreed(OptionalConsentType.PROFILE_RELIGION) }
    val introAdded = !optional.loaded || optional.agreed(OptionalConsentType.PROFILE_INTRODUCTION)

    ProfileConnectedEditScreen(state,
        onChange = model::change, onSave = { model.save() },
        onBack = onBack, onDiscard = onDiscard, onKeep = model::keepEditing,
        onOptional = onOptional, onCorrection = onCorrection, onReload = { model.load(); optionalModel.load() },
        religionChip = { ReligionChip(religion, enabled(OptionalConsentType.PROFILE_RELIGION)) { open(OptionalConsentType.PROFILE_RELIGION) } },
        // 결과 미확인 · 동의 저장 실패 · 조회 실패는 칩 아래에서 "다시 확인" · 같은 동의 다시 요청
        // 저장 · 확인 안내는 시트가 맡고, 사진은 위쪽 사진 칸이 맡는다. 여기서는 동의가 바뀌었다는 안내만 남긴다
        belowChips = { OptionalProfileStatus(optional.copy(photoFailed = false, notice = optional.notice.takeIf {
            it == OptionalProfileNotice.VersionChanged || it == OptionalProfileNotice.ConsentRequired }),
            optionalModel::load, optionalModel::retryUnresolvedConsent, editing = true) },
        introduction = {
            EditIntroCard(saved?.introduction.takeIf { introAdded }, introAdded, enabled(OptionalConsentType.PROFILE_INTRODUCTION)) {
                open(OptionalConsentType.PROFILE_INTRODUCTION)
            }
        })

    // 종교 동의는 무엇에 동의하는지 적은 시트로, 그 밖의 동의 · 철회는 기존 확인창으로 묻는다
    val religionAsk = optional.decision?.takeIf { it.agreed && it.type == OptionalConsentType.PROFILE_RELIGION }
    OptionalProfileDialogs(if (religionAsk != null) optional.copy(decision = null) else optional,
        ::confirm, ::dismiss, optionalModel::deletePhoto)
    if (religionAsk != null) ReligionConsentSheet(optional.busy, ::confirm, ::dismiss) {
        OptionalConsentInfo(optional, OptionalConsentType.PROFILE_RELIGION)
    }
    when (sheet?.takeIf { optional.agreed(it) }) {
        OptionalConsentType.PROFILE_RELIGION -> OptionSheet(stringResource(Res.string.profile_religion_title).withoutTag(),
            // 공개하지 않음도 저장되는 값이라 고를 수 있다(수정 화면 칩은 "종교 비공개", 보기 화면은 숨김)
            ProfileReligion.entries.map { Res.drawable.ic_church to stringResource(it.label()) }, optional.details.religion?.ordinal, ::close,
            footer = { SheetFooter(optional, { optionalModel.save() }, ::retry, ::close,
                onWithdraw = { withdraw(OptionalConsentType.PROFILE_RELIGION) }) }
        ) { optionalModel.religion(ProfileReligion.entries[it]) }
        // 소개는 필수 동의라 철회가 없다(철회하면 앱을 쓸 수 없게 된다)
        OptionalConsentType.PROFILE_INTRODUCTION -> IntroSheet(optional, optionalModel::introduction, ::close) {
            SheetFooter(optional, { optionalModel.save() }, ::retry, ::close, onWithdraw = null)
        }
        else -> Unit
    }
}

/** 종교 · 소개 시트 아래: 바로 저장 안내 · 오류 · 완료(결과 미확인이면 다시 시도) · 취소 · (종교만) 동의 철회 */
@Composable
private fun ColumnScope.SheetFooter(optional: OptionalProfileState, onSave: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    onWithdraw: (() -> Unit)?) {
    Text(stringResource(Res.string.profile_sheet_saves_now), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    optional.error?.let { InlineError(it.message) }
    if (optional.recoveryRequired) AppButton(stringResource(Res.string.state_retry), Modifier.fillMaxWidth(),
        loading = optional.busy, onClick = onRetry)
    else AppButton(stringResource(Res.string.profile_sheet_done), Modifier.fillMaxWidth(), loading = optional.busy,
        enabled = optional.canFinishSheet, onClick = onSave)
    AppButton(stringResource(Res.string.optional_cancel), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
        enabled = !optional.busy, onClick = onCancel)
    if (onWithdraw != null) TextButton(onClick = onWithdraw, enabled = !optional.busy && !optional.recoveryRequired,
        modifier = Modifier.align(Alignment.CenterHorizontally)) {
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
