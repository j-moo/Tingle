package com.ssafy.e106

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.AppBottomBar
import com.ssafy.e106.core.ui.components.AppButton
import com.ssafy.e106.core.ui.components.AppButtonVariant
import com.ssafy.e106.core.ui.components.AppCheckbox
import com.ssafy.e106.core.ui.components.AppDialog
import com.ssafy.e106.core.ui.components.AppRadio
import com.ssafy.e106.core.ui.components.AppSwitch
import com.ssafy.e106.core.ui.components.AppTabRow
import com.ssafy.e106.core.ui.components.AppTextField
import com.ssafy.e106.core.ui.components.AppTopBar
import com.ssafy.e106.core.ui.components.MessageBubble
import com.ssafy.e106.core.ui.components.ProfilePlaceholder
import com.ssafy.e106.core.ui.components.ServiceRow
import com.ssafy.e106.core.ui.components.StatusCard
import com.ssafy.e106.core.ui.theme.AppColors
import com.ssafy.e106.core.ui.theme.AppSpacing
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.core.ui.theme.screenPaddingValues
import kotlinx.coroutines.delay

/** 공통 컴포넌트 미리보기. 실제 화면이 생기면 NavHost 로 바뀐다. 문구는 미리보기용이라 리소스로 빼지 않았다. */
@Composable
fun ComponentGallery() {
    AppTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }   // 본문 탭 3개
        var nav by rememberSaveable { mutableIntStateOf(0) }   // 하단 탭 4개
        var nickname by rememberSaveable { mutableStateOf("") }
        var agree by rememberSaveable { mutableStateOf(false) }
        var notify by rememberSaveable { mutableStateOf(true) }
        var choice by rememberSaveable { mutableIntStateOf(0) }
        var dialog by rememberSaveable { mutableStateOf<Boolean?>(null) } // null 닫힘, false 확인, true 위험
        var saving by rememberSaveable { mutableStateOf(false) }
        // 미리보기용 가짜 저장: 1.5초 뒤 완료 처리
        LaunchedEffect(saving) { if (saving) { delay(1500); saving = false; dialog = null } }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                AppTopBar("공통 컴포넌트", onBack = {}, actionText = "메뉴", onAction = {})
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(screenPaddingValues()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Section),
                ) {
                    StatusCard("상태 안내", "현재 상태와 다음 행동을 짧게 안내합니다.")

                    Section("입력") {
                        AppTextField(
                            value = nickname, onValueChange = { nickname = it }, label = "닉네임", required = true,
                            placeholder = "예: Tingle User", supportingText = "다른 회원에게 표시되는 이름이에요.",
                            errorText = if (nickname.length == 1) "2자 이상 입력해 주세요." else null, enabled = !saving,
                        )
                        AppCheckbox(agree, { agree = it }, "이용약관에 동의합니다")
                        AppCheckbox(true, {}, "비활성 체크", enabled = false)
                        AppSwitch(notify, { notify = it }, "매칭 알림")
                        AppSwitch(false, {}, "비활성 스위치", enabled = false)
                        Column(Modifier.selectableGroup()) {
                            AppRadio(choice == 0, { choice = 0 }, "선택 항목 1")
                            AppRadio(choice == 1, { choice = 1 }, "선택 항목 2")
                        }
                    }

                    Section("탭") { AppTabRow(listOf("제안", "채팅", "내 정보"), tab, { tab = it }) }

                    Section("버튼") {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            AppButton("기본", Modifier.weight(1f), onClick = { dialog = false })
                            AppButton("보조", Modifier.weight(1f), variant = AppButtonVariant.Secondary, onClick = {})
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            AppButton("위험", Modifier.weight(1f), variant = AppButtonVariant.Danger, onClick = { dialog = true })
                            AppButton("선택됨", Modifier.weight(1f), variant = AppButtonVariant.Selected, onClick = {})
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            AppButton("비활성", Modifier.weight(1f), enabled = false, onClick = {})
                            AppButton("로딩", Modifier.weight(1f), loading = true, onClick = {})
                        }
                    }

                    Section("목록") {
                        ServiceRow("알림 설정", detail = "매칭·채팅 알림을 켜고 끕니다", onClick = {})
                        ServiceRow("앱 버전", detail = "0.1.0")
                    }

                    Section("채팅·프로필") {
                        MessageBubble("안녕하세요. 만나서 반가워요!", "오후 2:30")
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            MessageBubble("네, 반가워요.", "오후 2:31")
                        }
                        ProfilePlaceholder("프로필 사진\n등록된 사진이 없어요")
                    }

                }
                AppBottomBar(listOf("홈", "제안", "채팅", "내 정보"), nav, { nav = it })
            }
        }

        dialog?.let { danger ->
            AppDialog(
                title = "계속 진행할까요?",
                description = "선택한 작업을 진행해요. 내용을 확인한 뒤 결정해 주세요.",
                confirmText = if (danger) "삭제하기" else "저장하기",
                dismissText = "돌아가기",
                danger = danger,
                confirmLoading = saving,
                onConfirm = { saving = true },
                onDismiss = { dialog = null },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
        content()
    }
}
