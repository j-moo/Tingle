package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 가입 후 수정용 순수 화면. 저장·뒤로가기·사진·정정 문의의 실행은 호출자가 담당한다.
 * 위쪽은 보기 화면과 같은 [ProfileHero](사진 약 42%, ‹ 만), 아래는 닉네임 · 생활 칩 · 소개 · 잠긴 생년월일 · 성별 순서로 한 스크롤이다.
 * 흡연 · 음주는 칩을 눌러 아래 시트에서 고르고 "저장"으로 저장한다. 종교 칩([religionChip]) · 소개([introduction])는 호출자가 그린다.
 */
@Composable
internal fun ProfileEditScreen(
    state: ProfileEditState,
    onChange: (ProfileEditDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onPhoto: () -> Unit,
    onCorrection: () -> Unit,
    modifier: Modifier = Modifier,
    heroPhoto: HeroPhoto = HeroPhoto.Empty,
    heroImage: ImageBitmap? = null,
    religionChip: @Composable () -> Unit = {},
    belowChips: @Composable () -> Unit = {},
    introduction: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    val editable = !state.saving && state.interactionEnabled
    Surface(modifier.fillMaxSize(), color = AppColors.Screen) {
        // 사진은 상태 바 밑까지 깔고, 나머지는 가로 · 아래 안전 영역과 키보드만 피한다
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).imePadding()) {
            // 당겨서 새로고침은 호출부가 줄 때만 붙는다(서버 값을 다시 받아도 고치던 입력은 ViewModel 이 지킨다)
            val body: @Composable BoxScope.() -> Unit = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // 결과 재조회가 필요해 입력을 잠근 경우에도 확인 후 나갈 수 있게 뒤로가기는 유지한다.
                    // 사진 위 닉네임은 아래 입력을 따라 바뀌는 미리보기다
                    ProfileHero(ProfileHeroInfo(draft.nickname, internationalAge(state.birthDateLabel, todayKst()), state.gender),
                        heroPhoto, heroImage, onBack.takeIf { !state.saving }, stringResource(Res.string.profile_edit_title),
                        heightFraction = 0.42f, preview = true,
                        corner = { HeroRoundButton(Res.drawable.ic_camera, stringResource(Res.string.profile_edit_photo_manage), onPhoto, enabled = editable) })
                    Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth().padding(screenPaddingValues()),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Large)) {
                        header()
                        AppTextField(draft.nickname, { onChange(draft.copy(nickname = it)) },
                            label = stringResource(Res.string.profile_nickname), required = true, enabled = editable,
                            supportingText = stringResource(Res.string.profile_nickname_help),
                            errorText = if (draft.nicknameValid) null else stringResource(Res.string.profile_edit_nickname_invalid))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val smoking = stringResource(Res.string.profile_edit_smoking)
                            val drinking = stringResource(Res.string.profile_edit_drinking)
                            draft.smoking.let { v -> EditChip(v?.icon() ?: Res.drawable.ic_cigarette,
                                v?.let { stringResource(it.label()) } ?: stringResource(Res.string.profile_smoking_pick),
                                v?.let { stringResource(Res.string.profile_chip_change, smoking, stringResource(it.label())) }, editable) { selection = "smoking" } }
                            draft.drinking.let { v -> EditChip(Res.drawable.ic_wine,
                                v?.let { stringResource(it.chipLabel()) } ?: stringResource(Res.string.profile_drinking_pick),
                                v?.let { stringResource(Res.string.profile_chip_change, drinking, stringResource(it.label())) }, editable) { selection = "drinking" } }
                            religionChip()
                        }
                        belowChips()
                        introduction()
                        LockedIdentity(state, editable, onCorrection)
                    }
                }
            }
            if (onRefresh != null) PullDownRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(),
                indicatorInsets = WindowInsets.statusBars, content = body)
            else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter, content = body)
            // 저장 실패: 입력은 그대로 두고 버튼 바로 위에 한 줄로 알린다. 버튼은 "다시 시도"가 되고 입력을 고치면 원래대로 돌아온다
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                state.saveError?.let { InlineError(it) }
                AppButton(stringResource(if (state.saveError != null) Res.string.state_retry else Res.string.profile_edit_save), Modifier.fillMaxWidth(),
                    enabled = state.canSave, loading = state.saving, loadingText = stringResource(Res.string.profile_saving), onClick = onSave)
            }
        }
    }
    // 고르면 바로 닫히고 칩이 바뀐다. 저장은 아래 "저장" 버튼으로 한다
    if (selection == "smoking" && editable) OptionSheet(stringResource(Res.string.profile_edit_smoking),
        ProfileSmoking.entries.map { it.icon() to stringResource(it.label()) }, draft.smoking?.ordinal, { selection = null },
        help = stringResource(Res.string.profile_smoking_help)) { onChange(draft.copy(smoking = ProfileSmoking.entries[it])); selection = null }
    if (selection == "drinking" && editable) OptionSheet(stringResource(Res.string.profile_edit_drinking),
        ProfileDrinking.entries.map { Res.drawable.ic_wine to stringResource(it.label()) }, draft.drinking?.ordinal, { selection = null },
        help = stringResource(Res.string.profile_drinking_days_help)) { onChange(draft.copy(drinking = ProfileDrinking.entries[it])); selection = null }
    if (state.confirmDiscard && editable) AlertDialog(onDismissRequest = onKeepEditing,
        title = { Text(stringResource(Res.string.profile_edit_discard_title)) },
        text = { Text(stringResource(Res.string.profile_edit_discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(Res.string.profile_edit_discard)) } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text(stringResource(Res.string.profile_edit_keep)) } })
}

