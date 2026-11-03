package com.ssafy.e106.features.chat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 본인 메시지의 전송 상태만 표시한다. 재시도 시 동일 메시지 ID를 호출부에 돌려준다. */
@Composable
internal fun MessageDeliveryStatus(
    message: ConversationMessage,
    enabled: Boolean,
    onRecovery: (MessageRecoveryAction) -> Unit,
) {
    if (message.delivery == MessageDelivery.Sent) return
    val status = when (message.delivery) {
        MessageDelivery.Sending -> Res.string.chat_delivery_sending
        MessageDelivery.Failed -> Res.string.chat_delivery_failed
        MessageDelivery.Unknown -> Res.string.chat_delivery_unknown
        MessageDelivery.Checking -> Res.string.chat_delivery_checking
        MessageDelivery.Rejected -> Res.string.chat_delivery_rejected
        MessageDelivery.Sent -> return
    }
    Column(Modifier.fillMaxWidth().padding(top = AppSpacing.ExtraSmall),
        horizontalAlignment = Alignment.End) {
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
            color = if (message.delivery == MessageDelivery.Failed || message.delivery == MessageDelivery.Rejected)
                AppColors.Error else AppColors.TextSecondary)
        val action = when (message.delivery) {
            MessageDelivery.Failed -> MessageRecoveryAction.Retry
            MessageDelivery.Unknown -> MessageRecoveryAction.CheckResult
            else -> null
        }
        if (action != null) TextButton(enabled = enabled, onClick = { if (enabled) onRecovery(action) }) {
            Text(stringResource(if (action == MessageRecoveryAction.Retry)
                Res.string.chat_delivery_retry else Res.string.chat_delivery_check))
        }
    }
}
