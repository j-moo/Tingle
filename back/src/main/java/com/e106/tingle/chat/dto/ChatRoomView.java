package com.e106.tingle.chat.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChatRoomView(Long chatRoomId, String status, Instant createdAt, Instant expiresAt,
	Instant closedAt, Instant deleteAt, Instant serverTime, boolean canRead, boolean canSend, boolean hasNewMessage,
	@JsonInclude(JsonInclude.Include.NON_NULL) String partnerNickname,
	// 목록에서만 채운다. 순번 0이면 아직 메시지 없음. 읽을 수 없는 방 · 방 상세 응답은 모두 빠진다
	@JsonInclude(JsonInclude.Include.NON_NULL) Long lastMessageSeq,
	@JsonInclude(JsonInclude.Include.NON_NULL) String lastMessage,
	@JsonInclude(JsonInclude.Include.NON_NULL) Instant lastMessageAt) {
}
