package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.features.safety.data.SafetyNoticeItem
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 나에게만 보이는 AI 위험 안내 카드. 받은 사람에게는 보호 안내와 차단 진입을, 보낸 사람에게는 존중 안내를 보여 준다.
 * 자동 차단 · 신고는 하지 않는다. "안내 닫기"를 눌러야 서버에 표시 확인을 보낸다.
 */
@Composable
fun SafetyNoticeCard(notice: SafetyNoticeItem, onBlock: () -> Unit, onClose: () -> Unit) {
    val recipient = notice.audience == "RECIPIENT"
    val sender = notice.audience == "SENDER"
    Surface(color = AppColors.SurfaceMuted, shape = AppShapes.Control) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            Text(stringResource(when {
                recipient -> Res.string.safety_notice_protect
                sender -> Res.string.safety_notice_respect
                else -> Res.string.safety_notice_general
            }), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body(notice.type, notice.audience)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.safety_notice_help), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            if (recipient) TextButton(onClick = onBlock) { Text(stringResource(Res.string.safety_notice_block)) }
            TextButton(onClick = onClose) { Text(stringResource(Res.string.safety_notice_close)) }
        }
    }
}

// 서버는 보낸 사람 안내를 괴롭힘(HARASSMENT)에만 만든다. 대상이 없는 이전 기록이나 그 밖의 조합은 중립 문구로 보여 준다
private fun body(type: String, audience: String?): StringResource = when {
    audience == "SENDER" && type == "HARASSMENT" -> Res.string.safety_notice_harassment_sender
    audience != "RECIPIENT" -> Res.string.safety_notice_neutral
    type == "MONEY_REQUEST" -> Res.string.safety_notice_money
    type == "PERSONAL_INFO" -> Res.string.safety_notice_personal
    type == "OFF_PLATFORM" -> Res.string.safety_notice_off_platform
    else -> Res.string.safety_notice_harassment
}
