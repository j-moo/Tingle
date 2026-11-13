package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.core.ui.components.TagLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppRadio
import com.ssafy.e106.core.ui.components.AppTextField
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.StatusCard
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.screenGutter
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import com.ssafy.e106.resources.Res
import com.ssafy.e106.resources.profile_basics_title
import com.ssafy.e106.resources.profile_birth_date
import com.ssafy.e106.resources.profile_birth_date_help
import com.ssafy.e106.resources.profile_birth_date_placeholder
import com.ssafy.e106.resources.profile_continue
import com.ssafy.e106.resources.profile_female
import com.ssafy.e106.resources.profile_gender
import com.ssafy.e106.resources.profile_heading
import com.ssafy.e106.resources.profile_intro
import com.ssafy.e106.resources.profile_male
import com.ssafy.e106.resources.profile_nickname
import com.ssafy.e106.resources.profile_nickname_help
import com.ssafy.e106.resources.profile_nickname_placeholder
import com.ssafy.e106.resources.profile_notice_body
import com.ssafy.e106.resources.profile_notice_title
import com.ssafy.e106.resources.profile_saving
import org.jetbrains.compose.resources.stringResource

// 넓은 화면에서 한 줄 입력칸이 과하게 길어지지 않도록 이 화면의 본문 폭만 제한한다.
private val ProfileFormMaxWidth = 640.dp

/**
 * 프로필 최초 입력의 기본 정보 화면. 값과 클릭을 주고받을 뿐 저장/화면 이동을 실행하지 않는다.
 * 호출부는 필수값·닉네임 중복·나이 검증 후 canContinue를 전달한다.
 */
@Composable
fun ProfileBasicsScreen(
    state: ProfileBasicsState,
    onNicknameChange: (String) -> Unit,
    onBirthDateChange: (String) -> Unit,
    onGenderChange: (ProfileGender) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 가로+IME처럼 실제 남은 높이가 짧으면 제목/다음을 스크롤 안으로 옮긴다.
            // 입력 폼 자체는 같은 컴포지션 위치에 유지해 키보드 개폐로 포커스나 입력이 초기화되지 않는다.
            val compactHeight = maxHeight < 320.dp
            Column(Modifier.fillMaxSize()) {
                if (!compactHeight) AppTopBar(stringResource(Res.string.profile_basics_title))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = ProfileFormMaxWidth).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                    ) {
                        if (compactHeight) AppTopBar(stringResource(Res.string.profile_basics_title))
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            Text(stringResource(Res.string.profile_heading),
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(Res.string.profile_intro),
                                style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                        }
                        ProfileBasicsFields(state, onNicknameChange, onBirthDateChange, onGenderChange)
                        if (compactHeight) ProfileContinueButton(state, onContinue)
                    }
                }
                if (!compactHeight) ProfileContinueButton(state, onContinue)
            }
        }
    }
}

/** 닉네임 · 생년월일 · 성별 · 안내. 한 화면(위)과 가입 아코디언이 같이 쓴다 */
@Composable
internal fun ProfileBasicsFields(
    state: ProfileBasicsState,
    onNicknameChange: (String) -> Unit,
    onBirthDateChange: (String) -> Unit,
    onGenderChange: (ProfileGender) -> Unit,
) {
    val editable = !state.saving
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)) {
        AppTextField(
            value = state.nickname, onValueChange = onNicknameChange,
            label = stringResource(Res.string.profile_nickname), required = true,
            placeholder = stringResource(Res.string.profile_nickname_placeholder),
            supportingText = stringResource(Res.string.profile_nickname_help),
            errorText = state.nicknameError, enabled = editable,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        AppTextField(
            value = state.birthDateDigits,
            onValueChange = { value -> onBirthDateChange(value.filter { it in '0'..'9' }.take(8)) },
            label = stringResource(Res.string.profile_birth_date), required = true,
            placeholder = stringResource(Res.string.profile_birth_date_placeholder),
            supportingText = stringResource(Res.string.profile_birth_date_help),
            errorText = state.birthDateError, enabled = editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            visualTransformation = BirthDateVisualTransformation,
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            TagLabel(stringResource(Res.string.profile_gender), color = if (editable) AppColors.TextPrimary else AppColors.TextDisabled)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                ProfileGender.entries.forEach { gender ->
                    val selected = state.gender == gender
                    AppRadio(selected = selected, onSelect = { onGenderChange(gender) },
                        label = stringResource(if (gender == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female),
                        enabled = editable,
                        modifier = Modifier.weight(1f).background(
                            if (selected && editable) AppColors.SelectionContainer else AppColors.SurfaceMuted,
                            AppShapes.Control,
                        ),
                    )
                }
            }
            if (editable && state.genderError != null) {
                Text(state.genderError, style = MaterialTheme.typography.bodySmall, color = AppColors.Error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        StatusCard(stringResource(Res.string.profile_notice_title), stringResource(Res.string.profile_notice_body))
    }
}

/** 좁은 높이에서도 동일한 저장 잠금과 클릭 계약을 사용한다. 화면 배치만 호출부가 결정한다. */
@Composable
private fun ProfileContinueButton(state: ProfileBasicsState, onContinue: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AppButton(
            text = stringResource(Res.string.profile_continue),
            modifier = Modifier.widthIn(max = ProfileFormMaxWidth).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
            enabled = state.canContinue && !state.saving, loading = state.saving,
            loadingText = stringResource(Res.string.profile_saving),
            onClick = onContinue,
        )
    }
}
