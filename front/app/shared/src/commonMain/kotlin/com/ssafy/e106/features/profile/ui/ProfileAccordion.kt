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
import com.ssafy.e106.core.ui.components.InlineError
import com.ssafy.e106.core.ui.components.SkeletonBone
import com.ssafy.e106.core.ui.components.SkeletonGroup
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
    /** 종교 동의를 여기서 한 번 받기(확인 시트를 연다) */
    val onRequestReligionConsent: () -> Unit = {},
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
 * 아래 "다음"은 펼친 섹션을 저장하고 다음 섹션을 편다.
 * 앞 섹션이 저장되기 전의 뒤 섹션([reachable] 다음)도 눌러서 미리 펼쳐 볼 수 있다. 그때는 입력이 잠겨 있고 아래 버튼이 "○○부터 저장해 주세요"로 막힌다.
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
    onBack: (() -> Unit)? = null,
) {
    // 펼친 줄을 다시 누르면 모두 접힌다. 저장으로 다음 섹션이 열리면(page 가 바뀌면) 다시 펼친 상태로 돌아온다
    var collapsed by remember(page) { mutableStateOf(false) }
    // 머리줄을 눌러 펼치거나 접으면(다음으로 넘어가도) 맨 위로 올린다. 아래쪽 섹션을 열어도 제목부터 보이게
    // 아직 차례가 아닌 섹션을 미리 펼쳐 본 것(한 번에 하나). 저장으로 차례가 넘어가면 접는다
    var peek by remember(page, reachable) { mutableStateOf<ProfilePage?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(page, collapsed, peek) { scroll.animateScrollTo(0) }
    // 성별은 기본 정보의 마지막 칸이다. 처음 작성할 때 성별을 골라 칸이 다 채워지면 "다음"을 누른 것처럼 바로 확인 시트를 띄운다.
    // 칸이 덜 채워졌으면 아무 일도 하지 않는다(나중에 닉네임을 마저 적는다고 저절로 넘어가지 않는다)
    var genderPicked by remember { mutableStateOf(false) }
    LaunchedEffect(genderPicked, basics.gender) {
        if (!genderPicked) return@LaunchedEffect
        genderPicked = false
        if (page == ProfilePage.Basics && reachable == ProfilePage.Basics && basics.canContinue && !basics.saving) onSaveBasics()
    }
    // 생활 습관도 같다: 처음 작성할 때 흡연 · 음주를 둘 다 고르면 "다음"을 누른 것처럼 바로 저장하고 다음 섹션을 연다
    var lifestylePicked by remember { mutableStateOf(false) }
    LaunchedEffect(lifestylePicked, lifestyle.smoking, lifestyle.drinking) {
        if (!lifestylePicked) return@LaunchedEffect
        lifestylePicked = false
        if (page == ProfilePage.Lifestyle && reachable == ProfilePage.Lifestyle && lifestyle.canContinue) onSaveLifestyle()
    }
    // 선택 프로필: 자기소개를 적어 둔 채로 종교(마지막 칸)를 고르면 "다음"을 누른 것처럼 저장한다.
    // 자기소개가 비어 있으면 넘기지 않는다(종교를 먼저 고르고 소개를 쓰려는 사람을 내보내지 않게)
    var religionPicked by remember { mutableStateOf(false) }
    LaunchedEffect(religionPicked, optional.details.religion) {
        if (!religionPicked) return@LaunchedEffect
        religionPicked = false
        if (page == ProfilePage.Optional && reachable == ProfilePage.Optional && optional.details.introduction.isNotBlank() &&
            optional.details.religion != null && optional.canSave && !optional.details.saving) optional.onSave()
    }
    // 머리줄은 기본 · 생활 저장 중에만 잠근다(선택 프로필 다시 불러오기는 들어올 때 · 사진 선택에서 돌아올 때마다 돌아서 막으면 깜빡인다)
    val busy = basics.saving || lifestyle.saving
    val locked = busy || (page == ProfilePage.Optional && optional.details.saving)   // 선택 프로필 저장 중 섹션을 바꾸면 저장 결과가 버려진다
    Surface(Modifier.fillMaxSize(), color = AppColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AppTopBar(stringResource(Res.string.profile_flow_title), onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll).padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                ) {
                    Sections.forEach { (section, title) ->
                        val ahead = section > reachable
                        Section(title, open = if (peek != null) section == peek else section == page && !collapsed, enabled = !locked,
                            onToggle = { when {
                                ahead -> peek = if (peek == section) null else section
                                peek != null -> { peek = null; if (section == page) collapsed = false else onOpen(section) }
                                section == page -> collapsed = !collapsed
                                else -> onOpen(section)
                            } }) {
                            when (section) {
                                ProfilePage.Basics -> {
                                    // 사진 동의는 가입 전 동의 시트에서 필수로 받으므로 여기엔 동의 칸도, 동의 없음 안내도 없다(사진 등록 자체는 선택)
                                    ProfilePhotoBlock(optional.details, null,
                                        optional.onChoosePhoto, optional.onDeletePhoto, optional.photoContent, optional.consentInfo, optional.canChangeConsent)
                                    HorizontalDivider(color = AppColors.BorderSubtle)
                                    ProfileBasicsFields(basics, onNickname, onBirthDate, { onGender(it); genderPicked = true })
                                }
                                // 미리 펼쳐 본 섹션은 고를 수 없게 잠근다(저장 중과 같은 잠금을 쓴다)
                                ProfilePage.Lifestyle -> ProfileLifestyleFields(if (ahead) lifestyle.copy(saving = true) else lifestyle,
                                    { onSmoking(it); lifestylePicked = true }, { onDrinking(it); lifestylePicked = true })
                                else -> {
                                    val details = if (ahead) optional.details.copy(interactionEnabled = false) else optional.details
                                    if (!ahead) optional.status()
                                    // 동의는 가입 전 동의 시트 한 곳에서만 받는다. 자기소개는 필수 동의라 안내가 없고, 선택인 종교만 동의하지 않았으면 잠근 채 알려 준다
                                    ProfileIntroductionBlock(details, optional.onIntroduction, null, optional.consentInfo, optional.canChangeConsent)
                                    HorizontalDivider(color = AppColors.BorderSubtle)
                                    ProfileReligionBlock(details, { optional.onReligion(it); religionPicked = true }, null, optional.consentInfo, optional.canChangeConsent,
                                        consentMissing = optional.consentsLoaded && !optional.details.religionConsent,
                                        onRequestConsent = optional.onRequestReligionConsent.takeIf { !ahead })
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
            // 저장 실패: 입력은 그대로 두고 버튼 바로 위에 한 줄로 알린다. 버튼은 "다시 시도"가 되고, 다시 저장하거나 입력을 고치면 원래대로 돌아온다
            val failure = error?.takeIf { !saving }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()
                .padding(horizontal = screenGutter(), vertical = AppSpacing.Large), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                failure?.let { InlineError(it) }
                // 미리 펼쳐 본 동안에는 지금 저장할 섹션을 알려 주는 막힌 버튼
                val current = Sections.firstOrNull { it.first == reachable }
                if (peek != null) { if (current != null) AppButton(stringResource(Res.string.profile_section_blocked, stringResource(current.second)),
                    Modifier.fillMaxWidth(), enabled = false, onClick = {}) }
                // "다음"은 펼친 섹션의 칸이 다 채워졌을 때만 나온다(막힌 버튼을 미리 보여 주지 않는다). 저장 중 · 실패 뒤에는 계속 보인다
                else if ((enabled && !collapsed) || saving || failure != null) AppButton(stringResource(if (failure != null) Res.string.state_retry else Res.string.profile_continue), Modifier.fillMaxWidth(),
                    enabled = enabled && !saving && !collapsed, loading = saving,
                    loadingText = stringResource(Res.string.profile_saving), onClick = save)
            }
        }
    }
}

/** 불러오는 중: 실제 화면 틀(위쪽 줄 · 섹션 머리줄 · 항목 이름 · 아래 "다음")은 그대로 두고 값 자리만 회색으로 둔다 */
@Composable
internal fun ProfileAccordionSkeleton(onBack: (() -> Unit)? = null) {
    ProfileSkeletonFrame(stringResource(Res.string.profile_flow_title), null, onBack, spacing = AppSpacing.Small) {
        Sections.forEach { (section, title) ->
            Section(title, open = section == ProfilePage.Basics, enabled = false, onToggle = {}) {
                SkeletonGroup(spacing = AppSpacing.Section) {
                    BoneField(Res.string.profile_photo_title) { PhotoBoneWide() }
                    BoneField(Res.string.profile_nickname)
                    BoneField(Res.string.profile_birth_date)
                    BoneField(Res.string.profile_gender) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            repeat(2) { SkeletonBone(Modifier.weight(1f), height = AppSpacing.ControlMinHeight, shape = AppShapes.Control) }
                        }
                    }
                }
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
