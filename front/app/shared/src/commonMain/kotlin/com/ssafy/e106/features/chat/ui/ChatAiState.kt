package com.ssafy.e106.features.chat.ui

/** 화면용 문맥 키. 공개 API ID 형식이나 서버 권한 검증을 대체하지 않는다. */
data class ChatAiScope(val roomKey: String, val viewerKey: String)
enum class IcebreakerStatus { Disabled, Pending, Ready, Failed }
enum class RiskNoticeStatus { Disabled, Pending, NoNotice, Insufficient, Failed, Ready }
enum class RiskAudience { Recipient, Sender }
data class IcebreakerUi(
    val status: IcebreakerStatus = IcebreakerStatus.Disabled,
    val scope: ChatAiScope? = null,
    val questions: List<String> = emptyList(),
    val version: String? = null,
    val generalQuestions: Boolean = false,
) {
    val validQuestions: Boolean get() = questions.size == 3 && questions.all { it.isNotBlank() } && questions.distinct().size == 3
}
data class RiskNoticeUi(
    val status: RiskNoticeStatus = RiskNoticeStatus.Disabled,
    val scope: ChatAiScope? = null,
    val noticeKey: String? = null,
    val audience: RiskAudience? = null,
    val text: String? = null,
    val alreadyShown: Boolean = false,
)
data class ChatAiState(
    val scope: ChatAiScope? = null,
    val icebreakers: IcebreakerUi = IcebreakerUi(),
    val notice: RiskNoticeUi = RiskNoticeUi(),
) {
    val showNotice: Boolean get() = scope != null && notice.scope == scope &&
        notice.status == RiskNoticeStatus.Ready && !notice.alreadyShown && notice.audience != null &&
        !notice.noticeKey.isNullOrBlank() && !notice.text.isNullOrBlank()
}
enum class ChatAiAction { DismissNotice, OpenBlock }
