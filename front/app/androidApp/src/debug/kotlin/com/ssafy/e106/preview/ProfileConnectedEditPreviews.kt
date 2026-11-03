package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.network.AppError
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.profile.data.ProfileResponse
import com.ssafy.e106.features.profile.ui.*

private val original = ProfileEditDraft("봄날", ProfileSmoking.NonSmoker, ProfileDrinking.Never)
private val connectedEdit = ProfileManagementState(
    profile = ProfileResponse(1, "봄날", "2000-10-10", "FEMALE", "NON_SMOKER", "NEVER", "NONE", "산책을 좋아해요.", completed = true),
    editor = ProfileEditState(original, original.copy(nickname = "봄날 산책"), "2000-10-10", ProfileGender.Female))

@Composable private fun ConnectedEditPreview(initial: ProfileManagementState) {
    var s by remember { mutableStateOf(initial) }
    AppTheme {
        ProfileConnectedEditScreen(s,
            onChange = { s = s.copy(editor = s.editor?.edit(it)) }, onSave = {},
            onBack = { s = s.copy(editor = s.editor?.copy(confirmDiscard = true)) },
            onDiscard = { s = s.copy(editor = s.editor?.discard()) },
            onKeep = { s = s.copy(editor = s.editor?.copy(confirmDiscard = false)) },
            onOptional = {}, onCorrection = {}, onReload = {})
    }
}
@Preview(name = "실제 연결 · 수정 중", widthDp = 390, heightDp = 844)
@Composable fun ConnectedEditReadyPreview() = ConnectedEditPreview(connectedEdit)
@Preview(name = "실제 연결 · 응답 유실", widthDp = 390, heightDp = 844)
@Composable fun ConnectedEditUnknownPreview() = ConnectedEditPreview(connectedEdit.copy(
    recoveryRequired = true, problem = ProfileEditProblem.ResultUnknown, error = AppError.Offline))
@Preview(name = "실제 연결 · 저장 중", widthDp = 390, heightDp = 844)
@Composable fun ConnectedEditSavingPreview() = ConnectedEditPreview(connectedEdit.copy(busy = true))
@Preview(name = "실제 연결 · 작은 화면 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable fun ConnectedEditCompactPreview() = ConnectedEditPreview(connectedEdit)
