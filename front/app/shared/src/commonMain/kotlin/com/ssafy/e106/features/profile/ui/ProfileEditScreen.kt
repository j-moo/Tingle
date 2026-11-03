package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 가입 후 수정용 순수 화면. 저장·뒤로가기·사진·정정 문의의 실행은 호출자가 담당한다. */
@Composable
fun ProfileEditScreen(
    state: ProfileEditState,
    onChange: (ProfileEditDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onPhoto: () -> Unit,
    onCorrection: () -> Unit,
    modifier: Modifier = Modifier,
    photoContent: (@Composable () -> Unit)? = null,
    optionalContent: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit = {},
    photoActionLabel: String? = null,
) {
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    val editable = !state.saving && state.interactionEnabled
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 결과 재조회가 필요해 입력을 잠근 경우에도 확인 후 나갈 수 있게 뒤로가기는 유지한다.
            AppTopBar(stringResource(Res.string.profile_edit_title), onBack = if (!state.saving) onBack else null)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(screenPaddingValues()), verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                    Text(stringResource(Res.string.profile_edit_heading), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(Res.string.profile_edit_help), color = AppColors.TextSecondary)
                    header()
                    Text(stringResource(Res.string.profile_photo_title), style = MaterialTheme.typography.titleMedium)
                    if (state.hasPhoto && photoContent != null) {
                        Box(Modifier.size(144.dp).align(Alignment.CenterHorizontally).clip(AppShapes.Card)) {
                            photoContent()
                        }
                    }
                    Text(stringResource(if (state.hasPhoto) Res.string.profile_edit_photo_registered else Res.string.profile_photo_empty))
                    AppButton(photoActionLabel ?: stringResource(Res.string.profile_edit_photo_manage), variant = AppButtonVariant.Secondary,
                        enabled = editable, onClick = onPhoto)
                    HorizontalDivider()
                    AppTextField(draft.nickname, { onChange(draft.copy(nickname = it)) },
                        label = stringResource(Res.string.profile_nickname), required = true, enabled = editable,
                        supportingText = stringResource(Res.string.profile_nickname_help),
                        errorText = if (draft.nicknameValid) null else stringResource(Res.string.profile_edit_nickname_invalid))
                    Surface(color = AppColors.SurfaceMuted, shape = AppShapes.Control) {
                        Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            Text(stringResource(Res.string.profile_edit_identity), style = MaterialTheme.typography.titleSmall)
                            Text("${stringResource(Res.string.profile_birth_date)} · ${state.birthDateLabel}")
                            Text("${stringResource(Res.string.profile_gender)} · ${stringResource(if (state.gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female)}")
                            Text(stringResource(Res.string.profile_edit_locked), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onCorrection, enabled = editable) { Text(stringResource(Res.string.profile_edit_correction)) }
                        }
                    }
                    EditSelection(stringResource(Res.string.profile_edit_smoking), draft.smoking?.let { stringResource(it.label()) }, editable) { selection = "smoking" }
                    EditSelection(stringResource(Res.string.profile_edit_drinking), draft.drinking?.let { stringResource(it.label()) }, editable) { selection = "drinking" }
                    HorizontalDivider()
                    if (optionalContent != null) optionalContent() else {
                    Text(stringResource(Res.string.profile_religion_title), style = MaterialTheme.typography.titleMedium)
                    AppCheckbox(draft.religionConsent, { onChange(draft.copy(religionConsent = it,
                        religion = if (it) draft.religion else null)) }, stringResource(Res.string.profile_religion_consent), enabled = editable)
                    EditSelection(stringResource(Res.string.profile_religion_title), draft.religion?.let { stringResource(it.label()) }
                        ?: stringResource(Res.string.profile_religion_private), editable && draft.religionConsent) { selection = "religion" }
                    Text(stringResource(Res.string.profile_religion_private_help), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(stringResource(Res.string.profile_intro_title), style = MaterialTheme.typography.titleMedium)
                    AppCheckbox(draft.introductionConsent, { onChange(draft.copy(introductionConsent = it,
                        introduction = if (it) draft.introduction else "")) }, stringResource(Res.string.profile_intro_consent), enabled = editable)
                    val intro = draft.details
                    val introError = when {
                        intro.introductionTooLong -> stringResource(Res.string.profile_intro_too_long)
                        intro.introductionTooManyLines -> stringResource(Res.string.profile_intro_too_many_lines)
                        else -> null
                    }
                    OutlinedTextField(draft.introduction, { onChange(draft.copy(introduction = it)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, shape = AppShapes.Control,
                        enabled = editable && draft.introductionConsent, isError = introError != null,
                        label = { Text(stringResource(Res.string.profile_intro_title)) },
                        placeholder = { Text(stringResource(Res.string.profile_intro_placeholder)) },
                        supportingText = { Column {
                            Text(stringResource(Res.string.profile_intro_count, intro.introductionLength))
                            Text(introError ?: stringResource(Res.string.profile_intro_rule))
                        } })
                    }
                }
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Medium)) {
                state.saveError?.let { Text(it, color = AppColors.Error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                AppButton(stringResource(Res.string.profile_edit_save), Modifier.fillMaxWidth(),
                    enabled = state.canSave, loading = state.saving, loadingText = stringResource(Res.string.profile_saving), onClick = onSave)
            }
        }
    }
    if (selection != null && editable) {
        val options = when (selection) {
            "smoking" -> ProfileSmoking.entries.map { stringResource(it.label()) }
            "drinking" -> ProfileDrinking.entries.map { stringResource(it.label()) }
            else -> ProfileReligion.entries.map { stringResource(it.label()) }
        }
        val selected = when (selection) {
            "smoking" -> draft.smoking?.ordinal
            "drinking" -> draft.drinking?.ordinal
            else -> draft.religion?.ordinal
        }
        val title = stringResource(when (selection) {
            "smoking" -> Res.string.profile_edit_smoking
            "drinking" -> Res.string.profile_edit_drinking
            else -> Res.string.profile_religion_title
        })
        AlertDialog(onDismissRequest = { selection = null }, title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEachIndexed { index, label -> AppRadio(selected == index, onSelect = {
                    onChange(when (selection) {
                        "smoking" -> draft.copy(smoking = ProfileSmoking.entries[index])
                        "drinking" -> draft.copy(drinking = ProfileDrinking.entries[index])
                        else -> draft.copy(religion = ProfileReligion.entries[index])
                    }); selection = null
                }, label = label) }
            } }, confirmButton = { TextButton(onClick = { selection = null }) { Text(stringResource(Res.string.profile_edit_cancel)) } })
    }
    if (state.confirmDiscard && editable) AlertDialog(onDismissRequest = onKeepEditing,
        title = { Text(stringResource(Res.string.profile_edit_discard_title)) },
        text = { Text(stringResource(Res.string.profile_edit_discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(Res.string.profile_edit_discard)) } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text(stringResource(Res.string.profile_edit_keep)) } })
}

@Composable
private fun EditSelection(title: String, value: String?, enabled: Boolean, onSelect: () -> Unit) {
    AppButton(stringResource(Res.string.profile_edit_change, title, value ?: stringResource(Res.string.profile_edit_choose)),
        Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, enabled = enabled, onClick = onSelect)
}