/** 맨 아래 작은 잠금 카드: 생년월일 · 성별은 직접 바꿀 수 없고 정정을 요청한다 */
@Composable private fun LockedIdentity(state: ProfileEditState, editable: Boolean, onCorrection: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(AppShapes.Control, AppColors.SurfaceMuted).padding(AppSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Icon(painterResource(Res.drawable.ic_state_lock), null, Modifier.size(AppSizes.IconSmall), tint = AppColors.TextSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            // 문장 전체를 리소스에 두어 번역에서 어순과 구분자를 함께 바꿀 수 있게 한다.
            // 날짜 값은 서버 계약의 날짜 전용 값이며 여기서 시간대로 변환하지 않는다.
            Text(stringResource(Res.string.profile_edit_birth_date_value, state.birthDateLabel), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.profile_edit_gender_value,
                stringResource(if (state.gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.profile_edit_locked), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        TextButton(onClick = onCorrection, enabled = editable) { Text(stringResource(Res.string.profile_edit_correction)) }
    }
}

/**
 * 아래에서 올라오는 선택 시트. 선택지마다 아이콘을 두고, 고른 값은 연두 바탕과 체크로 표시한다.
 * 바깥 · 뒤로 가기는 [onDismiss]. [footer] 는 선택지 아래(완료 · 철회 등).
 */
@Composable
internal fun OptionSheet(title: String, options: List<Pair<DrawableResource, String>>, selected: Int?, onDismiss: () -> Unit,
    help: String? = null, footer: @Composable ColumnScope.() -> Unit = {}, onSelect: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ActionSheet(onDismiss = onDismiss) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                options.forEachIndexed { index, (icon, label) ->
                    val on = index == selected
                    Row(Modifier.fillMaxWidth().clip(AppShapes.Control).background(if (on) FreshSoft else AppColors.Background)
                        .selectable(on, role = Role.RadioButton) { onSelect(index) }.heightIn(min = AppSpacing.ControlMinHeight)
                        .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                        Icon(painterResource(icon), null, Modifier.size(AppSizes.IconSmall), tint = AppColors.FreshInk)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                        if (on) Icon(painterResource(Res.drawable.ic_check_done), null, Modifier.size(AppSizes.IconSmall), tint = AppColors.FreshInk)
                    }
                }
            }
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
            footer()
        }
    }
}

/** 고른 선택지 바탕(연두). 글자 · 아이콘은 [AppColors.FreshInk] */
private val FreshSoft = Color(0xFFE3F5D3)
