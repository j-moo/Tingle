package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 생활 칩 값. 모르는 값은 null 이라 그 칩만 빠진다 */
internal data class ProfileLifestyle(val smoking: ProfileSmoking? = null, val drinking: ProfileDrinking? = null, val religion: ProfileReligion? = null)

internal fun String?.smoking() = ProfileSmoking.entries.find { it.code() == this }
internal fun String?.drinking() = ProfileDrinking.entries.find { it.code() == this }
/** 보기 화면 값: 종교는 공개하지 않음 · 미입력 · 알 수 없는 값이면 숨긴다 */
internal fun lifestyleOf(smoking: String?, drinking: String?, religion: String?) =
    ProfileLifestyle(smoking.smoking(), drinking.drinking(), religion.religion()?.takeIf { it != ProfileReligion.Private })
internal fun ProfileResponse.lifestyle() = lifestyleOf(smokingStatus, drinkingFrequency, religion)

internal fun ProfileSmoking.icon() = if (this == ProfileSmoking.NonSmoker) Res.drawable.ic_cigarette_off else Res.drawable.ic_cigarette
internal fun ProfileDrinking.chipLabel(): StringResource = when (this) {
    ProfileDrinking.Never -> Res.string.profile_drinking_never_chip
    ProfileDrinking.SpecialOccasions -> Res.string.profile_drinking_special_chip
    ProfileDrinking.MonthlyOrLess -> Res.string.profile_drinking_monthly_less_chip
    ProfileDrinking.MonthlyTwoToFour -> Res.string.profile_drinking_monthly_chip
    ProfileDrinking.WeeklyTwoToThree -> Res.string.profile_drinking_weekly_chip
    ProfileDrinking.WeeklyFourOrMore -> Res.string.profile_drinking_often_chip
}

/** 보기 화면 생활 칩 줄(값만 있는 아이콘 칩). 화면 리더는 줄 전체를 한 문장으로 읽는다 */
@Composable
internal fun ProfileLifestyleChips(values: ProfileLifestyle, modifier: Modifier = Modifier) {
    val chips = listOfNotNull(
        values.smoking?.let { Triple(it.icon(), stringResource(it.label()), stringResource(Res.string.profile_chips_smoking, stringResource(it.label()))) },
        values.drinking?.let { Triple(Res.drawable.ic_wine, stringResource(it.chipLabel()), stringResource(Res.string.profile_chips_drinking, stringResource(it.label()))) },
        values.religion?.let { Triple(Res.drawable.ic_church, stringResource(it.label()), stringResource(Res.string.profile_chips_religion, stringResource(it.label()))) })
    if (chips.isEmpty()) return
    FlowRow(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = chips.joinToString(", ") { it.third } },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (icon, text) -> LifestyleChip(icon, text) }
    }
}

/** 흰 바탕 · 옅은 테두리 캡슐. 아이콘은 연두 16dp, 글자 12sp. 값이 없는 수정 칩은 [dashed] 점선 */
@Composable
internal fun LifestyleChip(icon: DrawableResource, text: String, modifier: Modifier = Modifier, trailing: DrawableResource? = null, dashed: Boolean = false) {
    val shape = modifier.clip(CircleShape).background(if (dashed) Color.Transparent else AppColors.Background)
    Row((if (dashed) shape.drawBehind {
        drawRoundRect(AppColors.BorderControl, cornerRadius = CornerRadius(size.height / 2),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    } else shape.border(1.dp, AppColors.BorderSubtle, CircleShape))
        .padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = AppColors.FreshInk)
        Text(text, fontSize = 12.sp, color = AppColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        if (trailing != null) Icon(painterResource(trailing), null, Modifier.size(16.dp), tint = AppColors.TextSecondary)
    }
}

/**
 * 수정 화면 칩: 누르면 시트가 열린다(▾). 값이 없으면 점선 칩에 "흡연 선택" 같은 글자를 쓰고, 있으면 [description]("흡연: 비흡연, 바꾸기")으로 읽힌다.
 * 터치 영역은 48dp 이상이다.
 */
@Composable
internal fun EditChip(icon: DrawableResource, text: String, description: String?, enabled: Boolean,
    trailing: DrawableResource = Res.drawable.ic_chevron_down, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(enabled, role = Role.Button, onClick = onClick).minimumInteractiveComponentSize()
        .semantics { contentDescription = description ?: text }.alpha(if (enabled) 1f else 0.5f), contentAlignment = Alignment.Center) {
        LifestyleChip(icon, text, Modifier.clearAndSetSemantics {}, trailing, dashed = description == null)
    }
}

/** 소개 카드(유리 카드, 제목 "소개"). 최대 200자라 접지 않고 다 보여 준다. [action] 은 제목 오른쪽(수정 화면의 "고치기") */
@Composable
internal fun ProfileIntroCard(text: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().glass(AppShapes.Card, AppColors.SurfaceMuted).padding(AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.profile_intro_card), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
            action?.invoke()
        }
        Text(text?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_partner_intro_empty), style = MaterialTheme.typography.bodyLarge,
            color = if (text.isNullOrBlank()) AppColors.TextSecondary else AppColors.TextPrimary)
    }
}
