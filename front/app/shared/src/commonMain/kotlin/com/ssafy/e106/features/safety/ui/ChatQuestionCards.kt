package com.ssafy.e106.features.safety.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.ssafy.e106.core.ui.components.*
import com.ssafy.e106.core.ui.theme.*
import com.ssafy.e106.resources.*
import org.jetbrains.compose.resources.stringResource

/** 개인에게만 보이는 추천 UI. 선택은 초안만 바꾸며 메시지를 보내지 않는다. */
@Composable
fun ChatQuestionCards(state: ChatExtrasState, editable: Boolean, draft: String,
    insert: (String) -> Unit, generate: () -> Unit, check: () -> Unit, onTopicConsent: (Boolean) -> Unit = {},
    /** 대화에 이미 보낸 메시지 본문. 같은 질문은 목록에서 빼고, 세 개를 다 쓰면 추천 칸을 숨긴다 */
    used: Set<String> = emptySet()) {
    // 추천 질문은 방마다 한 번만 받는다. 보낸 질문을 빼고 남은 게 없으면 다시 받을 수 없으니 칸을 보이지 않는다
    val remaining = state.questions.filter { it.trim() !in used }
    if (state.questions.isNotEmpty() && remaining.isEmpty()) return
    // 같은 방의 화면 재생성에서는 여닫기를 복원하되 다른 계정/방으로 넘기지 않는다.
    var expanded by rememberSaveable(state.owner?.userId, state.room) { mutableStateOf(false) }
    // 확인 중 권한·질문·초안이 달라지면 오래된 선택을 폐기한다. 질문 본문은 저장하지 않는다.
    var candidate by remember(state.owner?.userId, state.room, state.questions, editable, draft) {
        mutableStateOf<String?>(null)
    }
    Surface(color = AppColors.SurfaceMuted, shape = AppShapes.Control) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            TextButton(onClick = { expanded = !expanded; candidate = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (expanded) Res.string.chat_questions_collapse else Res.string.chat_questions_expand))
            }
            if (expanded) {
                // 일반 질문(GENERAL_FALLBACK)은 두 사람에게 맞춘 질문이 아니라는 걸 알린다
                Text(stringResource(if (state.source == "GENERAL_FALLBACK") Res.string.chat_questions_general
                    else Res.string.chat_questions_help), style = MaterialTheme.typography.bodySmall)
                if (state.questions.isNotEmpty()) Text(stringResource(Res.string.chat_questions_once), style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary)
                // 받는 중: 질문 자리 뼈대(이미 받은 질문이 있으면 그대로 둔다)
                if (state.busy && state.questions.isEmpty()) SkeletonGroup(spacing = AppSpacing.Small) {
                    repeat(3) { SkeletonBone(height = AppSpacing.ControlMinHeight, shape = AppShapes.Control) }
                }
                if (state.status == "PENDING") Text(stringResource(Res.string.chat_questions_pending))
                remaining.forEach { question ->
                    AppButton(question, Modifier.fillMaxWidth(), variant = AppButtonVariant.Secondary,
                        enabled = editable, onClick = {
                            if (editable) {
                                // 공백도 사용자가 작성한 초안으로 보고 임의로 덮어쓰지 않는다.
                                if (draft.isEmpty()) { insert(question); expanded = false }
                                else candidate = question
                            }
                        })
                }
                if (state.status == "FAILED") Text(stringResource(Res.string.chat_extras_failed))
                // 설문에서 고른 관심 주제를 추천에 쓸지. 두 사람이 모두 켜면 공통 관심사로 질문을 만든다
                state.topicConsent?.let { consent ->
                    AppSwitch(consent, { on -> onTopicConsent(on) }, stringResource(Res.string.chat_questions_topic_switch),
                        enabled = !state.topicSaving)
                    Text(stringResource(Res.string.chat_questions_topic_help), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary)
                }
                // 실패는 버튼 위 한 줄로 알린다
                state.error?.let { InlineError(it.stateTitle()) }
                if (state.accessRetryRequired) AppButton(stringResource(Res.string.state_retry), Modifier.fillMaxWidth(),
                    enabled = !state.busy, onClick = check)
                else if (state.questions.isEmpty() && state.status != "FAILED")
                    AppButton(stringResource(if (state.status == null) Res.string.chat_extras_generate else Res.string.chat_extras_check), Modifier.fillMaxWidth(),
                        enabled = !state.busy, onClick = { if (state.status == null) generate() else check() })
            }
        }
    }
    candidate?.let { question ->
        AlertDialog(onDismissRequest = { candidate = null },
            title = { Text(stringResource(Res.string.chat_questions_replace_title)) },
            text = { Text(stringResource(Res.string.chat_questions_replace_help)) },
            confirmButton = { TextButton(enabled = editable && question in state.questions, onClick = {
                if (editable && question in state.questions) { insert(question); expanded = false }
                candidate = null
            }) { Text(stringResource(Res.string.chat_questions_replace)) } },
            dismissButton = { TextButton(onClick = { candidate = null }) {
                Text(stringResource(Res.string.chat_questions_keep))
            } })
    }
}
