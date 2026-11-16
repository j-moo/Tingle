package com.ssafy.e106.features.chat.ui

enum class ConversationLoad { Loading, Failed, Active, Closed, ConsentRequired, Unavailable }
enum class HistoryLoad { Idle, Loading, Failed }
enum class ChatRealtimeStatus { Connecting, Connected, Reconnecting, Stopped }

// 전송 결과는 호출부가 확인해 전달한다. 시간 초과만으로 Failed를 지정하지 않는다.
enum class MessageDelivery { Sent, Sending, Failed, Unknown, Checking, Rejected }
enum class MessageRecoveryAction { Retry, CheckResult }

data class ConversationMessage(
    val id: String,
    val mine: Boolean,
    // 수신 본문은 서버 공개/마스킹 완료 값. 본인 미전송 본문은 호출부에서 분리해 전달한다.
    // 이 화면은 링크를 생성하거나 외부로 본문을 보내지 않는다.
    val text: String,
    val timeLabel: String? = null,
    val delivery: MessageDelivery = MessageDelivery.Sent,
)

data class ChatConversationState(
    val load: ConversationLoad = ConversationLoad.Loading,
    val nickname: String? = null,
    val closesAtLabel: String? = null,
    // 호출부가 오래된 순서부터 전달한다. 상대 읽음 상태는 받지 않는다.
    val messages: List<ConversationMessage> = emptyList(),
    val draft: String = "",
    val canRead: Boolean = false,
    val canSend: Boolean = false,
    val submitting: Boolean = false,
    // 서버가 준 불투명 커서 그대로. null은 마지막 페이지이며 메시지 수로 추정하지 않는다.
    val nextCursor: String? = null,
    val historyLoad: HistoryLoad = HistoryLoad.Idle,
    val realtime: ChatRealtimeStatus? = null,
) {
    val readable: Boolean get() = load == ConversationLoad.Active && canRead
    val inputEnabled: Boolean get() = readable && canSend && !submitting
    val validatedDraft: ChatDraft = ChatDraft.inspect(draft)
    val submitEnabled: Boolean get() = inputEnabled && validatedDraft.valid
    val canLoadOlder: Boolean get() = readable && !nextCursor.isNullOrBlank() && historyLoad != HistoryLoad.Loading

    /** 서버 응답을 적용할 때 사용한다. 재동의만으로 종료 방을 복원하지 않는다. */
    fun withAccess(status: String, read: Boolean, send: Boolean): ChatConversationState {
        val nextLoad = when (status) { "ACTIVE" -> ConversationLoad.Active; "CLOSED" -> ConversationLoad.Closed; else -> ConversationLoad.Failed }
        val next = copy(load = nextLoad, canRead = read && nextLoad == ConversationLoad.Active,
            canSend = send && read && nextLoad == ConversationLoad.Active)
        return if (next.readable) next else next.withoutContent()
    }

    fun accessError(code: String): ChatConversationState = copy(load = when (code) {
        "AI_RISK_CONSENT_REQUIRED" -> ConversationLoad.ConsentRequired
        "CHAT_CLOSED" -> ConversationLoad.Closed
        "RESOURCE_NOT_FOUND" -> ConversationLoad.Unavailable
        else -> ConversationLoad.Failed
    }, canRead = false, canSend = false).withoutContent()

    // 닉네임도 지운다. 다음 접근 성공 때 다시 받는다
    private fun withoutContent() = copy(messages = emptyList(), draft = "", nextCursor = null,
        historyLoad = HistoryLoad.Idle, submitting = false, nickname = null)
}

enum class ConversationAction { Back, Reload, Send, OpenMenu, OpenPartner, OpenAiNotice, OpenConsent, LoadOlder }
