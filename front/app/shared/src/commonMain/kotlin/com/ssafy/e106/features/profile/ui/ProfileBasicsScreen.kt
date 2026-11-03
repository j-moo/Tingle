package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    val editable = !state.saving
    Surface(modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AppTopBar(stringResource(Res.string.profile_basics_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = ProfileFormMaxWidth).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text(stringResource(Res.string.profile_heading),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(Res.string.profile_intro),
                            style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
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
                        Text(stringResource(Res.string.profile_gender), style = MaterialTheme.typography.labelLarge,
                            color = if (editable) AppColors.TextPrimary else AppColors.TextDisabled)
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppButton(
                    text = stringResource(Res.string.profile_continue),
                    modifier = Modifier.widthIn(max = ProfileFormMaxWidth).fillMaxWidth()
                        .padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    enabled = state.canContinue && editable, loading = state.saving,
                    loadingText = stringResource(Res.string.profile_saving),
                    onClick = onContinue,
                )
            }
        }
    }
}
