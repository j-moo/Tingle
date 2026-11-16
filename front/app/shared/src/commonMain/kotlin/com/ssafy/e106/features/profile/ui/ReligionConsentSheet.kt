package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.core.ui.theme.glass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ssafy.e106.core.ui.components.ActionSheet
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 가입할 때 종교 동의(선택)를 하지 않은 사람에게 프로필 작성의 종교 칸 자리에 보여 주는 안내 상자.
 * 동의하면 종교를 고를 수 있고 이 상자는 다시 나오지 않는다. 동의하지 않아도 "다음"으로 넘어갈 수 있다.
 */
@Composable
internal fun ReligionConsentAsk(enabled: Boolean, onRequest: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(AppShapes.Card, AppColors.SurfaceMuted).padding(AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(stringResource(Res.string.religion_ask_title), style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
        Text(stringResource(Res.string.religion_ask_help), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        AppButton(stringResource(Res.string.religion_ask_button), Modifier.fillMaxWidth().padding(top = AppSpacing.ExtraSmall), enabled = enabled, onClick = onRequest)
    }
}

/** 동의 시트 제목과 설명 세 줄(저장 · 상대 공개 / 동의하지 않아도 불이익 없음 / 철회하면 삭제). 종교와 소개가 쓴다 */
internal fun consentSheetText(type: OptionalConsentType): Pair<StringResource, List<StringResource>> = when (type) {
    OptionalConsentType.PROFILE_INTRODUCTION -> Res.string.intro_sheet_title to
        listOf(Res.string.intro_sheet_use, Res.string.intro_sheet_free, Res.string.intro_sheet_withdraw)
    else -> Res.string.religion_sheet_title to
        listOf(Res.string.religion_sheet_use, Res.string.religion_sheet_free, Res.string.religion_sheet_withdraw)
}

/**
 * 선택 정보 동의 확인 시트. 무엇에 동의하는지 세 줄([lines])로 적고 전문 링크([document])를 둔다.
 * [agree] 를 눌러야 동의가 저장된다. 바깥 · 뒤로 가기 · [decline] 은 아무것도 저장하지 않는다.
 */
@Composable
internal fun ConsentSheet(title: StringResource, lines: List<StringResource>, busy: Boolean, onAgree: () -> Unit, onDismiss: () -> Unit,
    agree: StringResource = Res.string.religion_sheet_agree, decline: StringResource = Res.string.religion_sheet_decline,
    document: @Composable () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ActionSheet(onDismiss = onDismiss.takeIf { !busy }) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
            lines.forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
            document()
            AppButton(stringResource(agree), Modifier.fillMaxWidth(), loading = busy, onClick = onAgree)
            AppButton(stringResource(decline), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, enabled = !busy, onClick = onDismiss)
        }
    }
}
