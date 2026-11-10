package com.ssafy.e106.features.profile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.profile.data.OptionalConsentType
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 선택 프로필 쪽(사진 · 자기소개 · 종교) 값과 동작. 선택 프로필 ViewModel 이 채우고, 프리뷰는 빈 동작으로 만든다 */
class ProfileOptionalSlot(
    val details: ProfileDetailsState,
    val canSave: Boolean,
    /** 서버 동의 상태를 불러왔는지. 불러오기 전엔 동의가 false 로 시작하므로 동의 칸을 띄우지 않는다(깜빡임) */
    val consentsLoaded: Boolean = true,
    val onChoosePhoto: () -> Unit = {},
    val onDeletePhoto: () -> Unit = {},
    val onIntroduction: (String) -> Unit = {},
    val onReligion: (ProfileReligion) -> Unit = {},
    val onReligionConsent: (Boolean) -> Unit = {},
    val onPhotoConsent: (Boolean) -> Unit = {},
    val onIntroductionConsent: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
    val photoContent: @Composable () -> Unit = {},
    val consentInfo: @Composable (OptionalConsentType) -> Unit = {},
    val canChangeConsent: (OptionalConsentType) -> Boolean = { true },
    val status: @Composable () -> Unit = {},
)

private val Sections = listOf(
    ProfilePage.Basics to Res.string.profile_section_basics,
    ProfilePage.Lifestyle to Res.string.profile_section_lifestyle,
    ProfilePage.Optional to Res.string.profile_section_optional,
)
private val OpenBorder = Color(0xFFC9C9C9)   // 펼친 섹션 테두리: 가입 준비 허브와 같은 연한 회색

/**
 * 가입 중 프로필 입력(시안 05): 기본 정보 · 생활 습관 · 선택 프로필을 한 화면 아코디언으로.
 * 완료 · 대기 표시는 없고 모든 줄이 같은 모양이다. 머리줄을 누르면 그 섹션이 펼쳐지고(다른 섹션은 접힘), 펼친 줄을 다시 누르면 접힌다.
 * 아래 "다음"은 펼친 섹션을 저장하고 다음 섹션을 편다. 앞 섹션이 저장되기 전에는 뒤 섹션을 열 수 없다([reachable]).
 * 사진은 기본 정보 맨 위(닉네임 위)에 둔다. 사진 · 자기소개 · 종교 동의는 약관 시트에서 받으므로 동의 칸이 없다.
 * 예외로 아직 동의가 없을 때(시트가 바뀌기 전에 동의했거나 문서가 없어 시트에서 못 받음, 종교는 시트에서 선택이라 안 고름)만 칸을 보여 준다.
 */
@Composable
fun ProfileAccordionScreen(
    page: ProfilePage,
    reachable: ProfilePage,
    onOpen: (ProfilePage) -> Unit,
    basics: ProfileBasicsState,
    onNickname: (String) -> Unit, onBirthDate: (String) -> Unit, onGender: (ProfileGender) -> Unit, onSaveBasics: () -> Unit,
    lifestyle: ProfileLifestyleState,
    onSmoking: (ProfileSmoking) -> Unit, onDrinking: (ProfileDrinking) -> Unit, onSaveLifestyle: () -> Unit,
    optional: ProfileOptionalSlot,
    error: String? = null,
) {
    // 펼친 줄을 다시 누르면 모두 접힌다. 저장으로 다음 섹션이 열리면(page 가 바뀌면) 다시 펼친 상태로 돌아온다
    var collapsed by remember(page) { mutableStateOf(false) }
    // 머리줄을 눌러 펼치거나 접으면(다음으로 넘어가도) 맨 위로 올린다. 아래쪽 섹션을 열어도 제목부터 보이게
    val scroll = rememberScrollState()
    LaunchedEffect(page, collapsed) { scroll.animateScrollTo(0) }
    // 머리줄은 기본 · 생활 저장 중에만 잠근다(선택 프로필 다시 불러오기는 들어올 때 · 사진 선택에서 돌아올 때마다 돌아서 막으면 깜빡인다)
    val busy = basics.saving || lifestyle.saving
    val locked = busy || (page == ProfilePage.Optional && optional.details.saving)   // 선택 프로필 저장 중 섹션을 바꾸면 저장 결과가 버려진다
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AppTopBar(stringResource(Res.string.profile_flow_title))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                ) {
                    error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = AppColors.Error) }
                    Sections.forEach { (section, title) ->
                        Section(title, open = section == page && !collapsed, enabled = !locked && section <= reachable,
                            onToggle = { if (section == page) collapsed = !collapsed else onOpen(section) }) {
                            when (section) {
                                ProfilePage.Basics -> {
                                    // 동의는 약관 시트에서 받는다. 아직 없을 때만(시트 변경 전 동의 · 문서 없음) 여기서 칸을 보여 준다
                                    ProfilePhotoBlock(optional.details, optional.onPhotoConsent.takeIf { optional.consentsLoaded && !optional.details.photoConsent },
                                        optional.onChoosePhoto, optional.onDeletePhoto, optional.photoContent, optional.consentInfo, optional.canChangeConsent)
                                    HorizontalDivider(color = AppColors.BorderSubtle)
                                    ProfileBasicsFields(basics, onNickname, onBirthDate, onGender)
                                }
                                ProfilePage.Lifestyle -> ProfileLifestyleFields(lifestyle, onSmoking, onDrinking)
                                else -> {
                                    optional.status()
                                    ProfileIntroductionBlock(optional.details, optional.onIntroduction,
                                        optional.onIntroductionConsent.takeIf { optional.consentsLoaded && !optional.details.introductionConsent }, optional.consentInfo, optional.canChangeConsent)
                                    HorizontalDivider(color = AppColors.BorderSubtle)
                                    ProfileReligionBlock(optional.details, optional.onReligion,
                                        optional.onReligionConsent.takeIf { optional.consentsLoaded && !optional.details.religionConsent }, optional.consentInfo, optional.canChangeConsent)
                                }
                            }
                        }
                    }
                }
            }
            val (enabled, save) = when (page) {
                ProfilePage.Basics -> basics.canContinue to onSaveBasics
                ProfilePage.Lifestyle -> lifestyle.canContinue to onSaveLifestyle
                else -> optional.canSave to optional.onSave
            }
            val saving = if (page == ProfilePage.Optional) optional.details.saving else busy
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppButton(stringResource(Res.string.profile_continue),
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = screenGutter(), vertical = AppSpacing.Large),
                    enabled = enabled && !saving && !collapsed, loading = saving,
                    loadingText = stringResource(Res.string.profile_saving), onClick = save)
            }
        }
    }
}

@Composable
private fun Section(title: StringResource, open: Boolean, enabled: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = stringResource(if (open) Res.string.profile_section_expanded else Res.string.profile_section_collapsed)
    val turn by animateFloatAsState(if (open) 90f else -90f)   // 왼쪽 화살표를 돌려 접힘 ↓ · 펼침 ↑
    Surface(shape = RoundedCornerShape(14.dp), color = AppColors.Background,
        border = BorderStroke(1.5.dp, if (open) OpenBorder else AppColors.BorderSubtle), shadowElevation = if (open) 2.dp else 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(horizontal = AppSpacing.Medium, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f), fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                    style = if (open) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                Icon(painterResource(Res.drawable.ic_arrow_back), null, Modifier.size(18.dp).rotate(turn), tint = AppColors.TextSecondary)
            }
            AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(start = AppSpacing.Medium, end = AppSpacing.Medium, bottom = AppSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section), content = content)
            }
        }
    }
}
