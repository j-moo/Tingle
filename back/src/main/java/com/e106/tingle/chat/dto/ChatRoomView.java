package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChatRoomView(UUID chatRoomId, String status, Instant createdAt, Instant expiresAt,
	Instant closedAt, Instant deleteAt, Instant serverTime, boolean canRead, boolean canSend) {
}
