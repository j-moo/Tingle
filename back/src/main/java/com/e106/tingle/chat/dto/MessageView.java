package com.e106.tingle.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageView(UUID messageId, UUID clientMessageId, String sender,
	long sequence, String text, Instant createdAt, boolean readByPartner) {
}
