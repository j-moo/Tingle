package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ssafy.e106.core.ui.components.ActionSheet
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppShapes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 생년월일 · 성별 확인: 저장하면 직접 바꿀 수 없는 두 값을 크게 다시 보여 주고 확인받는 아래 시트.
 * 바꿀 수 없다는 것과 만 19세 미만이면 탈퇴 처리된다는 것은 줄이지 않고 따로 적는다. 바깥을 누르거나 뒤로 가면 "다시 수정"과 같다.
 */
@Composable
internal fun ProfileBasicsConfirmSheet(birthDate: String, gender: String, onConfirm: () -> Unit, onEdit: () -> Unit) {
    Dialog(onDismissRequest = onEdit, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ActionSheet(onDismiss = onEdit) {
            Text(stringResource(Res.string.profile_confirm_basics_title), style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
            // 확인할 두 값: 이름은 작게, 값은 크게
            Column(Modifier.fillMaxWidth().background(AppColors.SurfaceMuted, AppShapes.Card).padding(AppSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                ConfirmValue(stringResource(Res.string.profile_confirm_basics_birth), birthDate)
                ConfirmValue(stringResource(Res.string.profile_confirm_basics_gender), gender)
            }
            Text(stringResource(Res.string.profile_confirm_basics_locked), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            ConfirmNote(Res.drawable.ic_state_circle_alert, stringResource(Res.string.profile_confirm_basics_underage), danger = true)
            AppButton(stringResource(Res.string.profile_confirm_basics_save), Modifier.fillMaxWidth().padding(top = AppSpacing.Small), onClick = onConfirm)
            AppButton(stringResource(Res.string.profile_confirm_basics_edit), Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary, onClick = onEdit)
        }
    }
}

@Composable
private fun ConfirmValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
    }
}

/** 아이콘 + 한 문장. [danger] 는 계정에 영향이 있는 안내(연한 빨강 상자) */
@Composable
private fun ConfirmNote(icon: DrawableResource, text: String, danger: Boolean) {
    val ink = if (danger) AppColors.OnErrorContainer else AppColors.TextSecondary
    Row((if (danger) Modifier.fillMaxWidth().background(AppColors.ErrorContainer, AppShapes.Control).padding(AppSpacing.Medium) else Modifier.fillMaxWidth()),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(18.dp), tint = ink)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ink)
    }
}
