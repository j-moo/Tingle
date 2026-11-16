package com.ssafy.e106.features.profile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
import com.ssafy.e106.core.ui.theme.AppSizes
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.features.chat.ui.decodeChatImage
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 사진 위에 올리는 이름 · 칩 값. 모르는 나이 · 성별은 null 이라 그 칩만 빠진다 */
internal data class ProfileHeroInfo(val nickname: String, val age: Int?, val gender: ProfileGender?)
internal enum class HeroPhoto { Empty, Loading, Ready, Failed }

internal fun String?.gender() = ProfileGender.entries.find { it.code() == this }
internal fun ProfileResponse.heroInfo(today: Triple<Int, Int, Int> = todayKst()) =
    ProfileHeroInfo(nickname.orEmpty(), birthDate?.let { internationalAge(it, today) }, gender.gender())

/**
 * 프로필 위쪽(A안): 사진이 상태 바까지 꽉 차고, 위에 ‹ · 제목 · [action], 왼쪽 아래에 닉네임과 나이 · 성별 칩, 오른쪽 아래에 [corner].
 * 사진이 없거나 못 받으면 그라데이션 바탕에 닉네임 첫 글자를 크게 두고, 못 받았으면 작은 "다시 시도"([onRetryPhoto])를 둔다.
 * [preview] 는 수정 화면용: 닉네임이 아래 입력칸을 따라 바뀌는 미리보기라 화면 리더에서 뺀다(입력칸이 이미 읽힌다).
 */
@Composable
internal fun ProfileHero(
    info: ProfileHeroInfo, photo: HeroPhoto, image: ImageBitmap?, onBack: (() -> Unit)?,
    title: String = "", heightFraction: Float = 0.6f, preview: Boolean = false,
    onRetryPhoto: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    corner: (@Composable () -> Unit)? = null,
) {
    LightStatusBarIcons()
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * heightFraction
    Box(Modifier.fillMaxWidth().height(height).background(HeroEmpty)) {
        when {
            photo == HeroPhoto.Ready && image != null -> Image(image, stringResource(Res.string.profile_hero_photo),
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            photo == HeroPhoto.Loading -> SkeletonGroup(label = stringResource(Res.string.chat_partner_photo_loading)) {
                SkeletonBone(height = height, shape = RectangleShape)
            }
            else -> Text(info.nickname.trim().take(1), Modifier.align(Alignment.Center).clearAndSetSemantics {},
                fontSize = 120.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.85f))
        }
        // 위 · 아래 그라데이션: 밝은 사진에서도 흰 글자와 버튼이 보이게
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.28f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.48f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))))
        if (photo == HeroPhoto.Failed && onRetryPhoto != null) Box(Modifier.align(Alignment.Center).padding(top = 168.dp)
            .clip(CircleShape).clickable(role = Role.Button, onClick = onRetryPhoto).minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center) { HeroChip(stringResource(Res.string.state_retry)) }
        // 사진은 상태 바 밑까지 깔고, 버튼 · 제목만 상태 바 아래로 내린다
        Box(Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .heightIn(min = 56.dp).padding(horizontal = AppSpacing.Small)) {
            if (onBack != null) HeroRoundButton(Res.drawable.ic_arrow_back, stringResource(Res.string.button_previous), onBack,
                Modifier.align(Alignment.CenterStart))
            if (title.isNotEmpty()) Text(title, Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium.copy(shadow = TextShade), color = Color.White,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (action != null) Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = AppSpacing.Large, vertical = AppSpacing.Medium), verticalAlignment = Alignment.Bottom) {
            // 닉네임 · 칩은 한 덩어리로 읽는다(예: "하늘, 만 27세, 여성")
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                Text(info.nickname, if (preview) Modifier.clearAndSetSemantics {} else Modifier,
                    fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = TextShade), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.age != null || info.gender != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.age?.let { HeroChip(stringResource(Res.string.profile_view_age, it)) }
                    info.gender?.let { HeroChip(stringResource(if (it == ProfileGender.Male) Res.string.profile_male else Res.string.profile_female),
                        icon = if (it == ProfileGender.Male) Res.drawable.ic_gender_male else Res.drawable.ic_gender_female) }
                }
            }
            corner?.invoke()
        }
    }
}

/** 사진 위 유리 원 버튼(보이는 원 34dp, 터치 영역 48dp) */
@Composable
internal fun HeroRoundButton(icon: DrawableResource, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.size(AppSizes.TouchTarget).clip(CircleShape).clickable(enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).background(HeroGlass, CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), description, Modifier.size(AppSizes.IconSmall), tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}

/** 사진 위 칩: 검정 35% 바탕이라 밝은 사진에서도 흰 글자가 읽힌다 */
@Composable
private fun HeroChip(text: String, modifier: Modifier = Modifier, icon: DrawableResource? = null) {
    Row(modifier.clip(CircleShape).background(HeroGlass).border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
        .padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(14.dp), tint = Color.White)
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 내 사진(받은 바이트)을 사진 칸 상태로: 받는 중 · 디코딩 중은 뼈대, 못 받았거나 못 읽으면 실패 */
@Composable
internal fun rememberHeroPhoto(bytes: ByteArray?, hasPhoto: Boolean, failed: Boolean): Pair<HeroPhoto, ImageBitmap?> {
    val decoded by produceState<Pair<ByteArray?, ImageBitmap?>>(null to null, bytes) { value = bytes to bytes?.let { decodeChatImage(it) } }
    return when {
        bytes != null && decoded.first !== bytes -> HeroPhoto.Loading to null
        bytes != null -> (if (decoded.second != null) HeroPhoto.Ready else HeroPhoto.Failed) to decoded.second
        !hasPhoto -> HeroPhoto.Empty to null
        failed -> HeroPhoto.Failed to null
        else -> HeroPhoto.Loading to null
    }
}

private val HeroGlass = Color.Black.copy(alpha = 0.35f)
private val HeroEmpty = Brush.linearGradient(listOf(Color(0xFFB9C8D6), Color(0xFFC4A796)))
private val TextShade = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 4f)
