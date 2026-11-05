package com.ssafy.e106.features.chat.ui

import androidx.compose.ui.graphics.ImageBitmap

enum class ChatPartnerLoad { Loading, Ready, Failed, Closed, Unavailable, LoginRequired, OnboardingRequired }
enum class ChatPartnerPhoto { Empty, Loading, Ready, Failed, Removed }

/** 화면 요청의 계정·방·조회 회차. generation은 서버 필드가 아니라 호출부가 갱신하는 값이다. */
data class ChatPartnerScope(val viewerId: Long, val chatRoomId: Long, val generation: Long) {
    val valid get() = viewerId > 0 && chatRoomId > 0 && generation > 0
}

/** 채팅 partner-profile의 공개 값만 사용한다. 제안의 profileVersion/religionDisplay와 혼용하지 않는다. */
data class PublicChatPartner(
    val nickname: String,
    val age: Int,
    val gender: String,
    val smokingStatus: String,
    val drinkingFrequency: String,
    val religion: String? = null,
    val introduction: String? = null,
    val hasProfilePhoto: Boolean = false,
)

data class ChatPartnerImage(val scope: ChatPartnerScope, val bitmap: ImageBitmap)

/** 순수 표시 상태. 실제 HTTP, 토큰 재발급, 캐시 삭제, 요청 취소는 연결 계층의 책임이다. */
data class ChatPartnerState(
    val scope: ChatPartnerScope? = null,
    val load: ChatPartnerLoad = ChatPartnerLoad.Loading,
    val profile: PublicChatPartner? = null,
    val photo: ChatPartnerPhoto = ChatPartnerPhoto.Empty,
    val image: ChatPartnerImage? = null,
) {
    val visible get() = load == ChatPartnerLoad.Ready && scope?.valid == true && profile != null
    val visiblePhoto get() = when {
        !visible || profile?.hasProfilePhoto != true -> ChatPartnerPhoto.Empty
        photo == ChatPartnerPhoto.Ready && (image == null || image.scope != scope) -> ChatPartnerPhoto.Failed
        else -> photo
    }

    fun begin(next: ChatPartnerScope) = ChatPartnerState(scope = next,
        load = if (next.valid) ChatPartnerLoad.Loading else ChatPartnerLoad.Unavailable)

    fun loaded(request: ChatPartnerScope, value: PublicChatPartner): ChatPartnerState =
        if (scope != request || !request.valid || load != ChatPartnerLoad.Loading) this
        else copy(load = ChatPartnerLoad.Ready, profile = value,
            photo = if (value.hasProfilePhoto) ChatPartnerPhoto.Loading else ChatPartnerPhoto.Empty, image = null)

    fun photoLoaded(value: ChatPartnerImage): ChatPartnerState =
        if (!visible || profile?.hasProfilePhoto != true || scope != value.scope || photo != ChatPartnerPhoto.Loading) this
        else copy(photo = ChatPartnerPhoto.Ready, image = value)

    fun failed(request: ChatPartnerScope, code: String): ChatPartnerState {
        if (scope != request || load !in listOf(ChatPartnerLoad.Loading, ChatPartnerLoad.Ready)) return this
        if (code == "PHOTO_REMOVED" && visible) return copy(photo = ChatPartnerPhoto.Removed, image = null)
        return copy(load = when (code) {
            "CHAT_CLOSED" -> ChatPartnerLoad.Closed
            "RESOURCE_NOT_FOUND", "INVALID_REQUEST" -> ChatPartnerLoad.Unavailable
            "AUTH_REQUIRED", "ACCESS_TOKEN_INVALID", "SESSION_REVOKED" -> ChatPartnerLoad.LoginRequired
            "ONBOARDING_REQUIRED" -> ChatPartnerLoad.OnboardingRequired
            else -> ChatPartnerLoad.Failed
        }, profile = null, image = null, photo = ChatPartnerPhoto.Empty)
    }

    fun photoFailed(request: ChatPartnerScope): ChatPartnerState =
        if (scope != request || !visible || photo != ChatPartnerPhoto.Loading) this
        else copy(photo = ChatPartnerPhoto.Failed, image = null)
}

enum class ChatPartnerAction { Back, Reload, RetryPhoto, ChatList, Login, Onboarding }
