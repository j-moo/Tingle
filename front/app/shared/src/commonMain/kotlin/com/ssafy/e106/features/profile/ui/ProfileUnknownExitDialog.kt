package com.ssafy.e106.features.profile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 서버 결과 미확인과 저장 실패를 구분한다. 나가기를 요청 취소·되돌리기로 안내하지 않는다. */
@Composable
internal fun ProfileUnknownExitDialog(onDismiss: () -> Unit, onExit: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_unknown_exit_title)) },
        text = { Text(stringResource(Res.string.profile_unknown_exit_body)) },
        confirmButton = { TextButton(onClick = onExit) { Text(stringResource(Res.string.profile_unknown_exit_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.profile_edit_keep)) } })
}
