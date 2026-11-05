package com.ssafy.e106.preview

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.ssafy.e106.core.ui.theme.AppTheme
import com.ssafy.e106.features.chat.ui.*
import com.ssafy.e106.features.safety.ui.*

/** 예시 데이터는 Preview에만 전달한다. 실제 API/Repository를 대체하지 않는다. */
@Composable private fun QuestionCardsPreviewContent() {
    var draft by remember { mutableStateOf("") }
    val questions = ChatExtrasState(room = 20, status = "SUCCEEDED",
        questions = listOf("요즘 즐겨 듣는 음악은?", "쉬는 날에는 뭘 하나요?", "좋아하는 음식은?"))
    AppTheme {
        ChatConversationScreen(ChatConversationState(load = ConversationLoad.Active, nickname = "상대방",
            canRead = true, canSend = true, draft = draft), { draft = it }, {}, conversationSupport = {
            ChatQuestionCards(questions, true, draft, { draft = it }, {}, {})
        })
    }
}
@Preview(name = "추천 카드 · 여닫기와 초안 교체", widthDp = 390, heightDp = 844)
@Composable fun QuestionCardsPreview() = QuestionCardsPreviewContent()
@Preview(name = "추천 카드 · 작은 화면 큰 글자", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Composable fun QuestionCardsCompactPreview() = QuestionCardsPreviewContent()
